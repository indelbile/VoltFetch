package com.voltfetch.app

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Owns the embedded yt-dlp + FFmpeg runtime.
 *
 * - Starts on a background thread (the first start unpacks Python, which used to freeze app launch).
 * - Reports a readable error if it can't start, and tries again on the next use.
 * - Updates yt-dlp from its official releases, by hand or once a week on Wi-Fi, but never while a
 *   media job is using it (jobs hold a read lock, updates need the write lock).
 */
object MediaEngine {
    private const val PREFS = "media_engine"
    private const val WEEK = 7L * 24 * 60 * 60 * 1000

    @Volatile var ready = false
        private set
    @Volatile private var initError: Throwable? = null
    private val lock = ReentrantReadWriteLock()

    /** Kicks off start-up in the background; safe to call many times. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        Thread({
            initNow(app)
            if (ready) runCatching { maybeAutoUpdate(app) }
        }, "voltfetch-media-init").apply { isDaemon = true }.start()
    }

    @Synchronized private fun initNow(app: Context) {
        if (ready) return
        try {
            YoutubeDL.getInstance().init(app)
            FFmpeg.getInstance().init(app)
            ready = true; initError = null
        } catch (t: Throwable) {
            initError = t
        }
    }

    /** Blocks until the engine is ready (waits for a start-up already in progress). Throws a readable error. */
    fun awaitReady(ctx: Context) {
        initNow(ctx.applicationContext)
        if (!ready) throw IllegalStateException("The media engine couldn't start on this phone: ${initError?.message ?: initError?.javaClass?.simpleName ?: "unknown error"}")
    }

    /** Runs [block] with the engine ready and protected from being updated underneath it. */
    fun <T> use(ctx: Context, block: () -> T): T {
        awaitReady(ctx)
        lock.readLock().lock()
        try { return block() } finally { lock.readLock().unlock() }
    }

    /** The yt-dlp version in use, or a placeholder when it hasn't been updated since install. */
    fun version(ctx: Context): String {
        if (!ready) return "Starting…"
        val app = ctx.applicationContext
        return runCatching { YoutubeDL.getInstance().versionName(app) ?: YoutubeDL.getInstance().version(app) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "Bundled with the app"
    }

    fun lastCheck(ctx: Context): Long = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("lastCheck", 0L)

    fun autoUpdate(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("auto", true)
    fun setAutoUpdate(ctx: Context, on: Boolean) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("auto", on).apply() }

    sealed class UpdateResult(val message: String) {
        class Updated(version: String) : UpdateResult("Updated to $version")
        object UpToDate : UpdateResult("Already up to date")
        object Busy : UpdateResult("A media download is running. Pause it, then update.")
        class Failed(reason: String) : UpdateResult("Couldn't update: $reason")
    }

    /** Updates yt-dlp now. Call from a background thread. */
    fun updateNow(ctx: Context): UpdateResult {
        val app = ctx.applicationContext
        try { awaitReady(app) } catch (e: Exception) { return UpdateResult.Failed(e.message ?: "engine unavailable") }
        if (!lock.writeLock().tryLock()) return UpdateResult.Busy
        try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("lastCheck", System.currentTimeMillis()).apply()
            return if (status == YoutubeDL.UpdateStatus.DONE) UpdateResult.Updated(version(app)) else UpdateResult.UpToDate
        } catch (t: Throwable) {
            return UpdateResult.Failed(t.message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(200) ?: t.javaClass.simpleName)
        } finally {
            lock.writeLock().unlock()
        }
    }

    /** Weekly check on an unmetered network, skipped if a media job is running or the user turned it off. */
    private fun maybeAutoUpdate(app: Context) {
        if (!autoUpdate(app)) return
        if (System.currentTimeMillis() - lastCheck(app) < WEEK) return
        val va = app as? VoltApp ?: return
        if (!va.online || !va.unmetered) return
        updateNow(app)
    }
}
