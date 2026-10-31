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
import androidx.lifecycle.lifecycleScope
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

    private var nowPlayingFragment: NowPlayingFragment? = null

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
        val fragments = listOf(
            LibraryFragment(),
            NowPlayingFragment().also { nowPlayingFragment = it },
            QueueFolderFragment()
        )

        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = fragments.size
            override fun createFragment(position: Int): Fragment = fragments[position]
        }

        // Set default screen to Page 1: Now Playing (Center)
        binding.viewPager.setCurrentItem(1, false)

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

        binding.dot0.background = if (position == 0) selected else unselected
        binding.dot0.layoutParams.width = if (position == 0) dpToPx(14) else dpToPx(5)
        binding.dot0.requestLayout()

        binding.dot1.background = if (position == 1) selected else unselected
        binding.dot1.layoutParams.width = if (position == 1) dpToPx(14) else dpToPx(5)
        binding.dot1.requestLayout()

        binding.dot2.background = if (position == 2) selected else unselected
        binding.dot2.layoutParams.width = if (position == 2) dpToPx(14) else dpToPx(5)
        binding.dot2.requestLayout()
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
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
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
        lifecycleScope.launch {
            val scanner = FolderScanner(this@MainActivity)
            scanner.ensureDefaultFoldersConfigured()
            scanner.startScan(isFullRescan = false)
        }
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
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (nowPlayingFragment?.hideLyricView() == true) {
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (ignored: Exception) {
        }
        if (_serviceConnectedFlow.value) {
            unbindService(serviceConnection)
        }
        super.onDestroy()
    }
}
