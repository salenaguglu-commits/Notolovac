package com.example

import com.example.audio.PianoNoteTracker
import com.example.audio.PianoPitchEngine
import com.example.model.MusicTheory
import com.example.model.NoteDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

class FastNotesAndAcousticsTest {

    private val sampleRate = 44100
    private val windowSize = 2048
    private val hopSize = 512

    private fun synthesizePianoSequence(
        events: List<Triple<Int, Double, Double>>, // (midi, startSec, activeDurSec)
        totalSec: Double,
        amplitude: Double = 0.28,
        clipGain: Double = 1.0,
        beatingOnLowNotes: Boolean = false
    ): ShortArray {
        val totalSamples = (sampleRate * totalSec).toInt()
        val mix = DoubleArray(totalSamples)
        val twoPi = 2.0 * PI
        val inharmonicityB = 0.00012

        for ((midi, startSec, durSec) in events) {
            val f0 = MusicTheory.midiToFrequency(midi, 0f)
            val startIdx = (startSec * sampleRate).toInt()
            val tailSec = 0.14
            val len = ((durSec + tailSec) * sampleRate).toInt()

            for (i in 0 until len) {
                val idx = startIdx + i
                if (idx >= totalSamples) break
                val t = i.toDouble() / sampleRate
                val attack = (t / 0.008).coerceAtMost(1.0)
                val damper = if (t > durSec) exp(-24.0 * (t - durSec)) else 1.0
                val beating = if (beatingOnLowNotes && midi <= 62) {
                    1.0 - 0.42 * (0.5 - 0.5 * cos(twoPi * 3.1 * t))
                } else {
                    1.0
                }
                val env = attack * damper * beating * exp(-2.2 * t)
                val tone =
                    0.56 * sin(twoPi * f0 * t) +
                        0.40 * sin(twoPi * f0 * 2.0 * sqrt(1.0 + 4.0 * inharmonicityB) * t) +
                        0.26 * sin(twoPi * f0 * 3.0 * sqrt(1.0 + 9.0 * inharmonicityB) * t) +
                        0.15 * sin(twoPi * f0 * 4.0 * sqrt(1.0 + 16.0 * inharmonicityB) * t)
                mix[idx] += tone * env * amplitude * clipGain
            }
        }

        return ShortArray(totalSamples) { i ->
            (mix[i].coerceIn(-0.999, 0.999) * 32767.0).toInt().toShort()
        }
    }

    private fun runTracker(
        pcm: ShortArray,
        metronomeBpm: Int = 100,
        metronomeActive: Boolean = true
    ): Pair<List<Int>, List<NoteDuration>> {
        val engine = PianoPitchEngine(sampleRate = sampleRate, windowSize = windowSize)
        val tracker = PianoNoteTracker()
        val buf = ShortArray(windowSize)
        var pos = 0
        var timeMs = 0L
        while (pos + windowSize <= pcm.size) {
            System.arraycopy(pcm, pos, buf, 0, windowSize)
            val frame = engine.analyze(buf, windowSize)
            tracker.processFrame(frame, timeMs)
            pos += hopSize
            timeMs += (hopSize * 1000L) / sampleRate
        }
        val result = tracker.finishAndQuantize(
            metronomeBpm = metronomeBpm,
            metronomeWasActive = metronomeActive,
            endTimestampMs = timeMs
        )
        val nonRests = result.notes.filter { !it.isRest }
        return nonRests.map { it.midiNote } to nonRests.map { it.duration }
    }

    @Test
    fun capturesFastEighthNoteScaleAt120Bpm() {
        // 120 BPM -> quarter = 0.50s, eighth = 0.25s
        val scale = listOf(60, 62, 64, 65, 67, 69, 71, 72)
        val step = 0.25
        val events = scale.mapIndexed { idx, midi ->
            Triple(midi, 0.15 + idx * step, step * 0.88)
        }
        val pcm = synthesizePianoSequence(events, totalSec = 0.15 + scale.size * step + 0.5)
        val (midis, durations) = runTracker(pcm, metronomeBpm = 120, metronomeActive = true)

        assertEquals(scale, midis)
        // First 7 notes have 0.25s IOI -> EIGHTH
        for (i in 0 until 7) {
            assertEquals("Note $i should be EIGHTH", NoteDuration.EIGHTH, durations[i])
        }
    }

    @Test
    fun capturesFastSixteenthNoteRunAt100Bpm() {
        // 100 BPM -> quarter = 0.60s, eighth = 0.30s, sixteenth = 0.15s
        val run = listOf(60, 62, 64, 65, 67, 65, 64, 62, 60)
        val step = 0.15
        val events = run.mapIndexed { idx, midi ->
            val dur = if (idx == run.lastIndex) 0.50 else step * 0.86
            Triple(midi, 0.15 + idx * step, dur)
        }
        val pcm = synthesizePianoSequence(events, totalSec = 0.15 + run.size * step + 0.7)
        val (midis, durations) = runTracker(pcm, metronomeBpm = 100, metronomeActive = true)

        assertEquals(run, midis)
        for (i in 0 until run.lastIndex) {
            assertEquals("Fast note $i should be SIXTEENTH", NoteDuration.SIXTEENTH, durations[i])
        }
    }

    @Test
    fun capturesMixedQuarterEighthAndSixteenthPassage() {
        // 96 BPM -> quarter = 0.625s, eighth = 0.3125s, sixteenth = 0.15625s
        val q = 60.0 / 96.0
        val e = q / 2.0
        val s = q / 4.0
        val events = listOf(
            Triple(60, 0.15, q * 0.88),                     // 1/4 C4
            Triple(64, 0.15 + q, e * 0.88),                 // 1/8 E4
            Triple(67, 0.15 + q + e, e * 0.88),             // 1/8 G4
            Triple(69, 0.15 + 2 * q, s * 0.85),             // 1/16 A4
            Triple(67, 0.15 + 2 * q + s, s * 0.85),         // 1/16 G4
            Triple(65, 0.15 + 2 * q + 2 * s, s * 0.85),     // 1/16 F4
            Triple(64, 0.15 + 2 * q + 3 * s, s * 0.85),     // 1/16 E4
            Triple(60, 0.15 + 3 * q, q * 0.90)              // 1/4 C4
        )
        val pcm = synthesizePianoSequence(events, totalSec = 0.15 + 4 * q + 0.5)
        val (midis, durations) = runTracker(pcm, metronomeBpm = 96, metronomeActive = true)

        assertEquals(listOf(60, 64, 67, 69, 67, 65, 64, 60), midis)
        assertEquals(NoteDuration.QUARTER, durations[0])
        assertEquals(NoteDuration.EIGHTH, durations[1])
        assertEquals(NoteDuration.EIGHTH, durations[2])
        assertEquals(NoteDuration.SIXTEENTH, durations[3])
        assertEquals(NoteDuration.SIXTEENTH, durations[4])
        assertEquals(NoteDuration.SIXTEENTH, durations[5])
        assertEquals(NoteDuration.SIXTEENTH, durations[6])
        assertEquals(NoteDuration.QUARTER, durations[7])
    }

    @Test
    fun capturesFastRepeatedEighthNotes() {
        // 110 BPM -> eighth = 0.273s, repeated notes Do-Do-Sol-Sol-La-La-Sol
        val notes = listOf(60, 60, 67, 67, 69, 69, 67)
        val step = 0.273
        val events = notes.mapIndexed { idx, midi ->
            val dur = if (idx == notes.lastIndex) 0.52 else step * 0.72
            Triple(midi, 0.15 + idx * step, dur)
        }
        val pcm = synthesizePianoSequence(events, totalSec = 0.15 + notes.size * step + 0.7)
        val (midis, _) = runTracker(pcm, metronomeBpm = 110, metronomeActive = true)

        assertEquals(notes, midis)
    }

    @Test
    fun recognizesChromaticAccidentalsDirectlyWithoutScaleRewriting() {
        // Chromatic notes should be captured verbatim "na prvu" with zero scale snapping
        val chromatic = listOf(60, 61, 62, 63, 64, 66, 67)
        val step = 0.35
        val events = chromatic.mapIndexed { idx, midi ->
            Triple(midi, 0.15 + idx * step, step * 0.85)
        }
        val pcm = synthesizePianoSequence(events, totalSec = 0.15 + chromatic.size * step + 0.5)
        val (midis, _) = runTracker(pcm, metronomeBpm = 90, metronomeActive = false)

        assertEquals(chromatic, midis)
    }

    @Test
    fun closeUpClippedPianoStillCapturesExactMelody() {
        val melody = listOf(60, 62, 64, 65, 67, 69, 71, 72)
        val step = 0.50
        val events = melody.mapIndexed { idx, midi ->
            Triple(midi, 0.15 + idx * step, step * 0.82)
        }
        // Heavy close-up overdrive (3.2x gain -> clipped peaks)
        val pcm = synthesizePianoSequence(events, totalSec = 0.15 + melody.size * step + 0.6, clipGain = 3.2)
        val (midis, _) = runTracker(pcm, metronomeBpm = 120, metronomeActive = true)

        assertEquals(melody, midis)
    }

    @Test
    fun cMajorScaleWithDeepUnisonBeatingNeverSplitsDoOrRe() {
        // 88 BPM -> quarter = 0.682s with strong 3-string beating on Do4 & Re4
        val scale = listOf(60, 62, 64, 65, 67, 69, 71, 72)
        val beat = 60.0 / 88.0
        val events = scale.mapIndexed { idx, midi ->
            val dur = if (idx == scale.lastIndex) beat * 1.4 else beat * 0.86
            Triple(midi, 0.15 + idx * beat, dur)
        }
        val pcm = synthesizePianoSequence(
            events = events,
            totalSec = 0.15 + scale.size * beat + 1.0,
            beatingOnLowNotes = true
        )
        val (midis, _) = runTracker(pcm, metronomeBpm = 88, metronomeActive = false)

        assertTrue("Expected exact 8-note scale without Do/Re duplicates, got $midis", midis == scale)
    }
}
