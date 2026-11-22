package com.example

import com.example.audio.PianoCalibrationProfile
import com.example.audio.PianoNoteTracker
import com.example.audio.PianoPitchEngine
import com.example.audio.ScaleCalibrationEngine
import com.example.audio.ScaleCalibrationPhase
import com.example.audio.ScaleCalibrationSnapshot
import com.example.model.MusicTheory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class ScaleCalibrationTest {

    private val sampleRate = 44100
    private val windowSize = 4096
    private val hopSize = 1024

    private fun synthAcousticKey(
        midi: Int,
        centsOffset: Float,
        durationSec: Double,
        amplitude: Double = 0.28,
        beatingDepth: Double = 0.0
    ): ShortArray {
        val total = (sampleRate * durationSec).toInt()
        val f0 = MusicTheory.midiToFrequency(midi, centsOffset)
        val out = ShortArray(total)
        val harmonics = doubleArrayOf(1.0, 0.72, 0.48, 0.28, 0.16)
        val b = 0.00012
        for (i in 0 until total) {
            val t = i.toDouble() / sampleRate
            val attack = (t / 0.012).coerceAtMost(1.0)
            val decay = exp(-t * 2.4)
            val beating = if (beatingDepth > 0.0) {
                1.0 - beatingDepth * (0.5 - 0.5 * kotlin.math.cos(2.0 * PI * 3.2 * t))
            } else {
                1.0
            }
            var s = 0.0
            for (h in harmonics.indices) {
                val n = h + 1
                val fn = f0 * n * sqrt(1.0 + b * n * n)
                s += harmonics[h] * sin(2.0 * PI * fn * t)
            }
            val sample = (s / 2.2) * amplitude * attack * decay * beating
            out[i] = (sample * 32767.0).toInt().coerceIn(-32767, 32767).toShort()
        }
        return out
    }

    private fun synthQuietRoom(durationSec: Double, amplitude: Double = 0.003): ShortArray {
        val total = (sampleRate * durationSec).toInt()
        val out = ShortArray(total)
        var seed = 1337L
        for (i in 0 until total) {
            seed = (seed * 1103515245L + 12345L) and 0x7fffffffL
            val rand = (seed.toDouble() / 0x7fffffff.toDouble()) * 2.0 - 1.0
            out[i] = (rand * amplitude * 32767.0).toInt().toShort()
        }
        return out
    }

    private fun feedSignal(
        signal: ShortArray,
        pitchEngine: PianoPitchEngine,
        scaleEngine: ScaleCalibrationEngine
    ): ScaleCalibrationSnapshot {
        var last = ScaleCalibrationSnapshot()
        val buf = ShortArray(windowSize)
        var pos = 0
        while (pos + windowSize <= signal.size) {
            System.arraycopy(signal, pos, buf, 0, windowSize)
            val frame = pitchEngine.analyze(buf, windowSize)
            last = scaleEngine.processFrame(frame)
            if (last.phase == ScaleCalibrationPhase.COMPLETED) break
            pos += hopSize
        }
        return last
    }

    @Test
    fun fullScaleCalibrationMeasuresDetunedStretchedPianoAndImprovesRecognition() {
        val pitchEngine = PianoPitchEngine(
            sampleRate = sampleRate,
            windowSize = windowSize,
            calibrationCents = 0f,
            widePitchTolerance = true
        )
        val scaleEngine = ScaleCalibrationEngine(silenceTargetFrames = 18, requiredSamplesPerNote = 8)
        scaleEngine.start()

        // 1. Quiet room check
        val quiet = synthQuietRoom(0.65, amplitude = 0.004)
        val afterSilence = feedSignal(quiet, pitchEngine, scaleEngine)
        assertEquals(ScaleCalibrationPhase.MEASURING_NOTE, afterSilence.phase)
        assertEquals(0, afterSilence.currentNoteIndex)

        // 2. Detuned flat piano (-38 ct average) with +14 ct/octave Railsback stretch and extra-flat F4
        val perKeyTrueCents = mapOf(
            60 to -45f, // C4
            62 to -42f, // D4
            64 to -40f, // E4
            65 to -54f, // F4 (single out-of-tune key!)
            67 to -36f, // G4
            69 to -34f, // A4
            71 to -32f, // B4
            72 to -31f  // C5
        )

        var snap = afterSilence
        for (midi in ScaleCalibrationEngine.SCALE_NOTES) {
            val tone = synthAcousticKey(
                midi = midi,
                centsOffset = perKeyTrueCents.getValue(midi),
                durationSec = 0.45,
                amplitude = 0.30,
                beatingDepth = if (midi <= 62) 0.35 else 0.08
            )
            snap = feedSignal(tone, pitchEngine, scaleEngine)
        }

        assertEquals(ScaleCalibrationPhase.COMPLETED, snap.phase)
        val profile = snap.resultingProfile
        assertNotNull(profile)
        profile!!
        assertTrue(profile.isCalibrated)
        assertTrue(profile.isScaleCalibrated)
        assertEquals(8, snap.completedNotes.size)

        // Global offset should be near -38 ct (median of -45..-31)
        assertTrue("Expected globalCents near -38, got ${profile.globalCents}", abs(profile.globalCents - (-38f)) < 6f)
        // Octave stretch should be positive (C5 sharper than C4)
        assertTrue("Expected positive stretch, got ${profile.octaveStretchCentsPerOctave}", profile.octaveStretchCentsPerOctave > 5f)
        // Specific flat F4 (MIDI 65) should be accurately calibrated near -54 ct
        val effF4 = profile.effectiveCentsForMidi(65)
        assertTrue("Expected effective F4 cents near -54, got $effF4", abs(effF4 - (-54f)) < 8f)
        // Median strike RMS & ambient noise captured
        assertTrue("Ambient noise should be > 0.0015", profile.ambientNoiseRms >= 0.0015f)
        assertTrue("Median strike RMS should be > 0.04", profile.medianStrikeRms > 0.04f)

        // 3. Now verify that recording with this profile locks onto every key accurately (including extra-flat F4!)
        val calibratedPitchEngine = PianoPitchEngine(
            sampleRate = sampleRate,
            windowSize = windowSize,
            calibrationCents = profile.globalCents,
            calibrationProfile = profile
        )
        val tracker = PianoNoteTracker(calibrationProfile = profile)

        val testMelody = listOf(60, 65, 67, 72)
        val totalSamples = (sampleRate * 3.0).toInt()
        val combined = DoubleArray(totalSamples)
        testMelody.forEachIndexed { idx, midi ->
            val start = (idx * 0.65 * sampleRate).toInt()
            val keyPcm = synthAcousticKey(midi, perKeyTrueCents.getValue(midi), durationSec = 0.55, amplitude = 0.30)
            for (k in keyPcm.indices) {
                if (start + k < totalSamples) {
                    combined[start + k] += keyPcm[k] / 32768.0
                }
            }
        }
        val melodyPcm = ShortArray(totalSamples) { i ->
            (combined[i].coerceIn(-0.98, 0.98) * 32767.0).toInt().toShort()
        }

        val buf = ShortArray(windowSize)
        var pos = 0
        var timeMs = 0L
        while (pos + windowSize <= melodyPcm.size) {
            System.arraycopy(melodyPcm, pos, buf, 0, windowSize)
            val frame = calibratedPitchEngine.analyze(buf, windowSize)
            tracker.processFrame(frame, timeMs)
            pos += hopSize
            timeMs += (hopSize * 1000L) / sampleRate
        }
        val result = tracker.finishAndQuantize(metronomeBpm = 92, metronomeWasActive = false, endTimestampMs = timeMs)
        val nonRests = result.notes.filter { !it.isRest }
        val recognizedMidis = nonRests.map { it.midiNote }
        val debugInfo = nonRests.joinToString { "${it.midiNote}@${it.startTimeMs}ms(dur=${it.durationMs}ms)" }
        assertEquals("Notes were: $debugInfo | profile=$profile", testMelody, recognizedMidis)
    }

    @Test
    fun ignoresPreviousKeyTailAndWarnsOnWrongKey() {
        val pitchEngine = PianoPitchEngine(sampleRate = sampleRate, windowSize = windowSize, widePitchTolerance = true)
        val scaleEngine = ScaleCalibrationEngine(silenceTargetFrames = 10, requiredSamplesPerNote = 6)
        scaleEngine.start()

        feedSignal(synthQuietRoom(0.4), pitchEngine, scaleEngine)
        // Complete C4 (index 0 -> advances to D4 index 1)
        val afterC4 = feedSignal(synthAcousticKey(60, 0f, 0.35), pitchEngine, scaleEngine)
        assertEquals(1, afterC4.currentNoteIndex)

        // Tail of C4 still ringing should NOT trigger wrong-note warning
        val afterC4Tail = feedSignal(synthAcousticKey(60, 0f, 0.22, amplitude = 0.14), pitchEngine, scaleEngine)
        assertFalse("Previous key tail should not trigger wrong-note warning", afterC4Tail.isWrongNoteWarning)
        assertEquals(1, afterC4Tail.currentNoteIndex)

        // Playing E4 (MIDI 64) when waiting for D4 (MIDI 62) MUST trigger wrong-note warning
        val afterWrongE4 = feedSignal(synthAcousticKey(64, 0f, 0.25), pitchEngine, scaleEngine)
        assertTrue("Unrelated key E4 should trigger wrong-note warning", afterWrongE4.isWrongNoteWarning)
        assertTrue(afterWrongE4.statusMessage.contains("Mi"))
        assertTrue(afterWrongE4.statusMessage.contains("Re"))
    }

    @Test
    fun calibrationProfileSerializationRoundTripIsLossless() {
        val original = PianoCalibrationProfile(
            globalCents = -24.5f,
            calibratedC4Hz = (MusicTheory.STANDARD_C4_HZ * 2.0.pow(-24.5 / 1200.0)).toFloat(),
            isCalibrated = true,
            isScaleCalibrated = true,
            octaveStretchCentsPerOctave = 11.2f,
            perKeyCentsOffsets = mapOf(60 to -6.5f, 65 to -14.2f, 72 to 5.8f),
            ambientNoiseRms = 0.0042f,
            medianStrikeRms = 0.145f,
            beatingGuardFactor = 1.22f,
            maxClippingRatio = 0.045f
        )

        val serialized = original.serialize()
        val restored = PianoCalibrationProfile.deserialize(serialized)

        assertTrue(restored.isCalibrated)
        assertTrue(restored.isScaleCalibrated)
        assertEquals(original.globalCents, restored.globalCents, 0.01f)
        assertEquals(original.calibratedC4Hz, restored.calibratedC4Hz, 0.01f)
        assertEquals(original.octaveStretchCentsPerOctave, restored.octaveStretchCentsPerOctave, 0.01f)
        assertEquals(original.ambientNoiseRms, restored.ambientNoiseRms, 0.0002f)
        assertEquals(original.medianStrikeRms, restored.medianStrikeRms, 0.001f)
        assertEquals(original.beatingGuardFactor, restored.beatingGuardFactor, 0.01f)
        assertEquals(original.clippingWarning, restored.clippingWarning)
        assertEquals(original.perKeyCentsOffsets.size, restored.perKeyCentsOffsets.size)
        assertEquals(-14.2f, restored.perKeyCentsOffsets.getValue(65), 0.05f)
    }
}
