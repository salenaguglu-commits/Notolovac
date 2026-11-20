package com.example.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SongRepository(private val songDao: SongDao) {
    val allSongs: Flow<List<SavedSong>> = songDao.getAllSongs().map { entities ->
        entities.map { SavedSong.fromEntity(it) }
    }

    suspend fun saveSong(song: SavedSong): Long {
        return songDao.upsertSong(song.toEntity())
    }

    suspend fun deleteSong(id: Long) {
        songDao.deleteSongById(id)
    }
}
