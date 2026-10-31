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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private enum class Tab { SONGS, ALBUMS, ARTISTS }
    private var currentTab = Tab.SONGS

    private lateinit var songAdapter: SongAdapter
    private lateinit var albumAdapter: AlbumAdapter
    private lateinit var artistAdapter: ArtistAdapter
    private lateinit var detailSongAdapter: SongAdapter

    private var currentDetailSongs = ArrayList<SongEntity>()

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
        songAdapter = SongAdapter(scope) { index, _ ->
            val list = songAdapter.currentList
            (activity as? MainActivity)?.playbackService?.setQueue(list, index, true)
            (activity as? MainActivity)?.navigateToPage(1)
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
        detailSongAdapter = SongAdapter(scope) { index, _ ->
            (activity as? MainActivity)?.playbackService?.setQueue(currentDetailSongs, index, true)
            (activity as? MainActivity)?.navigateToPage(1)
        }
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

        binding.btnDetailBack.setOnClickListener {
            binding.layoutDetail.visibility = View.GONE
        }

        binding.btnDetailPlayAll.setOnClickListener {
            if (currentDetailSongs.isNotEmpty()) {
                (activity as? MainActivity)?.playbackService?.setQueue(currentDetailSongs, 0, true)
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
                        songAdapter.submitList(songs)
                        updateListVisibility()
                    }
                }
                launch {
                    songDao.getAllAlbums().collect { albums ->
                        val sortedAlbums = albums.map { item ->
                            val initial = PinyinInitialHelper.getInitial(item.album)
                            val key = PinyinInitialHelper.getSortKey(item.album)
                            item.withSort(key, initial)
                        }.sortedWith(compareBy({ it.sortInitial == "#" }, { it.sortKey }))
                        albumAdapter.submitList(sortedAlbums)
                        updateListVisibility()
                    }
                }
                launch {
                    songDao.getAllArtists().collect { artists ->
                        val sortedArtists = artists.map { item ->
                            val initial = PinyinInitialHelper.getInitial(item.artist)
                            val key = PinyinInitialHelper.getSortKey(item.artist)
                            item.withSort(key, initial)
                        }.sortedWith(compareBy({ it.sortInitial == "#" }, { it.sortKey }))
                        artistAdapter.submitList(sortedArtists)
                        updateListVisibility()
                    }
                }
            }
        }
    }

    private fun openAlbumDetail(album: AlbumItem) {
        binding.tvDetailTitle.text = album.album
        binding.layoutDetail.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val songDao = AppDatabase.getInstance(requireContext()).songDao()
            val songs = songDao.getSongsByAlbumList(album.album)

            // Auto-heal missing track numbers from metadata / filename if any
            val metaHelper = com.enso.music.data.scanner.MetadataHelper(requireContext())
            var hasUpdates = false
            val resolvedSongs = songs.map { song ->
                if (song.trackNumber <= 0) {
                    val file = java.io.File(song.path)
                    if (file.exists()) {
                        val track = metaHelper.parseTrackNumberOnly(file)
                        if (track > 0) {
                            hasUpdates = true
                            return@map song.copy(trackNumber = track)
                        }
                    }
                }
                song
            }

            if (hasUpdates) {
                songDao.insertSongs(resolvedSongs)
            }

            // Strictly sort by album trackNumber ascending (1, 2, 3...), and fallback to filename/path
            val sortedSongs = resolvedSongs.sortedWith(compareBy(
                { if (it.trackNumber <= 0) 999999 else it.trackNumber },
                { it.path }
            ))

            withContext(Dispatchers.Main) {
                currentDetailSongs.clear()
                currentDetailSongs.addAll(sortedSongs)
                detailSongAdapter.submitList(sortedSongs)
            }
        }
    }

    private fun openArtistDetail(artist: ArtistItem) {
        binding.tvDetailTitle.text = artist.artist
        binding.layoutDetail.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val songs = AppDatabase.getInstance(requireContext()).songDao().getSongsByArtistList(artist.artist)
            withContext(Dispatchers.Main) {
                currentDetailSongs.clear()
                currentDetailSongs.addAll(songs)
                detailSongAdapter.submitList(songs)
            }
        }
    }

    fun handleBackPressed(): Boolean {
        if (_binding == null) return false
        if (binding.layoutAlphabetGrid.visibility == View.VISIBLE) {
            binding.layoutAlphabetGrid.visibility = View.GONE
            return true
        }
        if (binding.layoutDetail.visibility == View.VISIBLE) {
            binding.layoutDetail.visibility = View.GONE
            return true
        }
        return false
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
