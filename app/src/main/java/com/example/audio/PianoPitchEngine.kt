package com.example.audio

import com.example.model.MusicTheory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class SpectralPitchFrame(
    val midiNote: Int?,           // Best full-spectrum piano MIDI note (C3..C6) or null
    val onsetMidiNote: Int?,      // Best NEWLY STRUCK piano MIDI note from positive delta-spectrum
    val onsetConfidence: Float,   // Confidence of onsetMidiNote (0..1)
    val frequencyHz: Double,      // Parabolic-refined fundamental frequency in Hz
    val centsOff: Float,          // Deviation from calibrated target pitch in cents
    val confidence: Float,        // 0..1 harmonic clarity / SNR confidence
    val rms: Float,               // Time-domain RMS amplitude
    val onsetStrength: Float,     // Positive spectral flux
    val isOnset: Boolean,         // True when a genuine piano hammer attack occurred
    val activeNoteEnergy: Float,  // Harmonic energy of winning full-spectrum note
    private val keyEnergies: FloatArray = FloatArray(0),
    private val keyDeltaRatios: FloatArray = FloatArray(0)
) {
    fun energyForMidi(midi: Int): Float {
        val idx = midi - MusicTheory.MIN_PIANO_MIDI
        return if (idx in keyEnergies.indices) keyEnergies[idx] else 0f
    }

    fun deltaRatioForMidi(midi: Int): Float {
        val idx = midi - MusicTheory.MIN_PIANO_MIDI
        return if (idx in keyDeltaRatios.indices) keyDeltaRatios[idx] else 0f
    }
}

/**
 * High-accuracy Piano Pitch & Onset Detection Engine designed specifically for real room acoustics:
 * - 4096-sample Hann-windowed, 8192-point zero-padded Radix-2 FFT (~5.38 Hz bins, <0.5 Hz parabolic)
 * - Strict Fundamental-Presence & Odd-Harmonic Sub-Octave Rejection (eliminates octave-down ghost notes
 *   during fade/decay and room bass rumble!)
 * - Dual-Spectrum Scoring:
 *   1) Full spectrum (`mag`) for clean single-note pitch identification and C-calibration
 *   2) Positive Delta-Spectrum (`deltaMag`) for isolating newly struck legato or repeated piano keys
 *      even while the previous key is still ringing in the room!
 * - Thread-safe per-key energy snapshot (`keyEnergies` / `keyDeltaRatios`) for all C3..C6 keys so
 *   PianoNoteTracker can lock onto the peak pitch without any cross-thread callback lag.
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

    private val bitRev = IntArray(fftSize)
    private val cosTable = DoubleArray(halfFft)
    private val sinTable = DoubleArray(halfFft)

    // Piano melody range: C3 (48, 130.8 Hz) .. C6 (84, 1046.5 Hz)
    private val minMidi = MusicTheory.MIN_PIANO_MIDI // 48 (C3)
    private val maxMidi = MusicTheory.MAX_PIANO_MIDI // 84 (C6)
    private val numCandidates = maxMidi - minMidi + 1

    private val fluxHistory = FloatArray(12)
    private var fluxIndex = 0
    private var hasPrevSpectrum = false
    private var prevRms = 0f

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
        prevRms = 0f
    }

    fun analyze(pcmSamples: ShortArray, count: Int = windowSize): SpectralPitchFrame {
        val n = minOf(count, windowSize, pcmSamples.size)
        if (n < 1024) return emptyFrame(0f)

        // 1. DC-removed RMS + Hann window into zero-padded FFT buffer
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

        if (rms < 0.0015f) {
            if (hasPrevSpectrum) {
                for (k in 0 until halfFft) prevMag[k] *= 0.65f
            }
            prevRms = rms
            return emptyFrame(rms)
        }

        // 2. Run 8192-point in-place Radix-2 FFT
        computeFft()

        // 3. Compute magnitude spectrum & positive spectral flux (115 Hz .. 4800 Hz)
        val minBin = max(2, (115.0 / binHz).toInt())
        val maxBin = minOf(halfFft - 2, (4800.0 / binHz).toInt())

        var rawFlux = 0f
        var magSum = 0f
        var maxPeakMag = 0.0001f
        var maxDeltaMag = 0.0001f
        var deltaSum = 0f
        var magCount = 0

        for (k in minBin..maxBin) {
            val re = fftReal[k]
            val im = fftImag[k]
            val m = sqrt(re * re + im * im).toFloat()
            mag[k] = m
            magSum += m
            if (m > maxPeakMag) maxPeakMag = m
            magCount++

            if (hasPrevSpectrum) {
                val diff = m - prevMag[k] * 0.92f
                if (diff > 0f) {
                    deltaMag[k] = diff
                    deltaSum += diff
                    if (diff > maxDeltaMag) maxDeltaMag = diff
                    rawFlux += ln(1f + diff * 3.5f)
                } else {
                    deltaMag[k] = 0f
                }
            } else {
                deltaMag[k] = m
                deltaSum += m
                if (m > maxDeltaMag) maxDeltaMag = m
            }
        }

        val meanFloor = if (magCount > 0) magSum / magCount else 0.0001f
        val meanDeltaFloor = if (magCount > 0) deltaSum / magCount else 0.0001f

        // 4. Adaptive Onset Detection
        var avgFlux = 0f
        for (v in fluxHistory) avgFlux += v
        avgFlux /= fluxHistory.size

        val rmsJump = prevRms > 0.0005f && rms > prevRms * 1.38f && rms > 0.0055f
        val fluxSpike = hasPrevSpectrum &&
            rawFlux > max(14f, avgFlux * 2.25f + 7f) &&
            rms > 0.005f
        val isOnset = fluxSpike || rmsJump

        fluxHistory[fluxIndex] = rawFlux
        fluxIndex = (fluxIndex + 1) % fluxHistory.size
        prevRms = rms

        // 5. Score Full Spectrum (`mag`) and record per-key harmonic energies (C3..C6)
        val keyEnergies = FloatArray(numCandidates)
        val keyDeltaRatios = FloatArray(numCandidates)

        val fullResult = scoreSpectrum(
            spectrum = mag,
            meanFloor = meanFloor,
            maxPeak = maxPeakMag,
            minBin = minBin,
            maxBin = maxBin,
            outKeyEnergies = keyEnergies
        )

        // 6. Score Positive Delta-Spectrum (`deltaMag`) to isolate NEWLY STRUCK keys over ringing tails
        val deltaKeyEnergies = FloatArray(numCandidates)
        val onsetResult = if (hasPrevSpectrum && maxDeltaMag > maxPeakMag * 0.14f && maxDeltaMag > 0.45f) {
            scoreSpectrum(
                spectrum = deltaMag,
                meanFloor = max(meanDeltaFloor, meanFloor * 0.25f),
                maxPeak = maxDeltaMag,
                minBin = minBin,
                maxBin = maxBin,
                outKeyEnergies = deltaKeyEnergies
            )
        } else if (!hasPrevSpectrum && rms > 0.005f) {
            System.arraycopy(keyEnergies, 0, deltaKeyEnergies, 0, numCandidates)
            fullResult
        } else {
            ScoreResult(null, 0f, 0f)
        }

        for (i in 0 until numCandidates) {
            val e = keyEnergies[i]
            keyDeltaRatios[i] = if (e > 0.05f) (deltaKeyEnergies[i] / e).coerceIn(0f, 1f) else 0f
        }

        // Save current magnitude spectrum for next frame
        System.arraycopy(mag, minBin, prevMag, minBin, maxBin - minBin + 1)
        hasPrevSpectrum = true

        val winningMidi = fullResult.midi
        val refinedHz = if (winningMidi != null) {
            refineFundamentalHz(winningMidi, minBin, maxBin)
        } else {
            0.0
        }
        val centsOff = if (winningMidi != null && refinedHz > 0.0) {
            MusicTheory.frequencyToNearestMidi(refinedHz, calibrationCents).second
        } else {
            0f
        }
        val winEnergy = if (winningMidi != null) {
            keyEnergies[winningMidi - minMidi]
        } else {
            0f
        }

        return SpectralPitchFrame(
            midiNote = winningMidi,
            onsetMidiNote = onsetResult.midi,
            onsetConfidence = onsetResult.confidence,
            frequencyHz = refinedHz,
            centsOff = centsOff,
            confidence = fullResult.confidence,
            rms = rms,
            onsetStrength = rawFlux,
            isOnset = isOnset,
            activeNoteEnergy = winEnergy,
            keyEnergies = keyEnergies,
            keyDeltaRatios = keyDeltaRatios
        )
    }

    private data class ScoreResult(
        val midi: Int?,
        val score: Float,
        val confidence: Float
    )

    private fun scoreSpectrum(
        spectrum: FloatArray,
        meanFloor: Float,
        maxPeak: Float,
        minBin: Int,
        maxBin: Int,
        outKeyEnergies: FloatArray?
    ): ScoreResult {
        if (maxPeak < 0.25f) return ScoreResult(null, 0f, 0f)

        val harmonicWeights = floatArrayOf(1.15f, 0.78f, 0.56f, 0.34f, 0.20f)
        val inharmonicityB = 0.00012

        var bestMidi: Int? = null
        var bestScore = 0f
        var secondBestScore = 0f

        for (idx in 0 until numCandidates) {
            val midi = minMidi + idx
            val f0 = MusicTheory.midiToFrequency(midi, calibrationCents)

            var h1Peak = 0f
            var h2Peak = 0f
            var h3Peak = 0f
            var h1Snr = 0f
            var oddScore = 0f
            var evenScore = 0f
            var totalScore = 0f
            var activeHarmonics = 0

            for (h in 1..5) {
                val expectedHz = f0 * h * sqrt(1.0 + inharmonicityB * h * h)
                if (expectedHz > 4600.0) break

                val tolHz = max(binHz * 1.15, expectedHz * 0.020)
                val loBin = max(minBin, ((expectedHz - tolHz) / binHz).toInt())
                val hiBin = minOf(maxBin, ((expectedHz + tolHz) / binHz).toInt() + 1)

                var peak = 0f
                for (k in loBin..hiBin) {
                    val v = spectrum[k]
                    if (v > peak) peak = v
                }

                val localNoise = estimateLocalFloor(spectrum, loBin, hiBin, minBin, maxBin)
                val snr = peak / (localNoise + 0.003f)

                when (h) {
                    1 -> { h1Peak = peak; h1Snr = snr }
                    2 -> h2Peak = peak
                    3 -> h3Peak = peak
                }

                val minPeakForHarmonic = max(meanFloor * 1.6f, maxPeak * 0.035f)
                if (snr > 2.4f && peak >= minPeakForHarmonic) {
                    val relAmp = (peak / maxPeak).coerceIn(0f, 1f)
                    val snrBoost = ln(1f + (snr - 1f).coerceAtLeast(0f)).coerceAtMost(3.2f)
                    val contribution = harmonicWeights[h - 1] * sqrt(relAmp) * snrBoost
                    totalScore += contribution
                    if (h % 2 == 1) oddScore += contribution else evenScore += contribution
                    activeHarmonics++
                }
            }

            if (outKeyEnergies != null) {
                outKeyEnergies[idx] = h1Peak + h2Peak + h3Peak
            }

            // ANTI-SUB-OCTAVE RULE 1 (Strict Fundamental Presence):
            val minH1VsH2 = if (midi < 60) 0.18f else 0.26f
            val hasRealFundamental = h1Snr >= 2.6f &&
                h1Peak >= maxPeak * 0.10f &&
                h1Peak >= h2Peak * minH1VsH2

            if (!hasRealFundamental) {
                continue
            }

            // ANTI-SUB-OCTAVE RULE 2 (Odd-Harmonic Balance):
            if (evenScore > 0.01f && oddScore < evenScore * 0.58f) {
                totalScore *= 0.25f
            }

            if (activeHarmonics < 2 && midi < 79) {
                totalScore *= 0.50f
            }

            if (totalScore > bestScore) {
                secondBestScore = bestScore
                bestScore = totalScore
                bestMidi = midi
            } else if (totalScore > secondBestScore) {
                secondBestScore = totalScore
            }
        }

        if (bestMidi == null || bestScore < 1.35f) {
            return ScoreResult(null, 0f, 0f)
        }

        val margin = ((bestScore - secondBestScore) / bestScore).coerceIn(0f, 1f)
        val strength = (bestScore / 4.2f).coerceIn(0f, 1f)
        val confidence = (0.55f * strength + 0.45f * max(margin, 0.25f)).coerceIn(0f, 1f)

        return ScoreResult(bestMidi, bestScore, confidence)
    }

    private fun refineFundamentalHz(midi: Int, minBin: Int, maxBin: Int): Double {
        val nominalF0 = MusicTheory.midiToFrequency(midi, calibrationCents)
        val f1PeakBin = findPeakBinAround(mag, nominalF0, minBin, maxBin)
        val f1Interp = parabolicBinFrequency(mag, f1PeakBin)

        val f2 = nominalF0 * 2.0
        if (f2 < 4000.0 && nominalF0 < 240.0) {
            val f2PeakBin = findPeakBinAround(mag, f2, minBin, maxBin)
            if (mag[f2PeakBin] > mag[f1PeakBin] * 0.85f) {
                return parabolicBinFrequency(mag, f2PeakBin) / 2.0
            }
        }
        return if (f1Interp > 0.0) f1Interp else nominalF0
    }

    private fun findPeakBinAround(spectrum: FloatArray, targetHz: Double, minBin: Int, maxBin: Int): Int {
        val tolHz = max(binHz * 1.5, targetHz * 0.038)
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

    private fun estimateLocalFloor(
        spectrum: FloatArray,
        loBin: Int,
        hiBin: Int,
        minBin: Int,
        maxBin: Int
    ): Float {
        val leftStart = max(minBin, loBin - 7)
        val leftEnd = max(minBin, loBin - 2)
        val rightStart = minOf(maxBin, hiBin + 2)
        val rightEnd = minOf(maxBin, hiBin + 7)
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

    private fun computeFft() {
        for (i in 0 until fftSize) {
            val j = bitRev[i]
            if (j > i) {
                val tr = fftReal[i]; fftReal[i] = fftReal[j]; fftReal[j] = tr
                val ti = fftImag[i]; fftImag[i] = fftImag[j]; fftImag[j] = ti
            }
        }
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
        onsetMidiNote = null,
        onsetConfidence = 0f,
        frequencyHz = 0.0,
        centsOff = 0f,
        confidence = 0f,
        rms = rms,
        onsetStrength = 0f,
        isOnset = false,
        activeNoteEnergy = 0f
    )
}
