package com.example.model

/**
 * Pure, deterministic note-editing transformations for the sheet music editor.
 * Allows moving notes by staff step (diatonic), toggling sharps, assigning exact MIDI keys
 * from the glowing piano keyboard, changing note durations (1/16..1/1), inserting, and deleting.
 */
object SheetNoteEditor {

    private val DIATONIC_NATURAL_OFFSETS = intArrayOf(0, 2, 4, 5, 7, 9, 11)

    /**
     * Moves a MIDI note one diatonic step up (+1) or down (-1) along the treble staff.
     * - From a natural note (e.g. C4=60): +1 -> D4(62), -1 -> B3(59).
     * - From a sharp note (e.g. C#4=61): +1 -> D4(62), -1 -> C4(60).
     */
    fun stepDiatonic(midi: Int, direction: Int): Int {
        if (direction == 0) return midi.coerceIn(MusicTheory.MIN_PIANO_MIDI, MusicTheory.MAX_PIANO_MIDI)
        val clamped = midi.coerceIn(MusicTheory.MIN_PIANO_MIDI, MusicTheory.MAX_PIANO_MIDI)
        val octave = clamped / 12
        val semi = clamped % 12

        if (direction > 0) {
            val nextInOctave = DIATONIC_NATURAL_OFFSETS.firstOrNull { it > semi }
            val candidate = if (nextInOctave != null) {
                octave * 12 + nextInOctave
            } else {
                (octave + 1) * 12 // C of next octave
            }
            return candidate.coerceAtMost(MusicTheory.MAX_PIANO_MIDI)
        } else {
            val prevInOctave = DIATONIC_NATURAL_OFFSETS.lastOrNull { it < semi }
            val candidate = if (prevInOctave != null) {
                octave * 12 + prevInOctave
            } else {
                (octave - 1) * 12 + 11 // B of previous octave
            }
            return candidate.coerceAtLeast(MusicTheory.MIN_PIANO_MIDI)
        }
    }

    /**
     * Returns true if this MIDI note can toggle a sharp (#) cleanly (all notes except E and B,
     * which have no black key immediately above them, and top C6).
     */
    fun canToggleSharp(midi: Int): Boolean {
        if (midi !in MusicTheory.MIN_PIANO_MIDI..MusicTheory.MAX_PIANO_MIDI) return false
        val semi = ((midi % 12) + 12) % 12
        if (MusicTheory.isSharp(midi)) return true
        // Natural E (4) and B (11) have no sharp black key above; C6 (84) is max range
        return semi != 4 && semi != 11 && midi < MusicTheory.MAX_PIANO_MIDI
    }

    /**
     * Toggles sharp on a note: C4 <-> C#4, F4 <-> F#4, etc.
     */
    fun toggleSharp(midi: Int): Int {
        if (!canToggleSharp(midi)) return midi.coerceIn(MusicTheory.MIN_PIANO_MIDI, MusicTheory.MAX_PIANO_MIDI)
        return if (MusicTheory.isSharp(midi)) {
            (midi - 1).coerceAtLeast(MusicTheory.MIN_PIANO_MIDI)
        } else {
            (midi + 1).coerceAtMost(MusicTheory.MAX_PIANO_MIDI)
        }
    }

    fun updatePitch(
        notes: List<DetectedNote>,
        index: Int,
        newMidi: Int
    ): List<DetectedNote> {
        if (index !in notes.indices) return notes
        val clamped = newMidi.coerceIn(MusicTheory.MIN_PIANO_MIDI, MusicTheory.MAX_PIANO_MIDI)
        return notes.mapIndexed { i, note ->
            if (i == index) {
                note.copy(
                    midiNote = clamped,
                    isRest = false,
                    confidence = 1f
                )
            } else {
                note
            }
        }
    }

    fun updateDuration(
        notes: List<DetectedNote>,
        index: Int,
        newDuration: NoteDuration,
        bpm: Int
    ): List<DetectedNote> {
        if (index !in notes.indices) return notes
        val msPerBeat = 60_000.0 / bpm.coerceIn(50, 160)
        val newDurationMs = (newDuration.beats * msPerBeat).toLong().coerceAtLeast(80L)
        return notes.mapIndexed { i, note ->
            if (i == index) {
                note.copy(
                    beats = newDuration.beats,
                    durationMs = newDurationMs
                )
            } else {
                note
            }
        }
    }

    fun insertNoteAfter(
        notes: List<DetectedNote>,
        selectedIndex: Int?,
        bpm: Int
    ): Pair<List<DetectedNote>, Int> {
        val msPerBeat = 60_000.0 / bpm.coerceIn(50, 160)
        val insertPos = if (selectedIndex != null && selectedIndex in notes.indices) {
            selectedIndex + 1
        } else {
            notes.size
        }
        val refNote = selectedIndex?.let { notes.getOrNull(it) } ?: notes.lastOrNull()
        val refMidi = if (refNote != null && !refNote.isRest) refNote.midiNote else 60 // Default Middle C
        val nextId = (notes.maxOfOrNull { it.id } ?: 0L) + 1L
        val startMs = refNote?.let { it.startTimeMs + it.durationMs } ?: 0L

        val inserted = DetectedNote(
            id = nextId,
            midiNote = refMidi,
            startTimeMs = startMs,
            durationMs = msPerBeat.toLong(),
            beats = NoteDuration.QUARTER.beats,
            confidence = 1f,
            isRest = false
        )
        val updated = notes.toMutableList().apply { add(insertPos, inserted) }
        return updated to insertPos
    }

    fun removeNoteAt(
        notes: List<DetectedNote>,
        index: Int
    ): Pair<List<DetectedNote>, Int?> {
        if (index !in notes.indices) return notes to null
        val updated = notes.toMutableList().apply { removeAt(index) }
        val nextSelected = when {
            updated.isEmpty() -> null
            index < updated.size -> index
            else -> updated.lastIndex
        }
        return updated to nextSelected
    }
}
