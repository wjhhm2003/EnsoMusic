package com.enso.music.data.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "song_artist_cross_ref",
    primaryKeys = ["songPath", "artistName"],
    indices = [
        Index(value = ["artistName"]),
        Index(value = ["songPath"])
    ]
)
data class SongArtistCrossRef(
    val songPath: String,
    val artistName: String
)
