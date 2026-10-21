package com.voltfetch.app

import android.content.Intent
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.voltfetch.engine.MAIN_QUEUE
import com.voltfetch.engine.Status
import com.voltfetch.engine.YouTube
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Fully on-device media downloader. yt-dlp and FFmpeg are bundled as Android libraries;
 * this screen never contacts a VoltFetch backend or a companion computer.
 * Handles single videos, Shorts and whole playlists (one queue item per video).
 */
class MediaActivity : Screen() {
    override val screenTitle = "Video & audio downloader"
    override val wantsBottomBar = false

    private lateinit var urlIn: EditText
    private lateinit var infoBox: LinearLayout
    private lateinit var formatsBox: LinearLayout
    private lateinit var audioCard: View
    private lateinit var playlistBox: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var inspectBtn: TextView
    private var queuePick: () -> String = { MAIN_QUEUE }
    @Volatile private var busy = false
    private var generation = 0
    @Volatile private var inspectId: String? = null
    private var inspectedUrl = ""
    private var videoTitle = ""

    private data class Entry(val url: String, val title: String)
    private var playlistTitle = ""
    private var playlistEntries: List<Entry> = emptyList()

    private val prefs get() = getSharedPreferences("media_ui", MODE_PRIVATE)
    private var withSubs: Boolean
        get() = prefs.getBoolean("subs", false)
        set(v) { prefs.edit().putBoolean("subs", v).apply() }

    /** Subtitle languages: the phone's language, plus English. */
    private val subLangs: String
        get() = listOf(Locale.getDefault().language, "en").filter { it.matches(Regex("[a-z]{2,3}")) }.distinct().joinToString(",")

    override fun content() {
        body.addView(k.text(
            "Downloads run directly on this phone using the embedded yt-dlp and FFmpeg engines. No PC, server, Termux, or separate downloader app is required. Download only videos you have the right to save.",
            13.5f, p.mute
        ).apply { setLineSpacing(0f, 1.25f); setPadding(dp(4), 0, dp(4), dp(8)) })

        body.addView(k.sectionTitle("YouTube link"))
        val initial = YouTube.find(intent.getStringExtra("url"))
            ?: YouTube.find(intent.getStringExtra(Intent.EXTRA_TEXT))
            ?: YouTube.find(intent.dataString) ?: ""
        urlIn = k.input("https://www.youtube.com/watch?v=… or a playlist link", initial).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ -> inspect(); true }
        }
        body.addView(urlIn)
        inspectBtn = k.button("Check link", "primary", R.drawable.ic_scan) { inspect() }
        body.addView(inspectBtn, lp(-1, dp(50)).apply { topMargin = dp(12) })

        if (engine.settings.queues.isNotEmpty()) {
            body.addView(k.sectionTitle("Add to queue"))
            val (chips, getter) = queueChips(MAIN_QUEUE)
            queuePick = getter
            body.addView(chips)
        }

        body.addView(k.sectionTitle("Video details"))
        infoBox = k.vbox(gap = dp(8))
        infoBox.addView(k.text("Paste a video, Short or playlist link, then check it to load the available qualities.", 12.5f, p.mute))
        body.addView(k.card(infoBox, dp(14)))

        playlistBox = k.vbox(gap = dp(10)).apply { visibility = View.GONE }
        body.addView(playlistBox)

        body.addView(k.sectionTitle("Video quality"))
        val subsRow = toggleRow("Add subtitles", "Embeds ${langNames(subLangs)} subtitles in the video when YouTube has them. Auto-generated captions are used if there are no others.", withSubs) { withSubs = it }
        body.addView(k.card(subsRow, dp(2)))
        formatsBox = k.vbox(gap = dp(8)).apply { setPadding(0, dp(10), 0, 0) }
        formatsBox.addView(k.text("Available resolutions appear after checking the link.", 12.5f, p.mute))
        body.addView(formatsBox)

        body.addView(k.sectionTitle("Extract MP3 audio"))
        body.addView(k.text("Cover art and title tags are added to the MP3. A higher bitrate increases file size but cannot restore detail absent from the source.", 12f, p.mute).apply { setLineSpacing(0f, 1.2f) })
        val rates = k.hbox(dp(8))
        for (rate in listOf(128, 192, 256, 320)) {
            rates.addView(k.button("$rate", "tonal", R.drawable.ic_cat_music) { enqueueSingle("youtube_audio", rate) }, lp(0, dp(44), 1f))
        }
        audioCard = k.card(rates, dp(2))
        body.addView(audioCard)

        body.addView(k.sectionTitle("Progress"))
        statusText = k.text("Ready. Paste a link and tap Check link.", 12.5f, p.mute).apply {
            setPadding(dp(14), dp(14), dp(14), dp(14)); setLineSpacing(0f, 1.2f)
        }
        body.addView(k.card(statusText, dp(2)))
        if (initial.isNotEmpty() && inspectedUrl != initial) inspect()
    }

    private fun langNames(codes: String): String = codes.split(',').joinToString(" and ") { Locale(it).getDisplayLanguage(Locale.getDefault()).ifBlank { it } }

    override fun onDestroy() {
        super.onDestroy()
        generation++
        inspectId?.let { id -> runCatching { YoutubeDL.getInstance().destroyProcessById(id) } }
    }

    // ------------------------------------------------------------------ checking a link

    private fun inspect() {
        val raw = YouTube.find(urlIn.text.toString()) ?: run { toast("Enter a YouTube video or playlist link"); return }
        if (urlIn.text.toString().trim() != raw) urlIn.setText(raw)
        if (busy) { toast("Still checking the previous link"); return }
        val videoId = YouTube.videoId(raw)
        val listId = YouTube.playlistId(raw)
        val token = ++generation
        busy = true; setBusy(true)
        inspectedUrl = ""; videoTitle = ""; playlistEntries = emptyList()
        playlistBox.visibility = View.GONE; playlistBox.removeAllViews()
        statusText.text = if (MediaEngine.ready) "Checking the link on this phone…" else "Starting the media engine (first use can take a little while)…"
        Thread {
            val video = if (videoId != null || listId == null) runCatching { dumpJson(raw, playlist = false) } else null
            val list = if (listId != null) runCatching { dumpJson(YouTube.playlistUrl(listId), playlist = true) } else null
            runOnUiThread {
                if (isFinishing || isDestroyed || token != generation) return@runOnUiThread
                busy = false; setBusy(false)
                video?.onSuccess { showVideo(raw, it) }
                list?.onSuccess { showPlaylist(it) }
                val err = video?.exceptionOrNull() ?: if (video == null) list?.exceptionOrNull() else null
                statusText.text = when {
                    err != null && playlistEntries.isNotEmpty() -> "Couldn't load this video (${friendlyError(err)}), but its playlist is shown above."
                    err != null -> "Couldn't check this link: ${friendlyError(err)}\n\nCheck your connection and try again. Some videos are unavailable because of age, region, account, or platform restrictions. If many links fail, update the media engine in Settings."
                    video == null -> "Playlist loaded. Choose how to download its videos."
                    playlistEntries.isNotEmpty() -> "Ready. Choose a quality for this video, or download the whole playlist below."
                    else -> "Ready. Choose a video quality or an MP3 bitrate."
                }
            }
        }.start()
    }

    private fun dumpJson(url: String, playlist: Boolean): JSONObject = MediaEngine.use(this) {
        val id = "voltfetch-inspect-${System.nanoTime()}"
        inspectId = id
        val request = YoutubeDLRequest(url).apply {
            addOption("--dump-single-json"); addOption("--skip-download"); addOption("--no-warnings")
            if (playlist) addOption("--flat-playlist") else addOption("--no-playlist")
            val s = engine.settings
            if (s.proxyOn && s.proxyHost.isNotBlank()) addOption("--proxy", "http://${s.proxyHost.trim()}:${s.proxyPort}")
        }
        try { JSONObject(YoutubeDL.getInstance().execute(request, id).out) } finally { inspectId = null }
    }

    private fun showVideo(url: String, json: JSONObject) {
        inspectedUrl = url
        videoTitle = json.optString("title", "YouTube video")
        val duration = json.optDouble("duration", 0.0).toLong()
        infoBox.removeAllViews()
        infoBox.addView(k.text(videoTitle, 16f, p.fg, Type.medium))
        infoBox.addView(k.text(if (duration > 0) "Duration: ${duration(duration)}" else "Duration unavailable", 11.5f, p.mute))
        val uploader = json.optString("uploader", "").ifBlank { json.optString("channel", "") }
        if (uploader.isNotBlank()) infoBox.addView(k.text("Channel: $uploader", 11.5f, p.mute))
        val subs = (json.optJSONObject("subtitles")?.keys()?.asSequence()?.toList().orEmpty() + json.optJSONObject("automatic_captions")?.keys()?.asSequence()?.toList().orEmpty())
        val mine = subLangs.split(',')
        val has = mine.filter { l -> subs.any { it == l || it.startsWith("$l-") } }
        infoBox.addView(k.text(if (has.isEmpty()) "Subtitles: none in ${langNames(subLangs)}" else "Subtitles: ${langNames(has.joinToString(","))} available", 11.5f, p.mute))
        renderFormats(json.optJSONArray("formats") ?: JSONArray(), duration)
    }

    private data class Quality(val height: Int, val width: Int, val ext: String, val fps: Int, val size: Long, val h264: Boolean)

    /** Films are often wider than 16:9 (e.g. 3840×1608), so name the class by width too. */
    private fun className(q: Quality): String = when {
        q.width >= 3800 || q.height >= 2100 -> " · 4K"
        q.width >= 2500 || q.height >= 1400 -> " · 2K"
        q.width >= 1900 || q.height >= 1060 -> " · Full HD"
        q.width >= 1260 || q.height >= 700 -> " · HD"
        else -> ""
    }

    private fun sizeOf(f: JSONObject, duration: Long): Long {
        val s = f.optLong("filesize", 0L).takeIf { it > 0 } ?: f.optLong("filesize_approx", 0L).takeIf { it > 0 }
        if (s != null) return s
        val tbr = f.optDouble("tbr", 0.0) // kbit/s
        return if (tbr > 0 && duration > 0) (tbr * 1000 / 8 * duration).toLong() else 0L
    }

    private fun renderFormats(formats: JSONArray, duration: Long) {
        val byHeight = sortedMapOf<Int, Quality>()
        var audioSize = 0L
        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            val vcodec = f.optString("vcodec", "none")
            val acodec = f.optString("acodec", "none")
            if (vcodec == "none" && acodec != "none") {
                // the audio track the downloader will pick (AAC first, as in the format sort)
                val sz = sizeOf(f, duration)
                if (sz > 0 && (audioSize == 0L || acodec.startsWith("mp4a"))) audioSize = sz
                continue
            }
            val height = f.optInt("height", 0)
            if (height <= 0 || vcodec == "none") continue
            val q = Quality(height, f.optInt("width", 0), f.optString("ext", "mp4"), f.optInt("fps", 0), sizeOf(f, duration), vcodec.startsWith("avc"))
            val old = byHeight[height]
            // mirror the download's preference: H.264 first, then the higher frame rate
            if (old == null || (q.h264 && !old.h264) || (q.h264 == old.h264 && q.fps > old.fps)) byHeight[height] = q
        }
        formatsBox.removeAllViews()
        if (byHeight.isEmpty()) {
            formatsBox.addView(k.text("No video formats were returned for this link.", 12f, p.mute)); return
        }
        byHeight.values.toList().asReversed().forEach { quality ->
            val label = "${quality.height}p" + className(quality) + if (quality.fps >= 50) " · ${quality.fps} fps" else ""
            val total = if (quality.size > 0) quality.size + audioSize else 0L
            val detail = buildString {
                append(if (quality.h264) "MP4 · H.264" else "MP4 · ${if (quality.ext == "webm") "VP9" else "AV1/VP9"}")
                append(if (total > 0) " · about ${formatBytes(total)} with audio" else " · size unknown")
            }
            val row = k.hbox(dp(12)).apply { setPadding(dp(14), dp(12), dp(12), dp(12)); gravity = Gravity.CENTER_VERTICAL }
            row.addView(k.tile("Video", 38))
            val tx = k.vbox(gap = dp(3))
            tx.addView(k.text(label, 14f, p.fg, Type.medium))
            tx.addView(k.text(detail, 11f, p.mute))
            row.addView(tx, lp(0, -2, 1f))
            row.addView(k.button("Get", "tonal", R.drawable.ic_down) { enqueueSingle("youtube_video", quality.height) }.apply { maxLines = 1; setPadding(dp(14), 0, dp(16), 0) }, LinearLayout.LayoutParams(-2, dp(44)))
            formatsBox.addView(k.card(row, dp(1)))
        }
    }

    private fun showPlaylist(json: JSONObject) {
        playlistTitle = json.optString("title", "Playlist")
        val entries = ArrayList<Entry>()
        val arr = json.optJSONArray("entries") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val title = e.optString("title", "")
            if (title == "[Private video]" || title == "[Deleted video]") continue
            val id = e.optString("id", "")
            val url = e.optString("url", "").takeIf { YouTube.isUrl(it) } ?: if (id.isNotBlank()) YouTube.watchUrl(id) else continue
            entries.add(Entry(url, title.ifBlank { id }))
        }
        playlistEntries = entries
        playlistBox.removeAllViews()
        playlistBox.visibility = View.VISIBLE
        playlistBox.addView(k.sectionTitle("Playlist"))
        val box = k.vbox(gap = dp(8))
        box.addView(k.text(playlistTitle, 16f, p.fg, Type.medium))
        box.addView(k.text(if (entries.isEmpty()) "No downloadable videos in this playlist." else "${entries.size} videos · each one is added to the queue separately", 11.5f, p.mute))
        for (e in entries.take(5)) box.addView(k.text("• ${e.title}", 12.5f, p.mute, lines = 1))
        if (entries.size > 5) box.addView(k.text("…and ${entries.size - 5} more", 12f, p.faint))
        if (inspectedUrl.isEmpty()) {
            // no single video in the link: hide the single-video controls
            infoBox.removeAllViews(); infoBox.addView(k.text("This is a playlist link. Use the playlist options above.", 12.5f, p.mute))
            formatsBox.removeAllViews(); formatsBox.addView(k.text("Pick a quality for the whole playlist above.", 12.5f, p.mute))
        }
        if (entries.isNotEmpty()) {
            var height = 1080
            var rate = 192
            box.addView(k.text("Video quality (best available up to)", 12.5f, p.mute, Type.medium).apply { setPadding(0, dp(6), 0, 0) })
            val (hs, hrow) = k.chipRow(); hrow.setPadding(0, 0, 0, 0)
            fun renderH() { hrow.removeAllViews(); for (h in listOf(2160, 1440, 1080, 720, 480, 360)) hrow.addView(k.chip("${h}p", h == height) { height = h; renderH() }) }
            renderH(); box.addView(hs)
            box.addView(k.button("Download ${entries.size} videos", "primary", R.drawable.ic_down) { enqueuePlaylist("youtube_video", height) }, lp(-1, dp(46)))
            box.addView(k.text("MP3 bitrate", 12.5f, p.mute, Type.medium).apply { setPadding(0, dp(6), 0, 0) })
            val (rs, rrow) = k.chipRow(); rrow.setPadding(0, 0, 0, 0)
            fun renderR() { rrow.removeAllViews(); for (r in listOf(128, 192, 256, 320)) rrow.addView(k.chip("$r kbps", r == rate) { rate = r; renderR() }) }
            renderR(); box.addView(rs)
            box.addView(k.button("Download ${entries.size} as MP3", "tonal", R.drawable.ic_cat_music) { enqueuePlaylist("youtube_audio", rate) }, lp(-1, dp(46)))
        }
        playlistBox.addView(k.card(box, dp(14)))
    }

    // ------------------------------------------------------------------ queueing

    private fun alreadyQueued(url: String, kind: String, option: Int): Boolean = engine.views().any {
        it.url == url && it.taskKind == kind && it.taskOption == option && it.status != Status.COMPLETE
    }

    /** Media tasks are ordinary Engine items: queued, persisted, notified, paused/resumed and removed centrally. */
    private fun enqueueSingle(kind: String, option: Int) {
        val raw = YouTube.find(urlIn.text.toString())
        if (raw == null || inspectedUrl != raw || videoTitle.isBlank()) { toast(if (playlistEntries.isNotEmpty()) "Use the playlist buttons for this link" else "Check this link first so its details are loaded"); return }
        if (alreadyQueued(raw, kind, option)) { toast("This one is already in your list"); return }
        runCatching {
            val subs = if (kind == "youtube_video" && withSubs) subLangs else ""
            val item = engine.addMediaTask(raw, videoTitle, kind, option, queue = queuePick(), subs = subs)
            DownloadService.ensureRunning(this, force = true)
            val description = if (kind == "youtube_audio") "MP3 · $option kbps" else "up to ${option}p video${if (subs.isNotEmpty()) " with subtitles" else ""}"
            statusText.text = "Added to the ${item.queue} queue: $description. Progress, pause/resume and notifications are managed with your other VoltFetch downloads."
            toast("Added to ${item.queue} queue")
        }.onFailure { toast("Could not queue media: ${friendlyError(it)}") }
    }

    private fun enqueuePlaylist(kind: String, option: Int) {
        val entries = playlistEntries
        if (entries.isEmpty()) return
        val subs = if (kind == "youtube_video" && withSubs) subLangs else ""
        var added = 0; var skipped = 0
        val q = queuePick()
        for (e in entries) {
            if (alreadyQueued(e.url, kind, option)) { skipped++; continue }
            if (runCatching { engine.addMediaTask(e.url, e.title, kind, option, queue = q, subs = subs, playlist = playlistTitle) }.isSuccess) added++ else skipped++
        }
        if (added > 0) DownloadService.ensureRunning(this, force = true)
        val what = if (kind == "youtube_audio") "MP3 · $option kbps" else "up to ${option}p"
        statusText.text = "Added $added videos from \"$playlistTitle\" to the $q queue ($what)." + if (skipped > 0) " $skipped skipped (already in your list or unavailable)." else ""
        toast("Added $added videos")
    }

    // ------------------------------------------------------------------ helpers

    private fun duration(sec: Long): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "size unknown"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024) else String.format(Locale.US, "%.0f MB", mb.coerceAtLeast(1.0))
    }

    private fun friendlyError(error: Throwable): String {
        val raw = error.message ?: error.javaClass.simpleName
        val line = raw.lines().map { it.trim() }.lastOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim() ?: raw
        return line.replace(Regex("\\s+"), " ").take(400)
    }

    private fun setBusy(isBusy: Boolean) {
        inspectBtn.isEnabled = !isBusy
        inspectBtn.alpha = if (isBusy) .55f else 1f
        inspectBtn.text = if (isBusy) "Checking…" else "Check link"
        formatsBox.alpha = if (isBusy) .65f else 1f
        audioCard.alpha = if (isBusy) .65f else 1f
    }
}
