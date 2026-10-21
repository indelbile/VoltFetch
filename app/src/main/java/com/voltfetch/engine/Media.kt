package com.voltfetch.engine

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** YouTube address helpers, shared by every screen and the media handler so they never disagree. */
object YouTube {
    private val hosts = listOf("youtube.com", "youtu.be", "youtube-nocookie.com")
    private val idRe = Regex("^[A-Za-z0-9_-]{6,}$")

    private fun uri(raw: String): URI? = runCatching { URI(raw.trim()) }.getOrNull()

    /** A YouTube page (video, Short, playlist, Music). Raw stream hosts like googlevideo.com are not pages. */
    fun isUrl(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val u = uri(raw) ?: return false
        val scheme = u.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return false
        val h = u.host?.lowercase(Locale.ROOT) ?: return false
        return hosts.any { h == it || h.endsWith(".$it") }
    }

    /** The first YouTube link in shared or typed text; adds https:// to "youtu.be/…" typed without it. */
    fun find(text: String?): String? {
        if (text.isNullOrBlank()) return null
        Links.find(text).firstOrNull { isUrl(it) }?.let { return it }
        val t = text.trim()
        return if (!t.contains("://") && !t.contains(' ') && isUrl("https://$t")) "https://$t" else null
    }

    private fun query(url: String): Map<String, String> {
        val q = uri(url)?.rawQuery ?: return emptyMap()
        return q.split('&').mapNotNull { p ->
            val k = p.substringBefore('=', "")
            if (k.isEmpty()) null else k to runCatching { java.net.URLDecoder.decode(p.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
        }.toMap()
    }

    fun videoId(url: String): String? {
        if (!isUrl(url)) return null
        val u = uri(url) ?: return null
        val host = u.host.lowercase(Locale.ROOT)
        val path = (u.path ?: "").trim('/')
        val id = when {
            host == "youtu.be" || host.endsWith(".youtu.be") -> path.substringBefore('/')
            path.startsWith("shorts/") || path.startsWith("live/") || path.startsWith("embed/") || path.startsWith("v/") -> path.substringAfter('/').substringBefore('/')
            else -> query(url)["v"]
        }
        return id?.takeIf { idRe.matches(it) }
    }

    fun playlistId(url: String): String? = if (isUrl(url)) query(url)["list"]?.takeIf { it.isNotBlank() } else null

    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"
    fun playlistUrl(id: String) = "https://www.youtube.com/playlist?list=$id"
}

/**
 * Sites the media engine (yt-dlp) handles well. Any http(s) page can be tried; this list decides where a
 * shared link is sent and how a site is named in notes. yt-dlp itself supports well over a thousand sites.
 */
object MediaSites {
    private val known = linkedMapOf(
        "youtube.com" to "YouTube", "youtu.be" to "YouTube", "youtube-nocookie.com" to "YouTube",
        "instagram.com" to "Instagram", "facebook.com" to "Facebook", "fb.watch" to "Facebook", "fb.com" to "Facebook",
        "x.com" to "X", "twitter.com" to "X", "vimeo.com" to "Vimeo", "dailymotion.com" to "Dailymotion", "dai.ly" to "Dailymotion",
        "reddit.com" to "Reddit", "redd.it" to "Reddit", "soundcloud.com" to "SoundCloud", "tiktok.com" to "TikTok",
        "twitch.tv" to "Twitch", "bilibili.com" to "Bilibili", "vk.com" to "VK", "pinterest.com" to "Pinterest", "pin.it" to "Pinterest",
        "threads.net" to "Threads", "linkedin.com" to "LinkedIn", "streamable.com" to "Streamable", "bandcamp.com" to "Bandcamp",
        "mixcloud.com" to "Mixcloud", "ted.com" to "TED", "rumble.com" to "Rumble", "odysee.com" to "Odysee", "9gag.com" to "9GAG",
        "imgur.com" to "Imgur", "tumblr.com" to "Tumblr", "loom.com" to "Loom", "kick.com" to "Kick", "snapchat.com" to "Snapchat",
    )

    private fun host(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        return u.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
    }

    /** Any http(s) page with a host: something the media engine can at least try. */
    fun isWeb(url: String?): Boolean = host(url) != null

    /** "Instagram", "YouTube"… for known sites, else null. */
    fun site(url: String?): String? {
        val h = host(url) ?: return null
        return known.entries.firstOrNull { (d, _) -> h == d || h.endsWith(".$d") }?.value
    }

    fun isKnown(url: String?) = site(url) != null

    /** A short name for notes: the known site name, or the bare host. */
    fun label(url: String?): String = site(url) ?: host(url)?.removePrefix("www.")?.removePrefix("m.") ?: "Web"

    /** The best media link in shared or typed text: a known site first, then any web link; adds https:// when typed without it. */
    fun find(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val links = Links.find(text)
        links.firstOrNull { isKnown(it) }?.let { return it }
        links.firstOrNull()?.let { return it }
        val t = text.trim()
        return if (!t.contains("://") && !t.contains(' ') && t.contains('.') && isWeb("https://$t")) "https://$t" else null
    }
}

/** Kinds of media task. The first two keep their original names so lists saved by earlier versions still load. */
object MediaKinds {
    const val VIDEO = "youtube_video"
    const val MP3 = "youtube_audio"
    /** The site's own audio track (usually AAC in .m4a), copied without re-encoding. */
    const val AUDIO = "media_audio"
    val all = setOf(VIDEO, MP3, AUDIO)
    fun isAudio(kind: String) = kind == MP3 || kind == AUDIO
}

/**
 * Extra per-task choices, stored as "key=value;key=value":
 * sb=1 cut sponsor/non-music parts (SponsorBlock, YouTube only) · from/to = clip in seconds ·
 * ck=1 use the login (cookies) from VoltFetch's browser.
 */
object MediaOpts {
    val keys = setOf("sb", "from", "to", "ck")

    fun parse(s: String?): Map<String, String> = (s ?: "").split(';').mapNotNull { p ->
        val k = p.substringBefore('=', "").trim()
        if (k.isEmpty()) null else k to p.substringAfter('=', "").trim()
    }.toMap()

    fun encode(m: Map<String, String>): String =
        m.filter { it.key in keys && it.value.isNotBlank() }.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }

    /** (from, to) in whole seconds when a valid clip is set. */
    fun clip(m: Map<String, String>): Pair<Int, Int>? {
        val a = m["from"]?.toIntOrNull() ?: return null
        val b = m["to"]?.toIntOrNull() ?: return null
        return if (a >= 0 && b > a) a to b else null
    }

    /** "80" → "1:20", "3723" → "1:02:03". */
    fun clock(sec: Int): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** "1:20", "01:02:03" or "80" → seconds; null if it isn't a time. */
    fun parseClock(text: String?): Int? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty() || !t.matches(Regex("\\d{1,3}(:\\d{1,2}){0,2}"))) return null
        val parts = t.split(':').map { it.toInt() }
        if (parts.drop(1).any { it >= 60 }) return null
        return parts.fold(0) { acc, x -> acc * 60 + x }
    }
}

/** What an embedded media job needs; a plain snapshot, so the handler never touches engine state. */
data class MediaTask(
    val id: Long, val url: String, val kind: String, val option: Int,
    /** Subtitle languages to fetch and embed ("" = none), e.g. "en" or "en,hi". */
    val subs: String,
    val category: String, val name: String,
    /** Extra choices, see [MediaOpts]. */
    val opts: Map<String, String> = emptyMap(),
)

/** Services the engine lends to a media job. proxyUrl routes traffic through the shared speed limit and the user's proxy. */
class MediaEnv(val proxyUrl: String?)

/** One progress report: overall percent across every stage, what is happening, and current speed/ETA. */
data class MediaProgress(val percent: Double, val stage: String, val speed: Double, val eta: Double, val title: String = "")

/**
 * Turns yt-dlp's console output into one steadily rising percentage. yt-dlp reports 0–100% for each
 * stream separately (video, then audio) and is silent while merging or converting, so the bar would
 * otherwise jump back and stall at 100%.
 */
class MediaProgressTracker(private val kind: String, private val clipSeconds: Double = 0.0) {
    private val video = kind == MediaKinds.VIDEO
    // FFmpeg (used for clips) reports "time=00:00:12.34" instead of a percentage
    private val ffTimeRe = Regex("""time=(\d+):(\d+):(\d+(?:\.\d+)?)""")
    private var streams = 0
    private var currentIsMedia = false
    private var overall = 0.0
    private var stage = "Starting"
    private var speed = 0.0
    private var eta = -1.0
    private var title = ""

    private val progressRe = Regex(
        """^\[download]\s+([\d.]+)%(?:\s+of\s+~?\s*([\d.]+)\s*([KMGT]?i?B))?(?:\s+in\s+\S+)?(?:\s+at\s+(?:([\d.]+)\s*([KMGT]?i?B)/s|Unknown(?:\s+B/s|\s+speed)?))?(?:\s+ETA\s+(\S+))?"""
    )
    private val sideExt = setOf("vtt", "srt", "ttml", "srv1", "srv2", "srv3", "json3", "ass", "lrc", "webp", "jpg", "jpeg", "png", "json", "description")

    private fun span(): Pair<Double, Double> = when {
        video && streams <= 1 -> 0.0 to 85.0
        video -> 85.0 to 94.0
        else -> 0.0 to 90.0
    }

    private fun post(name: String, at: Double): MediaProgress {
        stage = name; speed = 0.0; eta = -1.0
        overall = maxOf(overall, at)
        return snapshot()
    }

    private fun snapshot() = MediaProgress(overall, stage, speed, eta, title)

    /** "/w/My Song [dQw4w9WgXcQ].f140.m4a" → "My Song". */
    private fun titleOf(file: String): String = file.substringAfterLast('/').substringBeforeLast('.')
        .replace(Regex("\\.f[^.\\s]+$"), "").replace(Regex("\\s*\\[[^\\]]+]$"), "").trim()

    fun feed(raw: String): MediaProgress? {
        val line = raw.trim()
        if (line.isEmpty()) return null
        if (line.startsWith("[download] Destination:") || line.endsWith("has already been downloaded")) {
            val file = if (line.startsWith("[download] Destination:")) line.substringAfter("Destination:").trim()
            else line.removePrefix("[download]").removeSuffix("has already been downloaded").trim()
            val ext = file.substringAfterLast('.', "").lowercase(Locale.ROOT)
            currentIsMedia = ext !in sideExt
            if (!currentIsMedia) { stage = if (ext in setOf("webp", "jpg", "jpeg", "png")) "Fetching cover art" else "Fetching subtitles"; speed = 0.0; eta = -1.0; return snapshot() }
            streams++
            if (title.isEmpty()) title = titleOf(file)
            stage = when { !video -> "Downloading audio"; streams == 1 -> "Downloading video"; else -> "Downloading audio track" }
            if (clipSeconds > 0) stage += " (clip)"
            if (line.endsWith("has already been downloaded")) overall = maxOf(overall, span().second)
            return snapshot()
        }
        progressRe.find(line)?.let { m ->
            if (!currentIsMedia) return null
            val pct = m.groupValues[1].toDoubleOrNull()?.coerceIn(0.0, 100.0) ?: return null
            speed = if (m.groupValues[4].isNotEmpty()) bytes(m.groupValues[4], m.groupValues[5]) else 0.0
            eta = seconds(m.groupValues[6])
            if (pct >= 100.0) { speed = 0.0; eta = -1.0 }
            val (a, b) = span()
            overall = maxOf(overall, a + (b - a) * pct / 100.0)
            return snapshot()
        }
        if (clipSeconds > 0 && currentIsMedia) ffTimeRe.find(line)?.let { m ->
            val t = m.groupValues[1].toDouble() * 3600 + m.groupValues[2].toDouble() * 60 + m.groupValues[3].toDouble()
            val pct = (t / clipSeconds * 100).coerceIn(0.0, 100.0)
            speed = 0.0; eta = -1.0
            val (a, b) = span()
            overall = maxOf(overall, a + (b - a) * pct / 100.0)
            return snapshot()
        }
        return when {
            line.startsWith("[Merger]") -> post("Merging video and audio", 94.0)
            line.startsWith("[ExtractAudio]") -> post(if (kind == MediaKinds.MP3) "Converting to MP3" else "Extracting audio", 91.0)
            line.startsWith("[SponsorBlock]") -> { stage = "Checking SponsorBlock"; speed = 0.0; eta = -1.0; snapshot() }
            line.startsWith("[ModifyChapters]") -> post("Cutting out sponsor parts", 95.5)
            line.startsWith("[VideoRemuxer]") || line.startsWith("[VideoConvertor]") -> post("Converting", 95.0)
            line.startsWith("[Fixup") -> post("Finishing", 95.0)
            line.startsWith("[EmbedSubtitle]") -> post("Embedding subtitles", 96.0)
            line.startsWith("[ThumbnailsConvertor]") || line.startsWith("[EmbedThumbnail]") -> post("Adding cover art", 97.0)
            line.startsWith("[Metadata]") -> post("Writing tags", 98.0)
            line.startsWith("[") && !line.startsWith("[download]") && streams == 0 -> { stage = "Preparing"; snapshot() }
            else -> null
        }
    }

    companion object {
        fun bytes(num: String, unit: String): Double {
            val v = num.toDoubleOrNull() ?: return 0.0
            val mult = when (unit.uppercase(Locale.ROOT)) {
                "KIB" -> 1024.0; "MIB" -> 1024.0 * 1024; "GIB" -> 1024.0 * 1024 * 1024; "TIB" -> 1024.0 * 1024 * 1024 * 1024
                "KB" -> 1000.0; "MB" -> 1e6; "GB" -> 1e9; "TB" -> 1e12
                else -> 1.0
            }
            return v * mult
        }

        /** "01:02:03", "02:03" or "Unknown" → seconds, or -1. */
        fun seconds(s: String): Double {
            if (s.isEmpty() || !s[0].isDigit()) return -1.0
            val parts = s.split(':').map { it.toLongOrNull() ?: return -1.0 }
            return parts.fold(0L) { acc, x -> acc * 60 + x }.toDouble()
        }
    }
}

/**
 * A tiny HTTP proxy on 127.0.0.1 for one media job. yt-dlp is pointed at it, so its traffic shares the
 * engine's speed limiter with ordinary downloads (limit changes apply immediately) and is forwarded through
 * the user's own HTTP proxy when one is set. Supports CONNECT tunnels (HTTPS) and plain absolute-form HTTP.
 */
class ThrottleProxy(
    private val limiter: Limiter,
    private val upstream: () -> Pair<String, Int>?,
    private val timeoutMs: Int,
) : Closeable {
    // Explicitly 127.0.0.1: on Android getLoopbackAddress() is ::1 (IPv6), which yt-dlp's 127.0.0.1 can't reach.
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val closed = AtomicBoolean(false)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()

    val url: String get() = "http://127.0.0.1:${server.localPort}"

    init {
        Thread({
            while (!closed.get()) {
                val s = try { server.accept() } catch (e: Exception) { break }
                sockets.add(s)
                Thread({ try { handle(s) } catch (e: Exception) { } finally { close(s) } }, "voltfetch-proxy-conn").apply { isDaemon = true }.start()
            }
        }, "voltfetch-proxy").apply { isDaemon = true }.start()
    }

    private fun close(s: Socket?) { if (s != null) { runCatching { s.close() }; sockets.remove(s) } }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        for (s in sockets.toList()) close(s)
    }

    /** Reads up to the blank line ending the headers. */
    private fun readHead(inp: InputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        var state = 0
        while (out.size() < 64 * 1024) {
            val b = inp.read()
            if (b < 0) return null
            out.write(b)
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                else -> 0
            }
            if (state == 4) return out.toString("ISO-8859-1")
        }
        return null
    }

    private fun connect(host: String, port: Int): Socket {
        val s = Socket()
        sockets.add(s)
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = maxOf(timeoutMs, 60_000)
        return s
    }

    private fun reply(out: OutputStream, status: String) {
        runCatching { out.write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1)); out.flush() }
    }

    private fun handle(client: Socket) {
        client.soTimeout = maxOf(timeoutMs, 60_000)
        val cin = BufferedInputStream(client.getInputStream())
        val cout = client.getOutputStream()
        val head = readHead(cin) ?: return
        val lines = head.split("\r\n")
        val parts = lines[0].split(' ')
        if (parts.size < 3) { reply(cout, "400 Bad Request"); return }
        val method = parts[0].uppercase(Locale.ROOT)
        val target = parts[1]
        val up = upstream()
        var remote: Socket? = null
        try {
            if (method == "CONNECT") {
                val host = target.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
                val port = target.substringAfterLast(':', "443").toIntOrNull() ?: 443
                if (up == null) remote = connect(host, port)
                else {
                    remote = connect(up.first, up.second)
                    val ro = remote.getOutputStream()
                    ro.write("CONNECT $target HTTP/1.1\r\nHost: $target\r\n\r\n".toByteArray(Charsets.ISO_8859_1)); ro.flush()
                    val rin = BufferedInputStream(remote.getInputStream())
                    val rh = readHead(rin)
                    val code = rh?.substringBefore("\r\n")?.split(' ')?.getOrNull(1)
                    if (code != "200") { reply(cout, if (code == "407") "407 Proxy Authentication Required" else "502 Bad Gateway"); return }
                    cout.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.ISO_8859_1)); cout.flush()
                    pipe(cin, remote, rin, client); return
                }
                cout.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.ISO_8859_1)); cout.flush()
                pipe(cin, remote, remote.getInputStream(), client)
            } else {
                val u = runCatching { URI(target) }.getOrNull()
                if (u == null || u.scheme?.lowercase(Locale.ROOT) != "http" || u.host == null) { reply(cout, "400 Bad Request"); return }
                val headers = lines.drop(1).filter { it.isNotEmpty() && !it.startsWith("Proxy-", true) && !it.startsWith("Connection:", true) }
                val requestLine = if (up == null) "${parts[0]} ${(u.rawPath ?: "").ifEmpty { "/" }}${u.rawQuery?.let { "?$it" } ?: ""} ${parts[2]}" else lines[0]
                remote = if (up == null) connect(u.host, if (u.port > 0) u.port else 80) else connect(up.first, up.second)
                val ro = remote.getOutputStream()
                ro.write((listOf(requestLine) + headers + "Connection: close" + "" + "").joinToString("\r\n").toByteArray(Charsets.ISO_8859_1)); ro.flush()
                pipe(cin, remote, remote.getInputStream(), client)
            }
        } finally {
            close(remote)
        }
    }

    /** Client→server is passed straight through; server→client is metered by the shared limiter. */
    private fun pipe(cin: InputStream, remote: Socket, rin: InputStream, client: Socket) {
        val ro = remote.getOutputStream()
        val upThread = Thread({
            val buf = ByteArray(16 * 1024)
            try {
                while (true) { val n = cin.read(buf); if (n < 0) break; ro.write(buf, 0, n); ro.flush() }
                runCatching { remote.shutdownOutput() }
            } catch (e: Exception) { close(remote); close(client) }
        }, "voltfetch-proxy-up").apply { isDaemon = true }
        upThread.start()
        val cout = client.getOutputStream()
        val buf = ByteArray(16 * 1024)
        try {
            while (!closed.get()) {
                val n = rin.read(buf)
                if (n < 0) break
                if (n == 0) continue
                limiter.acquire(n, closed)
                cout.write(buf, 0, n)
            }
            cout.flush()
        } catch (e: Exception) {
        } finally {
            close(remote); close(client)
        }
    }
}
