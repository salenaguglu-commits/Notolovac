package com.example

import com.example.audio.KeyboardPerformanceRecorder
import com.example.audio.PolyphonicPianoMixer
import com.example.model.NoteDuration
import com.example.ui.components.PianoKeyboardHitTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PolyphonicKeyboardAndMixerTest {

    @Test
    fun mixesMultiFingerChordWithoutClippingAndReleasesSmoothlyToSilence() {
        val mixer = PolyphonicPianoMixer(sampleRate = 44100, maxVoices = 16)
        val chunk = ShortArray(512)

        // Hold a rich 5-note Cmaj9 chord simultaneously (C4, E4, G4, B4, D5)
        val chord = listOf(60, 64, 67, 71, 74)
        chord.forEach { mixer.noteOn(it) }
        assertEquals(5, mixer.activeVoiceCount())

        var maxPeak = 0
        repeat(20) {
            val active = mixer.render(chunk)
            assertTrue("Chord should actively render audio", active)
            for (s in chunk) {
                val a = abs(s.toInt())
                if (a > maxPeak) maxPeak = a
            }
        }
        assertTrue("Chord audio should be clearly audible, peak=$maxPeak", maxPeak > 14000)
        assertTrue("Soft limiter must prevent harsh clipping, peak=$maxPeak", maxPeak <= 32767)

        // Release all 5 fingers and verify smooth damper decay to complete silence (~115ms = ~10 chunks)
        chord.forEach { mixer.noteOff(it) }
        repeat(20) { mixer.render(chunk) }
        assertEquals("All released voices should fade out completely", 0, mixer.activeVoiceCount())
        assertFalse("Mixer should report silence once all voices finish", mixer.render(chunk))
    }

    @Test
    fun handlesFastFullKeyboardGlissandoSwipeWithoutDroppingOrCrashing() {
        val mixer = PolyphonicPianoMixer(sampleRate = 44100, maxVoices = 16)
        val chunk = ShortArray(512)
        val whiteKeys = PianoKeyboardHitTest.whiteKeyMidis(startOctave = 4, octaveSpan = 2) // 15 keys C4..C6

        var prevMidi: Int? = null
        var nonSilentChunks = 0
        for (midi in whiteKeys) {
            if (prevMidi != null) mixer.noteOff(prevMidi)
            mixer.noteOn(midi)
            prevMidi = midi
            // ~35ms per key swipe (3 chunks of 512 samples)
            repeat(3) {
                if (mixer.render(chunk)) nonSilentChunks++
            }
        }
        if (prevMidi != null) mixer.noteOff(prevMidi)

        assertEquals(whiteKeys.size * 3, nonSilentChunks)
        assertTrue("Voice count must stay within polyphony limit", mixer.activeVoiceCount() <= 16)

        // Let final key tail fade cleanly
        repeat(20) { mixer.render(chunk) }
        assertEquals(0, mixer.activeVoiceCount())
    }

    @Test
    fun keyboardHitTestDistinguishesWhiteKeysBlackKeysAndOutOfBounds() {
        val whiteKeys = PianoKeyboardHitTest.whiteKeyMidis(startOctave = 4, octaveSpan = 2)
        val blackKeys = PianoKeyboardHitTest.blackKeys(startOctave = 4, octaveSpan = 2)
        val width = 1500f // exactly 100px per white key
        val height = 200f

        // Lower half (y = 160f > blackKeyHeight=112f): strictly white keys
        assertEquals(60, PianoKeyboardHitTest.hitTest(50f, 160f, width, height, whiteKeys, blackKeys))   // C4
        assertEquals(62, PianoKeyboardHitTest.hitTest(150f, 160f, width, height, whiteKeys, blackKeys))  // D4
        assertEquals(64, PianoKeyboardHitTest.hitTest(250f, 160f, width, height, whiteKeys, blackKeys))  // E4
        assertEquals(84, PianoKeyboardHitTest.hitTest(1450f, 160f, width, height, whiteKeys, blackKeys)) // C6

        // Upper half (y = 50f <= 112f): black keys take priority over white keys
        // C#4 sits at slotPos 0.68 -> x in [68..132]
        assertEquals(61, PianoKeyboardHitTest.hitTest(100f, 50f, width, height, whiteKeys, blackKeys))   // C#4
        // D#4 sits at slotPos 1.72 -> x in [172..236]
        assertEquals(63, PianoKeyboardHitTest.hitTest(200f, 50f, width, height, whiteKeys, blackKeys))   // D#4
        // F#4 sits at slotPos 3.66 -> x in [366..430]
        assertEquals(66, PianoKeyboardHitTest.hitTest(395f, 50f, width, height, whiteKeys, blackKeys))   // F#4
        // Between E4 and F4 (x = 300f) there is no black key -> white key F4
        assertEquals(65, PianoKeyboardHitTest.hitTest(305f, 50f, width, height, whiteKeys, blackKeys))   // F4

        // Out-of-bounds swipe leaves keyboard cleanly
        assertNull(PianoKeyboardHitTest.hitTest(-5f, 100f, width, height, whiteKeys, blackKeys))
        assertNull(PianoKeyboardHitTest.hitTest(1505f, 100f, width, height, whiteKeys, blackKeys))
        assertNull(PianoKeyboardHitTest.hitTest(500f, -1f, width, height, whiteKeys, blackKeys))
        assertNull(PianoKeyboardHitTest.hitTest(500f, 205f, width, height, whiteKeys, blackKeys))
    }

    @Test
    fun recordsTouchKeyboardMelodyAndQuantizesDurations() {
        val recorder = KeyboardPerformanceRecorder()
        assertFalse(recorder.isRecording)

        recorder.start()
        assertTrue(recorder.isRecording)

        // Play C4 (1/4), D4 (1/4), E4 (1/4), G4 (1/2) at ~120 BPM (500ms per beat)
        recorder.onKeyPressed(60, 1000L)
        recorder.onKeyReleased(60, 1460L)

        recorder.onKeyPressed(62, 1500L)
        recorder.onKeyReleased(62, 1960L)

        recorder.onKeyPressed(64, 2000L)
        recorder.onKeyReleased(64, 2460L)

        val liveNotes = recorder.onKeyPressed(67, 2500L)
        assertEquals(4, liveNotes.size)
        // Leave last key held until Finish is tapped at 3560L (~1060ms -> half note)
        val result = recorder.finishAndQuantize(
            endTimestampMs = 3560L,
            fallbackBpm = 120,
            lockToMetronomeBpm = true
        )

        assertFalse(recorder.isRecording)
        val pitched = result.notes.filter { !it.isRest }
        assertEquals(listOf(60, 62, 64, 67), pitched.map { it.midiNote })
        assertEquals(NoteDuration.QUARTER, pitched[0].duration)
        assertEquals(NoteDuration.QUARTER, pitched[1].duration)
        assertEquals(NoteDuration.QUARTER, pitched[2].duration)
        assertEquals(NoteDuration.HALF, pitched[3].duration)
        assertEquals(120, result.effectiveBpm)
    }
}
