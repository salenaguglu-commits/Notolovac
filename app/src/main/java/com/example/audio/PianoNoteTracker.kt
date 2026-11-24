package com.example.audio

import com.example.model.DetectedNote
import com.example.model.MusicTheory
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class TrackerUpdate(
    val liveMidiNote: Int?,
    val liveCentsOff: Float,
    val liveFrequencyHz: Double,
    val rmsLevel: Float,
    val isClipping: Boolean = false,
    val capturedNotes: List<DetectedNote>
)

data class QuantizedTranscription(
    val notes: List<DetectedNote>,
    val effectiveBpm: Int
)

/**
 * Direct, Low-Latency, Pitch-Locked Piano Note Tracker & Rhythm Quantizer.
 *
 * Pure acoustic ("na prvu") design — zero scale guessing or post-hoc pitch rewriting:
 * - Captures rapid 1/4, 1/8, and 1/16 passages cleanly (~11.6ms–23ms attack response).
 * - Once a piano key is struck, its MIDI pitch stays LOCKED during sustain/fade so
 *   unison-string beating, vibrato, and decaying room tails never spawn octave-down ghosts.
 * - A new note begins immediately on a genuine fresh hammer strike:
 *   1) From silence: clean attack above the ambient gate, OR
 *   2) While another key is ringing: strong positive delta-spectrum onset on a new key
 *      (fast legato 1/8 & 1/16 runs) or a clear valley-to-peak energy jump on the same key.
 */
class PianoNoteTracker(
    private val calibrationProfile: PianoCalibrationProfile? = null
) {
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
    private var activeMinEnergySincePeak: Float = 0f
    private var activePeakRms: Float = 0f
    private var activePrevEnergy: Float = 0f
    private var lowEnergyStartMs: Long = -1L
    private var dampedStartMs: Long = -1L
    private var recentClippingFrames: Int = 0

    // Short attack confirmation buffer (1 frame for strong clean strikes, 2 frames otherwise)
    private var pendingAttackMidi: Int? = null
    private var pendingAttackStartMs: Long = 0L
    private var pendingAttackCount: Int = 0

    // Adaptive ambient noise & post-release reverb guard
    private var smoothedFloorRms: Float = initialAmbientFloor()
    private var prevRms: Float = 0f
    private var postReleaseDecayGuardMs: Long = 0L
    private var lastReleasedMidi: Int? = null
    private var lastReleasedPeakRms: Float = 0f
    private var lastNoteStartMs: Long = 0L

    // Fast timing thresholds supporting crisp 1/4, 1/8, and 1/16 notes (even at 120–140 BPM)
    private val minNoteDurationMs = 50L
    private val minSameNoteRearticulationMs = 100L
    private val minNewNoteSwitchMs = 55L
    private val releaseSilentMs = 95L
    private val dampedReleaseMs = 28L

    private fun initialAmbientFloor(): Float {
        return if (calibrationProfile?.isScaleCalibrated == true) {
            calibrationProfile.ambientNoiseRms.coerceIn(0.0015f, 0.015f)
        } else {
            0.0025f
        }
    }

    fun reset() {
        finalizedNotes.clear()
        nextNoteId = 1L
        activeMidi = null
        activeStartMs = 0L
        activeLastSeenMs = 0L
        activeConfidenceSum = 0f
        activeFrameCount = 0
        activePeakEnergy = 0f
        activeMinEnergySincePeak = 0f
        activePeakRms = 0f
        activePrevEnergy = 0f
        lowEnergyStartMs = -1L
        dampedStartMs = -1L
        recentClippingFrames = 0
        clearPendingAttack()
        smoothedFloorRms = initialAmbientFloor()
        prevRms = 0f
        postReleaseDecayGuardMs = 0L
        lastReleasedMidi = null
        lastReleasedPeakRms = 0f
        lastNoteStartMs = 0L
    }

    fun processFrame(frame: SpectralPitchFrame, timestampMs: Long): TrackerUpdate {
        val rms = frame.rms
        if (frame.clippingRatio >= 0.025f) {
            recentClippingFrames = (recentClippingFrames + 1).coerceAtMost(20)
        } else if (recentClippingFrames > 0) {
            recentClippingFrames--
        }

        // Adapt ambient noise floor during quiet non-pitched moments
        if (rms < 0.010f && frame.midiNote == null && activeMidi == null) {
            smoothedFloorRms = smoothedFloorRms * 0.94f + rms * 0.06f
        }

        val baseAttackGate = (smoothedFloorRms * 2.4f).coerceIn(0.0045f, 0.022f)
        val attackRmsGate = if (calibrationProfile?.isScaleCalibrated == true) {
            val profileGate = max(
                calibrationProfile.ambientNoiseRms * 2.5f,
                calibrationProfile.medianStrikeRms * 0.14f
            )
            max(baseAttackGate, profileGate).coerceIn(0.0045f, 0.032f)
        } else {
            baseAttackGate
        }
        val releaseRmsGate = (attackRmsGate * 0.55f).coerceAtLeast(0.0025f)

        val current = activeMidi

        if (current == null) {
            // ================= IDLE STATE (Waiting for a clean new hammer strike) =================
            val candidate = selectCleanOnsetCandidate(frame)
            val inDecayGuard = timestampMs < postReleaseDecayGuardMs
            val isSameAsJustReleased = candidate != null && candidate == lastReleasedMidi
            val isFreshAttack = if (isSameAsJustReleased) {
                val strongEnoughForRestrike = rms >= lastReleasedPeakRms * 0.36f
                strongEnoughForRestrike && (
                    frame.isOnset ||
                        (frame.onsetMidiNote == candidate && frame.onsetConfidence >= 0.56f && rms > prevRms * 1.28f)
                    )
            } else {
                frame.isOnset || (rms > prevRms * 1.10f) || (frame.onsetMidiNote != null && frame.onsetConfidence >= 0.46f)
            }

            val alreadyConfirming = candidate != null && pendingAttackMidi == candidate && pendingAttackCount >= 1

            if (candidate != null && rms >= attackRmsGate && (!inDecayGuard || isFreshAttack || alreadyConfirming)) {
                val bestConf = max(frame.confidence, frame.onsetConfidence)
                if (pendingAttackMidi == candidate) {
                    pendingAttackCount++
                } else {
                    pendingAttackMidi = candidate
                    pendingAttackStartMs = timestampMs
                    pendingAttackCount = 1
                }

                // Confirm after 2 fast agreeing frames (~23ms at 11.6ms hop) — skips 1-frame hammer transients!
                if (pendingAttackCount >= 2) {
                    val startEnergy = max(frame.energyForMidi(candidate), 1.0f)
                    startNewNote(
                        midi = candidate,
                        timestampMs = pendingAttackStartMs,
                        confidence = bestConf,
                        energy = startEnergy,
                        rms = rms
                    )
                    clearPendingAttack()
                }
            } else {
                clearPendingAttack()
            }
        } else {
            // ================= LOCKED SUSTAIN STATE (Pitch frozen on `current`) =================
            val lockedEnergy = frame.energyForMidi(current)
            val lockedDeltaRatio = frame.deltaRatioForMidi(current)
            if (lockedEnergy > activePeakEnergy) activePeakEnergy = lockedEnergy
            if (rms > activePeakRms) activePeakRms = rms

            val timeSinceStart = timestampMs - activeStartMs

            // 1. Check for a GENUINE NEW HAMMER ONSET (fast legato new key OR repeated same key)
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

            val isPlungingDecay = prevRms > 0.005f && rms < prevRms * 0.86f
            val newKeyEnergy = if (onsetMidi != null) frame.energyForMidi(onsetMidi) else 0f
            val newKeyBeatsDecaying = onsetMidi != null &&
                newKeyEnergy >= lockedEnergy * 0.75f &&
                frame.onsetConfidence >= 0.60f

            val strongNewKeyOnset = onsetMidi != null &&
                onsetMidi != current &&
                onsetMidi in MusicTheory.MIN_PIANO_MIDI..MusicTheory.MAX_PIANO_MIDI &&
                frame.onsetConfidence >= 0.45f &&
                (frame.isOnset || frame.onsetConfidence >= 0.56f) &&
                (!isPlungingDecay || newKeyBeatsDecaying) &&
                rms >= attackRmsGate &&
                timeSinceStart >= minNewNoteSwitchMs

            // Refuse decaying sub-octave ghosts unless there is a sharp global onset + high confidence
            val validNewKeyOnset = strongNewKeyOnset &&
                (onsetMidi != current - 12 || (frame.isOnset && frame.onsetConfidence >= 0.64f))

            // Same-key restrike: requires a real valley-to-peak hammer strike (not unison beating / AGC swell)
            val beatGuard = calibrationProfile?.beatingGuardFactor?.coerceIn(1.0f, 1.38f) ?: 1.0f
            val prevJumpRatio = 1.34f * sqrt(beatGuard)
            val valleyJumpRatio = 1.72f * beatGuard
            val sameKeyRestrike = frame.isOnset &&
                lockedDeltaRatio >= 0.30f &&
                (onsetMidi == current || frame.midiNote == current) &&
                lockedEnergy > activePrevEnergy * prevJumpRatio &&
                lockedEnergy > activeMinEnergySincePeak * valleyJumpRatio &&
                lockedEnergy >= activePeakEnergy * 0.32f &&
                rms >= activePeakRms * 0.30f &&
                rms > prevRms * 1.15f &&
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

                // Confirm over 2 fast agreeing frames (~23ms at 11.6ms hop) — prevents 1-frame 2*f0 octave spikes!
                if (pendingAttackCount >= 2) {
                    commitActiveNote(pendingAttackStartMs)
                    startNewNote(
                        midi = targetMidi,
                        timestampMs = pendingAttackStartMs,
                        confidence = frame.onsetConfidence,
                        energy = max(newKeyEnergy, 1.0f),
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
                val isDampedFrame = activePrevEnergy > 1.2f &&
                    lockedEnergy < activePeakEnergy * 0.14f &&
                    (lockedEnergy < activePrevEnergy * 0.35f || dampedStartMs >= 0L) &&
                    timeSinceStart > 110L

                val fadedOut = rms < releaseRmsGate ||
                    (lockedEnergy < 0.28f && rms < attackRmsGate * 1.15f) ||
                    (activePeakEnergy > 1.0f && lockedEnergy < activePeakEnergy * 0.025f && rms < attackRmsGate * 1.25f)

                if (isDampedFrame) {
                    if (dampedStartMs < 0L) dampedStartMs = timestampMs
                    if (timestampMs - dampedStartMs >= dampedReleaseMs) {
                        lastReleasedMidi = current
                        lastReleasedPeakRms = activePeakRms
                        commitActiveNote(timestampMs)
                        postReleaseDecayGuardMs = timestampMs + 380L
                    }
                } else if (fadedOut) {
                    dampedStartMs = -1L
                    if (lowEnergyStartMs < 0L) lowEnergyStartMs = timestampMs
                    if (timestampMs - lowEnergyStartMs >= releaseSilentMs) {
                        lastReleasedMidi = current
                        lastReleasedPeakRms = activePeakRms
                        commitActiveNote(activeLastSeenMs)
                        postReleaseDecayGuardMs = timestampMs + 420L
                    }
                } else {
                    dampedStartMs = -1L
                    lowEnergyStartMs = -1L
                    activeLastSeenMs = timestampMs
                    activeConfidenceSum += max(frame.confidence, 0.5f)
                    activeFrameCount++
                }
                if (lockedEnergy < activeMinEnergySincePeak) {
                    activeMinEnergySincePeak = lockedEnergy
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
            isClipping = recentClippingFrames >= 3,
            capturedNotes = finalizedNotes.toList()
        )
    }

    private fun selectCleanOnsetCandidate(frame: SpectralPitchFrame): Int? {
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
            rawNotes = finalizedNotes.toList(),
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
        activeMinEnergySincePeak = energy
        activePeakRms = rms
        activePrevEnergy = energy
        lowEnergyStartMs = -1L
        dampedStartMs = -1L
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
        activeMinEnergySincePeak = 0f
        activePeakRms = 0f
        activePrevEnergy = 0f
        lowEnergyStartMs = -1L
        dampedStartMs = -1L
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
                60_000.0 / fallbackBpm.coerceIn(MetronomeEngine.MIN_BPM, MetronomeEngine.MAX_BPM)
            } else {
                estimateNaturalBeatMs(rawNotes, fallbackBpm)
            }
            val effectiveBpm = (60_000.0 / msPerBeat).roundToInt()
                .coerceIn(MetronomeEngine.MIN_BPM, MetronomeEngine.MAX_BPM)

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
                    val trimmedMs = (note.durationMs - 60L).coerceAtLeast(80L)
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
                if (dt in 70.0..2800.0) {
                    iois.add(dt)
                }
            }
            if (iois.isEmpty()) return 60_000.0 / fallbackBpm.coerceIn(60, 140)

            var bestBeatMs = 60_000.0 / fallbackBpm.coerceIn(60, 140)
            var bestLoss = Double.MAX_VALUE
            val allowedRatios = doubleArrayOf(0.25, 0.5, 1.0, 2.0, 4.0)

            for (bpm in 60..140 step 2) {
                val beatMs = 60_000.0 / bpm
                var totalLoss = 0.0
                var quarterCount = 0
                var eighthCount = 0

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
                    if (bestR == 0.5) eighthCount++
                }

                val quarterRatio = quarterCount.toDouble() / iois.size
                val eighthRatio = eighthCount.toDouble() / iois.size
                val preferenceBonus = -0.15 * quarterRatio - 0.06 * eighthRatio
                val tempoPrior = 0.0008 * abs(bpm - 96)
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
                rawBeats < 0.37 -> 0.25f // Šesnaestina (1/16)
                rawBeats < 0.72 -> 0.5f  // Osmina (1/8)
                rawBeats < 1.52 -> 1.0f  // Četvrtina (1/4)
                rawBeats < 2.85 -> 2.0f  // Polovina (1/2)
                else -> 4.0f             // Cela nota (1/1)
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
