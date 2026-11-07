package com.enso.music.ui.fragment

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.enso.music.R
import com.enso.music.data.db.AppDatabase
import com.enso.music.data.entity.ScanFolderEntity
import com.enso.music.data.scanner.FolderScanner
import com.enso.music.databinding.FragmentQueueFolderBinding
import com.enso.music.service.MusicPlaybackService
import com.enso.music.ui.MainActivity
import com.enso.music.ui.adapter.FolderAdapter
import com.enso.music.ui.adapter.QueueAdapter
import com.enso.music.ui.dialog.FolderPickerDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QueueFolderFragment : Fragment() {

    private var _binding: FragmentQueueFolderBinding? = null
    private val binding get() = _binding!!

    private enum class Tab { QUEUE, FOLDERS }
    private var currentTab = Tab.QUEUE

    private lateinit var queueAdapter: QueueAdapter
    private lateinit var folderAdapter: FolderAdapter
    private lateinit var folderScanner: FolderScanner

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQueueFolderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        folderScanner = FolderScanner.getInstance(requireContext())
        initAdapters()
        setupViews()
        observeData()
    }

    private fun getService(): MusicPlaybackService? {
        return (activity as? MainActivity)?.playbackService
    }

    private fun initAdapters() {
        queueAdapter = QueueAdapter(
            onItemLongClick = { index, song ->
                getService()?.removeFromQueue(index)
                Toast.makeText(requireContext(), getString(R.string.queue_removed, song.title), Toast.LENGTH_SHORT).show()
            }
        ) { index ->
            getService()?.playQueueItem(index)
            (activity as? MainActivity)?.navigateToPage(1)
        }

        folderAdapter = FolderAdapter(
            onToggle = { path, isSelected ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    AppDatabase.getInstance(requireContext())
                        .scanFolderDao()
                        .updateFolderSelection(path, isSelected)
                }
            },
            onDelete = { path ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    AppDatabase.getInstance(requireContext())
                        .scanFolderDao()
                        .deleteByPath(path)
                }
            }
        )
    }

    private fun setupViews() {
        binding.rvQueue.layoutManager = LinearLayoutManager(requireContext())
        binding.rvQueue.adapter = queueAdapter

        binding.rvFolders.layoutManager = LinearLayoutManager(requireContext())
        binding.rvFolders.adapter = folderAdapter

        binding.tabQueue.setOnClickListener { switchTab(Tab.QUEUE) }
        binding.tabFolders.setOnClickListener { switchTab(Tab.FOLDERS) }

        // Scans run on the scanner's own scope so leaving this page doesn't cancel them.
        binding.btnIncrementalScan.setOnClickListener { folderScanner.requestScan(isFullRescan = false) }
        binding.btnFullScan.setOnClickListener { folderScanner.requestScan(isFullRescan = true) }

        binding.btnAddFolder.setOnClickListener {
            FolderPickerDialog(requireContext()) { selectedPath ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    AppDatabase.getInstance(requireContext())
                        .scanFolderDao()
                        .insertFolder(ScanFolderEntity(path = selectedPath, isSelected = true))
                folderScanner.requestScan(isFullRescan = false)
                }
            }.show()
        }

        binding.btnBatteryWhitelist.setOnClickListener {
            requestBatteryOptimizationExemption()
        }
    }

    private fun switchTab(tab: Tab) {
        if (currentTab == tab) return
        currentTab = tab

        val selectedBg = ContextCompat.getDrawable(requireContext(), R.drawable.bg_pill_selected)
        val unselectedBg = ContextCompat.getDrawable(requireContext(), R.drawable.bg_pill_unselected)
        val white = ContextCompat.getColor(requireContext(), R.color.white)
        val gray = ContextCompat.getColor(requireContext(), R.color.text_secondary)

        binding.tabQueue.background = if (tab == Tab.QUEUE) selectedBg else unselectedBg
        binding.tabQueue.setTextColor(if (tab == Tab.QUEUE) white else gray)

        binding.tabFolders.background = if (tab == Tab.FOLDERS) selectedBg else unselectedBg
        binding.tabFolders.setTextColor(if (tab == Tab.FOLDERS) white else gray)

        if (tab == Tab.QUEUE) {
            binding.containerQueue.visibility = View.VISIBLE
            binding.containerFolders.visibility = View.GONE
        } else {
            binding.containerQueue.visibility = View.GONE
            binding.containerFolders.visibility = View.VISIBLE
        }
    }

    private fun observeData() {
        // Observe Queue from Service
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                (activity as? MainActivity)?.serviceConnectedFlow?.collectLatest { connected ->
                    if (connected) {
                        val service = getService() ?: return@collectLatest
                        coroutineScope {
                            launch {
                                service.currentQueue.collect { queue ->
                                    val curIndex = service.playbackInfo.value.currentIndex
                                    val firstFill = queueAdapter.itemCount == 0
                                    queueAdapter.updateList(queue, curIndex)
                                    binding.tvEmptyQueue.visibility = if (queue.isEmpty()) View.VISIBLE else View.GONE
                                    binding.tabQueue.text = if (queue.isEmpty()) getString(R.string.tab_queue)
                                    else getString(R.string.tab_queue) + " " + queue.size
                                    if (firstFill) scrollQueueToCurrent(curIndex)
                                }
                            }
                            launch {
                                service.playbackInfo
                                    .map { it.currentIndex }
                                    .distinctUntilChanged()
                                    .collect { index -> queueAdapter.setPlayingIndex(index) }
                            }
                        }
                    }
                }
            }
        }

        // Observe Folders from Database
        val scanFolderDao = AppDatabase.getInstance(requireContext()).scanFolderDao()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                scanFolderDao.getAllFolders().collect { folders ->
                    folderAdapter.submitList(folders)
                }
            }
        }

        // Observe Scan Progress
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                folderScanner.scanProgress.collect { progress ->
                    if (progress.isScanning) {
                        binding.pbScanning.visibility = View.VISIBLE
                        binding.tvScanStatus.visibility = View.VISIBLE
                        binding.tvScanStatus.text = getString(R.string.scan_progress, progress.currentPath, progress.totalFound)
                    } else {
                        binding.pbScanning.visibility = View.GONE
                        if (progress.totalFound > 0 || progress.addedOrUpdated > 0 || progress.removedCount > 0) {
                            binding.tvScanStatus.visibility = View.VISIBLE
                            binding.tvScanStatus.text = getString(
                                R.string.scan_done, progress.totalFound, progress.addedOrUpdated, progress.removedCount
                            )
                        } else if (progress.error != null) {
                            binding.tvScanStatus.visibility = View.VISIBLE
                            binding.tvScanStatus.text = getString(R.string.scan_error, progress.error)
                        }
                    }
                }
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val powerManager = requireContext().getSystemService(Context.POWER_SERVICE) as? PowerManager
        val packageName = requireContext().packageName

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (powerManager?.isIgnoringBatteryOptimizations(packageName) == true) {
                Toast.makeText(requireContext(), "已在电池优化白名单中", Toast.LENGTH_SHORT).show()
            } else {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (e2: Exception) {
                        Toast.makeText(requireContext(), "请在手表设置中将本应用加入电池白名单", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun scrollQueueToCurrent(index: Int) {
        if (index < 0) return
        (binding.rvQueue.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(index, dp(40))
    }

    override fun onResume() {
        super.onResume()
        // Bring the current song into view each time the page is shown.
        getService()?.playbackInfo?.value?.currentIndex?.let { scrollQueueToCurrent(it) }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
