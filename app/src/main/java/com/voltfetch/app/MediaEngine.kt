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
    /** True while yt-dlp is being brought up to [MIN_VERSION] on first start. */
    @Volatile var updating = false
        private set

    /**
     * The yt-dlp release this build expects. The Android library bundles an older one (late 2025), so on first
     * start the app installs this release from yt-dlp's official GitHub releases before any media job runs.
     * Later releases (Settings › Update media engine, or the weekly check) are always welcome.
     */
    const val MIN_VERSION = "2026.10.21"
    private val pinned = object : YoutubeDL.UpdateChannel("https://api.github.com/repos/yt-dlp/yt-dlp/releases/tags/$MIN_VERSION") {}
    @Volatile private var initError: Throwable? = null
    private val lock = ReentrantReadWriteLock()
    /** Opens once the start-up yt-dlp version check is done, so the first jobs never run on the old version. */
    private val startupDone = java.util.concurrent.CountDownLatch(1)

    /** Kicks off start-up in the background; safe to call many times. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        Thread({
            try {
                initNow(app)
                if (ready) runCatching { ensureMinimum(app) }
            } finally {
                startupDone.countDown()
            }
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
        startupDone.await(90, java.util.concurrent.TimeUnit.SECONDS)
        lock.readLock().lock()
        try { return block() } finally { lock.readLock().unlock() }
    }

    /** The yt-dlp version in use (its release tag, e.g. 2026.10.21), or a placeholder before the first update. */
    fun version(ctx: Context): String {
        if (!ready) return "Starting…"
        if (updating) return "updating…"
        val app = ctx.applicationContext
        return runCatching { YoutubeDL.getInstance().version(app) }.getOrNull()?.takeIf { it.isNotBlank() } ?: "bundled (older)"
    }

    /** Tags look like 2026.10.21 (sometimes 2026.10.21.1), so plain text comparison orders them correctly. */
    private fun older(v: String?, than: String) = v.isNullOrBlank() || v < than

    /**
     * First start (or still on the bundled yt-dlp): install [MIN_VERSION]. Media jobs wait for this (they need
     * the read lock), so nothing runs on the old version. Falls back to the latest stable release if that tag
     * can't be fetched; tried again on the next start if offline.
     */
    private fun ensureMinimum(app: Context) {
        if (!older(runCatching { YoutubeDL.getInstance().version(app) }.getOrNull(), MIN_VERSION)) return
        if (!lock.writeLock().tryLock(15, java.util.concurrent.TimeUnit.SECONDS)) return
        updating = true
        try {
            val ok = runCatching { YoutubeDL.getInstance().updateYoutubeDL(app, pinned) }.isSuccess
            if (!ok) runCatching { YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE) }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("lastCheck", System.currentTimeMillis()).apply()
        } finally {
            updating = false
            lock.writeLock().unlock()
        }
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
