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

/** What an embedded media job needs; a plain snapshot, so the handler never touches engine state. */
data class MediaTask(
    val id: Long, val url: String, val kind: String, val option: Int,
    /** Subtitle languages to fetch and embed ("" = none), e.g. "en" or "en,hi". */
    val subs: String,
    val category: String, val name: String,
)

/** Services the engine lends to a media job. proxyUrl routes traffic through the shared speed limit and the user's proxy. */
class MediaEnv(val proxyUrl: String?)

/** One progress report: overall percent across every stage, what is happening, and current speed/ETA. */
data class MediaProgress(val percent: Double, val stage: String, val speed: Double, val eta: Double)

/**
 * Turns yt-dlp's console output into one steadily rising percentage. yt-dlp reports 0–100% for each
 * stream separately (video, then audio) and is silent while merging or converting, so the bar would
 * otherwise jump back and stall at 100%.
 */
class MediaProgressTracker(private val kind: String) {
    private val video = kind == "youtube_video"
    private var streams = 0
    private var currentIsMedia = false
    private var overall = 0.0
    private var stage = "Starting"
    private var speed = 0.0
    private var eta = -1.0

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

    private fun snapshot() = MediaProgress(overall, stage, speed, eta)

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
            stage = when { !video -> "Downloading audio"; streams == 1 -> "Downloading video"; else -> "Downloading audio track" }
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
        return when {
            line.startsWith("[Merger]") -> post("Merging video and audio", 94.0)
            line.startsWith("[ExtractAudio]") -> post("Converting to MP3", 91.0)
            line.startsWith("[VideoRemuxer]") || line.startsWith("[VideoConvertor]") -> post("Converting", 95.0)
            line.startsWith("[Fixup") -> post("Finishing", 95.0)
            line.startsWith("[EmbedSubtitle]") -> post("Embedding subtitles", 96.0)
            line.startsWith("[ThumbnailsConvertor]") || line.startsWith("[EmbedThumbnail]") -> post("Adding cover art", 97.0)
            line.startsWith("[Metadata]") -> post("Writing tags", 98.0)
            line.startsWith("[youtube") || line.startsWith("[info]") -> { if (streams == 0) { stage = "Preparing"; snapshot() } else null }
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
