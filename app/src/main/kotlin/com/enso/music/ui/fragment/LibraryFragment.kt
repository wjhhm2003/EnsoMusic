package com.enso.music.ui.fragment

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.enso.music.R
import com.enso.music.data.db.AppDatabase
import com.enso.music.data.entity.AlbumItem
import com.enso.music.data.entity.ArtistItem
import com.enso.music.data.entity.SongEntity
import com.enso.music.data.util.PinyinInitialHelper
import com.enso.music.databinding.FragmentLibraryBinding
import com.enso.music.ui.MainActivity
import com.enso.music.ui.adapter.AlbumAdapter
import com.enso.music.ui.adapter.ArtistAdapter
import com.enso.music.ui.adapter.FolderBrowserAdapter
import com.enso.music.ui.adapter.FolderBrowserEntry
import com.enso.music.ui.adapter.SongAdapter
import android.widget.Toast
import com.enso.music.data.scanner.MetadataHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private enum class Tab { SONGS, ALBUMS, ARTISTS }
    private var currentTab = Tab.SONGS

    private lateinit var songAdapter: SongAdapter
    private lateinit var albumAdapter: AlbumAdapter
    private lateinit var artistAdapter: ArtistAdapter
    private lateinit var detailSongAdapter: SongAdapter
    private lateinit var folderBrowserAdapter: FolderBrowserAdapter
    private var folderPath: String? = null
    private var folderRoot: String? = null
    private var folderLoadToken = 0

    private var detailToken = 0
    private var currentDetailIsAlbum = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initAdapters()
        setupViews()
        setupAlphabetIndex()
        setupAlphabetGrid()
        observeData()
    }

    private fun initAdapters() {
        val scope = viewLifecycleOwner.lifecycleScope

        // All Songs
        songAdapter = SongAdapter(scope, onItemLongClick = ::playNext) { index, song ->
            playFromList(songAdapter.currentList, index, song, seamless = false)
        }

        // Albums
        albumAdapter = AlbumAdapter(scope) { album ->
            openAlbumDetail(album)
        }

        // Artists
        artistAdapter = ArtistAdapter { artist ->
            openArtistDetail(artist)
        }

        // Drilldown Songs
        folderBrowserAdapter = FolderBrowserAdapter(scope, ::openFolder, ::playFolder, ::playFolderSongs, ::playNext)

        detailSongAdapter = SongAdapter(scope, onItemLongClick = ::playNext, showTrackNumber = true) { index, song ->
            // Album tracks started from the album page play gaplessly.
            playFromList(detailSongAdapter.currentList, index, song, seamless = currentDetailIsAlbum)
        }
    }

    private fun playFromList(list: List<SongEntity>, clickedIndex: Int, song: SongEntity, seamless: Boolean) {
        val main = activity as? MainActivity ?: return
        val service = main.playbackService ?: return
        val index = if (list.getOrNull(clickedIndex)?.path == song.path) clickedIndex
        else list.indexOfFirst { it.path == song.path }
        if (index < 0) return
        val current = service.playbackInfo.value
        if (current.currentSong?.path == song.path && service.currentQueue.value == list) {
            // Tapping the song that is already loaded from this same list just
            // opens the player instead of restarting it.
            if (!current.isPlaying) service.resume()
        } else {
            service.setQueue(list, index, startPlay = true, enableSeamlessPlayback = seamless)
        }
        main.navigateToPage(1)
    }

    private fun playNext(song: SongEntity) {
        val service = (activity as? MainActivity)?.playbackService ?: return
        service.playNext(song)
        Toast.makeText(requireContext(), getString(R.string.queue_added_next, song.title), Toast.LENGTH_SHORT).show()
    }

    private fun setupViews() {
        binding.rvLibrary.layoutManager = LinearLayoutManager(requireContext())
        binding.rvLibrary.adapter = songAdapter

        binding.rvDetailSongs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDetailSongs.adapter = detailSongAdapter
        binding.rvFoldersBrowser.layoutManager = LinearLayoutManager(requireContext())
        binding.rvFoldersBrowser.adapter = folderBrowserAdapter

        val selectSongs = { switchTab(Tab.SONGS) }
        val selectAlbums = { switchTab(Tab.ALBUMS) }
        val selectArtists = { switchTab(Tab.ARTISTS) }

        binding.tabSongs.setOnClickListener { selectSongs() }
        binding.layoutTabSongs.setOnClickListener { selectSongs() }

        binding.tabAlbums.setOnClickListener { selectAlbums() }
        binding.layoutTabAlbums.setOnClickListener { selectAlbums() }

        binding.tabArtists.setOnClickListener { selectArtists() }
        binding.tabFolders.setOnClickListener { switchFolderTab() }
        binding.layoutTabFolders.setOnClickListener { switchFolderTab() }
        binding.layoutTabArtists.setOnClickListener { selectArtists() }

        binding.btnQuickScan.setOnClickListener {
            (activity as? MainActivity)?.navigateToPage(2)
        }

        binding.btnEmptyScan.setOnClickListener {
            (activity as? MainActivity)?.navigateToPage(2)
        }

        binding.btnDetailBack.setOnClickListener { hideDetail() }

        binding.btnDetailPlayAll.setOnClickListener {
            val list = detailSongAdapter.currentList
            if (list.isNotEmpty()) {
                // Album-level Play All opts into low-overhead gapless playback.
                (activity as? MainActivity)?.playbackService?.setQueue(
                    list,
                    startIndex = 0,
                    startPlay = true,
                    enableSeamlessPlayback = currentDetailIsAlbum
                )
                (activity as? MainActivity)?.navigateToPage(1)
            }
        }
    }

    private fun setupAlphabetIndex() {
        binding.alphabetIndexView.onLetterSelectedListener = { letter ->
            binding.tvIndexBubble.text = letter
            binding.tvIndexBubble.visibility = View.VISIBLE
            scrollToInitial(letter)
        }

        binding.alphabetIndexView.onTouchEndListener = {
            binding.tvIndexBubble.visibility = View.GONE
        }

        binding.alphabetIndexView.onGridTriggerListener = {
            binding.layoutAlphabetGrid.visibility = View.VISIBLE
        }

        binding.btnOpenAlphabetGrid.setOnClickListener {
            binding.layoutAlphabetGrid.visibility = View.VISIBLE
        }
    }

    private fun setupAlphabetGrid() {
        for (i in 0 until binding.gridAlphabet.childCount) {
            val child = binding.gridAlphabet.getChildAt(i)
            if (child is TextView) {
                val letter = child.text.toString()
                child.setOnClickListener {
                    scrollToInitial(letter)
                    binding.layoutAlphabetGrid.visibility = View.GONE
                }
            }
        }

        binding.btnAlphabetGridClose.setOnClickListener {
            binding.layoutAlphabetGrid.visibility = View.GONE
        }

        binding.layoutAlphabetGrid.setOnClickListener {
            binding.layoutAlphabetGrid.visibility = View.GONE
        }
    }

    private fun scrollToInitial(letter: String) {
        val targetIndex = when (currentTab) {
            Tab.SONGS -> songAdapter.currentList.indexOfFirst { it.sortInitial.equals(letter, ignoreCase = true) }
            Tab.ALBUMS -> albumAdapter.currentList.indexOfFirst { it.sortInitial.equals(letter, ignoreCase = true) }
            Tab.ARTISTS -> artistAdapter.currentList.indexOfFirst { it.sortInitial.equals(letter, ignoreCase = true) }
        }
        if (targetIndex != -1) {
            (binding.rvLibrary.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(targetIndex, 0)
        }
    }

    private fun switchTab(tab: Tab) {
        if (currentTab == tab && binding.rvFoldersBrowser.visibility != View.VISIBLE) return
        currentTab = tab

        val white = ContextCompat.getColor(requireContext(), R.color.white)
        val gray = ContextCompat.getColor(requireContext(), R.color.text_secondary)

        // Tab Songs
        binding.tabSongs.setTextColor(if (tab == Tab.SONGS) white else gray)
        binding.tabSongs.textSize = if (tab == Tab.SONGS) 15f else 14f
        binding.tabSongs.setTypeface(null, if (tab == Tab.SONGS) Typeface.BOLD else Typeface.NORMAL)
        binding.indicatorSongs.visibility = if (tab == Tab.SONGS) View.VISIBLE else View.INVISIBLE

        // Tab Albums
        binding.tabAlbums.setTextColor(if (tab == Tab.ALBUMS) white else gray)
        binding.tabAlbums.textSize = if (tab == Tab.ALBUMS) 15f else 14f
        binding.tabAlbums.setTypeface(null, if (tab == Tab.ALBUMS) Typeface.BOLD else Typeface.NORMAL)
        binding.indicatorAlbums.visibility = if (tab == Tab.ALBUMS) View.VISIBLE else View.INVISIBLE

        // Tab Artists
        binding.tabArtists.setTextColor(if (tab == Tab.ARTISTS) white else gray)
        binding.tabArtists.textSize = if (tab == Tab.ARTISTS) 15f else 14f
        binding.tabArtists.setTypeface(null, if (tab == Tab.ARTISTS) Typeface.BOLD else Typeface.NORMAL)
        binding.indicatorArtists.visibility = if (tab == Tab.ARTISTS) View.VISIBLE else View.INVISIBLE
        binding.tabFolders.setTextColor(gray)
        binding.tabFolders.setTypeface(null, Typeface.NORMAL)
        binding.indicatorFolders.visibility = View.INVISIBLE

        binding.rvFoldersBrowser.visibility = View.GONE
        binding.rvLibrary.visibility = View.VISIBLE
        when (tab) {
            Tab.SONGS -> binding.rvLibrary.adapter = songAdapter
            Tab.ALBUMS -> binding.rvLibrary.adapter = albumAdapter
            Tab.ARTISTS -> binding.rvLibrary.adapter = artistAdapter
        }

        updateListVisibility()
    }

    private fun switchFolderTab() {
        val white = ContextCompat.getColor(requireContext(), R.color.white)
        val gray = ContextCompat.getColor(requireContext(), R.color.text_secondary)
        binding.tabSongs.setTextColor(gray)
        binding.tabAlbums.setTextColor(gray)
        binding.tabArtists.setTextColor(gray)
        binding.tabFolders.setTextColor(white)
        binding.tabFolders.setTypeface(null, Typeface.BOLD)
        binding.indicatorSongs.visibility = View.INVISIBLE
        binding.indicatorAlbums.visibility = View.INVISIBLE
        binding.indicatorArtists.visibility = View.INVISIBLE
        binding.indicatorFolders.visibility = View.VISIBLE
        binding.rvLibrary.visibility = View.GONE
        binding.alphabetIndexView.visibility = View.GONE
        binding.btnOpenAlphabetGrid.visibility = View.GONE
        binding.layoutEmpty.visibility = View.GONE
        binding.rvFoldersBrowser.visibility = View.VISIBLE
        folderPath = null
        folderRoot = null
        currentTab = Tab.SONGS
        loadFolderBrowser()
    }

    private fun loadFolderBrowser() {
        val db = AppDatabase.getInstance(requireContext())
        val token = ++folderLoadToken
        val requestedPath = folderPath
        viewLifecycleOwner.lifecycleScope.launch {
            val browser = withContext(Dispatchers.IO) {
            val songs = db.songDao().getAllSongsList()
            val roots = db.scanFolderDao().getSelectedFolders().mapNotNull { runCatching { File(it.path).canonicalPath }.getOrNull() }
            val path = requestedPath
            if (path == null) {
                roots.distinctBy { it.lowercase() }
                    .filter { root -> roots.none { other -> other != root && isUnderPath(root, other) } }
                    .mapNotNull { root ->
                        val rootSongs = songs.filter { isUnderPath(it.path, root) }
                        if (rootSongs.isEmpty()) null
                        else FolderBrowserEntry.Folder(root, File(root).name.ifBlank { root }, rootSongs.size)
                    }
                    .sortedBy { it.name.lowercase() }
            } else {
                val prefix = path + File.separator
                val directSongs = songs.filter { song ->
                    File(song.path).parentFile?.let { runCatching { it.canonicalPath }.getOrNull() } == path
                }.sortedWith(compareBy<SongEntity> { it.trackNumber.takeIf { n -> n > 0 } ?: Int.MAX_VALUE }.thenBy { it.title.lowercase() })
                val childFolders = songs.mapNotNull { song ->
                    val songPath = runCatching { File(song.path).canonicalPath }.getOrNull() ?: return@mapNotNull null
                    if (!songPath.startsWith(prefix, ignoreCase = true)) return@mapNotNull null
                    val relative = songPath.removePrefix(prefix)
                    val childName = relative.substringBefore(File.separatorChar)
                    if (childName.isBlank() || !relative.contains(File.separatorChar)) return@mapNotNull null
                    File(path, childName).canonicalPath
                }.distinctBy { it.lowercase() }.map { child ->
                    FolderBrowserEntry.Folder(child, File(child).name, songs.count { isUnderPath(it.path, child) })
                }.sortedBy { it.name.lowercase() }
                childFolders + directSongs.map { FolderBrowserEntry.Song(it) }
            }
            }
            if (_binding == null || token != folderLoadToken) return@launch
            folderBrowserAdapter.submitList(browser)
        }
    }

    private fun isUnderPath(songPath: String, root: String): Boolean {
        val song = runCatching { File(songPath).canonicalPath }.getOrNull() ?: return false
        return song == root || song.startsWith(root + File.separator, ignoreCase = true)
    }

    private fun openFolder(folder: FolderBrowserEntry.Folder) { if (folderPath == null) folderRoot = folder.path; folderPath = folder.path; loadFolderBrowser() }
    private fun playFolder(folder: FolderBrowserEntry.Folder) { playFolderSongsForPath(folder.path) }
    private fun playFolderSongsForPath(path: String) {
        viewLifecycleOwner.lifecycleScope.launch { val songs = withContext(Dispatchers.IO) { val root = File(path).canonicalPath; val prefix = if (root.endsWith(File.separator)) root else "$root${File.separator}"; AppDatabase.getInstance(requireContext()).songDao().getAllSongsList().filter { val songPath = File(it.path).canonicalPath; songPath == root || songPath.startsWith(prefix) } }; if (songs.isNotEmpty()) { (activity as? MainActivity)?.playbackService?.setQueue(songs, 0, true); (activity as? MainActivity)?.navigateToPage(1) } }
    }
    private fun playFolderSongs(song: SongEntity) {
        val path = File(song.path).parent ?: return
        val db = AppDatabase.getInstance(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            val songs = withContext(Dispatchers.IO) {
                db.songDao().getAllSongsList().filter { isUnderPath(it.path, path) }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.path })
            }
            val index = songs.indexOfFirst { it.path == song.path }
            if (index >= 0) playFromList(songs, index, song, seamless = true)
        }
    }
    private fun updateListVisibility() {
        if (binding.rvFoldersBrowser.visibility == View.VISIBLE) return
        val (isEmpty, hasItems) = when (currentTab) {
            Tab.SONGS -> Pair(songAdapter.currentList.isEmpty(), songAdapter.currentList.isNotEmpty())
            Tab.ALBUMS -> Pair(albumAdapter.currentList.isEmpty(), albumAdapter.currentList.isNotEmpty())
            Tab.ARTISTS -> Pair(artistAdapter.currentList.isEmpty(), artistAdapter.currentList.isNotEmpty())
        }
        binding.layoutEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.alphabetIndexView.visibility = if (hasItems) View.VISIBLE else View.GONE
        binding.btnOpenAlphabetGrid.visibility = if (hasItems) View.VISIBLE else View.GONE
    }

    private fun observeData() {
        val db = AppDatabase.getInstance(requireContext())
        val songDao = db.songDao()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    songDao.getAllSongs().collect { songs ->
                        songAdapter.submitList(songs) { updateListVisibility() }
                        if (binding.rvFoldersBrowser.visibility == View.VISIBLE) loadFolderBrowser()
                    }
                }
                launch {
                    songDao.getAllAlbums()
                        .map { albums ->
                            albums.map { item ->
                                item.withSort(
                                    PinyinInitialHelper.getSortKey(item.album),
                                    PinyinInitialHelper.getInitial(item.album)
                                )
                            }.sortedWith(compareBy({ it.sortInitial == "#" }, { it.sortKey }))
                        }
                        .flowOn(Dispatchers.Default)
                        .collect { sortedAlbums ->
                            albumAdapter.submitList(sortedAlbums)
                            updateListVisibility()
                        }
                }
                launch {
                    songDao.getAllArtists()
                        .map { artists ->
                            artists.map { item ->
                                item.withSort(
                                    PinyinInitialHelper.getSortKey(item.artist),
                                    PinyinInitialHelper.getInitial(item.artist)
                                )
                            }.sortedWith(compareBy({ it.sortInitial == "#" }, { it.sortKey }))
                        }
                        .flowOn(Dispatchers.Default)
                        .collect { sortedArtists ->
                            artistAdapter.submitList(sortedArtists)
                            updateListVisibility()
                        }
                }
                launch {
                    (activity as? MainActivity)?.serviceConnectedFlow?.collectLatest { connected ->
                        val service = (activity as? MainActivity)?.playbackService
                        if (!connected || service == null) return@collectLatest
                        service.playbackInfo
                            .map { it.currentSong?.path }
                            .distinctUntilChanged()
                            .collect { path ->
                                songAdapter.setPlayingPath(path)
                                detailSongAdapter.setPlayingPath(path)
                                folderBrowserAdapter.setPlayingPath(path)
                            }
                    }
                }
            }
        }
    }

    private fun openAlbumDetail(album: AlbumItem) {
        currentDetailIsAlbum = true
        showDetail(album.album)
        val appContext = requireContext().applicationContext
        val token = ++detailToken

        viewLifecycleOwner.lifecycleScope.launch {
            val songDao = AppDatabase.getInstance(appContext).songDao()
            val songs = withContext(Dispatchers.IO) { songDao.getSongsByAlbumList(album.album) }
            if (token != detailToken || _binding == null) return@launch
            detailSongAdapter.submitList(songs)

            // Self-heal missing track numbers from tags / file names, off the
            // UI path, and only re-submit when something actually changed.
            if (songs.none { it.trackNumber <= 0 }) return@launch
            val healed = withContext(Dispatchers.IO) {
                val metaHelper = MetadataHelper(appContext)
                var changed = false
                val resolved = songs.map { song ->
                    if (song.trackNumber > 0) return@map song
                    val file = File(song.path)
                    val track = if (file.exists()) metaHelper.parseTrackNumberOnly(file) else 0
                    if (track > 0) {
                        changed = true
                        song.copy(trackNumber = track)
                    } else song
                }
                if (changed) {
                    songDao.insertSongs(resolved.filter { r -> songs.any { it.path == r.path && it.trackNumber != r.trackNumber } })
                    resolved.sortedWith(compareBy({ if (it.trackNumber <= 0) Int.MAX_VALUE else it.trackNumber }, { it.path }))
                } else null
            }
            if (healed != null && token == detailToken && _binding != null) {
                detailSongAdapter.submitList(healed)
            }
        }
    }

    private fun openArtistDetail(artist: ArtistItem) {
        currentDetailIsAlbum = false
        showDetail(artist.artist)
        val appContext = requireContext().applicationContext
        val token = ++detailToken
        viewLifecycleOwner.lifecycleScope.launch {
            val songs = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(appContext).songDao().getSongsByArtistList(artist.artist)
            }
            if (token == detailToken && _binding != null) detailSongAdapter.submitList(songs)
        }
    }

    private fun showDetail(title: String) {
        binding.tvDetailTitle.text = title
        detailSongAdapter.submitList(emptyList())
        val detail = binding.layoutDetail
        detail.animate().cancel()
        detail.alpha = 0f
        detail.translationX = detail.width * 0.15f
        detail.visibility = View.VISIBLE
        detail.animate().alpha(1f).translationX(0f).setDuration(180).start()
    }

    private fun hideDetail() {
        detailToken++
        binding.layoutDetail.animate().cancel()
        binding.layoutDetail.visibility = View.GONE
    }

    fun handleBackPressed(): Boolean {
        if (_binding == null) return false
        if (binding.layoutAlphabetGrid.visibility == View.VISIBLE) {
            binding.layoutAlphabetGrid.visibility = View.GONE
            return true
        }
        if (binding.rvFoldersBrowser.visibility == View.VISIBLE && folderPath != null) {
            val current = folderPath!!
            val parent = File(current).parentFile?.canonicalPath
            val isScanRoot = folderRoot == current
            if (parent == null || isScanRoot == true) {
                folderPath = null
                folderRoot = null
            } else folderPath = parent
            loadFolderBrowser()
            return true
        }
        if (binding.rvFoldersBrowser.visibility == View.VISIBLE) { switchTab(Tab.SONGS); return true }
        if (binding.layoutDetail.visibility == View.VISIBLE) {
            hideDetail()
            return true
        }
        return false
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
