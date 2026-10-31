package com.enso.music.data.cover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class CoverManager(private val context: Context) {

    private val coversDir: File = File(context.cacheDir, "covers").apply {
        if (!exists()) mkdirs()
    }

    // 4MB LruCache for decoded 240x240 RGB_565 Bitmaps
    private val memoryCache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }

    private val fallbackNames = arrayOf(
        "cover.jpg", "cover.png", "cover.webp",
        "folder.jpg", "folder.png",
        "Cover.jpg", "Folder.jpg",
        "albumart.jpg", "album.jpg"
    )

    /**
     * Extracts and saves a 240x240 RGB_565 thumbnail to disk cache if available.
     * Returns the absolute path of the cached image, or null if no art is found.
     * Must be called from a background thread (Dispatchers.IO).
     */
    fun extractCoverToCache(audioFilePath: String): String? {
        val cacheKey = hashPath(audioFilePath)
        val targetFile = File(coversDir, "$cacheKey.jpg")

        if (targetFile.exists() && targetFile.length() > 0) {
            return targetFile.absolutePath
        }

        var pictureBytes: ByteArray? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(audioFilePath)
            pictureBytes = retriever.embeddedPicture
        } catch (ignored: Exception) {
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {
            }
        }

        // 1. Try embedded picture
        if (pictureBytes != null && pictureBytes.isNotEmpty()) {
            val saved = decodeAndSaveThumbnail(pictureBytes, targetFile)
            if (saved) return targetFile.absolutePath
        }

        // 2. Fallback to folder images
        val parentDir = File(audioFilePath).parentFile
        if (parentDir != null && parentDir.exists() && parentDir.isDirectory) {
            for (name in fallbackNames) {
                val candidate = File(parentDir, name)
                if (candidate.exists() && candidate.isFile && candidate.length() > 0) {
                    val saved = decodeAndSaveThumbnail(candidate.absolutePath, targetFile)
                    if (saved) return targetFile.absolutePath
                }
            }
        }

        return null
    }

    private fun decodeAndSaveThumbnail(bytes: ByteArray, destFile: File): Boolean {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

            options.inSampleSize = calculateInSampleSize(options, 240, 240)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565

            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return false
            saveBitmapToFile(bitmap, destFile)
            bitmap.recycle()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun decodeAndSaveThumbnail(sourcePath: String, destFile: File): Boolean {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(sourcePath, options)

            options.inSampleSize = calculateInSampleSize(options, 240, 240)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565

            val bitmap = BitmapFactory.decodeFile(sourcePath, options) ?: return false
            saveBitmapToFile(bitmap, destFile)
            bitmap.recycle()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun saveBitmapToFile(bitmap: Bitmap, destFile: File) {
        val tempFile = File(destFile.parentFile, "${destFile.name}.tmp")
        FileOutputStream(tempFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.flush()
        }
        tempFile.renameTo(destFile)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    /**
     * Retrieves bitmap from memory cache or reads from disk cache into memory cache.
     */
    fun getBitmap(coverPath: String?): Bitmap? {
        if (coverPath.isNullOrBlank()) return null

        synchronized(memoryCache) {
            val memBmp = memoryCache.get(coverPath)
            if (memBmp != null && !memBmp.isRecycled) {
                return memBmp
            }
        }

        val file = File(coverPath)
        if (!file.exists() || file.length() == 0L) return null

        return try {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = BitmapFactory.decodeFile(coverPath, options)
            if (bitmap != null) {
                synchronized(memoryCache) {
                    memoryCache.put(coverPath, bitmap)
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Asynchronously binds cover art to an ImageView.
     */
    fun loadInto(
        imageView: ImageView,
        coverPath: String?,
        placeholderResId: Int,
        scope: CoroutineScope
    ): Job {
        imageView.tag = coverPath
        if (coverPath.isNullOrBlank()) {
            imageView.setImageResource(placeholderResId)
            return Job().apply { complete() }
        }

        // Fast memory cache check on Main thread
        synchronized(memoryCache) {
            val memBmp = memoryCache.get(coverPath)
            if (memBmp != null && !memBmp.isRecycled) {
                imageView.setImageBitmap(memBmp)
                return Job().apply { complete() }
            }
        }

        imageView.setImageResource(placeholderResId)

        return scope.launch(Dispatchers.IO) {
            val bitmap = getBitmap(coverPath)
            withContext(Dispatchers.Main) {
                if (imageView.tag == coverPath) {
                    if (bitmap != null && !bitmap.isRecycled) {
                        imageView.setImageBitmap(bitmap)
                    } else {
                        imageView.setImageResource(placeholderResId)
                    }
                }
            }
        }
    }

    fun clearMemory() {
        synchronized(memoryCache) {
            memoryCache.evictAll()
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: CoverManager? = null

        fun getInstance(context: Context): CoverManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CoverManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun hashPath(path: String): String {
            return try {
                val md = MessageDigest.getInstance("MD5")
                val bytes = md.digest(path.toByteArray())
                bytes.joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                path.hashCode().toString()
            }
        }
    }
}
