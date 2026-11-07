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
        detailSongAdapter = SongAdapter(scope, onItemLongClick = ::playNext) { index, song ->
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

        val selectSongs = { switchTab(Tab.SONGS) }
        val selectAlbums = { switchTab(Tab.ALBUMS) }
        val selectArtists = { switchTab(Tab.ARTISTS) }

        binding.tabSongs.setOnClickListener { selectSongs() }
        binding.layoutTabSongs.setOnClickListener { selectSongs() }

        binding.tabAlbums.setOnClickListener { selectAlbums() }
        binding.layoutTabAlbums.setOnClickListener { selectAlbums() }

        binding.tabArtists.setOnClickListener { selectArtists() }
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
        if (currentTab == tab) return
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

        when (tab) {
            Tab.SONGS -> binding.rvLibrary.adapter = songAdapter
            Tab.ALBUMS -> binding.rvLibrary.adapter = albumAdapter
            Tab.ARTISTS -> binding.rvLibrary.adapter = artistAdapter
        }

        updateListVisibility()
    }

    private fun updateListVisibility() {
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
