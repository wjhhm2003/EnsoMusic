package com.enso.music.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.enso.music.data.entity.AlbumItem
import com.enso.music.data.entity.ArtistItem
import com.enso.music.data.entity.SongArtistCrossRef
import com.enso.music.data.entity.SongEntity
import com.enso.music.data.entity.SongSnapshot
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {

    @Query("SELECT * FROM songs ORDER BY sortKey COLLATE NOCASE ASC, title COLLATE NOCASE ASC")
    fun getAllSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs ORDER BY sortKey COLLATE NOCASE ASC, title COLLATE NOCASE ASC")
    fun getAllSongsList(): List<SongEntity>

    @Query("""
        SELECT * FROM songs 
        WHERE album = :album 
        ORDER BY 
            CASE WHEN trackNumber <= 0 THEN 999999 ELSE trackNumber END ASC, 
            path COLLATE NOCASE ASC, 
            title COLLATE NOCASE ASC
    """)
    fun getSongsByAlbum(album: String): Flow<List<SongEntity>>

    @Query("""
        SELECT * FROM songs 
        WHERE album = :album 
        ORDER BY 
            CASE WHEN trackNumber <= 0 THEN 999999 ELSE trackNumber END ASC, 
            path COLLATE NOCASE ASC, 
            title COLLATE NOCASE ASC
    """)
    fun getSongsByAlbumList(album: String): List<SongEntity>

    @Query("""
        SELECT s.* 
        FROM songs s
        INNER JOIN song_artist_cross_ref ref ON s.path = ref.songPath
        WHERE ref.artistName = :artist
        ORDER BY s.sortKey COLLATE NOCASE ASC, s.title COLLATE NOCASE ASC
    """)
    fun getSongsByArtist(artist: String): Flow<List<SongEntity>>

    @Query("""
        SELECT s.* 
        FROM songs s
        INNER JOIN song_artist_cross_ref ref ON s.path = ref.songPath
        WHERE ref.artistName = :artist
        ORDER BY s.sortKey COLLATE NOCASE ASC, s.title COLLATE NOCASE ASC
    """)
    fun getSongsByArtistList(artist: String): List<SongEntity>

    @Query("""
        SELECT album, artist, COUNT(*) as songCount, MAX(coverPath) as coverPath 
        FROM songs 
        GROUP BY album 
        ORDER BY album COLLATE NOCASE ASC
    """)
    fun getAllAlbums(): Flow<List<AlbumItem>>

    @Query("""
        SELECT 
            ref.artistName AS artist, 
            COUNT(DISTINCT s.path) AS songCount, 
            COUNT(DISTINCT s.album) AS albumCount
        FROM song_artist_cross_ref ref
        INNER JOIN songs s ON ref.songPath = s.path
        GROUP BY ref.artistName
        ORDER BY ref.artistName COLLATE NOCASE ASC
    """)
    fun getAllArtists(): Flow<List<ArtistItem>>

    @Query("SELECT * FROM songs WHERE path IN (:paths)")
    fun getSongsByPaths(paths: List<String>): List<SongEntity>

    @Query("SELECT path, lastModified, size FROM songs")
    fun getAllSongSnapshots(): List<SongSnapshot>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSongs(songs: List<SongEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSongArtistCrossRefs(refs: List<SongArtistCrossRef>): List<Long>

    @Query("DELETE FROM song_artist_cross_ref WHERE songPath IN (:paths)")
    fun deleteSongArtistCrossRefsByPaths(paths: List<String>): Int

    @Query("DELETE FROM songs WHERE path IN (:paths)")
    fun deleteSongsByPaths(paths: List<String>): Int

    @Query("DELETE FROM song_artist_cross_ref")
    fun clearAllSongArtistCrossRefs(): Int

    @Query("DELETE FROM songs")
    fun clearAll(): Int

    @Transaction
    fun upsertSongsWithArtists(songs: List<SongEntity>, crossRefs: List<SongArtistCrossRef>) {
        if (songs.isEmpty()) return
        val paths = songs.map { it.path }
        deleteSongArtistCrossRefsByPaths(paths)
        insertSongs(songs)
        if (crossRefs.isNotEmpty()) {
            insertSongArtistCrossRefs(crossRefs)
        }
    }

    @Transaction
    fun deleteSongsWithArtists(paths: List<String>): Int {
        if (paths.isEmpty()) return 0
        deleteSongArtistCrossRefsByPaths(paths)
        return deleteSongsByPaths(paths)
    }

    @Transaction
    fun clearAllWithArtists() {
        clearAllSongArtistCrossRefs()
        clearAll()
    }

    @Query("SELECT COUNT(*) FROM songs")
    fun getSongCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM songs")
    fun getSongCount(): Int
}
