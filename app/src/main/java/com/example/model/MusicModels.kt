package com.example.model

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

enum class NoteLabelMode(val key: String) {
    SOLFEGE("solfege"),
    LETTERS("letters"),
    NONE("none");

    companion object {
        fun fromKey(key: String?): NoteLabelMode =
            entries.firstOrNull { it.key == key } ?: SOLFEGE
    }
}

enum class NoteDuration(val beats: Float, val serbianName: String) {
    EIGHTH(0.5f, "Osmina"),
    QUARTER(1.0f, "Četvrtina"),
    HALF(2.0f, "Polovina"),
    WHOLE(4.0f, "Cela nota");

    companion object {
        fun fromBeats(beats: Float): NoteDuration = when {
            beats < 0.75f -> EIGHTH
            beats < 1.5f -> QUARTER
            beats < 3.0f -> HALF
            else -> WHOLE
        }
    }
}

data class DetectedNote(
    val id: Long = 0L,
    val midiNote: Int, // -1 indicates a rest
    val startTimeMs: Long,
    val durationMs: Long,
    val beats: Float = 1.0f,
    val confidence: Float = 1.0f,
    val isRest: Boolean = false
) {
    val duration: NoteDuration
        get() = NoteDuration.fromBeats(beats)
}

object MusicTheory {
    const val STANDARD_A4_HZ = 440.0
    const val STANDARD_C4_HZ = 261.625565
    const val MIN_PIANO_MIDI = 48 // C3
    const val MAX_PIANO_MIDI = 84 // C6

    private val SOLFEGE_NAMES = arrayOf(
        "Do", "Do#", "Re", "Re#", "Mi", "Fa", "Fa#", "Sol", "Sol#", "La", "La#", "Si"
    )

    private val LETTER_NAMES = arrayOf(
        "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"
    )

    // Semitone index -> diatonic step within an octave (0..6)
    private val SEMITONE_TO_DIATONIC = intArrayOf(
        0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6
    )

    private val IS_SHARP = booleanArrayOf(
        false, true, false, true, false, false, true, false, true, false, true, false
    )

    fun midiToFrequency(midi: Int, calibrationCents: Float = 0f): Double {
        val baseHz = STANDARD_A4_HZ * 2.0.pow((midi - 69) / 12.0)
        return baseHz * 2.0.pow(calibrationCents / 1200.0)
    }

    fun frequencyToMidiFloat(freqHz: Double, calibrationCents: Float = 0f): Double {
        if (freqHz <= 0.0) return -1.0
        val adjustedHz = freqHz * 2.0.pow(-calibrationCents / 1200.0)
        return 69.0 + 12.0 * (ln(adjustedHz / STANDARD_A4_HZ) / ln(2.0))
    }

    fun frequencyToNearestMidi(freqHz: Double, calibrationCents: Float = 0f): Pair<Int, Float> {
        val midiFloat = frequencyToMidiFloat(freqHz, calibrationCents)
        val nearest = midiFloat.roundToInt()
        val centsOff = ((midiFloat - nearest) * 100.0).toFloat()
        return nearest to centsOff
    }

    /**
     * Computes how many cents a played C note (in any octave C2..C6) deviates from standard C.
     */
    fun computeCalibrationCentsFromC(measuredHz: Double): Pair<Float, Double>? {
        if (measuredHz < 60.0 || measuredHz > 2200.0) return null
        val midiFloat = 69.0 + 12.0 * (ln(measuredHz / STANDARD_A4_HZ) / ln(2.0))
        // Find nearest C midi note (36, 48, 60, 72, 84)
        val cCandidates = intArrayOf(36, 48, 60, 72, 84)
        val nearestC = cCandidates.minByOrNull { kotlin.math.abs(midiFloat - it) } ?: 60
        val centsDiff = ((midiFloat - nearestC) * 100.0).toFloat()
        // Reject if it's more than 120 cents away from any C (child probably played D or B)
        if (kotlin.math.abs(centsDiff) > 120f) return null
        val normalizedC4Hz = STANDARD_C4_HZ * 2.0.pow(centsDiff / 1200.0)
        return centsDiff to normalizedC4Hz
    }

    fun midiToSolfege(midi: Int): String {
        val index = ((midi % 12) + 12) % 12
        return SOLFEGE_NAMES[index]
    }

    fun midiToLetter(midi: Int, includeOctave: Boolean = false): String {
        val index = ((midi % 12) + 12) % 12
        val octave = (midi / 12) - 1
        return if (includeOctave) "${LETTER_NAMES[index]}$octave" else LETTER_NAMES[index]
    }

    fun formatNoteLabel(midi: Int, mode: NoteLabelMode, includeOctave: Boolean = false): String {
        return when (mode) {
            NoteLabelMode.SOLFEGE -> midiToSolfege(midi)
            NoteLabelMode.LETTERS -> midiToLetter(midi, includeOctave)
            NoteLabelMode.NONE -> ""
        }
    }

    fun formatFullNoteDescription(midi: Int): String {
        return "${midiToSolfege(midi)} (${midiToLetter(midi, includeOctave = true)})"
    }

    fun isSharp(midi: Int): Boolean {
        val index = ((midi % 12) + 12) % 12
        return IS_SHARP[index]
    }

    /**
     * Returns diatonic step relative to Middle C (C4 = 0).
     * E4 (bottom treble staff line) = 2
     * F4 = 3, G4 = 4, A4 = 5, B4 (middle line) = 6, C5 = 7, D5 = 8, E5 = 9, F5 (top line) = 10
     */
    fun midiToStaffStep(midi: Int): Int {
        val octave = (midi / 12) - 1
        val semitone = ((midi % 12) + 12) % 12
        val diatonicInOctave = SEMITONE_TO_DIATONIC[semitone]
        return (octave - 4) * 7 + diatonicInOctave
    }

    fun diatonicColorIndex(midi: Int): Int {
        val semitone = ((midi % 12) + 12) % 12
        return SEMITONE_TO_DIATONIC[semitone]
    }

    fun serializeNotes(notes: List<DetectedNote>): String {
        val array = JSONArray()
        notes.forEach { n ->
            val obj = JSONObject()
            obj.put("id", n.id)
            obj.put("m", n.midiNote)
            obj.put("s", n.startTimeMs)
            obj.put("d", n.durationMs)
            obj.put("b", n.beats.toDouble())
            obj.put("c", n.confidence.toDouble())
            obj.put("r", n.isRest)
            array.put(obj)
        }
        return array.toString()
    }

    fun deserializeNotes(raw: String): List<DetectedNote> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            val list = ArrayList<DetectedNote>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    DetectedNote(
                        id = obj.optLong("id", i.toLong() + 1L),
                        midiNote = obj.optInt("m", 60),
                        startTimeMs = obj.optLong("s", 0L),
                        durationMs = obj.optLong("d", 500L),
                        beats = obj.optDouble("b", 1.0).toFloat(),
                        confidence = obj.optDouble("c", 1.0).toFloat(),
                        isRest = obj.optBoolean("r", false)
                    )
                )
            }
            list
        }.getOrElse { emptyList() }
    }
}
