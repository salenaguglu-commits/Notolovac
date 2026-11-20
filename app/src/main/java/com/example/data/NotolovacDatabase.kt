package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SongEntity::class], version = 1, exportSchema = false)
abstract class NotolovacDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao

    companion object {
        @Volatile
        private var INSTANCE: NotolovacDatabase? = null

        fun getInstance(context: Context): NotolovacDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NotolovacDatabase::class.java,
                    "notolovac_notebook.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
