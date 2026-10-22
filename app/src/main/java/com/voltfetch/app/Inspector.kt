package com.voltfetch.app

import android.content.Context
import com.voltfetch.engine.MediaSites
import com.voltfetch.engine.YouTube
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Looks up what a link offers (title, qualities, playlist entries) with yt-dlp, and makes it feel fast:
 * - results are kept for 15 minutes, so checking the same link again is instant;
 * - the same lookup is never run twice at once (a background prefetch and the screen share one run);
 * - [prefetch] starts a lookup early, e.g. as soon as a video link is copied.
 * [quickInfo] gets just the title/thumbnail in about a second from the site's oEmbed service.
 */
object Inspector {
    private const val TTL = 15L * 60 * 1000
    private class Hit(val at: Long, val json: String)
    private val cache = ConcurrentHashMap<String, Hit>()
    private val running = ConcurrentHashMap<String, FutureTask<String>>()

    private fun key(url: String, mode: String, login: Boolean) = "$mode|$login|$url"

    /** mode: "video" (single item), "playlist" (entries only, fast) or "auto" (whatever the link is). Blocks; call off the main thread. */
    fun fetch(ctx: Context, url: String, mode: String, useLogin: Boolean): JSONObject {
        val k = key(url, mode, useLogin)
        cache[k]?.takeIf { System.currentTimeMillis() - it.at < TTL }?.let { return JSONObject(it.json) }
        val task = FutureTask<String>(java.util.concurrent.Callable { run(ctx.applicationContext, url, mode, useLogin) })
        val existing = running.putIfAbsent(k, task)
        val t = existing ?: task.also { it.run() }
        try {
            val json = t.get(5, TimeUnit.MINUTES)
            cache[k] = Hit(System.currentTimeMillis(), json)
            if (cache.size > 30) cache.entries.minByOrNull { it.value.at }?.let { cache.remove(it.key) }
            return JSONObject(json)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        } finally {
            if (existing == null) running.remove(k)
        }
    }

    /** Starts looking up a copied/shared link in the background so the Media screen has it ready. */
    fun prefetch(ctx: Context, url: String) {
        if (!MediaSites.isKnown(url)) return
        val app = ctx.applicationContext
        val login = app.getSharedPreferences("media_ui", Context.MODE_PRIVATE).getBoolean("ck", true)
        val mode = if (YouTube.isUrl(url)) "video" else "auto"
        if (YouTube.isUrl(url) && YouTube.videoId(url) == null) return // playlist-only links are checked on demand
        if (cache[key(url, mode, login)] != null || running.containsKey(key(url, mode, login))) return
        Thread({ runCatching { fetch(app, url, mode, login) } }, "voltfetch-prefetch").apply { isDaemon = true }.start()
    }

    private fun run(ctx: Context, url: String, mode: String, useLogin: Boolean): String = MediaEngine.use(ctx) {
        val cookies = File(ctx.cacheDir, "inspect-cookies-${System.nanoTime()}.txt")
        val request = YoutubeDLRequest(url).apply {
            addOption("--dump-single-json"); addOption("--skip-download"); addOption("--no-warnings")
            when (mode) { "video" -> addOption("--no-playlist"); else -> addOption("--flat-playlist") }
            val s = VoltApp.engine(ctx).settings
            if (s.proxyOn && s.proxyHost.isNotBlank()) addOption("--proxy", "http://${s.proxyHost.trim()}:${s.proxyPort}")
            if (useLogin && BrowserCookies.writeNetscape(url, cookies)) addOption("--cookies", cookies.absolutePath)
        }
        try { YoutubeDL.getInstance().execute(request, "voltfetch-inspect-${System.nanoTime()}").out } finally { runCatching { cookies.delete() } }
    }

    data class Quick(val title: String, val author: String, val thumb: String)

    /** Title, channel and thumbnail in ~1 s for YouTube and Vimeo (their public oEmbed services); null elsewhere. */
    fun quickInfo(url: String): Quick? {
        val endpoint = when {
            YouTube.isUrl(url) && YouTube.videoId(url) != null -> "https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(YouTube.watchUrl(YouTube.videoId(url)!!), "UTF-8")
            MediaSites.site(url) == "Vimeo" -> "https://vimeo.com/api/oembed.json?url=" + URLEncoder.encode(url, "UTF-8")
            else -> return null
        }
        return runCatching {
            val c = URL(endpoint).openConnection() as HttpURLConnection
            c.connectTimeout = 4000; c.readTimeout = 4000
            try {
                if (c.responseCode != 200) return@runCatching null
                val j = JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
                Quick(j.optString("title", ""), j.optString("author_name", ""), j.optString("thumbnail_url", "")).takeIf { it.title.isNotBlank() }
            } finally { c.disconnect() }
        }.getOrNull()
    }
}
