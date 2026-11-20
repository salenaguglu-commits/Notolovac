package com.example.audio

import com.example.model.MusicTheory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class SpectralPitchFrame(
    val midiNote: Int?,           // Winning piano MIDI note (45..84) or null if unpitched/silent
    val frequencyHz: Double,      // Parabolic-refined fundamental frequency in Hz
    val centsOff: Float,          // Deviation from calibrated target pitch in cents
    val confidence: Float,        // 0..1 harmonic clarity / SNR confidence
    val rms: Float,               // Time-domain RMS amplitude
    val onsetStrength: Float,     // Positive spectral flux (spikes sharply on new piano hammer strike)
    val isOnset: Boolean,         // True when spectral flux crosses adaptive onset threshold
    val activeNoteEnergy: Float   // Harmonic energy of the detected note (tracks damper release cleanly)
)

/**
 * High-accuracy Piano Pitch & Onset Detection Engine based on:
 * - 4096-sample Hann-windowed, 8192-point zero-padded Radix-2 FFT (5.38 Hz bin resolution, <0.5 Hz interpolated)
 * - Positive Spectral Flux onset detection (isolates newly struck piano hammers even over ringing room tails)
 * - Delta-Spectrum + Full-Spectrum Piano Harmonic Comb scoring (harmonics h = 1..5 with inharmonicity)
 * - Strict Fundamental Presence & Odd-Harmonic Anti-Octave / Anti-Fifth suppression
 */
class PianoPitchEngine(
    private val sampleRate: Int = 44100,
    private val windowSize: Int = 4096,
    private var calibrationCents: Float = 0f
) {
    private val fftSize = 8192
    private val halfFft = fftSize / 2
    private val binHz = sampleRate.toDouble() / fftSize.toDouble()

    private val hannWindow = DoubleArray(windowSize) { i ->
        0.5 * (1.0 - cos(2.0 * PI * i / (windowSize - 1)))
    }

    private val fftReal = DoubleArray(fftSize)
    private val fftImag = DoubleArray(fftSize)
    private val mag = FloatArray(halfFft)
    private val prevMag = FloatArray(halfFft)
    private val deltaMag = FloatArray(halfFft)

    // Precomputed bit-reversal & twiddle tables for fast zero-allocation Radix-2 FFT
    private val bitRev = IntArray(fftSize)
    private val cosTable = DoubleArray(halfFft)
    private val sinTable = DoubleArray(halfFft)

    // MIDI evaluation range: A2 (45) .. C6 (84)
    private val minMidi = 45
    private val maxMidi = 84
    private val numCandidates = maxMidi - minMidi + 1
    private val candidateScores = FloatArray(numCandidates)
    private val candidateFundEnergies = FloatArray(numCandidates)

    // Adaptive spectral flux history for onset detection
    private val fluxHistory = FloatArray(10)
    private var fluxIndex = 0
    private var hasPrevSpectrum = false
    private var lastWinningMidi: Int? = null

    init {
        val logN = (ln(fftSize.toDouble()) / ln(2.0)).toInt()
        for (i in 0 until fftSize) {
            var x = i
            var r = 0
            for (b in 0 until logN) {
                r = (r shl 1) or (x and 1)
                x = x ushr 1
            }
            bitRev[i] = r
        }
        for (i in 0 until halfFft) {
            val angle = -2.0 * PI * i / fftSize
            cosTable[i] = cos(angle)
            sinTable[i] = sin(angle)
        }
    }

    fun setCalibrationCents(cents: Float) {
        calibrationCents = cents
    }

    fun reset() {
        prevMag.fill(0f)
        fluxHistory.fill(0f)
        fluxIndex = 0
        hasPrevSpectrum = false
        lastWinningMidi = null
    }

    fun analyze(pcmSamples: ShortArray, count: Int = windowSize): SpectralPitchFrame {
        val n = minOf(count, windowSize, pcmSamples.size)
        if (n < 1024) {
            return emptyFrame(0f)
        }

        // 1. Compute DC-removed RMS and apply Hann window into zero-padded FFT buffer
        var mean = 0.0
        for (i in 0 until n) {
            mean += pcmSamples[i] / 32768.0
        }
        mean /= n

        var sumSq = 0.0
        for (i in 0 until n) {
            val s = (pcmSamples[i] / 32768.0) - mean
            sumSq += s * s
            fftReal[i] = s * hannWindow[i]
            fftImag[i] = 0.0
        }
        for (i in n until fftSize) {
            fftReal[i] = 0.0
            fftImag[i] = 0.0
        }
        val rms = sqrt(sumSq / n).toFloat()

        // Early exit for dead silence
        if (rms < 0.0015f) {
            if (hasPrevSpectrum) {
                for (k in 0 until halfFft) prevMag[k] *= 0.7f
            }
            lastWinningMidi = null
            return emptyFrame(rms)
        }

        // 2. Run 8192-point in-place Radix-2 FFT
        computeFft()

        // 3. Compute Magnitude Spectrum (focus on piano musical band 80 Hz .. 4800 Hz)
        val minBin = max(2, (80.0 / binHz).toInt())
        val maxBin = minOf(halfFft - 2, (4800.0 / binHz).toInt())

        var rawFlux = 0f
        var magSum = 0f
        var magCount = 0

        for (k in minBin..maxBin) {
            val re = fftReal[k]
            val im = fftImag[k]
            val m = sqrt(re * re + im * im).toFloat()
            mag[k] = m
            magSum += m
            magCount++

            if (hasPrevSpectrum) {
                val diff = m - prevMag[k] * 0.85f
                if (diff > 0f) {
                    deltaMag[k] = diff
                    // Log-compressed spectral flux reacts strongly to new hammer strikes even at moderate volume
                    rawFlux += ln(1f + diff * 4f)
                } else {
                    deltaMag[k] = 0f
                }
            } else {
                deltaMag[k] = m
            }
        }

        val meanSpectralFloor = if (magCount > 0) magSum / magCount else 0.0001f

        // 4. Adaptive Onset Detection from Spectral Flux
        var avgFlux = 0f
        for (v in fluxHistory) avgFlux += v
        avgFlux /= fluxHistory.size

        val isOnset = hasPrevSpectrum &&
            rawFlux > max(12f, avgFlux * 2.15f + 6f) &&
            rms > 0.0045f

        fluxHistory[fluxIndex] = rawFlux
        fluxIndex = (fluxIndex + 1) % fluxHistory.size

        // 5. Score every piano key (A2..C6) using Weighted Harmonic Comb
        // On onset frames, blend 65% deltaMag + 35% full mag to cancel out previous ringing room notes!
        val useDeltaBlend = isOnset && hasPrevSpectrum
        val spectralSource = if (useDeltaBlend) {
            FloatArray(maxBin + 1).also { blended ->
                for (k in minBin..maxBin) {
                    blended[k] = deltaMag[k] * 0.65f + mag[k] * 0.35f
                }
            }
        } else {
            mag
        }

        val harmonicWeights = floatArrayOf(1.00f, 0.78f, 0.56f, 0.38f, 0.24f)
        // Slight piano string inharmonicity coefficient B (helps match upper partials of real upright/grand pianos)
        val inharmonicityB = 0.00012

        var bestIdx = -1
        var bestScore = 0f
        var secondBestScore = 0f

        for (idx in 0 until numCandidates) {
            val midi = minMidi + idx
            val f0 = MusicTheory.midiToFrequency(midi, calibrationCents)

            var harmonicScore = 0f
            var fundMag = 0f
            var activeHarmonics = 0

            for (h in 1..5) {
                val expectedHz = f0 * h * sqrt(1.0 + inharmonicityB * h * h)
                if (expectedHz > 4600.0) break

                // Search narrow +/- 38 cents band around expected harmonic frequency
                val tolHz = max(binHz * 1.15, expectedHz * 0.022)
                val loBin = max(minBin, ((expectedHz - tolHz) / binHz).toInt())
                val hiBin = minOf(maxBin, ((expectedHz + tolHz) / binHz).toInt() + 1)

                var peak = 0f
                for (k in loBin..hiBin) {
                    val v = spectralSource[k]
                    if (v > peak) peak = v
                }

                // Local spectral contrast (peak must stand out above local neighborhood, rejects broadband noise)
                val localNoise = estimateLocalFloor(spectralSource, loBin, hiBin, minBin, maxBin)
                val snr = peak / (localNoise + 0.002f)

                if (h == 1) {
                    fundMag = peak
                }

                if (snr > 2.2f && peak > meanSpectralFloor * 1.4f) {
                    // Log-magnitude compression prevents a single loud overtone from overriding a true fundamental
                    harmonicScore += harmonicWeights[h - 1] * ln(1f + (peak / (meanSpectralFloor + 0.001f)))
                    activeHarmonics++
                }
            }

            // CRITICAL ANTI-OCTAVE & ANTI-FIFTH RULES:
            // Rule A: For F3 (53) and above, the fundamental MUST actually have energy!
            // (If someone plays C5, C4 has zero energy at 261 Hz -> reject C4 immediately!)
            val fundThreshold = if (midi >= 53) meanSpectralFloor * 2.2f else meanSpectralFloor * 1.2f
            if (fundMag < fundThreshold) {
                harmonicScore *= 0.15f
            }

            // Rule B: Require at least 2 harmonics (or very strong fundamental for high C6 notes)
            if (activeHarmonics < 2 && midi < 78) {
                harmonicScore *= 0.45f
            }

            // Rule C: Gentle continuity hysteresis during sustain (non-onset) to prevent jitter
            if (!isOnset && lastWinningMidi == midi) {
                harmonicScore *= 1.12f
            }

            candidateScores[idx] = harmonicScore
            candidateFundEnergies[idx] = fundMag

            if (harmonicScore > bestScore) {
                secondBestScore = bestScore
                bestScore = harmonicScore
                bestIdx = idx
            } else if (harmonicScore > secondBestScore) {
                secondBestScore = harmonicScore
            }
        }

        // Sub-octave rescue check: if winning note is an octave above another candidate whose fundamental
        // IS clearly present AND has strong odd harmonics (3rd harmonic), prefer the lower fundamental!
        if (bestIdx >= 12) {
            val subIdx = bestIdx - 12
            val subMidi = minMidi + subIdx
            val subFund = candidateFundEnergies[subIdx]
            val winFund = candidateFundEnergies[bestIdx]
            if (subFund > meanSpectralFloor * 3.5f &&
                subFund >= winFund * 0.35f &&
                candidateScores[subIdx] >= bestScore * 0.78f
            ) {
                // Verify 3rd harmonic of subMidi (which is NOT a harmonic of winning note!)
                val fSub3 = MusicTheory.midiToFrequency(subMidi, calibrationCents) * 3.0
                val h3Peak = peakAround(spectralSource, fSub3, minBin, maxBin)
                if (h3Peak > meanSpectralFloor * 2.5f) {
                    bestIdx = subIdx
                    bestScore = candidateScores[subIdx]
                }
            }
        }

        // Save current magnitude spectrum for next frame's flux/delta calculation
        System.arraycopy(mag, minBin, prevMag, minBin, maxBin - minBin + 1)
        hasPrevSpectrum = true

        // 6. Validate winning score clarity (rejects speech, taps, claps, room noise)
        if (bestIdx == -1 || bestScore < 2.2f) {
            if (rms < 0.006f) lastWinningMidi = null
            return SpectralPitchFrame(
                midiNote = null,
                frequencyHz = 0.0,
                centsOff = 0f,
                confidence = 0f,
                rms = rms,
                onsetStrength = rawFlux,
                isOnset = isOnset,
                activeNoteEnergy = 0f
            )
        }

        val winningMidi = minMidi + bestIdx
        lastWinningMidi = winningMidi

        // 7. Parabolic sub-bin interpolation around fundamental (and 2nd harmonic) for razor-sharp Hz (for Štimuj C!)
        val refinedHz = refineFundamentalHz(winningMidi, minBin, maxBin)
        val (_, centsOff) = MusicTheory.frequencyToNearestMidi(refinedHz, calibrationCents)

        // Compute normalized confidence 0..1 from score strength and margin over runner-up
        val marginRatio = if (bestScore > 0f) ((bestScore - secondBestScore) / bestScore).coerceIn(0f, 1f) else 0f
        val strengthFactor = (bestScore / 7.5f).coerceIn(0f, 1f)
        val confidence = (0.55f * strengthFactor + 0.45f * max(marginRatio, 0.35f)).coerceIn(0f, 1f)

        // Compute total harmonic energy of winning note (used to detect piano key damper release!)
        val winEnergy = computeNoteHarmonicEnergy(winningMidi, minBin, maxBin)

        return SpectralPitchFrame(
            midiNote = winningMidi,
            frequencyHz = refinedHz,
            centsOff = centsOff,
            confidence = confidence,
            rms = rms,
            onsetStrength = rawFlux,
            isOnset = isOnset,
            activeNoteEnergy = winEnergy
        )
    }

    private fun refineFundamentalHz(midi: Int, minBin: Int, maxBin: Int): Double {
        val nominalF0 = MusicTheory.midiToFrequency(midi, calibrationCents)
        // Prefer 2nd harmonic / 2 for low notes (< 220 Hz) where bin spacing is coarser relative to semitone,
        // otherwise use fundamental directly when strong
        val f1PeakBin = findPeakBinAround(mag, nominalF0, minBin, maxBin)
        val f1Interp = parabolicBinFrequency(mag, f1PeakBin)

        val f2 = nominalF0 * 2.0
        if (f2 < 4000.0 && nominalF0 < 240.0) {
            val f2PeakBin = findPeakBinAround(mag, f2, minBin, maxBin)
            if (mag[f2PeakBin] > mag[f1PeakBin] * 0.8f) {
                return parabolicBinFrequency(mag, f2PeakBin) / 2.0
            }
        }
        return if (f1Interp > 0.0) f1Interp else nominalF0
    }

    private fun findPeakBinAround(spectrum: FloatArray, targetHz: Double, minBin: Int, maxBin: Int): Int {
        val tolHz = max(binHz * 1.5, targetHz * 0.035) // +/- ~60 cents for tuning detection
        val lo = max(minBin, ((targetHz - tolHz) / binHz).toInt())
        val hi = minOf(maxBin, ((targetHz + tolHz) / binHz).toInt() + 1)
        var bestK = lo
        var bestVal = -1f
        for (k in lo..hi) {
            if (spectrum[k] > bestVal) {
                bestVal = spectrum[k]
                bestK = k
            }
        }
        return bestK
    }

    private fun parabolicBinFrequency(spectrum: FloatArray, k: Int): Double {
        if (k <= 1 || k >= halfFft - 1) return k * binHz
        val alpha = ln(spectrum[k - 1].toDouble().coerceAtLeast(1e-6))
        val beta = ln(spectrum[k].toDouble().coerceAtLeast(1e-6))
        val gamma = ln(spectrum[k + 1].toDouble().coerceAtLeast(1e-6))
        val denom = (alpha - 2.0 * beta + gamma)
        val shift = if (abs(denom) > 1e-9) {
            (0.5 * (alpha - gamma) / denom).coerceIn(-0.5, 0.5)
        } else {
            0.0
        }
        return (k + shift) * binHz
    }

    private fun peakAround(spectrum: FloatArray, targetHz: Double, minBin: Int, maxBin: Int): Float {
        if (targetHz > 4600.0) return 0f
        val tolHz = max(binHz * 1.15, targetHz * 0.022)
        val lo = max(minBin, ((targetHz - tolHz) / binHz).toInt())
        val hi = minOf(maxBin, ((targetHz + tolHz) / binHz).toInt() + 1)
        var p = 0f
        for (k in lo..hi) {
            if (spectrum[k] > p) p = spectrum[k]
        }
        return p
    }

    private fun estimateLocalFloor(
        spectrum: FloatArray,
        loBin: Int,
        hiBin: Int,
        minBin: Int,
        maxBin: Int
    ): Float {
        val leftStart = max(minBin, loBin - 6)
        val leftEnd = max(minBin, loBin - 2)
        val rightStart = minOf(maxBin, hiBin + 2)
        val rightEnd = minOf(maxBin, hiBin + 6)
        var sum = 0f
        var count = 0
        for (k in leftStart..leftEnd) {
            sum += spectrum[k]
            count++
        }
        for (k in rightStart..rightEnd) {
            sum += spectrum[k]
            count++
        }
        return if (count > 0) sum / count else 0.001f
    }

    private fun computeNoteHarmonicEnergy(midi: Int, minBin: Int, maxBin: Int): Float {
        val f0 = MusicTheory.midiToFrequency(midi, calibrationCents)
        var energy = 0f
        for (h in 1..3) {
            val p = peakAround(mag, f0 * h, minBin, maxBin)
            energy += p
        }
        return energy
    }

    private fun computeFft() {
        // Bit-reversal permutation
        for (i in 0 until fftSize) {
            val j = bitRev[i]
            if (j > i) {
                val tr = fftReal[i]; fftReal[i] = fftReal[j]; fftReal[j] = tr
                val ti = fftImag[i]; fftImag[i] = fftImag[j]; fftImag[j] = ti
            }
        }
        // Cooley-Tukey iterative Radix-2 FFT
        var len = 2
        while (len <= fftSize) {
            val halfLen = len ushr 1
            val step = fftSize / len
            var i = 0
            while (i < fftSize) {
                var tableIdx = 0
                for (j in 0 until halfLen) {
                    val wr = cosTable[tableIdx]
                    val wi = sinTable[tableIdx]
                    tableIdx += step

                    val uRe = fftReal[i + j]
                    val uIm = fftImag[i + j]
                    val vIdx = i + j + halfLen
                    val vRe = fftReal[vIdx] * wr - fftImag[vIdx] * wi
                    val vIm = fftReal[vIdx] * wi + fftImag[vIdx] * wr

                    fftReal[i + j] = uRe + vRe
                    fftImag[i + j] = uIm + vIm
                    fftReal[vIdx] = uRe - vRe
                    fftImag[vIdx] = uIm - vIm
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun emptyFrame(rms: Float) = SpectralPitchFrame(
        midiNote = null,
        frequencyHz = 0.0,
        centsOff = 0f,
        confidence = 0f,
        rms = rms,
        onsetStrength = 0f,
        isOnset = false,
        activeNoteEnergy = 0f
    )
}
