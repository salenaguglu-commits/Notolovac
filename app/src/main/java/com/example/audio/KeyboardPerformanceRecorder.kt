package com.example.audio

import com.example.model.DetectedNote

/**
 * Pure Kotlin touch-piano performance recorder used by Landscape Piano Stage ("Snimi svirku" / "Record Play").
 * Captures exact press and release timestamps for every touched key (including multi-touch chords & glissandos)
 * and quantizes the resulting performance into musical notation via [PianoNoteTracker.quantizeNotes].
 */
class KeyboardPerformanceRecorder {
    private val recordedNotes = mutableListOf<DetectedNote>()
    private val activePressIndexByMidi = mutableMapOf<Int, Int>()
    private var nextId = 1L

    var isRecording: Boolean = false
        private set

    fun start() {
        recordedNotes.clear()
        activePressIndexByMidi.clear()
        nextId = 1L
        isRecording = true
    }

    fun onKeyPressed(midiNote: Int, timestampMs: Long): List<DetectedNote> {
        if (!isRecording) return recordedNotes.toList()
        // Finalize any lingering press on the same key before starting a fresh strike
        finalizeKey(midiNote, timestampMs)

        val note = DetectedNote(
            id = nextId++,
            midiNote = midiNote,
            startTimeMs = timestampMs,
            durationMs = DEFAULT_PRESS_DURATION_MS,
            beats = 1.0f,
            confidence = 1f,
            isRest = false
        )
        val idx = recordedNotes.size
        recordedNotes.add(note)
        activePressIndexByMidi[midiNote] = idx
        return recordedNotes.toList()
    }

    fun onKeyReleased(midiNote: Int, timestampMs: Long): List<DetectedNote> {
        if (!isRecording) return recordedNotes.toList()
        finalizeKey(midiNote, timestampMs)
        return recordedNotes.toList()
    }

    fun finishAndQuantize(
        endTimestampMs: Long,
        fallbackBpm: Int,
        lockToMetronomeBpm: Boolean = false
    ): QuantizedTranscription {
        if (!isRecording) {
            return QuantizedTranscription(emptyList(), fallbackBpm)
        }
        val heldKeys = activePressIndexByMidi.keys.toList()
        heldKeys.forEach { midi -> finalizeKey(midi, endTimestampMs) }
        isRecording = false

        val snapshot = recordedNotes.toList()
        recordedNotes.clear()
        activePressIndexByMidi.clear()

        return PianoNoteTracker.quantizeNotes(
            rawNotes = snapshot,
            fallbackBpm = fallbackBpm,
            lockToMetronomeBpm = lockToMetronomeBpm
        )
    }

    fun cancel() {
        isRecording = false
        recordedNotes.clear()
        activePressIndexByMidi.clear()
    }

    private fun finalizeKey(midiNote: Int, endTimestampMs: Long) {
        val idx = activePressIndexByMidi.remove(midiNote) ?: return
        val existing = recordedNotes.getOrNull(idx) ?: return
        val duration = (endTimestampMs - existing.startTimeMs).coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
        recordedNotes[idx] = existing.copy(durationMs = duration)
    }

    companion object {
        private const val DEFAULT_PRESS_DURATION_MS = 280L
        private const val MIN_DURATION_MS = 90L
        private const val MAX_DURATION_MS = 4000L
    }
}
