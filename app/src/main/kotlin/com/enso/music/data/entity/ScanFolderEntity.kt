package com.enso.music.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scan_folders")
data class ScanFolderEntity(
    @PrimaryKey
    val path: String,
    val isSelected: Boolean = true,
    val lastScanTime: Long = 0,
    val songCount: Int = 0
)
