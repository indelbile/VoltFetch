package com.voltfetch.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Small square thumbnails for media downloads, shown in the list and in notifications.
 * Fetched once, kept as ~10 KB JPEGs in cacheDir/thumbs (Android may clear that folder; they're re-fetched).
 */
object Thumbs {
    private const val SIZE = 160
    private val mem = LruCache<Long, Bitmap>(80)
    private val loading = ConcurrentHashMap.newKeySet<Long>()
    private val failed = ConcurrentHashMap.newKeySet<Long>()
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "voltfetch-thumbs").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    private fun file(ctx: Context, id: Long) = File(File(ctx.cacheDir, "thumbs").apply { mkdirs() }, "$id.jpg")

    /** Memory or disk only; never touches the network. */
    fun cached(ctx: Context, id: Long): Bitmap? {
        mem.get(id)?.let { return it }
        val f = file(ctx, id)
        if (!f.exists()) return null
        return runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull()?.also { mem.put(id, it) }
    }

    /** The thumbnail if ready; otherwise starts fetching it and calls [onReady] on the main thread when done. */
    fun get(ctx: Context, id: Long, url: String, onReady: (() -> Unit)? = null): Bitmap? {
        cached(ctx, id)?.let { return it }
        if (url.isBlank() || id in failed || !loading.add(id)) return null
        val app = ctx.applicationContext
        pool.execute {
            try {
                val bmp = fetch(url)
                if (bmp == null) failed.add(id)
                else {
                    runCatching { file(app, id).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
                    mem.put(id, bmp)
                    onReady?.let { main.post(it) }
                }
            } finally {
                loading.remove(id)
            }
        }
        return null
    }

    private fun fetch(url: String): Bitmap? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", com.voltfetch.engine.Settings.DEFAULT_AGENT)
        try {
            if (c.responseCode !in 200..299) return@runCatching null
            val bytes = c.inputStream.use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= SIZE && bounds.outHeight / (sample * 2) >= SIZE) sample *= 2
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching null
            // centre-crop to a square, then scale down
            val side = minOf(src.width, src.height)
            val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
            Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    /** Drops cached thumbnails of downloads that are no longer in the list. */
    fun prune(ctx: Context, keep: Set<Long>) {
        pool.execute {
            File(ctx.cacheDir, "thumbs").listFiles()?.forEach { f ->
                if (f.nameWithoutExtension.toLongOrNull() !in keep) runCatching { f.delete() }
            }
        }
    }
}
