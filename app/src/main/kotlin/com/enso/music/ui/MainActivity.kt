package com.enso.music.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.enso.music.R
import com.enso.music.data.scanner.FolderScanner
import com.enso.music.databinding.ActivityMainBinding
import com.enso.music.service.MusicPlaybackService
import com.enso.music.ui.fragment.LibraryFragment
import com.enso.music.ui.fragment.NowPlayingFragment
import com.enso.music.ui.fragment.QueueFolderFragment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    var playbackService: MusicPlaybackService? = null
        private set

    private val _serviceConnectedFlow = MutableStateFlow(false)
    val serviceConnectedFlow: StateFlow<Boolean> = _serviceConnectedFlow.asStateFlow()

    // ViewPager2's FragmentStateAdapter tags pages "f<itemId>". Look them up
    // instead of caching references, which go stale after the system restores
    // fragments on process recreation.
    private val nowPlayingFragment: NowPlayingFragment?
        get() = supportFragmentManager.findFragmentByTag("f1") as? NowPlayingFragment
    private val libraryFragment: LibraryFragment?
        get() = supportFragmentManager.findFragmentByTag("f0") as? LibraryFragment

    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? MusicPlaybackService.MusicBinder
            playbackService = binder?.service
            _serviceConnectedFlow.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            _serviceConnectedFlow.value = false
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    nowPlayingFragment?.onScreenStateChanged(false)
                }
                Intent.ACTION_SCREEN_ON -> {
                    nowPlayingFragment?.onScreenStateChanged(true)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViewPager()
        registerScreenReceiver()
        startAndBindPlaybackService()
        checkAndRequestStoragePermission()
    }

    private fun setupViewPager() {
        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 3
            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> LibraryFragment()
                1 -> NowPlayingFragment()
                else -> QueueFolderFragment()
            }
        }
        // Keep all three pages alive: swiping never re-inflates a page.
        binding.viewPager.offscreenPageLimit = 2

        // Always land on the Now Playing page. A stale ViewPager2 saved state can
        // otherwise restore the library page and make the primary screen appear
        // to be missing after the app is relaunched by the watch system.
        binding.viewPager.setCurrentItem(1, false)
        binding.viewPager.post {
            if (!isFinishing && binding.viewPager.currentItem != 1) {
                binding.viewPager.setCurrentItem(1, false)
            }
        }

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateDotIndicator(position)
            }
        })
    }

    private fun updateDotIndicator(position: Int) {
        val selected = ContextCompat.getDrawable(this, R.drawable.bg_pill_selected)
        val unselected = ContextCompat.getDrawable(this, R.drawable.bg_circle_card)

        val dots = arrayOf(binding.dot0, binding.dot1, binding.dot2)
        for ((i, dot) in dots.withIndex()) {
            val active = i == position
            dot.background = if (active) selected else unselected
            val targetWidth = if (active) dpToPx(14) else dpToPx(5)
            if (dot.layoutParams.width != targetWidth) {
                dot.layoutParams.width = targetWidth
                dot.requestLayout()
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    fun navigateToPage(pageIndex: Int) {
        if (pageIndex in 0..2) {
            binding.viewPager.setCurrentItem(pageIndex, true)
        }
    }

    private fun startAndBindPlaybackService() {
        val intent = Intent(this, MusicPlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isServiceBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
    }

    private fun checkAndRequestStoragePermission() {
        val perm = Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(perm), 101)
        } else {
            triggerInitialScanIfEmpty()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            triggerInitialScanIfEmpty()
        }
    }

    private fun triggerInitialScanIfEmpty() {
        // Scans run on the scanner's own scope so they survive config changes
        // and the folder page sees the same progress.
        FolderScanner.getInstance(this).requestScan(isFullRescan = false)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                playbackService?.adjustFineVolume(4)
                nowPlayingFragment?.showVolumeOverlay()
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                playbackService?.adjustFineVolume(-4)
                nowPlayingFragment?.showVolumeOverlay()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                onBackPressed()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Priority 1: Handle overlays / secondary views in active fragments
        if (nowPlayingFragment?.handleBackPressed() == true) {
            return
        }
        if (libraryFragment?.handleBackPressed() == true) {
            return
        }

        // Priority 2: If currently on Page 0 (Library) or Page 2 (Queue/Folders), return to root page (Page 1: Now Playing)
        if (binding.viewPager.currentItem != 1) {
            binding.viewPager.setCurrentItem(1, true)
            return
        }

        // Priority 3: Only when already on the root interface (Page 1 with zero overlays open)
        // Return to watch launcher / home screen without terminating background music playback
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (ignored: Exception) {
        }
        if (isServiceBound) {
            runCatching { unbindService(serviceConnection) }
            isServiceBound = false
        }
        super.onDestroy()
    }
}
