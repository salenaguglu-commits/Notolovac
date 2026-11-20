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
     * Synthesizes a realistic acoustic piano note with string inharmonicity,
     * strong 2nd/3rd/4th harmonics, hammer attack, exponential decay, and mild room noise.
     */
    private fun synthesizeRealisticPianoBuffer(
        events: List<Triple<Int, Double, Double>>, // (midi, startSec, durationSec)
        totalSeconds: Double,
        calibrationCents: Float = 0f,
        noiseAmp: Double = 0.004
    ): ShortArray {
        val totalSamples = (totalSeconds * sampleRate).toInt()
        val mix = DoubleArray(totalSamples)
        val rng = Random(42)

        // Add subtle room noise floor
        for (i in 0 until totalSamples) {
            mix[i] = (rng.nextDouble() * 2.0 - 1.0) * noiseAmp
        }

        val twoPi = 2.0 * PI
        val inharmonicityB = 0.00014

        for ((midi, startSec, durSec) in events) {
            val f0 = MusicTheory.midiToFrequency(midi, calibrationCents)
            val startIdx = (startSec * sampleRate).toInt().coerceAtLeast(0)
            // Real piano strings have a brief 120ms damper release tail after finger lifts
            val activeLen = (durSec * sampleRate).toInt()
            val tailLen = (0.12 * sampleRate).toInt()
            val endIdx = (startIdx + activeLen + tailLen).coerceAtMost(totalSamples)

            val decayRate = 2.2 + ((f0 - 260.0).coerceAtLeast(0.0) / 350.0)

            for (i in startIdx until endIdx) {
                val localIdx = i - startIdx
                val t = localIdx.toDouble() / sampleRate
                val attack = (t / 0.008).coerceAtMost(1.0)
                val damper = if (localIdx > activeLen) {
                    exp(-28.0 * ((localIdx - activeLen).toDouble() / sampleRate))
                } else {
                    1.0
                }
                val env = attack * damper * exp(-decayRate * t)

                // Strong harmonics (2nd harmonic nearly as strong as fundamental, like real upright piano!)
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
    fun detectsEveryChromaticPianoNoteFromC3ToC6WithoutOctaveOrFifthErrors() {
        val engine = PianoPitchEngine(sampleRate = sampleRate, windowSize = 4096, calibrationCents = 0f)
        for (expectedMidi in 48..84) {
            engine.reset()
            val pcm = synthesizeRealisticPianoBuffer(
                events = listOf(Triple(expectedMidi, 0.02, 0.25)),
                totalSeconds = 0.30,
                noiseAmp = 0.003
            )
            // Sample mid-note at 80ms
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
        // Play "Twinkle Twinkle" slowly at ~75 BPM (beat = 0.80s) WITHOUT metronome!
        // C4(1), C4(1), G4(1), G4(1), A4(1), A4(1), G4(2)
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
        val pcm = synthesizeRealisticPianoBuffer(events, totalSeconds = 7.2)
        // Deliberately pass WRONG fallback BPM (120) with metronomeActive=false to verify Auto-Tempo works!
        val transcription = runFullPipeline(pcm, metronomeBpm = 120, metronomeActive = false)

        val notes = transcription.notes
        val playable = notes.filter { !it.isRest }
        assertEquals("Expected exact 7 melody notes, got ${playable.map { it.midiNote }}", 7, playable.size)
        assertEquals(listOf(60, 60, 67, 67, 69, 69, 67), playable.map { it.midiNote })

        // No fake rests should have been inserted!
        assertEquals("Expected zero fake rests", 0, notes.count { it.isRest })

        // First 6 notes should be quarter notes (1.0), final G4 should be a half note (2.0)!
        for (i in 0..5) {
            assertEquals("Note $i should be QUARTER", NoteDuration.QUARTER, playable[i].duration)
        }
        assertEquals("Final G4 should be HALF", NoteDuration.HALF, playable[6].duration)
    }

    @Test
    fun detectsDeliberateMusicalRestBetweenNotes() {
        // At 100 BPM (beat = 0.60s): C4(1 beat), E4(1 beat), 1-beat REST, G4(1 beat)
        val beat = 0.60
        val events = listOf(
            Triple(60, 0.20, 0.45),
            Triple(64, 0.20 + beat, 0.45),
            // 1 full beat silence gap (from 0.20 + 1.05s to 0.20 + 2.40s -> > 1.2s gap!)
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

        // Now run a melody on that flat piano using the detected calibration!
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
        // At 100 BPM (beat = 0.60s):
        // C4 (Quarter 0.60s), D4 (Eighth 0.30s), E4 (Eighth 0.30s), F4 (Half 1.20s), G4 (Quarter 0.60s)
        // Deliberately overlap each key's release by 80ms into the next key (beginner legato overlap!)
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
}
