package com.example

import com.example.model.DetectedNote
import com.example.model.MusicTheory
import com.example.model.NoteDuration
import com.example.model.SheetNoteEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetNoteEditorTest {

    private fun sampleNotes(): List<DetectedNote> = listOf(
        DetectedNote(id = 1, midiNote = 60, startTimeMs = 0L, durationMs = 600L, beats = 1.0f),
        DetectedNote(id = 2, midiNote = 64, startTimeMs = 600L, durationMs = 600L, beats = 1.0f),
        DetectedNote(id = 3, midiNote = 67, startTimeMs = 1200L, durationMs = 600L, beats = 1.0f)
    )

    @Test
    fun diatonicSteppingMovesCleanlyAlongStaffAndRespectsBounds() {
        // Ascending C4 -> D4 -> E4 -> F4 -> G4 -> A4 -> B4 -> C5
        var midi = 60
        val expectedUp = listOf(62, 64, 65, 67, 69, 71, 72)
        for (exp in expectedUp) {
            midi = SheetNoteEditor.stepDiatonic(midi, +1)
            assertEquals(exp, midi)
        }

        // Descending C5 -> B4 -> A4 -> G4 -> F4 -> E4 -> D4 -> C4 -> B3
        val expectedDown = listOf(71, 69, 67, 65, 64, 62, 60, 59)
        for (exp in expectedDown) {
            midi = SheetNoteEditor.stepDiatonic(midi, -1)
            assertEquals(exp, midi)
        }

        // Stepping from a sharp (F#4 = 66) goes to G4 (67) up and F4 (65) down
        assertEquals(67, SheetNoteEditor.stepDiatonic(66, +1))
        assertEquals(65, SheetNoteEditor.stepDiatonic(66, -1))

        // Clamps safely at piano range bounds (C3=48 .. C6=84)
        assertEquals(MusicTheory.MIN_PIANO_MIDI, SheetNoteEditor.stepDiatonic(MusicTheory.MIN_PIANO_MIDI, -1))
        assertEquals(MusicTheory.MAX_PIANO_MIDI, SheetNoteEditor.stepDiatonic(MusicTheory.MAX_PIANO_MIDI, +1))
    }

    @Test
    fun toggleSharpWorksForEligibleKeysAndProtectsEAndB() {
        assertTrue(SheetNoteEditor.canToggleSharp(60)) // C4 -> C#4
        assertEquals(61, SheetNoteEditor.toggleSharp(60))
        assertEquals(60, SheetNoteEditor.toggleSharp(61))

        assertTrue(SheetNoteEditor.canToggleSharp(65)) // F4 -> F#4
        assertEquals(66, SheetNoteEditor.toggleSharp(65))

        // E4 (64) and B4 (71) have no sharp black key above
        assertFalse(SheetNoteEditor.canToggleSharp(64))
        assertFalse(SheetNoteEditor.canToggleSharp(71))
        assertEquals(64, SheetNoteEditor.toggleSharp(64))
        assertEquals(71, SheetNoteEditor.toggleSharp(71))
    }

    @Test
    fun updatePitchMovesNoteAndConvertsRestToNote() {
        val withRest = sampleNotes() + DetectedNote(
            id = 4, midiNote = -1, startTimeMs = 1800L, durationMs = 600L, beats = 1.0f, isRest = true
        )
        // Fix misdetected second note from E4 (64) to D4 (62)
        val moved = SheetNoteEditor.updatePitch(withRest, index = 1, newMidi = 62)
        assertEquals(62, moved[1].midiNote)
        assertFalse(moved[1].isRest)

        // Convert rest at index 3 to G4 (67)
        val converted = SheetNoteEditor.updatePitch(moved, index = 3, newMidi = 67)
        assertEquals(67, converted[3].midiNote)
        assertFalse(converted[3].isRest)
    }

    @Test
    fun updateDurationSupportsSixteenthThroughWholeNote() {
        val bpm = 100 // 600ms per beat
        var notes = sampleNotes()

        val expectedDurations = listOf(
            NoteDuration.SIXTEENTH to 150L,
            NoteDuration.EIGHTH to 300L,
            NoteDuration.QUARTER to 600L,
            NoteDuration.HALF to 1200L,
            NoteDuration.WHOLE to 2400L
        )

        for ((dur, expectedMs) in expectedDurations) {
            notes = SheetNoteEditor.updateDuration(notes, index = 0, newDuration = dur, bpm = bpm)
            assertEquals(dur, notes[0].duration)
            assertEquals(dur.beats, notes[0].beats, 0.001f)
            assertEquals(expectedMs, notes[0].durationMs)
        }
    }

    @Test
    fun insertAndRemoveMaintainCleanSelectionIndices() {
        val notes = sampleNotes()
        val (afterInsert, newIdx) = SheetNoteEditor.insertNoteAfter(notes, selectedIndex = 1, bpm = 100)
        assertEquals(4, afterInsert.size)
        assertEquals(2, newIdx)
        assertEquals(64, afterInsert[newIdx].midiNote) // matches previous note pitch
        assertEquals(NoteDuration.QUARTER, afterInsert[newIdx].duration)

        val (afterRemoveMid, nextSelMid) = SheetNoteEditor.removeNoteAt(afterInsert, index = 2)
        assertEquals(3, afterRemoveMid.size)
        assertEquals(2, nextSelMid)

        val (afterRemoveLast, nextSelLast) = SheetNoteEditor.removeNoteAt(afterRemoveMid, index = 2)
        assertEquals(2, afterRemoveLast.size)
        assertEquals(1, nextSelLast)

        val (empty, selEmpty) = SheetNoteEditor.removeNoteAt(listOf(notes.first()), index = 0)
        assertTrue(empty.isEmpty())
        assertNull(selEmpty)
    }

    @Test
    fun playbackTempoSpeedUpDownClampsSafelyAndAdjustsStepDuration() {
        assertEquals(95, SheetNoteEditor.adjustTempo(90, +SheetNoteEditor.TEMPO_STEP_BPM))
        assertEquals(85, SheetNoteEditor.adjustTempo(90, -SheetNoteEditor.TEMPO_STEP_BPM))
        assertEquals(40, SheetNoteEditor.adjustTempo(42, -5))
        assertEquals(260, SheetNoteEditor.adjustTempo(258, +5))

        // Speeding up from 60 BPM (1000ms/beat) to 120 BPM (500ms/beat) halves quarter-note step duration
        assertEquals(1000L, SheetNoteEditor.computePlaybackStepMs(beats = 1.0f, bpm = 60))
        assertEquals(500L, SheetNoteEditor.computePlaybackStepMs(beats = 1.0f, bpm = 120))
        // 260 BPM eighth note stays comfortably above minimum floor
        assertEquals(115L, SheetNoteEditor.computePlaybackStepMs(beats = 0.5f, bpm = 260))
    }

    @Test
    fun resolvePlaybackStartIndexStartsFromSelectedNoteInEditMode() {
        assertNull(SheetNoteEditor.resolvePlaybackStartIndex(notesSize = 0, requestedStartIndex = 0))
        assertEquals(0, SheetNoteEditor.resolvePlaybackStartIndex(notesSize = 5, requestedStartIndex = null))
        assertEquals(3, SheetNoteEditor.resolvePlaybackStartIndex(notesSize = 5, requestedStartIndex = 3))
        assertEquals(4, SheetNoteEditor.resolvePlaybackStartIndex(notesSize = 5, requestedStartIndex = 99))
        assertEquals(0, SheetNoteEditor.resolvePlaybackStartIndex(notesSize = 5, requestedStartIndex = -2))
    }

    @Test
    fun clearAllNotesWipesStaffAndSelection() {
        val (cleared, sel) = SheetNoteEditor.clearAllNotes()
        assertTrue(cleared.isEmpty())
        assertNull(sel)
    }
}
