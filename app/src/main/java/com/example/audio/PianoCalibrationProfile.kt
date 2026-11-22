package com.example.audio

import com.example.model.MusicTheory
import java.util.Locale
import kotlin.math.pow

/**
 * Complete acoustic profile of the user's specific piano + room + microphone placement.
 *
 * Can be created either from:
 * 1) Quick single-note C calibration (`isScaleCalibrated = false`), or
 * 2) Guided C4–C5 scale calibration (`isScaleCalibrated = true`), which captures:
 *    - Global pitch offset & C4/A4 reference frequency
 *    - Per-key tuning deviations & acoustic piano octave stretch (Railsback slope)
 *    - Room ambient noise floor & typical struck-key loudness (adaptive onset gate)
 *    - Piano unison-string beating / mic AGC swell factor (prevents duplicate note splitting)
 *    - Close-up microphone clipping warning
 */
data class PianoCalibrationProfile(
    val globalCents: Float = 0f,
    val calibratedC4Hz: Float = MusicTheory.STANDARD_C4_HZ.toFloat(),
    val isCalibrated: Boolean = false,
    val isScaleCalibrated: Boolean = false,
    val octaveStretchCentsPerOctave: Float = 0f,
    val perKeyCentsOffsets: Map<Int, Float> = emptyMap(),
    val ambientNoiseRms: Float = 0.0025f,
    val medianStrikeRms: Float = 0.08f,
    val beatingGuardFactor: Float = 1.0f,
    val maxClippingRatio: Float = 0f
) {
    val equivalentA4Hz: Double
        get() = MusicTheory.equivalentA4Hz(if (isCalibrated) globalCents else 0f)

    val clippingWarning: Boolean
        get() = isScaleCalibrated && maxClippingRatio >= 0.03f

    /**
     * Returns the effective pitch calibration offset in cents for a specific MIDI key.
     * - If uncalibrated: 0f
     * - If quick C-calibrated: globalCents
     * - If scale-calibrated: uses the measured per-key residual when available, or smoothly
     *   extrapolates using the measured octave-stretch slope centered at F#4 (MIDI 66).
     */
    fun effectiveCentsForMidi(midi: Int): Float {
        if (!isCalibrated) return 0f
        if (!isScaleCalibrated) return globalCents
        val perKeyResidual = perKeyCentsOffsets[midi]
        if (perKeyResidual != null) {
            return (globalCents + perKeyResidual).coerceIn(-95f, 95f)
        }
        val stretchCents = (octaveStretchCentsPerOctave * ((midi - 66f) / 12f)).coerceIn(-25f, 30f)
        return (globalCents + stretchCents).coerceIn(-95f, 95f)
    }

    /**
     * Serializes the profile into a compact deterministic string safe for DataStore and pure JVM tests.
     */
    fun serialize(): String {
        val keyEntries = perKeyCentsOffsets.entries
            .sortedBy { it.key }
            .joinToString(",") { (midi, residual) ->
                "$midi:${String.format(Locale.US, "%.2f", residual)}"
            }
        return listOf(
            "v1",
            String.format(Locale.US, "%.3f", globalCents),
            String.format(Locale.US, "%.3f", calibratedC4Hz),
            if (isCalibrated) "1" else "0",
            if (isScaleCalibrated) "1" else "0",
            String.format(Locale.US, "%.3f", octaveStretchCentsPerOctave),
            String.format(Locale.US, "%.5f", ambientNoiseRms),
            String.format(Locale.US, "%.5f", medianStrikeRms),
            String.format(Locale.US, "%.3f", beatingGuardFactor),
            String.format(Locale.US, "%.4f", maxClippingRatio),
            keyEntries
        ).joinToString("|")
    }

    companion object {
        val STANDARD = PianoCalibrationProfile()

        fun fromQuickC(cents: Float, c4Hz: Float, isCalibrated: Boolean): PianoCalibrationProfile {
            if (!isCalibrated) return STANDARD
            return PianoCalibrationProfile(
                globalCents = cents,
                calibratedC4Hz = c4Hz,
                isCalibrated = true,
                isScaleCalibrated = false
            )
        }

        fun deserialize(
            raw: String?,
            fallbackCents: Float = 0f,
            fallbackC4Hz: Float = MusicTheory.STANDARD_C4_HZ.toFloat(),
            fallbackIsCalibrated: Boolean = false
        ): PianoCalibrationProfile {
            if (raw.isNullOrBlank()) {
                return fromQuickC(fallbackCents, fallbackC4Hz, fallbackIsCalibrated)
            }
            return runCatching {
                val parts = raw.split("|")
                if (parts.size < 10 || parts[0] != "v1") {
                    return@runCatching fromQuickC(fallbackCents, fallbackC4Hz, fallbackIsCalibrated)
                }
                val globalCents = parts[1].toFloat().coerceIn(-95f, 95f)
                val c4Hz = parts[2].toFloat().coerceIn(220f, 310f)
                val isCal = parts[3] == "1"
                val isScale = parts[4] == "1"
                val stretch = parts[5].toFloat().coerceIn(-25f, 35f)
                val ambient = parts[6].toFloat().coerceIn(0.001f, 0.03f)
                val strike = parts[7].toFloat().coerceIn(0.008f, 0.9f)
                val beating = parts[8].toFloat().coerceIn(1.0f, 1.45f)
                val clip = parts[9].toFloat().coerceIn(0f, 1f)

                val keyMap = mutableMapOf<Int, Float>()
                val rawKeys = parts.getOrNull(10).orEmpty()
                if (rawKeys.isNotBlank()) {
                    rawKeys.split(",").forEach { item ->
                        val kv = item.split(":")
                        if (kv.size == 2) {
                            val m = kv[0].toIntOrNull()
                            val v = kv[1].toFloatOrNull()
                            if (m != null && v != null && m in MusicTheory.MIN_PIANO_MIDI..MusicTheory.MAX_PIANO_MIDI) {
                                keyMap[m] = v.coerceIn(-40f, 40f)
                            }
                        }
                    }
                }

                PianoCalibrationProfile(
                    globalCents = globalCents,
                    calibratedC4Hz = if (c4Hz > 0f) c4Hz else (MusicTheory.STANDARD_C4_HZ * 2.0.pow(globalCents / 1200.0)).toFloat(),
                    isCalibrated = isCal,
                    isScaleCalibrated = isCal && isScale,
                    octaveStretchCentsPerOctave = stretch,
                    perKeyCentsOffsets = keyMap,
                    ambientNoiseRms = ambient,
                    medianStrikeRms = strike,
                    beatingGuardFactor = beating,
                    maxClippingRatio = clip
                )
            }.getOrElse {
                fromQuickC(fallbackCents, fallbackC4Hz, fallbackIsCalibrated)
            }
        }
    }
}
