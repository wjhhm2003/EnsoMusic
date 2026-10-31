package com.enso.music.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.enso.music.R
import com.enso.music.data.cover.CoverManager
import com.enso.music.data.entity.AlbumItem
import com.enso.music.data.entity.ArtistItem
import com.enso.music.data.entity.ScanFolderEntity
import com.enso.music.data.entity.SongEntity
import com.enso.music.databinding.ItemAlbumBinding
import com.enso.music.databinding.ItemArtistBinding
import com.enso.music.databinding.ItemFolderBinding
import com.enso.music.databinding.ItemFolderPickBinding
import com.enso.music.databinding.ItemQueueBinding
import com.enso.music.databinding.ItemSongBinding
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.util.Locale

object FormatUtils {
    fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.getDefault(), "%02d:%02d", min, sec)
    }
}

// 1. Song Adapter
class SongAdapter(
    private val scope: CoroutineScope,
    private val onItemClick: (index: Int, song: SongEntity) -> Unit
) : ListAdapter<SongEntity, SongAdapter.SongViewHolder>(DiffCallback) {

    private var playingPath: String? = null

    fun setPlayingPath(path: String?) {
        if (playingPath != path) {
            val oldPath = playingPath
            playingPath = path
            val oldIndex = currentList.indexOfFirst { it.path == oldPath }
            val newIndex = currentList.indexOfFirst { it.path == path }
            if (oldIndex != -1) notifyItemChanged(oldIndex)
            if (newIndex != -1) notifyItemChanged(newIndex)
        }
    }

    fun setPlayingIndex(index: Int) {
        val path = if (index in currentList.indices) currentList[index].path else null
        setPlayingPath(path)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val binding = ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SongViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, position)
    }

    inner class SongViewHolder(private val binding: ItemSongBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(song: SongEntity, position: Int) {
            val isNowPlaying = (playingPath != null && song.path == playingPath)
            val accentGreen = ContextCompat.getColor(itemView.context, R.color.accent_green)
            val white = ContextCompat.getColor(itemView.context, R.color.white)

            binding.tvTitle.text = song.title
            binding.tvTitle.setTextColor(if (isNowPlaying) accentGreen else white)

            val subtitle = if (song.album.isBlank()) {
                song.artist
            } else {
                "${song.artist} · ${song.album}"
            }
            binding.tvSubtitle.text = subtitle
            binding.tvDuration.text = FormatUtils.formatDuration(song.duration)
            binding.ivNowPlayingIndicator.visibility = if (isNowPlaying) View.VISIBLE else View.GONE

            CoverManager.getInstance(itemView.context).loadInto(
                binding.ivCover,
                song.coverPath,
                R.drawable.ic_music_note_24,
                scope
            )

            itemView.setOnClickListener {
                val adapterPosition = bindingAdapterPosition
                if (adapterPosition != RecyclerView.NO_POSITION && adapterPosition < currentList.size) {
                    onItemClick(adapterPosition, currentList[adapterPosition])
                }
            }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<SongEntity>() {
        override fun areItemsTheSame(oldItem: SongEntity, newItem: SongEntity): Boolean =
            oldItem.path == newItem.path

        override fun areContentsTheSame(oldItem: SongEntity, newItem: SongEntity): Boolean =
            oldItem == newItem
    }
}

// 2. Album Adapter
class AlbumAdapter(
    private val scope: CoroutineScope,
    private val onAlbumClick: (album: AlbumItem) -> Unit
) : ListAdapter<AlbumItem, AlbumAdapter.AlbumViewHolder>(AlbumDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumViewHolder {
        val binding = ItemAlbumBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AlbumViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AlbumViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class AlbumViewHolder(private val binding: ItemAlbumBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(album: AlbumItem) {
            binding.tvAlbumName.text = album.album
            binding.tvAlbumDesc.text = album.artist
            binding.tvSongCount.text = "${album.songCount} 首"

            CoverManager.getInstance(itemView.context).loadInto(
                binding.ivAlbumCover,
                album.coverPath,
                R.drawable.ic_album_24,
                scope
            )

            itemView.setOnClickListener {
                onAlbumClick(album)
            }
        }
    }

    object AlbumDiffCallback : DiffUtil.ItemCallback<AlbumItem>() {
        override fun areItemsTheSame(oldItem: AlbumItem, newItem: AlbumItem): Boolean =
            oldItem.album == newItem.album

        override fun areContentsTheSame(oldItem: AlbumItem, newItem: AlbumItem): Boolean =
            oldItem == newItem
    }
}

// 3. Artist Adapter
class ArtistAdapter(
    private val onArtistClick: (artist: ArtistItem) -> Unit
) : ListAdapter<ArtistItem, ArtistAdapter.ArtistViewHolder>(ArtistDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArtistViewHolder {
        val binding = ItemArtistBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ArtistViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ArtistViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ArtistViewHolder(private val binding: ItemArtistBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(artist: ArtistItem) {
            binding.tvArtistName.text = artist.artist
            binding.tvArtistStats.text = "${artist.songCount} 首单曲 · ${artist.albumCount} 张专辑"

            itemView.setOnClickListener {
                onArtistClick(artist)
            }
        }
    }

    object ArtistDiffCallback : DiffUtil.ItemCallback<ArtistItem>() {
        override fun areItemsTheSame(oldItem: ArtistItem, newItem: ArtistItem): Boolean =
            oldItem.artist == newItem.artist

        override fun areContentsTheSame(oldItem: ArtistItem, newItem: ArtistItem): Boolean =
            oldItem == newItem
    }
}

// 4. Queue Adapter
class QueueAdapter(
    private val onItemClick: (index: Int) -> Unit
) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

    private val items = ArrayList<SongEntity>()
    private var playingIndex = -1

    fun updateList(newItems: List<SongEntity>, currentPlayingIndex: Int) {
        items.clear()
        items.addAll(newItems)
        playingIndex = currentPlayingIndex
        notifyDataSetChanged()
    }

    fun setPlayingIndex(index: Int) {
        if (playingIndex != index) {
            val old = playingIndex
            playingIndex = index
            if (old in items.indices) notifyItemChanged(old)
            if (playingIndex in items.indices) notifyItemChanged(playingIndex)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val binding = ItemQueueBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return QueueViewHolder(binding)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    inner class QueueViewHolder(private val binding: ItemQueueBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(song: SongEntity, position: Int) {
            val isNowPlaying = (position == playingIndex)

            binding.tvQueueTitle.text = song.title
            val subtitle = if (song.album.isBlank()) {
                song.artist
            } else {
                "${song.artist} · ${song.album}"
            }
            binding.tvQueueArtist.text = subtitle

            val accentGreen = ContextCompat.getColor(itemView.context, R.color.accent_green)
            val white = ContextCompat.getColor(itemView.context, R.color.white)

            if (isNowPlaying) {
                binding.tvQueueIndex.visibility = View.GONE
                binding.ivNowPlayingIndicator.visibility = View.VISIBLE
                binding.tvQueueTitle.setTextColor(accentGreen)
            } else {
                binding.tvQueueIndex.visibility = View.VISIBLE
                binding.ivNowPlayingIndicator.visibility = View.GONE
                binding.tvQueueIndex.text = (position + 1).toString()
                binding.tvQueueTitle.setTextColor(white)
            }

            itemView.setOnClickListener {
                val adapterPosition = bindingAdapterPosition
                if (adapterPosition != RecyclerView.NO_POSITION && adapterPosition < items.size) {
                    onItemClick(adapterPosition)
                }
            }
        }
    }
}

// 5. Folder Adapter
class FolderAdapter(
    private val onToggle: (path: String, isSelected: Boolean) -> Unit,
    private val onDelete: (path: String) -> Unit
) : ListAdapter<ScanFolderEntity, FolderAdapter.FolderViewHolder>(FolderDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val binding = ItemFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FolderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FolderViewHolder(private val binding: ItemFolderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(folder: ScanFolderEntity) {
            binding.tvFolderPath.text = folder.path
            binding.tvFolderInfo.text = "已收录 ${folder.songCount} 首"

            binding.cbFolderEnabled.setOnCheckedChangeListener(null)
            binding.cbFolderEnabled.isChecked = folder.isSelected
            binding.cbFolderEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggle(folder.path, isChecked)
            }

            binding.btnRemoveFolder.setOnClickListener {
                onDelete(folder.path)
            }
        }
    }

    object FolderDiffCallback : DiffUtil.ItemCallback<ScanFolderEntity>() {
        override fun areItemsTheSame(oldItem: ScanFolderEntity, newItem: ScanFolderEntity): Boolean =
            oldItem.path == newItem.path

        override fun areContentsTheSame(oldItem: ScanFolderEntity, newItem: ScanFolderEntity): Boolean =
            oldItem == newItem
    }
}

// 6. Folder Picker Adapter
class FolderPickerAdapter(
    private val onFolderClick: (folder: File) -> Unit
) : RecyclerView.Adapter<FolderPickerAdapter.ViewHolder>() {

    private val subDirs = ArrayList<File>()

    fun setFolders(folders: List<File>) {
        subDirs.clear()
        subDirs.addAll(folders)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFolderPickBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val dir = subDirs[position]
        holder.binding.tvFolderName.text = dir.name
        holder.itemView.setOnClickListener {
            onFolderClick(dir)
        }
    }

    override fun getItemCount(): Int = subDirs.size

    class ViewHolder(val binding: ItemFolderPickBinding) : RecyclerView.ViewHolder(binding.root)
}
