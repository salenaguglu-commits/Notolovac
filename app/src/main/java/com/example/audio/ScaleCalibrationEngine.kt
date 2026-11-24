package com.example.audio

import com.example.model.MusicTheory
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

enum class ScaleCalibrationPhase {
    IDLE,
    SILENCE_CHECK,
    MEASURING_NOTE,
    COMPLETED
}

data class ScaleNoteMeasurement(
    val midi: Int,
    val measuredCents: Float,
    val measuredHz: Double,
    val peakRms: Float,
    val maxClippingRatio: Float,
    val beatingSwellRatio: Float
)

enum class ScaleStatusCode {
    IDLE,
    SILENCE_START,
    SILENCE_LOUD,
    SILENCE_MEASURING,
    SILENCE_DONE,
    PRESS_KEY,
    HOLD_KEY,
    NOTE_DONE,
    LOCKING_NOTE,
    NEXT_KEY,
    WRONG_NOTE,
    COMPLETED,
    MIC_UNAVAILABLE
}

data class ScaleCalibrationSnapshot(
    val phase: ScaleCalibrationPhase = ScaleCalibrationPhase.IDLE,
    val silenceProgress: Float = 0f,
    val currentNoteIndex: Int = 0,
    val currentNoteLockedSamples: Int = 0,
    val requiredSamplesPerNote: Int = 24,
    val completedNotes: List<ScaleNoteMeasurement> = emptyList(),
    val heardNoteName: String? = null,
    val heardRawHz: Double = 0.0,
    val isWrongNoteWarning: Boolean = false,
    val liveRms: Float = 0f,
    val statusCode: ScaleStatusCode = ScaleStatusCode.IDLE,
    val statusPrimaryNote: String? = null,
    val statusSecondaryNote: String? = null,
    val statusCents: Int = 0,
    val statusMessage: String = "",
    val resultingProfile: PianoCalibrationProfile? = null
) {
    val currentTargetMidi: Int
        get() = ScaleCalibrationEngine.SCALE_NOTES.getOrElse(currentNoteIndex) { 60 }

    val currentNoteProgress: Float
        get() = (currentNoteLockedSamples.toFloat() / requiredSamplesPerNote.coerceAtLeast(1)).coerceIn(0f, 1f)

    val overallProgress: Float
        get() = when (phase) {
            ScaleCalibrationPhase.IDLE -> 0f
            ScaleCalibrationPhase.SILENCE_CHECK -> silenceProgress * 0.12f
            ScaleCalibrationPhase.MEASURING_NOTE -> {
                val perNote = 0.88f / ScaleCalibrationEngine.SCALE_NOTES.size
                0.12f + (completedNotes.size + currentNoteProgress) * perNote
            }
            ScaleCalibrationPhase.COMPLETED -> 1f
        }.coerceIn(0f, 1f)
}

/**
 * Guided 8-note C-major scale calibration engine (Do4 -> Re4 -> Mi4 -> Fa4 -> Sol4 -> La4 -> Si4 -> Do5).
 *
 * Step 1 (~1.0s): Measures room ambient noise floor while quiet.
 * Step 2 (8 keys): Guides the pianist note-by-note through C4..C5, tolerating pianos detuned up to ±75 ct,
 *                  ignoring the decaying reverb tail of the previous key so legato playing works seamlessly,
 *                  and measuring each key's true frequency, strike loudness, clipping, and unison beating swell.
 * Step 3: Computes a personalized [PianoCalibrationProfile] for [PianoPitchEngine] and [PianoNoteTracker].
 */
class ScaleCalibrationEngine(
    private val silenceTargetFrames: Int = 85,
    private val requiredSamplesPerNote: Int = 24
) {
    private var phase: ScaleCalibrationPhase = ScaleCalibrationPhase.IDLE

    // Step 1: Silence check state
    private val quietRmsSamples = mutableListOf<Float>()
    private var totalSilenceElapsedFrames = 0
    private var measuredAmbientRms = 0.0025f

    // Step 2: Per-note measurement state
    private var currentNoteIndex = 0
    private val completedMeasurements = mutableListOf<ScaleNoteMeasurement>()
    private val currentCentsSamples = mutableListOf<Float>()
    private val currentHzSamples = mutableListOf<Double>()
    private var currentPeakRms = 0f
    private var currentMaxClip = 0f
    private var currentPeakEnergy = 0f
    private var currentMinEnergySincePeak = Float.MAX_VALUE
    private var currentMaxSwellRatio = 1.0f
    private var wrongNoteMidi: Int? = null
    private var wrongNoteFrames = 0
    private var lastProfile: PianoCalibrationProfile? = null

    fun start(): ScaleCalibrationSnapshot {
        phase = ScaleCalibrationPhase.SILENCE_CHECK
        quietRmsSamples.clear()
        totalSilenceElapsedFrames = 0
        measuredAmbientRms = 0.0025f
        currentNoteIndex = 0
        completedMeasurements.clear()
        resetCurrentNoteBuffers()
        lastProfile = null
        return ScaleCalibrationSnapshot(
            phase = ScaleCalibrationPhase.SILENCE_CHECK,
            silenceProgress = 0f,
            requiredSamplesPerNote = requiredSamplesPerNote,
            statusCode = ScaleStatusCode.SILENCE_START
        )
    }

    fun reset(): ScaleCalibrationSnapshot {
        phase = ScaleCalibrationPhase.IDLE
        quietRmsSamples.clear()
        completedMeasurements.clear()
        resetCurrentNoteBuffers()
        lastProfile = null
        return ScaleCalibrationSnapshot(requiredSamplesPerNote = requiredSamplesPerNote)
    }

    fun processFrame(frame: SpectralPitchFrame): ScaleCalibrationSnapshot {
        return when (phase) {
            ScaleCalibrationPhase.IDLE -> ScaleCalibrationSnapshot(requiredSamplesPerNote = requiredSamplesPerNote)
            ScaleCalibrationPhase.COMPLETED -> buildCompletedSnapshot(frame.rms)
            ScaleCalibrationPhase.SILENCE_CHECK -> processSilenceFrame(frame)
            ScaleCalibrationPhase.MEASURING_NOTE -> processScaleNoteFrame(frame)
        }
    }

    private fun processSilenceFrame(frame: SpectralPitchFrame): ScaleCalibrationSnapshot {
        totalSilenceElapsedFrames++
        if (frame.rms < 0.045f) {
            quietRmsSamples.add(frame.rms)
        }

        val doneByQuiet = quietRmsSamples.size >= silenceTargetFrames
        val doneByTimeout = totalSilenceElapsedFrames >= silenceTargetFrames * 2

        if (doneByQuiet || doneByTimeout) {
            measuredAmbientRms = if (quietRmsSamples.isNotEmpty()) {
                val sorted = quietRmsSamples.sorted()
                val p75 = sorted[(sorted.size * 3) / 4]
                p75.coerceIn(0.0015f, 0.015f)
            } else {
                0.004f
            }
            phase = ScaleCalibrationPhase.MEASURING_NOTE
            currentNoteIndex = 0
            resetCurrentNoteBuffers()
            val firstLabel = MusicTheory.formatFullNoteDescription(SCALE_NOTES[0])
            return ScaleCalibrationSnapshot(
                phase = ScaleCalibrationPhase.MEASURING_NOTE,
                silenceProgress = 1f,
                currentNoteIndex = 0,
                currentNoteLockedSamples = 0,
                requiredSamplesPerNote = requiredSamplesPerNote,
                completedNotes = emptyList(),
                liveRms = frame.rms,
                statusCode = ScaleStatusCode.SILENCE_DONE,
                statusPrimaryNote = firstLabel
            )
        }

        val progress = (quietRmsSamples.size.toFloat() / silenceTargetFrames).coerceIn(0f, 1f)
        val code = if (frame.rms >= 0.045f) {
            ScaleStatusCode.SILENCE_LOUD
        } else {
            ScaleStatusCode.SILENCE_MEASURING
        }
        return ScaleCalibrationSnapshot(
            phase = ScaleCalibrationPhase.SILENCE_CHECK,
            silenceProgress = progress,
            requiredSamplesPerNote = requiredSamplesPerNote,
            liveRms = frame.rms,
            statusCode = code
        )
    }

    private fun processScaleNoteFrame(frame: SpectralPitchFrame): ScaleCalibrationSnapshot {
        val targetMidi = SCALE_NOTES[currentNoteIndex]
        val prevMidi = if (currentNoteIndex > 0) SCALE_NOTES[currentNoteIndex - 1] else null
        val targetLabel = MusicTheory.formatFullNoteDescription(targetMidi)
        val minRmsGate = max(measuredAmbientRms * 2.2f, 0.0032f)

        val hasClearPitch = frame.midiNote != null &&
            frame.frequencyHz > 0.0 &&
            frame.confidence >= 0.38f &&
            frame.rms >= minRmsGate

        if (!hasClearPitch) {
            wrongNoteFrames = 0
            return ScaleCalibrationSnapshot(
                phase = ScaleCalibrationPhase.MEASURING_NOTE,
                silenceProgress = 1f,
                currentNoteIndex = currentNoteIndex,
                currentNoteLockedSamples = currentCentsSamples.size,
                requiredSamplesPerNote = requiredSamplesPerNote,
                completedNotes = completedMeasurements.toList(),
                liveRms = frame.rms,
                statusCode = if (currentCentsSamples.isEmpty()) ScaleStatusCode.PRESS_KEY else ScaleStatusCode.HOLD_KEY,
                statusPrimaryNote = targetLabel
            )
        }

        val rawMidiFloat = MusicTheory.frequencyToMidiFloat(frame.frequencyHz, 0f)
        val centsFromTarget = ((rawMidiFloat - targetMidi) * 100.0).toFloat()

        // Does the heard fundamental match the expected scale key (within ±75 ct for heavily detuned pianos)?
        if (abs(centsFromTarget) <= 75f) {
            wrongNoteMidi = null
            wrongNoteFrames = 0

            val unstableJump = currentCentsSamples.isNotEmpty() &&
                abs(currentCentsSamples.last() - centsFromTarget) > 22f

            if (unstableJump) {
                currentCentsSamples.clear()
                currentHzSamples.clear()
            }

            currentCentsSamples.add(centsFromTarget)
            currentHzSamples.add(frame.frequencyHz)
            if (frame.rms > currentPeakRms) currentPeakRms = frame.rms
            if (frame.clippingRatio > currentMaxClip) currentMaxClip = frame.clippingRatio

            // Measure single-note sustain beating/AGC swell on the locked target key
            val energy = frame.energyForMidi(targetMidi)
            if (energy > currentPeakEnergy) {
                currentPeakEnergy = energy
                currentMinEnergySincePeak = energy
            } else if (energy > 0.25f) {
                if (energy < currentMinEnergySincePeak) {
                    currentMinEnergySincePeak = energy
                } else if (currentMinEnergySincePeak > 0.25f && currentCentsSamples.size >= 4) {
                    val swell = energy / currentMinEnergySincePeak
                    if (swell > currentMaxSwellRatio) {
                        currentMaxSwellRatio = swell
                    }
                }
            }

            val roundedCt = centsFromTarget.roundToInt()
            val sign = if (roundedCt > 0) "+" else ""
            val hzStr = String.format(Locale.US, "%.1f Hz", frame.frequencyHz)

            if (currentCentsSamples.size >= requiredSamplesPerNote) {
                val sortedCents = currentCentsSamples.sorted()
                val sortedHz = currentHzSamples.sorted()
                val medianCents = sortedCents[sortedCents.size / 2]
                val medianHz = sortedHz[sortedHz.size / 2]

                completedMeasurements.add(
                    ScaleNoteMeasurement(
                        midi = targetMidi,
                        measuredCents = medianCents,
                        measuredHz = medianHz,
                        peakRms = currentPeakRms,
                        maxClippingRatio = currentMaxClip,
                        beatingSwellRatio = currentMaxSwellRatio
                    )
                )

                currentNoteIndex++
                resetCurrentNoteBuffers()

                if (currentNoteIndex >= SCALE_NOTES.size) {
                    lastProfile = buildFinalProfile()
                    phase = ScaleCalibrationPhase.COMPLETED
                    return buildCompletedSnapshot(frame.rms)
                }

                val nextLabel = MusicTheory.formatFullNoteDescription(SCALE_NOTES[currentNoteIndex])
                return ScaleCalibrationSnapshot(
                    phase = ScaleCalibrationPhase.MEASURING_NOTE,
                    silenceProgress = 1f,
                    currentNoteIndex = currentNoteIndex,
                    currentNoteLockedSamples = 0,
                    requiredSamplesPerNote = requiredSamplesPerNote,
                    completedNotes = completedMeasurements.toList(),
                    heardNoteName = targetLabel,
                    heardRawHz = medianHz,
                    liveRms = frame.rms,
                    statusCode = ScaleStatusCode.NOTE_DONE,
                    statusPrimaryNote = targetLabel,
                    statusSecondaryNote = nextLabel
                )
            }

            return ScaleCalibrationSnapshot(
                phase = ScaleCalibrationPhase.MEASURING_NOTE,
                silenceProgress = 1f,
                currentNoteIndex = currentNoteIndex,
                currentNoteLockedSamples = currentCentsSamples.size,
                requiredSamplesPerNote = requiredSamplesPerNote,
                completedNotes = completedMeasurements.toList(),
                heardNoteName = targetLabel,
                heardRawHz = frame.frequencyHz,
                liveRms = frame.rms,
                statusCode = ScaleStatusCode.LOCKING_NOTE,
                statusPrimaryNote = targetLabel,
                statusCents = roundedCt
            )
        }

        // Ignore decaying reverb tail of the previous scale note so legato transitions stay smooth!
        if (prevMidi != null && abs((rawMidiFloat - prevMidi) * 100.0) <= 75.0) {
            wrongNoteFrames = 0
            return ScaleCalibrationSnapshot(
                phase = ScaleCalibrationPhase.MEASURING_NOTE,
                silenceProgress = 1f,
                currentNoteIndex = currentNoteIndex,
                currentNoteLockedSamples = currentCentsSamples.size,
                requiredSamplesPerNote = requiredSamplesPerNote,
                completedNotes = completedMeasurements.toList(),
                liveRms = frame.rms,
                statusCode = ScaleStatusCode.NEXT_KEY,
                statusPrimaryNote = targetLabel
            )
        }

        // Heard a genuinely different key -> warn after 3 consecutive frames
        val heardMidi = frame.midiNote
        if (wrongNoteMidi == heardMidi) {
            wrongNoteFrames++
        } else {
            wrongNoteMidi = heardMidi
            wrongNoteFrames = 1
        }

        val showWarning = wrongNoteFrames >= 3
        val heardLabel = MusicTheory.formatFullNoteDescription(heardMidi)

        return ScaleCalibrationSnapshot(
            phase = ScaleCalibrationPhase.MEASURING_NOTE,
            silenceProgress = 1f,
            currentNoteIndex = currentNoteIndex,
            currentNoteLockedSamples = currentCentsSamples.size,
            requiredSamplesPerNote = requiredSamplesPerNote,
            completedNotes = completedMeasurements.toList(),
            heardNoteName = if (showWarning) heardLabel else null,
            heardRawHz = if (showWarning) frame.frequencyHz else 0.0,
            isWrongNoteWarning = showWarning,
            liveRms = frame.rms,
            statusCode = if (showWarning) ScaleStatusCode.WRONG_NOTE else ScaleStatusCode.PRESS_KEY,
            statusPrimaryNote = if (showWarning) heardLabel else targetLabel,
            statusSecondaryNote = if (showWarning) targetLabel else null,
            statusMessage = if (showWarning) "Heard $heardLabel — press $targetLabel" else "Press $targetLabel"
        )
    }

    private fun buildFinalProfile(): PianoCalibrationProfile {
        val sortedCents = completedMeasurements.map { it.measuredCents }.sorted()
        val globalCents = sortedCents[sortedCents.size / 2].coerceIn(-85f, 85f)
        val c4Hz = (MusicTheory.STANDARD_C4_HZ * 2.0.pow(globalCents / 1200.0)).toFloat()

        // Linear regression for piano octave stretch (Railsback curve) across C4..C5 (centered at MIDI 66)
        val meanX = completedMeasurements.map { it.midi - 66.0 }.average()
        val meanY = completedMeasurements.map { (it.measuredCents - globalCents).toDouble() }.average()
        var num = 0.0
        var den = 0.0
        for (m in completedMeasurements) {
            val dx = (m.midi - 66.0) - meanX
            val dy = (m.measuredCents - globalCents).toDouble() - meanY
            num += dx * dy
            den += dx * dx
        }
        val slopePerSemitone = if (den > 1e-6) num / den else 0.0
        val stretchPerOctave = (slopePerSemitone * 12.0).toFloat().coerceIn(-25f, 35f)

        // Blend 75% measured key residual + 25% smooth linear stretch to suppress single-key jitter
        val perKeyOffsets = mutableMapOf<Int, Float>()
        for (m in completedMeasurements) {
            val measuredResidual = (m.measuredCents - globalCents).coerceIn(-35f, 35f)
            val smoothStretch = stretchPerOctave * ((m.midi - 66f) / 12f)
            val blended = (measuredResidual * 0.75f + smoothStretch * 0.25f).coerceIn(-35f, 35f)
            perKeyOffsets[m.midi] = blended
        }

        val sortedPeakRms = completedMeasurements.map { it.peakRms }.sorted()
        val medianStrikeRms = sortedPeakRms[sortedPeakRms.size / 2].coerceIn(0.01f, 0.85f)

        val maxClip = completedMeasurements.maxOfOrNull { it.maxClippingRatio } ?: 0f

        // Translate observed single-note beating/AGC swell into a safe adaptive restrike guard
        val maxSwell = completedMeasurements.maxOfOrNull { it.beatingSwellRatio } ?: 1.0f
        val beatingGuard = when {
            maxSwell <= 1.25f -> 1.0f
            else -> (1.0f + (maxSwell - 1.25f) * 0.45f).coerceIn(1.0f, 1.38f)
        }

        return PianoCalibrationProfile(
            globalCents = globalCents,
            calibratedC4Hz = c4Hz,
            isCalibrated = true,
            isScaleCalibrated = true,
            octaveStretchCentsPerOctave = stretchPerOctave,
            perKeyCentsOffsets = perKeyOffsets,
            ambientNoiseRms = measuredAmbientRms,
            medianStrikeRms = medianStrikeRms,
            beatingGuardFactor = beatingGuard,
            maxClippingRatio = maxClip
        )
    }

    private fun buildCompletedSnapshot(liveRms: Float): ScaleCalibrationSnapshot {
        val profile = lastProfile ?: PianoCalibrationProfile.STANDARD
        return ScaleCalibrationSnapshot(
            phase = ScaleCalibrationPhase.COMPLETED,
            silenceProgress = 1f,
            currentNoteIndex = SCALE_NOTES.size,
            currentNoteLockedSamples = requiredSamplesPerNote,
            requiredSamplesPerNote = requiredSamplesPerNote,
            completedNotes = completedMeasurements.toList(),
            liveRms = liveRms,
            statusCode = ScaleStatusCode.COMPLETED,
            resultingProfile = profile
        )
    }

    private fun resetCurrentNoteBuffers() {
        currentCentsSamples.clear()
        currentHzSamples.clear()
        currentPeakRms = 0f
        currentMaxClip = 0f
        currentPeakEnergy = 0f
        currentMinEnergySincePeak = Float.MAX_VALUE
        currentMaxSwellRatio = 1.0f
        wrongNoteMidi = null
        wrongNoteFrames = 0
    }

    companion object {
        // Middle C major octave: Do4, Re4, Mi4, Fa4, Sol4, La4, Si4, Do5
        val SCALE_NOTES = intArrayOf(60, 62, 64, 65, 67, 69, 71, 72)
    }
}
