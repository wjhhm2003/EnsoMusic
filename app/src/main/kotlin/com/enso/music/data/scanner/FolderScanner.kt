package com.enso.music.data.scanner

import android.content.Context
import android.os.Environment
import com.enso.music.data.db.AppDatabase
import com.enso.music.data.entity.ScanFolderEntity
import com.enso.music.data.entity.SongArtistCrossRef
import com.enso.music.data.entity.SongEntity
import com.enso.music.data.entity.SongSnapshot
import com.enso.music.data.util.ArtistParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class ScanProgress(
    val isScanning: Boolean = false,
    val currentPath: String = "",
    val totalFound: Int = 0,
    val addedOrUpdated: Int = 0,
    val removedCount: Int = 0,
    val error: String? = null
)

class FolderScanner(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val songDao = db.songDao()
    private val scanFolderDao = db.scanFolderDao()
    private val metadataHelper = MetadataHelper(context)

    private val _scanProgress = MutableStateFlow(ScanProgress())
    val scanProgress: StateFlow<ScanProgress> = _scanProgress.asStateFlow()

    private val supportedExtensions = hashSetOf("mp3", "flac", "wav", "m4a", "aac", "ogg")

    suspend fun getInitialDefaultFolders(): List<String> {
        val rootPath = Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"
        val candidates = listOf(
            "$rootPath/Music",
            "$rootPath/Download",
            "$rootPath/netease/cloudmusic/Music",
            "$rootPath/qqmusic/song",
            "$rootPath/kuwo/music"
        )
        val existing = candidates.filter { File(it).exists() }
        return if (existing.isNotEmpty()) existing else listOf(rootPath)
    }

    suspend fun ensureDefaultFoldersConfigured() = withContext(Dispatchers.IO) {
        val existingFolders = scanFolderDao.getAllFoldersList()
        if (existingFolders.isEmpty()) {
            val defaults = getInitialDefaultFolders()
            val entities = defaults.map { ScanFolderEntity(path = it, isSelected = true) }
            scanFolderDao.insertFolders(entities)
        }
    }

    /**
     * Executes scan. If isFullRescan is true, every found file will have its metadata re-extracted.
     * Otherwise, incremental comparison (path, lastModified, size) avoids redundant IO.
     */
    suspend fun startScan(isFullRescan: Boolean = false) = withContext(Dispatchers.IO) {
        if (_scanProgress.value.isScanning) return@withContext

        ensureDefaultFoldersConfigured()
        val selectedFolders = scanFolderDao.getSelectedFolders().map { it.path }
        if (selectedFolders.isEmpty()) {
            _scanProgress.value = ScanProgress(isScanning = false, error = "没有勾选任何扫描目录")
            return@withContext
        }

        _scanProgress.value = ScanProgress(isScanning = true)

        try {
            val existingSnapshots: Map<String, SongSnapshot> = if (isFullRescan) {
                emptyMap()
            } else {
                songDao.getAllSongSnapshots().associateBy { it.path }
            }

            val foundFiles = ArrayList<File>()
            for (folderPath in selectedFolders) {
                val folder = File(folderPath)
                if (folder.exists() && folder.isDirectory) {
                    collectAudioFiles(folder, foundFiles)
                }
            }

            val foundPathSet = HashSet<String>(foundFiles.size)
            val batchToInsert = ArrayList<SongEntity>(50)
            val batchCrossRefs = ArrayList<SongArtistCrossRef>(100)
            var addedOrUpdated = 0
            var processed = 0

            for (file in foundFiles) {
                val path = file.absolutePath
                foundPathSet.add(path)
                processed++

                val snapshot = existingSnapshots[path]
                val isModified = snapshot == null ||
                        snapshot.lastModified != file.lastModified() ||
                        snapshot.size != file.length()

                if (isFullRescan || isModified) {
                    val song = metadataHelper.parseSong(file)
                    batchToInsert.add(song)

                    val artists = ArtistParser.parse(song.rawArtist)
                    for (art in artists) {
                        batchCrossRefs.add(SongArtistCrossRef(songPath = song.path, artistName = art))
                    }
                    addedOrUpdated++

                    if (batchToInsert.size >= 50) {
                        songDao.upsertSongsWithArtists(batchToInsert, batchCrossRefs)
                        batchToInsert.clear()
                        batchCrossRefs.clear()
                    }
                }

                if (processed % 10 == 0 || processed == foundFiles.size) {
                    _scanProgress.value = _scanProgress.value.copy(
                        currentPath = file.name,
                        totalFound = processed,
                        addedOrUpdated = addedOrUpdated
                    )
                }
            }

            if (batchToInsert.isNotEmpty()) {
                songDao.upsertSongsWithArtists(batchToInsert, batchCrossRefs)
                batchToInsert.clear()
                batchCrossRefs.clear()
            }

            // Remove deleted files that belonged to scanned folders
            var removedCount = 0
            if (isFullRescan) {
                // In full rescan, remove any DB song whose path was not found
                val allCurrentPaths = songDao.getAllSongSnapshots().map { it.path }
                val toDelete = allCurrentPaths.filter { !foundPathSet.contains(it) }
                if (toDelete.isNotEmpty()) {
                    songDao.deleteSongsWithArtists(toDelete)
                    removedCount = toDelete.size
                }
            } else {
                val toDelete = ArrayList<String>()
                for (oldPath in existingSnapshots.keys) {
                    // Check if oldPath is inside one of the scanned folders
                    val insideScanned = selectedFolders.any { oldPath.startsWith(it) }
                    if (insideScanned && !foundPathSet.contains(oldPath)) {
                        toDelete.add(oldPath)
                    }
                }
                if (toDelete.isNotEmpty()) {
                    songDao.deleteSongsWithArtists(toDelete)
                    removedCount = toDelete.size
                }
            }

            // Update folder scan times and counts
            val now = System.currentTimeMillis()
            for (folderPath in selectedFolders) {
                val countInFolder = foundFiles.count { it.absolutePath.startsWith(folderPath) }
                scanFolderDao.updateScanResult(folderPath, now, countInFolder)
            }

            _scanProgress.value = ScanProgress(
                isScanning = false,
                currentPath = "扫描完成",
                totalFound = foundFiles.size,
                addedOrUpdated = addedOrUpdated,
                removedCount = removedCount
            )
        } catch (e: Exception) {
            _scanProgress.value = ScanProgress(
                isScanning = false,
                error = e.localizedMessage ?: "扫描出错"
            )
        }
    }

    private fun collectAudioFiles(dir: File, result: ArrayList<File>) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            val name = file.name
            // Skip hidden folders and Android system data directories
            if (name.startsWith(".") || name.equals("Android", ignoreCase = true)) {
                continue
            }

            if (file.isDirectory) {
                collectAudioFiles(file, result)
            } else if (file.isFile) {
                val ext = file.extension.lowercase(Locale.ROOT)
                if (supportedExtensions.contains(ext) && file.length() > 1024) { // filter out 0 or tiny corrupt files
                    result.add(file)
                }
            }
        }
    }
}
