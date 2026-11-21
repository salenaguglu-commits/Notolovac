package com.example

import com.example.audio.PianoNoteTracker
import com.example.audio.PianoPitchEngine
import com.example.model.MusicTheory
import com.example.model.NoteDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class YinPitchAndTrackerTest {

    private val sampleRate = 44100

    /**
     * Synthesizes a realistic acoustic piano recording with:
     * - String inharmonicity and strong 2nd/3rd/4th harmonics
     * - Unison-string beating ("vibrato" amplitude modulation during sustain/fade!)
     * - Realistic low-frequency room rumble (100-131 Hz hum that triggers octave-down bugs in naive detectors)
     * - Natural exponential fade + damper release tail
     */
    private fun synthesizeRealisticPianoBuffer(
        events: List<Triple<Int, Double, Double>>, // (midi, startSec, durationSec)
        totalSeconds: Double,
        calibrationCents: Float = 0f,
        noiseAmp: Double = 0.004,
        addUnisonBeatingAndRoomHum: Boolean = true
    ): ShortArray {
        val totalSamples = (totalSeconds * sampleRate).toInt()
        val mix = DoubleArray(totalSamples)
        val rng = Random(42)
        val twoPi = 2.0 * PI

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            var bg = (rng.nextDouble() * 2.0 - 1.0) * noiseAmp
            if (addUnisonBeatingAndRoomHum) {
                bg += 0.006 * sin(twoPi * 100.0 * t) + 0.005 * sin(twoPi * 130.8 * t)
            }
            mix[i] = bg
        }

        val inharmonicityB = 0.00014

        for ((midi, startSec, durSec) in events) {
            val f0 = MusicTheory.midiToFrequency(midi, calibrationCents)
            val startIdx = (startSec * sampleRate).toInt().coerceAtLeast(0)
            val activeLen = (durSec * sampleRate).toInt()
            val tailLen = (0.14 * sampleRate).toInt()
            val endIdx = (startIdx + activeLen + tailLen).coerceAtMost(totalSamples)

            val decayRate = 1.9 + ((f0 - 260.0).coerceAtLeast(0.0) / 360.0)

            for (i in startIdx until endIdx) {
                val localIdx = i - startIdx
                val t = localIdx.toDouble() / sampleRate
                val attack = (t / 0.008).coerceAtMost(1.0)
                val damper = if (localIdx > activeLen) {
                    exp(-26.0 * ((localIdx - activeLen).toDouble() / sampleRate))
                } else {
                    1.0
                }
                val beating = if (addUnisonBeatingAndRoomHum) {
                    0.78 + 0.22 * sin(twoPi * 3.2 * t)
                } else {
                    1.0
                }
                val env = attack * damper * beating * exp(-decayRate * t)

                val wave =
                    0.50 * sin(twoPi * f0 * sqrt(1.0 + inharmonicityB) * t) +
                    0.42 * sin(twoPi * f0 * 2.0 * sqrt(1.0 + 4.0 * inharmonicityB) * t) +
                    0.26 * sin(twoPi * f0 * 3.0 * sqrt(1.0 + 9.0 * inharmonicityB) * t) +
                    0.15 * sin(twoPi * f0 * 4.0 * sqrt(1.0 + 16.0 * inharmonicityB) * t) +
                    0.08 * sin(twoPi * f0 * 5.0 * sqrt(1.0 + 25.0 * inharmonicityB) * t)

                mix[i] += wave * env * 0.28
            }
        }

        return ShortArray(totalSamples) { i ->
            (mix[i] * 32767.0).coerceIn(-32767.0, 32767.0).toInt().toShort()
        }
    }

    private fun runFullPipeline(
        pcm: ShortArray,
        calibrationCents: Float = 0f,
        metronomeBpm: Int = 90,
        metronomeActive: Boolean = false
    ) = run {
        val engine = PianoPitchEngine(sampleRate = sampleRate, windowSize = 4096, calibrationCents = calibrationCents)
        val tracker = PianoNoteTracker()
        val window = ShortArray(4096)
        val hop = 1024
        var pos = 0
        var lastTimestampMs = 0L

        while (pos + 4096 <= pcm.size) {
            System.arraycopy(pcm, pos, window, 0, 4096)
            lastTimestampMs = ((pos + 2048) * 1000L) / sampleRate
            val spectral = engine.analyze(window, 4096)
            tracker.processFrame(spectral, lastTimestampMs)
            pos += hop
        }
        tracker.finishAndQuantize(
            metronomeBpm = metronomeBpm,
            metronomeWasActive = metronomeActive,
            endTimestampMs = lastTimestampMs + 50L
        )
    }

    @Test
    fun singleHeldNoteWithLongFadeAndVibratoNeverSpawnsOctaveDownGhostNotes() {
        val pcm = synthesizeRealisticPianoBuffer(
            events = listOf(Triple(60, 0.20, 1.80)),
            totalSeconds = 2.80,
            noiseAmp = 0.005,
            addUnisonBeatingAndRoomHum = true
        )
        val transcription = runFullPipeline(pcm, metronomeBpm = 100, metronomeActive = true)
        val playable = transcription.notes.filter { !it.isRest }

        assertEquals(
            "Expected EXACTLY ONE C4 note without octave-down fade ghosts, got ${playable.map { it.midiNote }}",
            listOf(60),
            playable.map { it.midiNote }
        )
    }

    @Test
    fun detectsEveryChromaticPianoNoteFromC3ToC6WithoutOctaveOrFifthErrors() {
        val engine = PianoPitchEngine(sampleRate = sampleRate, windowSize = 4096, calibrationCents = 0f)
        for (expectedMidi in 48..84) {
            engine.reset()
            val pcm = synthesizeRealisticPianoBuffer(
                events = listOf(Triple(expectedMidi, 0.02, 0.25)),
                totalSeconds = 0.30,
                noiseAmp = 0.004,
                addUnisonBeatingAndRoomHum = true
            )
            val start = (0.06 * sampleRate).toInt()
            val window = pcm.copyOfRange(start, start + 4096)
            val result = engine.analyze(window, 4096)
            assertEquals(
                "Wrong MIDI for expected $expectedMidi (${MusicTheory.midiToLetter(expectedMidi, true)}), got ${result.midiNote} (${result.frequencyHz}Hz)",
                expectedMidi,
                result.midiNote
            )
            assertTrue("Expected cents error < 10 for $expectedMidi, got ${result.centsOff}", abs(result.centsOff) < 10f)
        }
    }

    @Test
    fun transcribesTwinkleTwinkleWithRepeatedNotesAndAutoTempoAccurately() {
        val beat = 0.80
        val events = listOf(
            Triple(60, 0.20 + 0 * beat, beat * 0.85),
            Triple(60, 0.20 + 1 * beat, beat * 0.85),
            Triple(67, 0.20 + 2 * beat, beat * 0.85),
            Triple(67, 0.20 + 3 * beat, beat * 0.85),
            Triple(69, 0.20 + 4 * beat, beat * 0.85),
            Triple(69, 0.20 + 5 * beat, beat * 0.85),
            Triple(67, 0.20 + 6 * beat, beat * 1.80)
        )
        val pcm = synthesizeRealisticPianoBuffer(events, totalSeconds = 7.4)
        val transcription = runFullPipeline(pcm, metronomeBpm = 120, metronomeActive = false)

        val notes = transcription.notes
        val playable = notes.filter { !it.isRest }
        assertEquals("Expected exact 7 melody notes, got ${playable.map { it.midiNote }}", 7, playable.size)
        assertEquals(listOf(60, 60, 67, 67, 69, 69, 67), playable.map { it.midiNote })
        assertEquals("Expected zero fake rests", 0, notes.count { it.isRest })

        for (i in 0..5) {
            assertEquals("Note $i should be QUARTER", NoteDuration.QUARTER, playable[i].duration)
        }
        assertEquals("Final G4 should be HALF", NoteDuration.HALF, playable[6].duration)
    }

    @Test
    fun detectsDeliberateMusicalRestBetweenNotes() {
        val beat = 0.60
        val events = listOf(
            Triple(60, 0.20, 0.45),
            Triple(64, 0.20 + beat, 0.45),
            Triple(67, 0.20 + 3 * beat, 0.55)
        )
        val pcm = synthesizeRealisticPianoBuffer(events, totalSeconds = 3.2)
        val transcription = runFullPipeline(pcm, metronomeBpm = 100, metronomeActive = true)

        val notes = transcription.notes
        val playable = notes.filter { !it.isRest }
        assertEquals(listOf(60, 64, 67), playable.map { it.midiNote })
        assertTrue("Expected a deliberate rest before G4, got $notes", notes.any { it.isRest })
    }

    @Test
    fun cCalibrationCompensatesFlatHomePianoEndToEnd() {
        val flatCents = -35f
        val pcmC4 = synthesizeRealisticPianoBuffer(
            events = listOf(Triple(60, 0.02, 0.25)),
            totalSeconds = 0.30,
            calibrationCents = flatCents
        )
        val uncalibratedEngine = PianoPitchEngine(sampleRate = sampleRate, windowSize = 4096, calibrationCents = 0f)
        val start = (0.06 * sampleRate).toInt()
        val measured = uncalibratedEngine.analyze(pcmC4.copyOfRange(start, start + 4096), 4096)
        val cal = MusicTheory.computeCalibrationCentsFromC(measured.frequencyHz)
        assertNotNull(cal)
        val (detectedCents, _) = cal!!
        assertTrue("Expected ~ -35 cents, got $detectedCents", abs(detectedCents - flatCents) < 6f)

        val events = listOf(
            Triple(60, 0.20, 0.45),
            Triple(62, 0.70, 0.45),
            Triple(64, 1.20, 0.45),
            Triple(65, 1.70, 0.45),
            Triple(67, 2.20, 0.65)
        )
        val pcmScale = synthesizeRealisticPianoBuffer(events, totalSeconds = 3.3, calibrationCents = flatCents)
        val transcription = runFullPipeline(pcmScale, calibrationCents = detectedCents, metronomeBpm = 120, metronomeActive = true)
        val playable = transcription.notes.filter { !it.isRest }
        assertEquals(listOf(60, 62, 64, 65, 67), playable.map { it.midiNote })
    }

    @Test
    fun transcribesMixedRhythmsAndOverlappingLegatoNotes() {
        val beat = 0.60
        val events = listOf(
            Triple(60, 0.20, beat + 0.08),
            Triple(62, 0.20 + 1.0 * beat, 0.5 * beat + 0.06),
            Triple(64, 0.20 + 1.5 * beat, 0.5 * beat + 0.06),
            Triple(65, 0.20 + 2.0 * beat, 2.0 * beat + 0.08),
            Triple(67, 0.20 + 4.0 * beat, 1.0 * beat)
        )
        val pcm = synthesizeRealisticPianoBuffer(events, totalSeconds = 3.6)
        val transcription = runFullPipeline(pcm, metronomeBpm = 100, metronomeActive = true)
        val playable = transcription.notes.filter { !it.isRest }

        assertEquals(listOf(60, 62, 64, 65, 67), playable.map { it.midiNote })
        assertEquals(
            listOf(
                NoteDuration.QUARTER,
                NoteDuration.EIGHTH,
                NoteDuration.EIGHTH,
                NoteDuration.HALF,
                NoteDuration.QUARTER
            ),
            playable.map { it.duration }
        )
    }

    @Test
    fun wideToleranceCalibratesHeavilyDetunedPiano55CentsFlat() {
        val heavyFlatCents = -55f
        val pcmC4 = synthesizeRealisticPianoBuffer(
            events = listOf(Triple(60, 0.02, 0.30)),
            totalSeconds = 0.35,
            calibrationCents = heavyFlatCents
        )
        val tunerEngine = PianoPitchEngine(
            sampleRate = sampleRate,
            windowSize = 4096,
            calibrationCents = 0f,
            widePitchTolerance = true
        )
        val start = (0.06 * sampleRate).toInt()
        val measured = tunerEngine.analyze(pcmC4.copyOfRange(start, start + 4096), 4096)
        assertNotNull("Expected wide-tolerance tuner to hear -55ct C4", measured.midiNote)
        val cal = MusicTheory.computeCalibrationCentsFromC(measured.frequencyHz)
        assertNotNull("Expected frequency ${measured.frequencyHz}Hz to map to C calibration", cal)
        val (detectedCents, _) = cal!!
        assertTrue("Expected ~ -55 cents, got $detectedCents", abs(detectedCents - heavyFlatCents) < 6f)
    }
}
