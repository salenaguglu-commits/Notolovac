package com.example.audio

import com.example.model.DetectedNote
import com.example.model.MusicTheory
import kotlin.math.abs
import kotlin.math.max
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
 * Onset-Gated, Pitch-Locked Piano Note Tracker & Rhythm Quantizer.
 *
 * Physics-first design for real acoustic & digital pianos:
 * - A struck piano key CANNOT change pitch while decaying/fading or beating (vibrato).
 * - Therefore, once a note is captured at its clean attack peak, its MIDI pitch is LOCKED
 *   for the entire sustain/fade phase!
 * - Decaying room tails, unison-string beating, and sub-octave room rumble can NEVER spawn
 *   false ghost notes while a note is fading out.
 * - A new note can ONLY begin on a genuine fresh hammer strike:
 *   1) From silence: clean attack above the ambient gate (and not a dying reverb tail), OR
 *   2) While another note is ringing: a strong positive delta-spectrum onset at a new key
 *      (for legato) or a sharp energy re-jump on the same key (for repeated notes like Do-Do).
 */
class PianoNoteTracker {
    private val finalizedNotes = mutableListOf<DetectedNote>()
    private var nextNoteId = 1L

    // Currently locked active piano note
    var activeMidi: Int? = null
        private set

    private var activeStartMs: Long = 0L
    private var activeLastSeenMs: Long = 0L
    private var activeConfidenceSum: Float = 0f
    private var activeFrameCount: Int = 0
    private var activePeakEnergy: Float = 0f
    private var activePeakRms: Float = 0f
    private var activePrevEnergy: Float = 0f
    private var lowEnergyFrames: Int = 0

    // Pending attack confirmation (2 consecutive frames right after strike to skip raw hammer thump)
    private var pendingAttackMidi: Int? = null
    private var pendingAttackStartMs: Long = 0L
    private var pendingAttackCount: Int = 0

    // Adaptive ambient noise & post-release reverb guard
    private var smoothedFloorRms: Float = 0.0025f
    private var prevRms: Float = 0f
    private var postReleaseDecayGuardMs: Long = 0L
    private var lastReleasedMidi: Int? = null
    private var lastNoteStartMs: Long = 0L

    // Minimum duration for a genuine piano note
    private val minNoteDurationMs = 95L
    // Refractory window preventing double-triggers on the same hammer bounce
    private val minSameNoteRearticulationMs = 170L
    // Refractory window preventing attack transient glitches right after starting a note
    private val minNewNoteSwitchMs = 110L
    // Consecutive low-energy frames (~23ms each = ~115ms) required to release a note (bridges unison beating dips!)
    private val releaseFramesRequired = 5

    fun reset() {
        finalizedNotes.clear()
        nextNoteId = 1L
        activeMidi = null
        activeStartMs = 0L
        activeLastSeenMs = 0L
        activeConfidenceSum = 0f
        activeFrameCount = 0
        activePeakEnergy = 0f
        activePeakRms = 0f
        activePrevEnergy = 0f
        lowEnergyFrames = 0
        pendingAttackMidi = null
        pendingAttackStartMs = 0L
        pendingAttackCount = 0
        smoothedFloorRms = 0.0025f
        prevRms = 0f
        postReleaseDecayGuardMs = 0L
        lastReleasedMidi = null
        lastNoteStartMs = 0L
    }

    fun processFrame(frame: SpectralPitchFrame, timestampMs: Long): TrackerUpdate {
        val rms = frame.rms

        // Adapt ambient noise floor during quiet non-pitched moments
        if (rms < 0.010f && frame.midiNote == null && activeMidi == null) {
            smoothedFloorRms = smoothedFloorRms * 0.94f + rms * 0.06f
        }
        val attackRmsGate = (smoothedFloorRms * 2.4f).coerceIn(0.0045f, 0.022f)
        val releaseRmsGate = (attackRmsGate * 0.55f).coerceAtLeast(0.0025f)

        val current = activeMidi

        if (current == null) {
            // ================= IDLE STATE (Waiting for a clean new hammer strike) =================
            val candidate = selectCleanOnsetCandidate(frame)
            // After releasing a note, require a genuine onset so lingering room reverb or
            // unison beating swells can NEVER trigger a fake repeat or octave-down ghost note!
            val inDecayGuard = timestampMs < postReleaseDecayGuardMs
            val isSameAsJustReleased = candidate != null && candidate == lastReleasedMidi
            val isFreshAttack = if (isSameAsJustReleased) {
                frame.isOnset || (frame.onsetMidiNote == candidate && frame.onsetConfidence >= 0.55f && rms > prevRms * 1.30f)
            } else {
                frame.isOnset || (rms > prevRms * 1.12f) || (frame.onsetMidiNote != null && frame.onsetConfidence >= 0.48f)
            }

            if (candidate != null && rms >= attackRmsGate && (!inDecayGuard || isFreshAttack)) {
                if (pendingAttackMidi == candidate) {
                    pendingAttackCount++
                } else {
                    pendingAttackMidi = candidate
                    pendingAttackStartMs = timestampMs
                    pendingAttackCount = 1
                }
                // Confirm after 2 agreeing frames (~46ms) — eliminates 1-frame mechanical thumps!
                if (pendingAttackCount >= 2) {
                    val startEnergy = max(frame.energyForMidi(candidate), 1.0f)
                    startNewNote(
                        midi = candidate,
                        timestampMs = pendingAttackStartMs,
                        confidence = max(frame.confidence, frame.onsetConfidence),
                        energy = startEnergy,
                        rms = rms
                    )
                    clearPendingAttack()
                }
            } else {
                clearPendingAttack()
            }
        } else {
            // ================= LOCKED SUSTAIN STATE (Pitch is frozen on `current`!) =================
            val lockedEnergy = frame.energyForMidi(current)
            val lockedDeltaRatio = frame.deltaRatioForMidi(current)
            if (lockedEnergy > activePeakEnergy) activePeakEnergy = lockedEnergy
            if (rms > activePeakRms) activePeakRms = rms

            val timeSinceStart = timestampMs - activeStartMs

            // 1. Check for a GENUINE NEW HAMMER ONSET (new legato key OR repeated strike of same key)
            val rawOnsetMidi = frame.onsetMidiNote
            // Guard against 1-frame upper-octave harmonic transient on hammer attack (e.g., G5 spike on G4 hit)
            val onsetMidi = if (
                rawOnsetMidi != null &&
                frame.midiNote != null &&
                rawOnsetMidi == frame.midiNote + 12 &&
                frame.confidence >= 0.50f &&
                frame.midiNote != current
            ) {
                frame.midiNote
            } else {
                rawOnsetMidi
            }

            val strongNewKeyOnset = onsetMidi != null &&
                onsetMidi != current &&
                onsetMidi in MusicTheory.MIN_PIANO_MIDI..MusicTheory.MAX_PIANO_MIDI &&
                frame.onsetConfidence >= 0.46f &&
                (frame.isOnset || frame.onsetConfidence >= 0.58f) &&
                rms >= attackRmsGate &&
                timeSinceStart >= minNewNoteSwitchMs

            // Refuse decaying sub-octave ghosts unless there is a sharp global onset + high confidence
            val validNewKeyOnset = strongNewKeyOnset &&
                (onsetMidi != current - 12 || (frame.isOnset && frame.onsetConfidence >= 0.62f))

            // Same-key restrike MUST have a genuine global hammer onset (frame.isOnset),
            // so slow unison-string vibrato/beating swells during sustain NEVER split a held note!
            val sameKeyRestrike = frame.isOnset &&
                lockedDeltaRatio >= 0.28f &&
                (onsetMidi == current || frame.midiNote == current) &&
                lockedEnergy > activePrevEnergy * 1.50f &&
                rms >= attackRmsGate &&
                timeSinceStart >= minSameNoteRearticulationMs &&
                (timestampMs - lastNoteStartMs) >= minSameNoteRearticulationMs

            if (validNewKeyOnset) {
                val targetMidi = onsetMidi
                if (pendingAttackMidi == targetMidi) {
                    pendingAttackCount++
                } else {
                    pendingAttackMidi = targetMidi
                    pendingAttackStartMs = timestampMs
                    pendingAttackCount = 1
                }
                // Always confirm over 2 consecutive frames (~46ms) to eliminate 1-frame harmonic transients
                if (pendingAttackCount >= 2) {
                    commitActiveNote(pendingAttackStartMs)
                    startNewNote(
                        midi = targetMidi,
                        timestampMs = pendingAttackStartMs,
                        confidence = frame.onsetConfidence,
                        energy = max(frame.energyForMidi(targetMidi), 1.0f),
                        rms = rms
                    )
                    clearPendingAttack()
                }
            } else if (sameKeyRestrike) {
                clearPendingAttack()
                commitActiveNote(timestampMs)
                startNewNote(
                    midi = current,
                    timestampMs = timestampMs,
                    confidence = max(frame.confidence, 0.7f),
                    energy = max(lockedEnergy, 1.0f),
                    rms = rms
                )
            } else {
                clearPendingAttack()

                // 2. Track whether the locked note is still ringing OR has been damped / faded out
                // Note: We NEVER switch pitch here during fade or vibrato! We only decide keep vs release.
                val sharpDamperDrop = activePrevEnergy > 1.2f &&
                    lockedEnergy < activePrevEnergy * 0.30f &&
                    lockedEnergy < activePeakEnergy * 0.14f &&
                    timeSinceStart > 140L

                // Do NOT prematurely release a held note while its RMS is still clearly above ambient
                // and its locked harmonics are still ringing (prevents unison beating troughs from splitting a held note!)
                val fadedOut = rms < releaseRmsGate ||
                    (lockedEnergy < 0.28f && rms < attackRmsGate * 1.15f) ||
                    (activePeakEnergy > 1.0f && lockedEnergy < activePeakEnergy * 0.025f && rms < attackRmsGate * 1.25f)

                if (sharpDamperDrop) {
                    lastReleasedMidi = current
                    commitActiveNote(timestampMs)
                    postReleaseDecayGuardMs = timestampMs + 320L
                } else if (fadedOut) {
                    lowEnergyFrames++
                    if (lowEnergyFrames >= releaseFramesRequired) {
                        lastReleasedMidi = current
                        commitActiveNote(activeLastSeenMs)
                        postReleaseDecayGuardMs = timestampMs + 420L
                    }
                } else {
                    lowEnergyFrames = 0
                    activeLastSeenMs = timestampMs
                    activeConfidenceSum += max(frame.confidence, 0.5f)
                    activeFrameCount++
                }
                activePrevEnergy = lockedEnergy
            }
        }

        prevRms = rms

        return TrackerUpdate(
            liveMidiNote = activeMidi,
            liveCentsOff = if (activeMidi != null) frame.centsOff else 0f,
            liveFrequencyHz = if (activeMidi != null) frame.frequencyHz else 0.0,
            rmsLevel = rms,
            capturedNotes = finalizedNotes.toList()
        )
    }

    private fun selectCleanOnsetCandidate(frame: SpectralPitchFrame): Int? {
        // Prefer delta-spectrum onset pitch if strong, unless full-spectrum clearly confirms the true fundamental an octave below
        val onset = frame.onsetMidiNote
        val full = frame.midiNote
        if (onset != null && onset in MusicTheory.MIN_PIANO_MIDI..MusicTheory.MAX_PIANO_MIDI && frame.onsetConfidence >= 0.44f) {
            if (full != null && onset == full + 12 && frame.confidence >= 0.50f && full in MusicTheory.MIN_PIANO_MIDI..MusicTheory.MAX_PIANO_MIDI) {
                return full
            }
            return onset
        }
        if (full != null && full in MusicTheory.MIN_PIANO_MIDI..MusicTheory.MAX_PIANO_MIDI && frame.confidence >= 0.46f) {
            return full
        }
        return null
    }

    private fun clearPendingAttack() {
        pendingAttackMidi = null
        pendingAttackStartMs = 0L
        pendingAttackCount = 0
    }

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

    private fun startNewNote(
        midi: Int,
        timestampMs: Long,
        confidence: Float,
        energy: Float,
        rms: Float
    ) {
        activeMidi = midi
        activeStartMs = timestampMs
        activeLastSeenMs = timestampMs
        activeConfidenceSum = confidence
        activeFrameCount = 1
        activePeakEnergy = energy
        activePeakRms = rms
        activePrevEnergy = energy
        lowEnergyFrames = 0
        lastNoteStartMs = timestampMs
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
        activePeakRms = 0f
        activePrevEnergy = 0f
        lowEnergyFrames = 0
    }

    companion object {
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

        private fun estimateNaturalBeatMs(notes: List<DetectedNote>, fallbackBpm: Int): Double {
            val iois = mutableListOf<Double>()
            for (i in 0 until notes.lastIndex) {
                val dt = (notes[i + 1].startTimeMs - notes[i].startTimeMs).toDouble()
                if (dt in 160.0..2800.0) {
                    iois.add(dt)
                }
            }
            if (iois.isEmpty()) return 60_000.0 / fallbackBpm.coerceIn(60, 140)

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
                        val err = abs(kotlin.math.ln(ratio / r))
                        if (err < minErr) {
                            minErr = err
                            bestR = r
                        }
                    }
                    totalLoss += minErr
                    if (bestR == 1.0) quarterCount++
                }

                val quarterRatio = quarterCount.toDouble() / iois.size
                val preferenceBonus = -0.18 * quarterRatio
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
