package com.enso.music.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.core.content.ContextCompat
import com.enso.music.R
import com.enso.music.data.cover.CoverManager
import com.enso.music.data.entity.SongEntity
import com.enso.music.databinding.ItemFolderBrowserBinding
import com.enso.music.databinding.ItemSongBinding
import kotlinx.coroutines.CoroutineScope

sealed class FolderBrowserEntry {
    data class Folder(val path: String, val name: String, val songCount: Int) : FolderBrowserEntry()
    data class Song(val song: SongEntity) : FolderBrowserEntry()
}

/** Displays child directories followed by the songs directly inside the current directory. */
class FolderBrowserAdapter(
    private val scope: CoroutineScope,
    private val onFolderClick: (FolderBrowserEntry.Folder) -> Unit,
    private val onFolderPlay: (FolderBrowserEntry.Folder) -> Unit,
    private val onSongClick: (SongEntity) -> Unit,
    private val onSongLongClick: ((SongEntity) -> Unit)? = null
) : ListAdapter<FolderBrowserEntry, RecyclerView.ViewHolder>(Diff) {
    private var playingPath: String? = null

    fun setPlayingPath(path: String?) {
        if (playingPath == path) return
        val old = playingPath
        playingPath = path
        currentList.forEachIndexed { index, entry ->
            if (entry is FolderBrowserEntry.Song && (entry.song.path == old || entry.song.path == path)) {
                notifyItemChanged(index)
            }
        }
    }

    override fun getItemViewType(position: Int) = if (getItem(position) is FolderBrowserEntry.Folder) TYPE_FOLDER else TYPE_SONG

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_FOLDER) {
            FolderHolder(ItemFolderBrowserBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        } else {
            SongHolder(ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val entry = getItem(position)) {
            is FolderBrowserEntry.Folder -> (holder as FolderHolder).bind(entry)
            is FolderBrowserEntry.Song -> (holder as SongHolder).bind(entry.song)
        }
    }

    inner class FolderHolder(private val binding: ItemFolderBrowserBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(folder: FolderBrowserEntry.Folder) {
            binding.tvFolderBrowserName.text = folder.name
            binding.tvFolderBrowserInfo.text = "${folder.songCount} 首"
            binding.root.setOnClickListener { onFolderClick(folder) }
            binding.btnFolderBrowserPlay.setOnClickListener { onFolderPlay(folder) }
        }
    }

    inner class SongHolder(private val binding: ItemSongBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(song: SongEntity) {
            binding.tvTitle.text = song.title
            binding.tvSubtitle.text = if (song.album.isBlank()) song.artist else "${song.artist} · ${song.album}"
            binding.tvDuration.text = FormatUtils.formatDuration(song.duration)
            val isPlaying = song.path == playingPath
            binding.tvTitle.setTextColor(ContextCompat.getColor(itemView.context, if (isPlaying) R.color.accent_green else R.color.white))
            binding.ivNowPlayingIndicator.visibility = if (isPlaying) View.VISIBLE else View.GONE
            CoverManager.getInstance(itemView.context).loadInto(binding.ivCover, song.coverPath, R.drawable.ic_music_note_24, scope)
            binding.root.setOnClickListener { onSongClick(song) }
            binding.root.setOnLongClickListener { onSongLongClick?.let { it(song); true } ?: false }
        }
    }

    companion object {
        private const val TYPE_FOLDER = 0
        private const val TYPE_SONG = 1
        private val Diff = object : DiffUtil.ItemCallback<FolderBrowserEntry>() {
            override fun areItemsTheSame(a: FolderBrowserEntry, b: FolderBrowserEntry): Boolean = a == b
            override fun areContentsTheSame(a: FolderBrowserEntry, b: FolderBrowserEntry): Boolean = a == b
        }
    }
}
