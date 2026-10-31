package com.enso.music.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.enso.music.data.entity.ScanFolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanFolderDao {

    @Query("SELECT * FROM scan_folders ORDER BY path ASC")
    fun getAllFolders(): Flow<List<ScanFolderEntity>>

    @Query("SELECT * FROM scan_folders ORDER BY path ASC")
    fun getAllFoldersList(): List<ScanFolderEntity>

    @Query("SELECT * FROM scan_folders WHERE isSelected = 1")
    fun getSelectedFolders(): List<ScanFolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertFolder(folder: ScanFolderEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertFolders(folders: List<ScanFolderEntity>): List<Long>

    @Delete
    fun deleteFolder(folder: ScanFolderEntity): Int

    @Query("DELETE FROM scan_folders WHERE path = :path")
    fun deleteByPath(path: String): Int

    @Query("UPDATE scan_folders SET isSelected = :selected WHERE path = :path")
    fun updateFolderSelection(path: String, selected: Boolean): Int

    @Query("UPDATE scan_folders SET lastScanTime = :time, songCount = :count WHERE path = :path")
    fun updateScanResult(path: String, time: Long, count: Int): Int
}
