package com.enso.music.data.entity

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "songs",
    indices = [
        Index(value = ["path"], unique = true),
        Index(value = ["album"]),
        Index(value = ["artist"]),
        Index(value = ["sortKey"]),
        Index(value = ["sortInitial"])
    ]
)
data class SongEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val path: String,
    val title: String,
    val artist: String,
    val rawArtist: String = artist,
    val album: String,
    val duration: Long,
    val trackNumber: Int = 0,
    val size: Long = 0,
    val lastModified: Long = 0,
    val coverPath: String? = null,
    val sortKey: String = "",
    val sortInitial: String = "#"
)

data class SongSnapshot(
    val path: String,
    val lastModified: Long,
    val size: Long
)

data class AlbumItem(
    val album: String,
    val artist: String,
    val songCount: Int,
    val coverPath: String?
) {
    @Ignore
    var sortKey: String = ""

    @Ignore
    var sortInitial: String = "#"

    fun withSort(key: String, initial: String): AlbumItem = apply {
        this.sortKey = key
        this.sortInitial = initial
    }
}

data class ArtistItem(
    val artist: String,
    val songCount: Int,
    val albumCount: Int
) {
    @Ignore
    var sortKey: String = ""

    @Ignore
    var sortInitial: String = "#"

    fun withSort(key: String, initial: String): ArtistItem = apply {
        this.sortKey = key
        this.sortInitial = initial
    }
}
