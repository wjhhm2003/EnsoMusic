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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class CoverManager(private val context: Context) {

    private val coversDir: File = File(context.cacheDir, "covers").apply {
        if (!exists()) mkdirs()
    }

    // 4MB LruCache for decoded 240x240 RGB_565 Bitmaps. Keyed by cover file
    // path; since identical covers now share one file, they share one entry.
    private val memoryCache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }

    // Dominant colour per cover path (an Int is tiny, so cap by entry count).
    private val colorCache = LruCache<String, Int>(128)

    private val fallbackNames = arrayOf(
        "cover.jpg", "cover.png", "cover.webp",
        "folder.jpg", "folder.png",
        "Cover.jpg", "Folder.jpg",
        "albumart.jpg", "album.jpg"
    )

    /**
     * Extracts and saves a 240x240 RGB_565 thumbnail to disk cache if available.
     * [embeddedPicture] should come from the MediaMetadataRetriever the caller
     * already opened, so the audio file is not opened a second time.
     *
     * Embedded covers are keyed by a hash of the picture bytes and folder images
     * by a hash of the image path + lastModified, so identical covers (e.g. all
     * tracks of an album) share one cached file.
     *
     * Returns the absolute path of the cached image, or null if no art is found.
     * Must be called from a background thread (Dispatchers.IO).
     */
    fun extractCoverToCache(audioFilePath: String, embeddedPicture: ByteArray?): String? {
        // 1. Try embedded picture
        if (embeddedPicture != null && embeddedPicture.isNotEmpty()) {
            val targetFile = File(coversDir, "${CoverKeys.embeddedKey(embeddedPicture)}.jpg")
            if (isUsableFile(targetFile)) return targetFile.absolutePath
            if (decodeAndSaveThumbnail(embeddedPicture, targetFile)) return targetFile.absolutePath
        }

        // 2. Fallback to folder images
        val parentDir = File(audioFilePath).parentFile
        if (parentDir != null && parentDir.isDirectory) {
            for (name in fallbackNames) {
                val candidate = File(parentDir, name)
                if (!candidate.isFile) continue
                val length = candidate.length()
                if (length <= 0L) continue
                val key = CoverKeys.folderImageKey(candidate.absolutePath, candidate.lastModified())
                val targetFile = File(coversDir, "$key.jpg")
                if (isUsableFile(targetFile)) return targetFile.absolutePath
                if (decodeAndSaveThumbnail(candidate.absolutePath, targetFile)) {
                    return targetFile.absolutePath
                }
            }
        }

        return null
    }

    /**
     * Convenience overload that opens its own MediaMetadataRetriever. Prefer
     * [extractCoverToCache] with the bytes when a retriever is already open.
     */
    fun extractCoverToCache(audioFilePath: String): String? {
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
        return extractCoverToCache(audioFilePath, pictureBytes)
    }

    private fun isUsableFile(file: File): Boolean = file.isFile && file.length() > 0L

    private fun decodeAndSaveThumbnail(bytes: ByteArray, destFile: File): Boolean {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return false

            options.inSampleSize = calculateInSampleSize(options, 240, 240)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565

            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return false
            try {
                saveBitmapToFile(bitmap, destFile)
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            false
        } catch (e: OutOfMemoryError) {
            false
        }
    }

    private fun decodeAndSaveThumbnail(sourcePath: String, destFile: File): Boolean {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(sourcePath, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return false

            options.inSampleSize = calculateInSampleSize(options, 240, 240)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565

            val bitmap = BitmapFactory.decodeFile(sourcePath, options) ?: return false
            try {
                saveBitmapToFile(bitmap, destFile)
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            false
        } catch (e: OutOfMemoryError) {
            false
        }
    }

    /**
     * Writes to a unique temp file and moves it into place. If the rename
     * fails, falls back to copying and always deletes the temp file.
     * Returns true if [destFile] holds a usable image afterwards.
     */
    private fun saveBitmapToFile(bitmap: Bitmap, destFile: File): Boolean {
        val dir = destFile.parentFile ?: coversDir
        if (!dir.exists()) dir.mkdirs()
        // Unique temp name: two scans/threads may write the same shared cover.
        val tempFile = File.createTempFile("${destFile.nameWithoutExtension}_", ".tmp", dir)
        try {
            val compressed = FileOutputStream(tempFile).use { out ->
                val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                out.flush()
                ok
            }
            if (!compressed || tempFile.length() == 0L) return false

            if (tempFile.renameTo(destFile)) return true

            // Another thread may have produced the same content-keyed file.
            if (isUsableFile(destFile)) return true

            return try {
                tempFile.copyTo(destFile, overwrite = true)
                isUsableFile(destFile)
            } catch (e: Exception) {
                destFile.delete()
                false
            }
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
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
     * Works for any plain file path, including legacy per-song cover files.
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
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /**
     * Returns a representative colour (opaque ARGB) for the cover, or null when
     * there is no cover. Near-black and near-white pixels are down-weighted.
     * Does disk IO / decoding: call off the main thread.
     */
    fun getDominantColor(coverPath: String?): Int? {
        if (coverPath.isNullOrBlank()) return null
        colorCache.get(coverPath)?.let { return it }

        val bitmap = getBitmap(coverPath) ?: return null
        val color = try {
            val scaled = Bitmap.createScaledBitmap(bitmap, COLOR_SAMPLE_SIZE, COLOR_SAMPLE_SIZE, true)
            val pixels = IntArray(COLOR_SAMPLE_SIZE * COLOR_SAMPLE_SIZE)
            scaled.getPixels(pixels, 0, COLOR_SAMPLE_SIZE, 0, 0, COLOR_SAMPLE_SIZE, COLOR_SAMPLE_SIZE)
            // createScaledBitmap may return the source itself; never recycle the cached bitmap.
            if (scaled !== bitmap) scaled.recycle()
            CoverColors.weightedAverage(pixels)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null

        colorCache.put(coverPath, color)
        return color
    }

    /**
     * Asynchronously binds cover art to an ImageView. When the view already
     * shows the same coverPath (tag match), the placeholder is not set again,
     * so rebinding the same song does not flicker.
     */
    fun loadInto(
        imageView: ImageView,
        coverPath: String?,
        placeholderResId: Int,
        scope: CoroutineScope
    ): Job {
        val previousTag = imageView.tag
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

        if (previousTag != coverPath) {
            imageView.setImageResource(placeholderResId)
        }

        return scope.launch(Dispatchers.IO) {
            val bitmap = getBitmap(coverPath)
            withContext(Dispatchers.Main) {
                if (isActive && imageView.tag == coverPath) {
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
        colorCache.evictAll()
    }

    companion object {
        private const val COLOR_SAMPLE_SIZE = 8

        @Volatile
        private var INSTANCE: CoverManager? = null

        fun getInstance(context: Context): CoverManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CoverManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun hashPath(path: String): String = CoverKeys.md5Hex(path.toByteArray())
    }
}

/** Pure (JVM-testable) cache-key helpers for cover thumbnails. */
object CoverKeys {

    fun md5Hex(bytes: ByteArray): String {
        return try {
            val digest = MessageDigest.getInstance("MD5").digest(bytes)
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xFF
                sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            sb.toString()
        } catch (e: Exception) {
            bytes.contentHashCode().toUInt().toString(16)
        }
    }

    /** Key for an embedded picture: content hash, so identical covers share one file. */
    fun embeddedKey(pictureBytes: ByteArray): String = "e_" + md5Hex(pictureBytes)

    /** Key for a folder image (cover.jpg etc.): path + lastModified, so edits invalidate it. */
    fun folderImageKey(imagePath: String, lastModified: Long): String =
        "f_" + md5Hex("$imagePath|$lastModified".toByteArray(Charsets.UTF_8))

    private val HEX = "0123456789abcdef".toCharArray()
}

/** Pure (JVM-testable) colour maths used by [CoverManager.getDominantColor]. */
object CoverColors {

    /**
     * Weighted average of ARGB pixels. Near-black / near-white pixels get a tiny
     * weight and saturated pixels get a larger one, so the result tracks the
     * cover's main hue rather than its borders or background. Falls back to a
     * plain average when every pixel is near-black/white. Returns an opaque
     * colour, or null for an empty array.
     */
    fun weightedAverage(pixels: IntArray): Int? {
        if (pixels.isEmpty()) return null
        var wSum = 0.0
        var rSum = 0.0
        var gSum = 0.0
        var bSum = 0.0
        var rPlain = 0L
        var gPlain = 0L
        var bPlain = 0L

        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            rPlain += r; gPlain += g; bPlain += b

            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val luma = (299 * r + 587 * g + 114 * b) / 1000
            val lumaWeight = if (luma < NEAR_BLACK || luma > NEAR_WHITE) 0.02 else 1.0
            val saturation = if (max == 0) 0.0 else (max - min).toDouble() / max
            val w = lumaWeight * (0.25 + saturation)

            wSum += w
            rSum += r * w
            gSum += g * w
            bSum += b * w
        }

        val n = pixels.size
        // If nearly everything was black/white, the weighted mean is dominated
        // by noise; use the plain mean instead.
        if (wSum < n * 0.05) {
            return opaque((rPlain / n).toInt(), (gPlain / n).toInt(), (bPlain / n).toInt())
        }
        return opaque(
            Math.round(rSum / wSum).toInt().coerceIn(0, 255),
            Math.round(gSum / wSum).toInt().coerceIn(0, 255),
            Math.round(bSum / wSum).toInt().coerceIn(0, 255)
        )
    }

    private fun opaque(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private const val NEAR_BLACK = 24
    private const val NEAR_WHITE = 232
}
