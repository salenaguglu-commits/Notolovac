package com.example

import com.example.audio.MetronomeSampleGenerator
import com.example.audio.PianoNoteTracker
import com.example.audio.PianoPitchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MetronomeSampleGeneratorTest {

    @Test
    fun emitsExactSampleAccurateBeatsWithZeroDriftAtNonIntegerBpm() {
        val sampleRate = 44100
        val bpm = 115 // 44100 * 60 / 115 = 23008.69565... samples/beat
        val expectedSpacing = sampleRate * 60.0 / bpm
        val generator = MetronomeSampleGenerator(sampleRate = sampleRate)

        val scheduled = mutableListOf<Pair<Long, Int>>()
        val chunk = ShortArray(512)
        val totalSamples = (expectedSpacing * 32.5).toInt()
        val fullPcm = ShortArray(((totalSamples / 512) + 1) * 512)

        var offset = 0
        while (offset + 512 <= fullPcm.size) {
            generator.fillBuffer(chunk, bpm = bpm, beatsPerMeasure = 4) { framePos, beatIdx ->
                scheduled.add(framePos to beatIdx)
            }
            System.arraycopy(chunk, 0, fullPcm, offset, 512)
            offset += 512
        }

        // Must have at least 32 exact beats with zero skips
        assertTrue("Expected >= 32 beats, got ${scheduled.size}", scheduled.size >= 32)
        for (i in 0 until 32) {
            val (framePos, beatIdx) = scheduled[i]
            val expectedFrame = (i * expectedSpacing).toLong()
            assertEquals("Beat index mismatch at beat $i", i % 4, beatIdx)
            assertTrue(
                "Beat $i drifted! frame=$framePos expected=$expectedFrame",
                abs(framePos - expectedFrame) <= 1L
            )

            // Verify actual audio click energy exists right at beat framePos
            var peak = 0
            val start = framePos.toInt()
            val end = minOf(fullPcm.size, start + 600)
            for (s in start until end) {
                val a = abs(fullPcm[s].toInt())
                if (a > peak) peak = a
            }
            assertTrue("Beat $i click audio missing/too quiet: peak=$peak", peak > 12000)
        }
    }

    @Test
    fun supportsLiveBpmAndTimeSignatureChangesWithoutSkippingOrDuplicatingBeats() {
        val sampleRate = 44100
        val generator = MetronomeSampleGenerator(sampleRate = sampleRate)
        val chunk = ShortArray(512)
        val scheduled = mutableListOf<Pair<Long, Int>>()

        // 1) 4 seconds at 80 BPM in 4/4
        repeat((sampleRate * 4) / 512) {
            generator.fillBuffer(chunk, bpm = 80, beatsPerMeasure = 4) { framePos, beatIdx ->
                scheduled.add(framePos to beatIdx)
            }
        }
        // 2) Live jump to 130 BPM in 3/4 for 4 seconds
        repeat((sampleRate * 4) / 512) {
            generator.fillBuffer(chunk, bpm = 130, beatsPerMeasure = 3) { framePos, beatIdx ->
                scheduled.add(framePos to beatIdx)
            }
        }
        // 3) Live drop to 70 BPM in 4/4 for 4 seconds
        repeat((sampleRate * 4) / 512) {
            generator.fillBuffer(chunk, bpm = 70, beatsPerMeasure = 4) { framePos, beatIdx ->
                scheduled.add(framePos to beatIdx)
            }
        }

        // Every consecutive beat gap must be within valid musical bounds (never a double-click or huge gap)
        assertTrue("Expected >= 16 beats across 12s, got ${scheduled.size}", scheduled.size >= 16)
        for (i in 1 until scheduled.size) {
            val gapSamples = scheduled[i].first - scheduled[i - 1].first
            val gapMs = (gapSamples * 1000.0) / sampleRate
            assertTrue(
                "Detected bad beat gap of ${gapMs}ms between beat ${i - 1} and $i",
                gapMs in 380.0..920.0
            )
        }
    }

    @Test
    fun woodblockMetronomeClicksDoNotTriggerFalsePianoNotes() {
        val sampleRate = 44100
        val generator = MetronomeSampleGenerator(sampleRate = sampleRate)
        val totalChunks = (sampleRate * 4) / 512
        val pcm = ShortArray(totalChunks * 512)
        val chunk = ShortArray(512)

        for (c in 0 until totalChunks) {
            generator.fillBuffer(chunk, bpm = 100, beatsPerMeasure = 4) { _, _ -> }
            // Simulate realistic speaker-to-mic bleed (~20% volume)
            for (i in chunk.indices) {
                pcm[c * 512 + i] = (chunk[i] * 0.20f).toInt().toShort()
            }
        }

        val engine = PianoPitchEngine(sampleRate = sampleRate, windowSize = 4096)
        val tracker = PianoNoteTracker()
        val window = ShortArray(4096)
        var pos = 0
        var lastMs = 0L
        while (pos + 4096 <= pcm.size) {
            System.arraycopy(pcm, pos, window, 0, 4096)
            lastMs = ((pos + 2048) * 1000L) / sampleRate
            val frame = engine.analyze(window, 4096)
            tracker.processFrame(frame, lastMs)
            pos += 1024
        }
        val result = tracker.finishAndQuantize(100, true, lastMs + 50L)
        val falseNotes = result.notes.filter { !it.isRest }
        assertEquals(
            "Metronome clicks should not transcribe as false piano notes, got $falseNotes",
            0,
            falseNotes.size
        )
    }
}
