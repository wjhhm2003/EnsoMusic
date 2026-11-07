package com.enso.music.data.scanner

import android.content.Context
import android.os.Environment
import com.enso.music.data.db.AppDatabase
import com.enso.music.data.entity.ScanFolderEntity
import com.enso.music.data.entity.SongArtistCrossRef
import com.enso.music.data.entity.SongEntity
import com.enso.music.data.entity.SongSnapshot
import com.enso.music.data.util.ArtistParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

/**
 * App-wide singleton scanner. All callers share one progress flow and one
 * "scan running" guard. Scans requested through [requestScan] run on an
 * internal scope, so destroying a fragment view does not cancel them.
 */
class FolderScanner private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val db = AppDatabase.getInstance(appContext)
    private val songDao = db.songDao()
    private val scanFolderDao = db.scanFolderDao()
    private val metadataHelper = MetadataHelper(appContext)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = ScanRequestGate()

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
     * Fire-and-forget scan on the scanner's own scope. If a scan is already
     * running, the request is remembered and exactly one more scan runs after
     * the current one finishes (a pending full rescan wins over incremental).
     */
    fun requestScan(isFullRescan: Boolean = false) {
        if (!gate.tryStart(isFullRescan)) return
        scope.launch { runScanLoop(isFullRescan) }
    }

    /**
     * Runs a scan in the caller's coroutine and suspends until it (and any
     * scans queued meanwhile) completes. If a scan is already running, this
     * request is queued exactly like [requestScan] and the call returns at once.
     *
     * If isFullRescan is true, every found file will have its metadata re-extracted.
     * Otherwise, incremental comparison (path, lastModified, size) avoids redundant IO.
     */
    suspend fun startScan(isFullRescan: Boolean = false) = withContext(Dispatchers.IO) {
        if (!gate.tryStart(isFullRescan)) return@withContext
        runScanLoop(isFullRescan)
    }

    /** Must only be called by the coroutine that won [ScanRequestGate.tryStart]. */
    private suspend fun runScanLoop(firstIsFull: Boolean) {
        var next: Boolean? = firstIsFull
        try {
            while (next != null) {
                performScan(next)
                next = gate.finishOrTakePending()
            }
        } catch (e: Throwable) {
            // Release the guard. Hand any queued request to the internal scope
            // so a cancelled caller scope does not swallow it.
            val pending = gate.abandon()
            if (pending != null) requestScan(pending)
            throw e
        }
    }

    private suspend fun performScan(isFullRescan: Boolean) {
        ensureDefaultFoldersConfigured()
        val selectedFolders = scanFolderDao.getSelectedFolders().map { it.path }
        if (selectedFolders.isEmpty()) {
            _scanProgress.value = ScanProgress(isScanning = false, error = "没有勾选任何扫描目录")
            return
        }

        _scanProgress.value = ScanProgress(isScanning = true)

        try {
            // Canonicalize each selected folder once per scan; every membership
            // check afterwards is a pure string prefix comparison.
            val folderMatchers = selectedFolders.map { folderPath ->
                val canonical = runCatching { File(folderPath).canonicalPath }.getOrNull()
                FolderPathMatcher.compile(folderPath, canonical)
            }

            val existingSnapshots: Map<String, SongSnapshot> = if (isFullRescan) {
                emptyMap()
            } else {
                songDao.getAllSongSnapshots().associateBy { it.path }
            }
            val allKnownPaths: Collection<String> = if (isFullRescan) {
                songDao.getAllSongSnapshots().map { it.path }
            } else {
                existingSnapshots.keys
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

            val foundPathSet = HashSet<String>(foundFiles.size * 2)
            val batchToInsert = ArrayList<SongEntity>(50)
            val batchCrossRefs = ArrayList<SongArtistCrossRef>(100)
            var addedOrUpdated = 0
            var processed = 0
            var lastEmitNanos = 0L

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

                // Time-based throttle: StateFlow updates wake every collector,
                // so emit at most ~4 times per second (plus first and last).
                val nowNanos = System.nanoTime()
                if (processed == 1 || processed == foundFiles.size ||
                    nowNanos - lastEmitNanos >= PROGRESS_INTERVAL_NANOS
                ) {
                    lastEmitNanos = nowNanos
                    _scanProgress.value = ScanProgress(
                        isScanning = true,
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

            // Remove deleted files that belonged to scanned folders. A full
            // rescan still only owns the selected folders: never delete songs
            // belonging to a folder the user left unselected.
            val toDelete = allKnownPaths.filter { oldPath ->
                !foundPathSet.contains(oldPath) && folderMatchers.any { it.contains(oldPath) }
            }
            var removedCount = 0
            if (toDelete.isNotEmpty()) {
                songDao.deleteSongsWithArtists(toDelete)
                removedCount = toDelete.size
            }

            // Update folder scan times and counts
            val now = System.currentTimeMillis()
            for ((index, folderPath) in selectedFolders.withIndex()) {
                val matcher = folderMatchers[index]
                val countInFolder = foundFiles.count { matcher.contains(it.absolutePath) }
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

    companion object {
        private const val PROGRESS_INTERVAL_NANOS = 250_000_000L

        @Volatile
        private var INSTANCE: FolderScanner? = null

        fun getInstance(context: Context): FolderScanner {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FolderScanner(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}

/**
 * Pure string "is this path inside that folder" matcher (no filesystem access).
 * A folder is normalized once into its absolute and canonical forms; checks
 * require a separator boundary, so "/Music2/a.mp3" is not inside "/Music".
 * Both '/' and '\\' count as separators.
 */
class FolderPathMatcher private constructor(private val prefixes: List<String>) {

    fun contains(path: String): Boolean {
        for (prefix in prefixes) {
            if (isInside(path, prefix)) return true
        }
        return false
    }

    companion object {
        /** Builds a matcher from a folder's absolute form and (optional) canonical form. */
        fun compile(absoluteFolder: String, canonicalFolder: String?): FolderPathMatcher {
            val forms = ArrayList<String>(2)
            normalizeFolder(absoluteFolder)?.let { forms.add(it) }
            canonicalFolder?.let { normalizeFolder(it) }?.let { if (it !in forms) forms.add(it) }
            return FolderPathMatcher(forms)
        }

        /** Strips trailing separators, keeping a bare root ("/") intact. Returns null for blank input. */
        fun normalizeFolder(folder: String): String? {
            if (folder.isBlank()) return null
            var end = folder.length
            while (end > 1 && isSeparator(folder[end - 1])) end--
            return folder.substring(0, end)
        }

        /** [folder] must already be normalized by [normalizeFolder]. */
        fun isInside(path: String, folder: String): Boolean {
            if (folder.isEmpty() || !path.startsWith(folder)) return false
            if (path.length == folder.length) return true
            // A root folder like "/" already ends with a separator.
            return isSeparator(folder[folder.length - 1]) || isSeparator(path[folder.length])
        }

        private fun isSeparator(c: Char): Boolean = c == '/' || c == '\\'
    }
}

/**
 * Thread-safe single-runner guard that coalesces requests. At most one scan
 * runs at a time. Requests made while it runs collapse into one pending
 * request, and a full rescan wins over incremental.
 */
class ScanRequestGate {
    private var running = false

    /** null = nothing pending; otherwise whether the pending request is a full rescan. */
    private var pendingFull: Boolean? = null

    val isRunning: Boolean
        @Synchronized get() = running

    /** Returns true if the caller now owns the run; otherwise records a pending request. */
    @Synchronized
    fun tryStart(isFullRescan: Boolean): Boolean {
        if (!running) {
            running = true
            return true
        }
        pendingFull = (pendingFull == true) || isFullRescan
        return false
    }

    /**
     * Called by the owner after a scan finishes. Returns the pending request
     * (the owner keeps the guard and must run it), or null after releasing the guard.
     */
    @Synchronized
    fun finishOrTakePending(): Boolean? {
        val next = pendingFull
        pendingFull = null
        if (next == null) running = false
        return next
    }

    /** Releases the guard unconditionally (e.g. on cancellation) and returns and clears any pending request. */
    @Synchronized
    fun abandon(): Boolean? {
        running = false
        val next = pendingFull
        pendingFull = null
        return next
    }
}
