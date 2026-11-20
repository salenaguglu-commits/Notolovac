package com.example.audio

import com.example.model.DetectedNote
import com.example.model.MusicTheory
import kotlin.math.abs
import kotlin.math.roundToInt

data class TrackerUpdate(
    val liveMidiNote: Int?,
    val liveCentsOff: Float,
    val liveFrequencyHz: Double,
    val rmsLevel: Float,
    val capturedNotes: List<DetectedNote>
)

data class QuantizedTranscription(
    val notes: List<DetectedNote>,
    val effectiveBpm: Int
)

/**
 * High-accuracy real-time Piano Note Tracker & Rhythm Quantizer.
 *
 * Key improvements for real pianos:
 * - Uses FFT Harmonic Comb pitch + Spectral Flux onset detection (isolates newly struck keys even over ringing room echo)
 * - Detects piano damper release (sharp harmonic energy drop) so held notes vs genuine rests are distinguished cleanly
 * - Splits repeated strikes of the exact same piano key (e.g. C4-C4-G4-G4) reliably via spectral flux onsets
 * - Auto-estimates natural tempo (BPM) from Inter-Onset Intervals when the metronome is OFF, so rhythms
 *   (eighth / quarter / half / whole notes) and rests match what the child actually played!
 */
class PianoNoteTracker {
    private val finalizedNotes = mutableListOf<DetectedNote>()
    private var nextNoteId = 1L

    // Active note tracking state
    private var activeMidi: Int? = null
    private var activeStartMs: Long = 0L
    private var activeLastSeenMs: Long = 0L
    private var activeConfidenceSum: Float = 0f
    private var activeFrameCount: Int = 0
    private var activePeakEnergy: Float = 0f
    private var activePrevEnergy: Float = 0f

    // Stability & onset tracking
    private val recentMidiWindow = ArrayDeque<Int>(3)
    private var smoothedFloorRms: Float = 0.0025f
    private var lastOnsetCommitMs: Long = 0L
    private var silentFrameCount: Int = 0
    private var pendingOnsetLockFrames: Int = 0

    // Minimum duration for a genuine piano note (rejects brief clicks/transients)
    private val minNoteDurationMs = 95L
    // Cooldown between two strikes of the exact same key
    private val minSameNoteRearticulationMs = 155L
    // Consecutive low-energy frames (~23ms each) to release a damped/decayed note
    private val silenceFramesToRelease = 4

    fun reset() {
        finalizedNotes.clear()
        nextNoteId = 1L
        activeMidi = null
        activeStartMs = 0L
        activeLastSeenMs = 0L
        activeConfidenceSum = 0f
        activeFrameCount = 0
        activePeakEnergy = 0f
        activePrevEnergy = 0f
        recentMidiWindow.clear()
        smoothedFloorRms = 0.0025f
        lastOnsetCommitMs = 0L
        silentFrameCount = 0
        pendingOnsetLockFrames = 0
    }

    fun processFrame(frame: SpectralPitchFrame, timestampMs: Long): TrackerUpdate {
        val rms = frame.rms

        // Adapt room ambient floor during quiet moments
        if (rms < 0.009f && frame.midiNote == null) {
            smoothedFloorRms = smoothedFloorRms * 0.95f + rms * 0.05f
        }
        val effectiveRmsGate = (smoothedFloorRms * 1.9f).coerceIn(0.0035f, 0.018f)

        val candidateMidi = frame.midiNote
        val validPitch = candidateMidi != null &&
            candidateMidi in MusicTheory.MIN_PIANO_MIDI..MusicTheory.MAX_PIANO_MIDI &&
            frame.confidence >= 0.42f &&
            rms >= effectiveRmsGate

        // If a sharp spectral onset occurs, allow quick note switch on the next 2 frames
        if (frame.isOnset) {
            pendingOnsetLockFrames = 3
        } else if (pendingOnsetLockFrames > 0) {
            pendingOnsetLockFrames--
        }

        if (!validPitch) {
            silentFrameCount++
            recentMidiWindow.clear()
            // Damper release or decay below floor
            if (activeMidi != null && silentFrameCount >= silenceFramesToRelease) {
                commitActiveNote(activeLastSeenMs)
            }
            return TrackerUpdate(
                liveMidiNote = activeMidi,
                liveCentsOff = 0f,
                liveFrequencyHz = 0.0,
                rmsLevel = rms,
                capturedNotes = finalizedNotes.toList()
            )
        }

        silentFrameCount = 0
        val midi = candidateMidi

        // Push into 3-frame stability buffer
        if (recentMidiWindow.size >= 3) {
            recentMidiWindow.removeFirst()
        }
        recentMidiWindow.addLast(midi)

        val stableMidi = computeStableMidi(allowFastOnset = pendingOnsetLockFrames > 0)

        if (stableMidi != null) {
            val current = activeMidi
            when {
                current == null -> {
                    startNewNote(stableMidi, timestampMs, frame.confidence, frame.activeNoteEnergy)
                    pendingOnsetLockFrames = 0
                }
                current == stableMidi -> {
                    // Check for same-key re-articulation (e.g. C4 -> C4 -> G4 -> G4)
                    val energyJump = activePrevEnergy > 0.1f &&
                        frame.activeNoteEnergy > activePrevEnergy * 1.75f
                    val isRearticulated = (frame.isOnset || energyJump) &&
                        (timestampMs - activeStartMs) >= minSameNoteRearticulationMs &&
                        (timestampMs - lastOnsetCommitMs) >= minSameNoteRearticulationMs

                    if (isRearticulated) {
                        commitActiveNote(timestampMs)
                        startNewNote(stableMidi, timestampMs, frame.confidence, frame.activeNoteEnergy)
                        pendingOnsetLockFrames = 0
                    } else {
                        // Track sustained note smoothly; real piano strings naturally decay well below
                        // 10% of attack peak while still held down, so only release on true silence/damper drop.
                        val sharpDamperDrop = activePrevEnergy > 0.8f &&
                            frame.activeNoteEnergy < activePrevEnergy * 0.35f &&
                            frame.activeNoteEnergy < activePeakEnergy * 0.08f &&
                            (timestampMs - activeStartMs) > 180L
                        if (sharpDamperDrop) {
                            commitActiveNote(timestampMs)
                        } else {
                            activeLastSeenMs = timestampMs
                            activeConfidenceSum += frame.confidence
                            activeFrameCount++
                            if (frame.activeNoteEnergy > activePeakEnergy) {
                                activePeakEnergy = frame.activeNoteEnergy
                            }
                            activePrevEnergy = frame.activeNoteEnergy
                        }
                    }
                }
                else -> {
                    // Switching to a different note!
                    // Guard against decaying octave/fifth overtone artifact when there is NO onset and energy is dropping
                    val isHarmonicArtifact = (stableMidi == current + 12 || stableMidi == current + 19) &&
                        !frame.isOnset &&
                        pendingOnsetLockFrames == 0 &&
                        frame.activeNoteEnergy < activePeakEnergy * 0.45f

                    if (!isHarmonicArtifact) {
                        commitActiveNote(timestampMs)
                        startNewNote(stableMidi, timestampMs, frame.confidence, frame.activeNoteEnergy)
                        pendingOnsetLockFrames = 0
                    } else {
                        activeLastSeenMs = timestampMs
                    }
                }
            }
        }

        return TrackerUpdate(
            liveMidiNote = activeMidi ?: stableMidi,
            liveCentsOff = frame.centsOff,
            liveFrequencyHz = frame.frequencyHz,
            rmsLevel = rms,
            capturedNotes = finalizedNotes.toList()
        )
    }

    /**
     * Finalizes the trailing note and quantizes durations & rests.
     * When [metronomeWasActive] is false, automatically estimates the child's natural tempo (BPM)
     * from their Inter-Onset Intervals so note lengths (eighth/quarter/half/whole) feel natural!
     */
    fun finishAndQuantize(
        metronomeBpm: Int,
        metronomeWasActive: Boolean,
        endTimestampMs: Long
    ): QuantizedTranscription {
        if (activeMidi != null) {
            commitActiveNote(maxOf(activeLastSeenMs, endTimestampMs))
        }
        return quantizeNotes(
            rawNotes = finalizedNotes,
            fallbackBpm = metronomeBpm,
            lockToMetronomeBpm = metronomeWasActive
        )
    }

    private fun computeStableMidi(allowFastOnset: Boolean): Int? {
        if (recentMidiWindow.isEmpty()) return null
        val list = recentMidiWindow.toList()
        if (allowFastOnset && list.size >= 2 && list[list.size - 1] == list[list.size - 2]) {
            return list.last()
        }
        if (list.size >= 2 && list[list.size - 1] == list[list.size - 2]) {
            return list.last()
        }
        if (list.size == 3) {
            val sorted = list.sorted()
            if (sorted[0] == sorted[1] || sorted[1] == sorted[2]) {
                return sorted[1]
            }
        }
        return null
    }

    private fun startNewNote(midi: Int, timestampMs: Long, confidence: Float, energy: Float) {
        activeMidi = midi
        activeStartMs = timestampMs
        activeLastSeenMs = timestampMs
        activeConfidenceSum = confidence
        activeFrameCount = 1
        activePeakEnergy = energy
        activePrevEnergy = energy
        lastOnsetCommitMs = timestampMs
    }

    private fun commitActiveNote(endMs: Long) {
        val midi = activeMidi ?: return
        val duration = (endMs - activeStartMs).coerceAtLeast(0L)
        if (duration >= minNoteDurationMs && activeFrameCount >= 2) {
            val avgConf = (activeConfidenceSum / activeFrameCount).coerceIn(0f, 1f)
            finalizedNotes.add(
                DetectedNote(
                    id = nextNoteId++,
                    midiNote = midi,
                    startTimeMs = activeStartMs,
                    durationMs = duration,
                    beats = 1.0f,
                    confidence = avgConf,
                    isRest = false
                )
            )
        }
        activeMidi = null
        activeFrameCount = 0
        activeConfidenceSum = 0f
        activePeakEnergy = 0f
        activePrevEnergy = 0f
    }

    companion object {
        /**
         * Quantizes recorded notes into clean musical durations and genuine rests.
         * - If [lockToMetronomeBpm] is true, uses [fallbackBpm] directly.
         * - Otherwise, infers the child's natural quarter-note beat duration from their played
         *   Inter-Onset Intervals (IOIs), preventing slow playing from turning into all half/whole notes
         *   and preventing fast playing from turning into all eighth notes!
         */
        fun quantizeNotes(
            rawNotes: List<DetectedNote>,
            fallbackBpm: Int,
            lockToMetronomeBpm: Boolean = false
        ): QuantizedTranscription {
            if (rawNotes.isEmpty()) {
                return QuantizedTranscription(emptyList(), fallbackBpm)
            }

            val msPerBeat = if (lockToMetronomeBpm || rawNotes.size < 3) {
                60_000.0 / fallbackBpm.coerceIn(55, 160)
            } else {
                estimateNaturalBeatMs(rawNotes, fallbackBpm)
            }
            val effectiveBpm = (60_000.0 / msPerBeat).roundToInt().coerceIn(55, 160)

            val result = mutableListOf<DetectedNote>()
            var idCounter = 1L

            for (i in rawNotes.indices) {
                val note = rawNotes[i]
                val nextNote = rawNotes.getOrNull(i + 1)

                if (nextNote != null) {
                    val ioiMs = (nextNote.startTimeMs - note.startTimeMs).coerceAtLeast(note.durationMs)
                    val silentGapMs = (nextNote.startTimeMs - (note.startTimeMs + note.durationMs)).coerceAtLeast(0L)

                    // Only insert a rest if there was a TRUE prolonged silence (>= 0.85 beats AND > 42% of IOI)
                    val hasDeliberateRest = silentGapMs >= msPerBeat * 0.85 &&
                        silentGapMs.toDouble() >= ioiMs * 0.42

                    if (hasDeliberateRest) {
                        val noteBeats = snapNoteBeats(note.durationMs / msPerBeat)
                        result.add(
                            note.copy(
                                id = idCounter++,
                                beats = noteBeats,
                                isRest = false
                            )
                        )
                        val restBeats = snapRestBeats(silentGapMs / msPerBeat)
                        result.add(
                            DetectedNote(
                                id = idCounter++,
                                midiNote = -1,
                                startTimeMs = note.startTimeMs + note.durationMs,
                                durationMs = silentGapMs,
                                beats = restBeats,
                                confidence = 1f,
                                isRest = true
                            )
                        )
                    } else {
                        // Connected melodic playing: Inter-Onset Interval IS the true musical rhythm!
                        val noteBeats = snapNoteBeats(ioiMs / msPerBeat)
                        result.add(
                            note.copy(
                                id = idCounter++,
                                beats = noteBeats,
                                isRest = false
                            )
                        )
                    }
                } else {
                    // Final note has no next onset: subtract short ~75ms damper tail and quantize cleanly
                    val trimmedMs = (note.durationMs - 75L).coerceAtLeast(120L)
                    val finalBeats = snapNoteBeats(trimmedMs / msPerBeat)
                    result.add(
                        note.copy(
                            id = idCounter++,
                            beats = finalBeats,
                            isRest = false
                        )
                    )
                }
            }

            return QuantizedTranscription(notes = result, effectiveBpm = effectiveBpm)
        }

        /**
         * Estimates the child's natural quarter-note duration (ms) by finding the dominant IOI cluster
         * and folding double/half-time intervals into the comfortable 65..140 BPM piano range.
         */
        private fun estimateNaturalBeatMs(notes: List<DetectedNote>, fallbackBpm: Int): Double {
            val iois = mutableListOf<Double>()
            for (i in 0 until notes.lastIndex) {
                val dt = (notes[i + 1].startTimeMs - notes[i].startTimeMs).toDouble()
                if (dt in 160.0..2800.0) {
                    iois.add(dt)
                }
            }
            if (iois.isEmpty()) return 60_000.0 / fallbackBpm.coerceIn(60, 140)

            // Search candidate beat durations in 60..145 BPM (step 2 BPM) to minimize musical grid snapping error
            var bestBeatMs = 60_000.0 / fallbackBpm.coerceIn(60, 140)
            var bestLoss = Double.MAX_VALUE
            val allowedRatios = doubleArrayOf(0.5, 1.0, 2.0, 4.0)

            for (bpm in 60..144 step 2) {
                val beatMs = 60_000.0 / bpm
                var totalLoss = 0.0
                var quarterCount = 0

                for (dt in iois) {
                    val ratio = dt / beatMs
                    var minErr = Double.MAX_VALUE
                    var bestR = 1.0
                    for (r in allowedRatios) {
                        // Relative error on log scale treats 0.5x and 2x symmetrically
                        val err = abs(kotlin.math.ln(ratio / r))
                        if (err < minErr) {
                            minErr = err
                            bestR = r
                        }
                    }
                    totalLoss += minErr
                    if (bestR == 1.0) quarterCount++
                }

                // Prefer beat grid where the majority of melody notes land naturally on quarter notes (1.0 beat)
                val quarterRatio = quarterCount.toDouble() / iois.size
                val preferenceBonus = -0.18 * quarterRatio
                // Tiny tie-breaker gently preferring moderate kids' piano tempos (80..115 BPM)
                val tempoPrior = 0.0005 * abs(bpm - 96)
                val score = (totalLoss / iois.size) + preferenceBonus + tempoPrior

                if (score < bestLoss) {
                    bestLoss = score
                    bestBeatMs = beatMs
                }
            }

            return bestBeatMs
        }

        private fun snapNoteBeats(rawBeats: Double): Float {
            return when {
                rawBeats < 0.72 -> 0.5f  // Osmina
                rawBeats < 1.52 -> 1.0f  // Četvrtina
                rawBeats < 2.85 -> 2.0f  // Polovina
                else -> 4.0f             // Cela nota
            }
        }

        private fun snapRestBeats(rawBeats: Double): Float {
            return when {
                rawBeats < 1.55 -> 1.0f  // Četvrtinska pauza
                else -> 2.0f             // Polovinska pauza
            }
        }
    }
}
