package com.example

import com.example.audio.MusicalHarmonyResolver
import com.example.audio.NoteAcousticEvidence
import com.example.audio.PianoNoteTracker
import com.example.audio.PianoPitchEngine
import com.example.model.DetectedNote
import com.example.model.MusicTheory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

class MusicalHarmonyAndClippingTest {

    private fun evidence(
        id: Long,
        rawMidi: Int,
        candidates: Map<Int, Float>,
        peakConf: Float = 0.85f,
        clipRatio: Float = 0f,
        durationMs: Long = 500L
    ): NoteAcousticEvidence {
        return NoteAcousticEvidence(
            rawNote = DetectedNote(
                id = id,
                midiNote = rawMidi,
                startTimeMs = id * 500L,
                durationMs = durationMs,
                beats = 1f,
                confidence = peakConf,
                isRest = false
            ),
            candidateScores = candidates,
            peakConfidence = peakConf,
            maxClippingRatio = clipRatio
        )
    }

    @Test
    fun resolvesUncertainDisharmonicNoteIntoDetectedCMajorScale() {
        // Melody: C4 - D4 - E4 - F4 - [uncertain clipped F#4 vs G4] - A4 - G4 - C4
        val list = listOf(
            evidence(1, 60, mapOf(60 to 3.8f, 72 to 0.7f)),
            evidence(2, 62, mapOf(62 to 3.7f, 69 to 0.6f)),
            evidence(3, 64, mapOf(64 to 3.9f, 76 to 0.5f)),
            evidence(4, 65, mapOf(65 to 3.6f, 60 to 0.6f)),
            // Clipped/uncertain note: raw winner slightly F#4 (66), true G4 (67) strong runner-up
            evidence(5, 66, mapOf(66 to 1.90f, 67 to 1.75f), peakConf = 0.54f, clipRatio = 0.06f),
            evidence(6, 69, mapOf(69 to 3.7f, 64 to 0.5f)),
            evidence(7, 67, mapOf(67 to 3.8f, 60 to 0.6f)),
            evidence(8, 60, mapOf(60 to 4.0f), durationMs = 900L)
        )

        val result = MusicalHarmonyResolver.resolve(list)
        assertNotNull(result.detectedKey)
        assertEquals("C-dur", result.detectedKey!!.serbianName)
        assertEquals(listOf(60, 62, 64, 65, 67, 69, 67, 60), result.resolvedNotes.map { it.midiNote })
        assertEquals(1, result.correctedCount)
    }

    @Test
    fun preservesHighConfidenceIntentionalChromaticAccidental() {
        // Player deliberately & cleanly plays F#4 (66) in C major with strong unclipped confidence
        val list = listOf(
            evidence(1, 60, mapOf(60 to 4.0f)),
            evidence(2, 64, mapOf(64 to 3.9f)),
            evidence(3, 67, mapOf(67 to 3.8f)),
            evidence(4, 66, mapOf(66 to 3.9f, 67 to 0.8f), peakConf = 0.90f, clipRatio = 0f),
            evidence(5, 67, mapOf(67 to 4.0f)),
            evidence(6, 60, mapOf(60 to 4.1f), durationMs = 800L)
        )

        val result = MusicalHarmonyResolver.resolve(list)
        assertEquals(listOf(60, 64, 67, 66, 67, 60), result.resolvedNotes.map { it.midiNote })
        assertEquals(0, result.correctedCount)
    }

    @Test
    fun detectsGMajorScaleAndKeepsFSharpInHarmony() {
        // Melody in G major: G4 - A4 - B4 - C5 - D5 - [uncertain F4 vs F#4] - G4
        val list = listOf(
            evidence(1, 67, mapOf(67 to 4.0f)),
            evidence(2, 69, mapOf(69 to 3.8f)),
            evidence(3, 71, mapOf(71 to 3.9f)),
            evidence(4, 72, mapOf(72 to 3.7f)),
            evidence(5, 74, mapOf(74 to 3.9f)),
            evidence(6, 65, mapOf(65 to 1.85f, 66 to 1.78f), peakConf = 0.55f, clipRatio = 0.04f),
            evidence(7, 67, mapOf(67 to 4.1f), durationMs = 900L)
        )

        val result = MusicalHarmonyResolver.resolve(list)
        assertNotNull(result.detectedKey)
        assertEquals("G-dur", result.detectedKey!!.serbianName)
        assertEquals(listOf(67, 69, 71, 72, 74, 66, 67), result.resolvedNotes.map { it.midiNote })
    }

    @Test
    fun viterbiVoiceLeadingRescuesUncertainOctaveGlitch() {
        // Melody in middle register C4..G4 where one clipped note has an octave-up G5 (79) tie vs G4 (67)
        val list = listOf(
            evidence(1, 60, mapOf(60 to 3.9f)),
            evidence(2, 64, mapOf(64 to 3.8f)),
            evidence(3, 79, mapOf(79 to 2.00f, 67 to 1.88f), peakConf = 0.56f, clipRatio = 0.05f),
            evidence(4, 64, mapOf(64 to 3.8f)),
            evidence(5, 60, mapOf(60 to 4.0f), durationMs = 800L)
        )

        val result = MusicalHarmonyResolver.resolve(list)
        assertEquals(listOf(60, 64, 67, 64, 60), result.resolvedNotes.map { it.midiNote })
    }

    @Test
    fun closeUpHardClippedPianoWithKeybedThumpTranscribesCleanly() {
        val sampleRate = 44100
        val beat = 0.65
        val melody = listOf(60, 62, 64, 65, 67, 64, 60) // C-D-E-F-G-E-C
        val totalSeconds = 5.4
        val totalSamples = (totalSeconds * sampleRate).toInt()
        val mix = DoubleArray(totalSamples)
        val twoPi = 2.0 * PI
        val inharmonicityB = 0.00014

        melody.forEachIndexed { idx, midi ->
            val startSec = 0.25 + idx * beat
            val f0 = MusicTheory.midiToFrequency(midi, 0f)
            val startIdx = (startSec * sampleRate).toInt()
            val activeLen = (beat * 0.85 * sampleRate).toInt()
            val tailLen = (0.14 * sampleRate).toInt()
            val endIdx = (startIdx + activeLen + tailLen).coerceAtMost(totalSamples)

            for (i in startIdx until endIdx) {
                val local = i - startIdx
                val t = local.toDouble() / sampleRate
                val attack = (t / 0.006).coerceAtMost(1.0)
                val damper = if (local > activeLen) exp(-26.0 * ((local - activeLen).toDouble() / sampleRate)) else 1.0
                val env = attack * damper * exp(-2.2 * t)
                val pianoWave =
                    0.50 * sin(twoPi * f0 * sqrt(1.0 + inharmonicityB) * t) +
                    0.44 * sin(twoPi * f0 * 2.0 * sqrt(1.0 + 4.0 * inharmonicityB) * t) +
                    0.28 * sin(twoPi * f0 * 3.0 * sqrt(1.0 + 9.0 * inharmonicityB) * t) +
                    0.18 * sin(twoPi * f0 * 4.0 * sqrt(1.0 + 16.0 * inharmonicityB) * t) +
                    0.10 * sin(twoPi * f0 * 5.0 * sqrt(1.0 + 25.0 * inharmonicityB) * t)

                // Simulate phone placed right on piano: HUGE close-up gain (causes hard clipping at attack!)
                // + strong 65 Hz mechanical keybed thud!
                val keybedThump = 0.55 * exp(-70.0 * t) * sin(twoPi * 65.0 * t)
                mix[i] += (pianoWave * env * 1.45) + keybedThump
            }
        }

        // Hard-clip to 16-bit PCM just like an overloaded smartphone microphone ADC!
        val pcm = ShortArray(totalSamples) { i ->
            (mix[i] * 32767.0).coerceIn(-32767.0, 32767.0).toInt().toShort()
        }

        val engine = PianoPitchEngine(sampleRate = sampleRate, windowSize = 4096)
        val tracker = PianoNoteTracker()
        val window = ShortArray(4096)
        var pos = 0
        var lastMs = 0L
        var sawClipping = false

        while (pos + 4096 <= pcm.size) {
            System.arraycopy(pcm, pos, window, 0, 4096)
            lastMs = ((pos + 2048) * 1000L) / sampleRate
            val frame = engine.analyze(window, 4096)
            val upd = tracker.processFrame(frame, lastMs)
            if (upd.isClipping) sawClipping = true
            pos += 1024
        }

        val result = tracker.finishAndQuantize(92, true, lastMs + 50L)
        val playable = result.notes.filter { !it.isRest }

        assertTrue("Expected clipping detector to notice close-up hard clipping", sawClipping)
        assertEquals(
            "Expected clean C-D-E-F-G-E-C even under close-up hard clipping, got ${playable.map { it.midiNote }}",
            melody,
            playable.map { it.midiNote }
        )
        assertEquals("C-dur", result.detectedKey?.serbianName)
    }
}
