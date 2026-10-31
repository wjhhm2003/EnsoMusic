package com.enso.music

import android.app.Application
import com.enso.music.data.cover.CoverManager
import com.enso.music.data.db.AppDatabase

class EnsoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppDatabase.getInstance(this)
        CoverManager.getInstance(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) {
            CoverManager.getInstance(this).clearMemory()
        }
    }
}
