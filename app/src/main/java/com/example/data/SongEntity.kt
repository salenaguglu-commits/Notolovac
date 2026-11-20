package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.model.DetectedNote
import com.example.model.MusicTheory

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val bpm: Int = 90,
    val beatsPerMeasure: Int = 4,
    val notesJson: String,
    val calibrationCentsUsed: Float = 0f
)

data class SavedSong(
    val id: Long = 0L,
    val title: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val bpm: Int = 90,
    val beatsPerMeasure: Int = 4,
    val notes: List<DetectedNote>,
    val calibrationCentsUsed: Float = 0f
) {
    val playableNoteCount: Int
        get() = notes.count { !it.isRest }

    fun toEntity(): SongEntity = SongEntity(
        id = id,
        title = title,
        createdAtMillis = createdAtMillis,
        bpm = bpm,
        beatsPerMeasure = beatsPerMeasure,
        notesJson = MusicTheory.serializeNotes(notes),
        calibrationCentsUsed = calibrationCentsUsed
    )

    companion object {
        fun fromEntity(entity: SongEntity): SavedSong = SavedSong(
            id = entity.id,
            title = entity.title,
            createdAtMillis = entity.createdAtMillis,
            bpm = entity.bpm,
            beatsPerMeasure = entity.beatsPerMeasure,
            notes = MusicTheory.deserializeNotes(entity.notesJson),
            calibrationCentsUsed = entity.calibrationCentsUsed
        )
    }
}
