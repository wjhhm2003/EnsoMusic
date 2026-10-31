package com.enso.music.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.enso.music.data.dao.ScanFolderDao
import com.enso.music.data.dao.SongDao
import com.enso.music.data.entity.ScanFolderEntity
import com.enso.music.data.entity.SongArtistCrossRef
import com.enso.music.data.entity.SongEntity

@Database(
    entities = [
        SongEntity::class,
        ScanFolderEntity::class,
        SongArtistCrossRef::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun scanFolderDao(): ScanFolderDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "enso_music.db"
                )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
