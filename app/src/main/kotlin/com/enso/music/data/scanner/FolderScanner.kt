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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque
import java.util.LinkedHashMap
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
            val allKnownPaths = if (isFullRescan) {
                songDao.getAllSongSnapshots().map { it.path }
            } else {
                existingSnapshots.keys.toList()
            }

            // A user can select both a folder and one of its descendants. Keep
            // one entry per canonical path so Room cross-ref inserts cannot hit
            // duplicate primary keys and metadata is not parsed twice.
            val foundFilesByPath = LinkedHashMap<String, File>()
            for (folderPath in selectedFolders) {
                val folder = File(folderPath)
                if (folder.exists() && folder.isDirectory) {
                    collectAudioFiles(folder, foundFilesByPath)
                }
            }
            val foundFiles = foundFilesByPath.values.toList()

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
                // A full rescan still only owns the selected folders. Do not
                // delete songs belonging to a folder the user left unselected.
                val toDelete = allKnownPaths.filter { oldPath ->
                    isInsideAnySelectedFolder(oldPath, selectedFolders) && !foundPathSet.contains(oldPath)
                }
                if (toDelete.isNotEmpty()) {
                    songDao.deleteSongsWithArtists(toDelete)
                    removedCount = toDelete.size
                }
            } else {
                val toDelete = ArrayList<String>()
                for (oldPath in existingSnapshots.keys) {
                    // Check if oldPath is inside one of the scanned folders
                    val insideScanned = isInsideAnySelectedFolder(oldPath, selectedFolders)
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
                val countInFolder = foundFiles.count {
                    isInsideFolder(it.absolutePath, folderPath)
                }
                scanFolderDao.updateScanResult(folderPath, now, countInFolder)
            }

            _scanProgress.value = ScanProgress(
                isScanning = false,
                currentPath = "扫描完成",
                totalFound = foundFiles.size,
                addedOrUpdated = addedOrUpdated,
                removedCount = removedCount
            )
        } catch (e: CancellationException) {
            _scanProgress.value = ScanProgress()
            throw e
        } catch (e: Exception) {
            _scanProgress.value = ScanProgress(
                isScanning = false,
                error = e.localizedMessage ?: "扫描出错"
            )
        }
    }

    private fun collectAudioFiles(dir: File, result: MutableMap<String, File>) {
        val pending = ArrayDeque<File>()
        val visitedDirectories = HashSet<String>()
        pending.add(dir)

        while (pending.isNotEmpty()) {
            val currentDir = pending.removeFirst()
            val currentKey = runCatching { currentDir.canonicalPath }
                .getOrDefault(currentDir.absolutePath)
            if (!visitedDirectories.add(currentKey)) continue

            val files = try {
                currentDir.listFiles()
            } catch (_: SecurityException) {
                null
            } ?: continue

            for (file in files) {
                val name = file.name
                // Skip hidden folders and Android system data directories.
                if (name.startsWith(".") || name.equals("Android", ignoreCase = true)) {
                    continue
                }

                if (file.isDirectory) {
                    pending.addLast(file)
                } else if (file.isFile) {
                    val ext = file.extension.lowercase(Locale.ROOT)
                    if (supportedExtensions.contains(ext) && file.length() > 1024) {
                        val path = runCatching { file.canonicalPath }
                            .getOrDefault(file.absolutePath)
                        result.putIfAbsent(path, file)
                    }
                }
            }
        }
    }

    private fun isInsideAnySelectedFolder(path: String, selectedFolders: List<String>): Boolean {
        return selectedFolders.any { folderPath -> isInsideFolder(path, folderPath) }
    }

    private fun isInsideFolder(path: String, folderPath: String): Boolean {
        val normalizedPath = runCatching { File(path).canonicalPath }.getOrDefault(path)
        val normalizedFolder = runCatching { File(folderPath).canonicalPath }
            .getOrDefault(folderPath)
        return normalizedPath == normalizedFolder ||
                normalizedPath.startsWith(normalizedFolder + File.separator)
    }
}
