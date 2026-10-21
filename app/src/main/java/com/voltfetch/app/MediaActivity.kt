package com.voltfetch.app

import android.content.ClipboardManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.voltfetch.engine.Links
import com.voltfetch.engine.MAIN_QUEUE
import com.voltfetch.engine.MediaKinds
import com.voltfetch.engine.MediaOpts
import com.voltfetch.engine.MediaSites
import com.voltfetch.engine.Status
import com.voltfetch.engine.YouTube
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Fully on-device media downloader for YouTube, Instagram, Facebook, X, Vimeo and the many other sites
 * yt-dlp supports. yt-dlp and FFmpeg are bundled; nothing goes through a VoltFetch server.
 * Single videos (with optional clip), whole playlists (one queue item per video) and followed playlists.
 */
class MediaActivity : Screen() {
    override val screenTitle = "Video & audio downloader"
    override val wantsBottomBar = false

    private lateinit var urlIn: EditText
    private lateinit var infoBox: LinearLayout
    private lateinit var formatsBox: LinearLayout
    private lateinit var audioCard: View
    private lateinit var playlistBox: LinearLayout
    private lateinit var sbRow: View
    private lateinit var loginText: TextView
    private lateinit var clipBox: View
    private lateinit var clipFrom: EditText
    private lateinit var clipTo: EditText
    private lateinit var statusText: TextView
    private lateinit var inspectBtn: TextView
    private var queuePick: () -> String = { MAIN_QUEUE }
    @Volatile private var busy = false
    private var generation = 0
    @Volatile private var inspectId: String? = null
    private var inspectedUrl = ""
    private var videoTitle = ""
    private var videoThumb = ""
    private var videoDuration = 0L
    private val ui = Handler(Looper.getMainLooper())
    private var autoChecked = ""
    private var clipTried = false
    /** A pasted link is checked by itself shortly after it lands in the box. */
    private val autoCheck = Runnable {
        val u = currentUrl()
        if (u != null && !busy && u != inspectedUrl && !isFinishing) inspect()
    }

    private data class Entry(val key: String, val url: String, val title: String, val thumb: String)
    private var playlistTitle = ""
    private var playlistUrl = ""
    private var playlistEntries: List<Entry> = emptyList()

    private val prefs get() = getSharedPreferences("media_ui", MODE_PRIVATE)
    private fun pref(k: String, d: Boolean) = prefs.getBoolean(k, d)
    private fun setPref(k: String, v: Boolean) = prefs.edit().putBoolean(k, v).apply()

    /** Subtitle languages: the phone's language, plus English. */
    private val subLangs: String
        get() = listOf(Locale.getDefault().language, "en").filter { it.matches(Regex("[a-z]{2,3}")) }.distinct().joinToString(",")

    override fun content() {
        body.addView(k.text(
            "Works with YouTube, Instagram, Facebook, X, Vimeo, Reddit, SoundCloud and many more sites. Everything runs on this phone with the built-in yt-dlp and FFmpeg. Download only what you have the right to save.",
            13.5f, p.mute
        ).apply { setLineSpacing(0f, 1.25f); setPadding(dp(4), 0, dp(4), dp(8)) })

        body.addView(k.sectionTitle("Link"))
        val initial = MediaSites.find(intent.getStringExtra("url"))
            ?: MediaSites.find(intent.getStringExtra(Intent.EXTRA_TEXT))
            ?: MediaSites.find(intent.dataString) ?: ""
        urlIn = k.input("Paste a video, post or playlist link", initial).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ -> inspect(); true }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    ui.removeCallbacks(autoCheck)
                    val text = s?.toString()
                    val u = MediaSites.find(text) ?: return
                    if (u != autoChecked && u != inspectedUrl && Links.find(text).isNotEmpty()) ui.postDelayed(autoCheck, 700)
                }
            })
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

        body.addView(k.sectionTitle("Details"))
        infoBox = k.vbox(gap = dp(8))
        infoBox.addView(k.text("Paste a link, then check it to load the available qualities.", 12.5f, p.mute))
        body.addView(k.card(infoBox, dp(14)))

        playlistBox = k.vbox(gap = dp(10)).apply { visibility = View.GONE }
        body.addView(playlistBox)

        // options shared by single videos and playlists
        body.addView(k.sectionTitle("Options"))
        val opts = k.vbox()
        opts.addView(toggleRow("Add subtitles", "Embeds ${langNames(subLangs)} subtitles in videos when the site has them. Auto-generated captions are used if there are no others.", pref("subs", false)) { setPref("subs", it) })
        sbRow = k.vbox().apply {
            addView(k.divider())
            addView(toggleRow("Cut sponsor and non-music parts", "Uses SponsorBlock, a free community database, to remove sponsor segments, self-promotion, \"subscribe\" reminders and non-music parts of music videos. YouTube only.", pref("sb", false)) { setPref("sb", it) })
            visibility = View.GONE
        }
        opts.addView(sbRow)
        opts.addView(k.divider())
        opts.addView(toggleRow("Use my browser login", "For sites that only show videos to signed-in users (Instagram, Facebook, X…). Sign in once in VoltFetch's Browser tab; only that site's login is used, and never stored with the download.", pref("ck", true)) { setPref("ck", it); updateLoginText() })
        loginText = k.text("", 12f, p.faint).apply { setPadding(dp(14), 0, dp(14), dp(12)); setLineSpacing(0f, 1.15f) }
        opts.addView(loginText)
        body.addView(k.card(opts, dp(2)))

        // clip: start/end time for a single video
        val clipInner = k.vbox(gap = dp(8)).apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
        clipInner.addView(k.text("Download only part (optional)", 15f, p.fg, Type.medium))
        clipInner.addView(k.text("Enter a start and end time like 1:20 and 2:45. Leave both empty for the whole video. Applies to single videos.", 12.5f, p.mute).apply { setLineSpacing(0f, 1.15f) })
        val times = k.hbox(dp(10))
        clipFrom = k.input("Start 0:00").apply { inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME }
        clipTo = k.input("End").apply { inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME }
        times.addView(clipFrom, lp(0, -2, 1f)); times.addView(clipTo, lp(0, -2, 1f))
        clipInner.addView(times)
        clipBox = k.card(clipInner, dp(2)).also { it.layoutParams = lp().apply { topMargin = dp(10) } }
        body.addView(clipBox)

        body.addView(k.sectionTitle("Video quality"))
        formatsBox = k.vbox(gap = dp(8))
        formatsBox.addView(k.text("Available resolutions appear after checking the link.", 12.5f, p.mute))
        body.addView(formatsBox)

        body.addView(k.sectionTitle("Audio only"))
        body.addView(k.text("Original keeps the site's own audio track without re-encoding: best quality, smallest file (usually M4A). MP3 plays everywhere; cover art and title tags are added to both.", 12f, p.mute).apply { setLineSpacing(0f, 1.2f) })
        val audio = k.vbox(gap = dp(8))
        audio.addView(k.button("Original audio (no re-encoding)", "tonal", R.drawable.ic_cat_music) { enqueueSingle(MediaKinds.AUDIO, 0) }, lp(-1, dp(46)))
        val rates = k.hbox(dp(8))
        for (rate in listOf(128, 192, 256, 320)) {
            rates.addView(k.button("MP3 $rate", "ghost") { enqueueSingle(MediaKinds.MP3, rate) }.apply { maxLines = 1; setPadding(dp(6), 0, dp(6), 0) }, lp(0, dp(44), 1f))
        }
        audio.addView(rates)
        audioCard = k.card(audio, dp(10))
        body.addView(audioCard)

        body.addView(k.sectionTitle("Progress"))
        statusText = k.text("Ready. Paste a link and tap Check link.", 12.5f, p.mute).apply {
            setPadding(dp(14), dp(14), dp(14), dp(14)); setLineSpacing(0f, 1.2f)
        }
        body.addView(k.card(statusText, dp(2)))
        updateLoginText()
        if (initial.isNotEmpty() && inspectedUrl != initial) inspect()
    }

    private fun langNames(codes: String): String = codes.split(',').joinToString(" and ") { Locale(it).getDisplayLanguage(Locale.getDefault()).ifBlank { it } }

    private fun currentUrl(): String? = MediaSites.find(urlIn.text.toString())

    private fun updateLoginText() {
        val url = currentUrl()
        sbRow.visibility = if (url != null && YouTube.isUrl(url)) View.VISIBLE else View.GONE
        loginText.text = when {
            !pref("ck", true) -> "Off: downloads are made without a login."
            url == null -> "Check a link to see whether VoltFetch's browser is signed in to that site."
            BrowserCookies.hasLogin(url) -> "VoltFetch's browser has a session for ${MediaSites.label(url)}; it will be used if the site needs it."
            else -> "VoltFetch's browser has no session for ${MediaSites.label(url)}. If downloads fail with a login error, sign in there in the Browser tab."
        }
    }

    /** Opened with an empty box and a video link on the clipboard: fill it in (it's then checked by itself). */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || clipTried || !::urlIn.isInitialized) return
        clipTried = true
        if (urlIn.text.toString().isNotBlank()) return
        val clip = runCatching { getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()
        val url = Links.find(clip).firstOrNull { MediaSites.isKnown(it) && !Links.looksLikeFile(it) } ?: return
        urlIn.setText(url)
        urlIn.setSelection(url.length)
        toast("Pasted the link you copied")
    }

    /** Remembered for next time and for "Save video" / "Save audio" in the share menu. */
    private fun remember(kind: String, option: Int) {
        val e = prefs.edit()
        when (kind) {
            MediaKinds.VIDEO -> e.putInt("lastHeight", option)
            MediaKinds.MP3 -> { e.putString("lastAudioKind", kind); e.putInt("lastRate", option) }
            MediaKinds.AUDIO -> e.putString("lastAudioKind", kind)
        }
        e.apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(autoCheck)
        generation++
        inspectId?.let { id -> runCatching { YoutubeDL.getInstance().destroyProcessById(id) } }
    }

    // ------------------------------------------------------------------ checking a link

    private fun inspect() {
        val raw = currentUrl() ?: run { toast("Enter a web link (YouTube, Instagram, Facebook, X, Vimeo…)"); return }
        if (urlIn.text.toString().trim() != raw) urlIn.setText(raw)
        if (busy) { toast("Still checking the previous link"); return }
        autoChecked = raw
        ui.removeCallbacks(autoCheck)
        val yt = YouTube.isUrl(raw)
        val videoId = if (yt) YouTube.videoId(raw) else null
        val listId = if (yt) YouTube.playlistId(raw) else null
        val token = ++generation
        busy = true; setBusy(true)
        inspectedUrl = ""; videoTitle = ""; videoThumb = ""; videoDuration = 0; playlistEntries = emptyList(); playlistUrl = ""
        playlistBox.visibility = View.GONE; playlistBox.removeAllViews()
        updateLoginText()
        statusText.text = if (MediaEngine.ready) "Checking the link on this phone…" else "Starting the media engine (first use can take a little while)…"
        val useLogin = pref("ck", true)
        Thread {
            // YouTube: the video and its playlist separately. Other sites: one look, which says whether it's a playlist.
            val first = if (!yt || videoId != null || listId == null) runCatching { dumpJson(raw, if (yt) "video" else "auto", useLogin) } else null
            val list = if (listId != null) runCatching { dumpJson(YouTube.playlistUrl(listId), "playlist", useLogin) } else null
            runOnUiThread {
                if (isFinishing || isDestroyed || token != generation) return@runOnUiThread
                busy = false; setBusy(false)
                first?.onSuccess { json ->
                    if (json.optString("_type") == "playlist") showPlaylist(json, raw) else showVideo(raw, json)
                }
                list?.onSuccess { showPlaylist(it, YouTube.playlistUrl(listId!!)) }
                val err = first?.exceptionOrNull() ?: if (first == null) list?.exceptionOrNull() else null
                statusText.text = when {
                    err != null && playlistEntries.isNotEmpty() -> "Couldn't load this video (${friendlyError(err)}), but its playlist is shown above."
                    err != null -> "Couldn't check this link: ${friendlyError(err)}\n\n" + hintFor(err)
                    inspectedUrl.isEmpty() -> "Playlist loaded. Choose how to download its videos."
                    playlistEntries.isNotEmpty() -> "Ready. Choose a quality for this video, or download the whole playlist above."
                    else -> "Ready. Choose a video quality or an audio option."
                }
            }
        }.start()
    }

    private fun hintFor(err: Throwable): String {
        val m = err.message ?: ""
        return when {
            m.contains("Unsupported URL", true) -> "The media engine doesn't support this site. For a direct file link, use the normal downloader (+)."
            Regex("login|log in|cookies|private|rate-limit", RegexOption.IGNORE_CASE).containsMatchIn(m) -> "This site wants a login. Sign in to it in VoltFetch's Browser tab, keep \"Use my browser login\" on, and check the link again."
            else -> "Check your connection and try again. If many links fail, update the media engine in Settings."
        }
    }

    /** mode: "video" (single item), "playlist" (entries only, fast) or "auto" (whatever the link is). */
    private fun dumpJson(url: String, mode: String, useLogin: Boolean): JSONObject = MediaEngine.use(this) {
        val id = "voltfetch-inspect-${System.nanoTime()}"
        inspectId = id
        val cookies = File(cacheDir, "inspect-cookies-${System.nanoTime()}.txt")
        val request = YoutubeDLRequest(url).apply {
            addOption("--dump-single-json"); addOption("--skip-download"); addOption("--no-warnings")
            when (mode) { "video" -> addOption("--no-playlist"); else -> addOption("--flat-playlist") }
            val s = engine.settings
            if (s.proxyOn && s.proxyHost.isNotBlank()) addOption("--proxy", "http://${s.proxyHost.trim()}:${s.proxyPort}")
            if (useLogin && BrowserCookies.writeNetscape(url, cookies)) addOption("--cookies", cookies.absolutePath)
        }
        try { JSONObject(YoutubeDL.getInstance().execute(request, id).out) } finally { inspectId = null; runCatching { cookies.delete() } }
    }

    private fun thumbOf(json: JSONObject): String {
        json.optString("thumbnail", "").takeIf { MediaSites.isWeb(it) }?.let { return it }
        val arr = json.optJSONArray("thumbnails") ?: return ""
        for (i in arr.length() - 1 downTo 0) arr.optJSONObject(i)?.optString("url", "")?.takeIf { MediaSites.isWeb(it) }?.let { return it }
        return ""
    }

    private fun showVideo(url: String, json: JSONObject) {
        inspectedUrl = url
        videoTitle = json.optString("title", "").ifBlank { json.optString("description", "").lineSequence().firstOrNull().orEmpty().take(80) }.ifBlank { "${MediaSites.label(url)} video" }
        videoThumb = thumbOf(json)
        videoDuration = json.optDouble("duration", 0.0).toLong()
        infoBox.removeAllViews()
        infoBox.addView(k.text(videoTitle, 16f, p.fg, Type.medium))
        infoBox.addView(k.text("${MediaSites.label(url)} · " + if (videoDuration > 0) "Duration ${MediaOpts.clock(videoDuration.toInt())}" else "duration unknown", 11.5f, p.mute))
        val uploader = json.optString("uploader", "").ifBlank { json.optString("channel", "") }
        if (uploader.isNotBlank()) infoBox.addView(k.text("By $uploader", 11.5f, p.mute))
        val subs = (json.optJSONObject("subtitles")?.keys()?.asSequence()?.toList().orEmpty() + json.optJSONObject("automatic_captions")?.keys()?.asSequence()?.toList().orEmpty())
        val has = subLangs.split(',').filter { l -> subs.any { it == l || it.startsWith("$l-") } }
        infoBox.addView(k.text(if (has.isEmpty()) "Subtitles: none in ${langNames(subLangs)}" else "Subtitles: ${langNames(has.joinToString(","))} available", 11.5f, p.mute))
        if (videoDuration > 0) clipTo.hint = "End ${MediaOpts.clock(videoDuration.toInt())}"
        renderFormats(json.optJSONArray("formats") ?: JSONArray(), videoDuration)
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
            val separateAudio = acodec == "none"
            val q = Quality(height, f.optInt("width", 0), f.optString("ext", "mp4"), f.optInt("fps", 0), sizeOf(f, duration).let { if (separateAudio) it else -it }, vcodec.startsWith("avc") || vcodec.startsWith("h264"))
            val old = byHeight[height]
            // mirror the download's preference: H.264 first, then the higher frame rate
            if (old == null || (q.h264 && !old.h264) || (q.h264 == old.h264 && q.fps > old.fps)) byHeight[height] = q
        }
        formatsBox.removeAllViews()
        if (byHeight.isEmpty()) {
            // some sites don't list heights: offer the best they have
            formatsBox.addView(qualityRow("Best available", "The site didn't list its qualities") { enqueueSingle(MediaKinds.VIDEO, 4320) })
            return
        }
        byHeight.values.toList().asReversed().forEach { quality ->
            val label = "${quality.height}p" + className(quality) + if (quality.fps >= 50) " · ${quality.fps} fps" else ""
            // a negative size marks a format that already contains audio
            val total = when { quality.size > 0 -> quality.size + audioSize; quality.size < 0 -> -quality.size; else -> 0L }
            val detail = buildString {
                append(if (quality.h264) "MP4 · H.264" else "MP4 · ${if (quality.ext == "webm") "VP9" else "AV1/VP9"}")
                append(if (total > 0) " · about ${formatBytes(total)} with audio" else " · size unknown")
            }
            formatsBox.addView(qualityRow(label, detail) { enqueueSingle(MediaKinds.VIDEO, quality.height.coerceIn(144, 4320)) })
        }
    }

    private fun qualityRow(label: String, detail: String, onGet: () -> Unit): View {
        val row = k.hbox(dp(12)).apply { setPadding(dp(14), dp(12), dp(12), dp(12)); gravity = Gravity.CENTER_VERTICAL }
        row.addView(k.tile("Video", 38))
        val tx = k.vbox(gap = dp(3))
        tx.addView(k.text(label, 14f, p.fg, Type.medium))
        tx.addView(k.text(detail, 11f, p.mute))
        row.addView(tx, lp(0, -2, 1f))
        row.addView(k.button("Get", "tonal", R.drawable.ic_down) { onGet() }.apply { maxLines = 1; setPadding(dp(14), 0, dp(16), 0) }, LinearLayout.LayoutParams(-2, dp(44)))
        return k.card(row, dp(1))
    }

    private fun showPlaylist(json: JSONObject, url: String) {
        playlistTitle = json.optString("title", "").ifBlank { "Playlist" }
        playlistUrl = url
        val ytList = YouTube.isUrl(url)
        val entries = ArrayList<Entry>()
        val arr = json.optJSONArray("entries") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val title = e.optString("title", "")
            if (title == "[Private video]" || title == "[Deleted video]") continue
            val id = e.optString("id", "")
            val entryUrl = e.optString("url", "").takeIf { MediaSites.isWeb(it) }
                ?: e.optString("webpage_url", "").takeIf { MediaSites.isWeb(it) }
                ?: if (ytList && id.isNotBlank()) YouTube.watchUrl(id) else continue
            entries.add(Entry(id.ifBlank { entryUrl }, entryUrl, title.ifBlank { id.ifBlank { "Item ${i + 1}" } }, thumbOf(e)))
        }
        playlistEntries = entries
        playlistBox.removeAllViews()
        playlistBox.visibility = View.VISIBLE
        playlistBox.addView(k.sectionTitle("Playlist"))
        val box = k.vbox(gap = dp(8))
        box.addView(k.text(playlistTitle, 16f, p.fg, Type.medium))
        box.addView(k.text(if (entries.isEmpty()) "No downloadable items in this playlist." else "${entries.size} items · each one is added to the queue separately", 11.5f, p.mute))
        for (e in entries.take(5)) box.addView(k.text("• ${e.title}", 12.5f, p.mute, lines = 1))
        if (entries.size > 5) box.addView(k.text("…and ${entries.size - 5} more", 12f, p.faint))
        if (inspectedUrl.isEmpty()) {
            infoBox.removeAllViews(); infoBox.addView(k.text("This is a playlist link. Use the playlist options above.", 12.5f, p.mute))
            formatsBox.removeAllViews(); formatsBox.addView(k.text("Pick a quality for the whole playlist above.", 12.5f, p.mute))
        }
        if (entries.isEmpty()) { playlistBox.addView(k.card(box, dp(14))); return }

        var kind = MediaKinds.VIDEO
        var height = prefs.getInt("lastHeight", 1080).let { h -> listOf(2160, 1440, 1080, 720, 480, 360).firstOrNull { it <= h } ?: 360 }
        var rate = prefs.getInt("lastRate", 192).let { r -> listOf(128, 192, 256, 320).firstOrNull { it == r } ?: 192 }
        var keepSynced = PlaylistSync.isFollowed(this, url)
        val optionRow = k.vbox(gap = dp(8))
        val download = k.button("", "primary", R.drawable.ic_down) {}
        fun renderOptions() {
            optionRow.removeAllViews()
            val (ks, krow) = k.chipRow(); krow.setPadding(0, 0, 0, 0)
            for ((k2, label) in listOf(MediaKinds.VIDEO to "Video", MediaKinds.MP3 to "MP3", MediaKinds.AUDIO to "Original audio")) krow.addView(k.chip(label, k2 == kind) { kind = k2; renderOptions() })
            optionRow.addView(ks)
            when (kind) {
                MediaKinds.VIDEO -> {
                    optionRow.addView(k.text("Best available up to", 12.5f, p.mute, Type.medium))
                    val (hs, hrow) = k.chipRow(); hrow.setPadding(0, 0, 0, 0)
                    for (h in listOf(2160, 1440, 1080, 720, 480, 360)) hrow.addView(k.chip("${h}p", h == height) { height = h; renderOptions() })
                    optionRow.addView(hs)
                }
                MediaKinds.MP3 -> {
                    val (rs, rrow) = k.chipRow(); rrow.setPadding(0, 0, 0, 0)
                    for (r in listOf(128, 192, 256, 320)) rrow.addView(k.chip("$r kbps", r == rate) { rate = r; renderOptions() })
                    optionRow.addView(rs)
                }
            }
            download.text = when (kind) { MediaKinds.MP3 -> "Download ${entries.size} as MP3"; MediaKinds.AUDIO -> "Download ${entries.size} as original audio"; else -> "Download ${entries.size} videos" }
        }
        fun option() = when (kind) { MediaKinds.VIDEO -> height; MediaKinds.MP3 -> rate; else -> 0 }
        renderOptions()
        box.addView(optionRow)
        box.addView(toggleRow("Keep in sync", "Every night, videos added to this playlist later are downloaded the same way.", keepSynced) { keepSynced = it })
        download.setOnClickListener { haptic(download, true); enqueuePlaylist(kind, option(), keepSynced) }
        box.addView(download, lp(-1, dp(48)))
        box.addView(k.button("Only follow: new videos from now on", "ghost", R.drawable.ic_calendar) { followOnly(kind, option()) }, lp(-1, dp(46)))
        playlistBox.addView(k.card(box, dp(14)))
    }

    // ------------------------------------------------------------------ queueing

    private fun alreadyQueued(url: String, kind: String, option: Int): Boolean = engine.views().any {
        it.url == url && it.taskKind == kind && it.taskOption == option && it.status != Status.COMPLETE
    }

    /** Options from the switches; the clip only for single videos. Null (with a message) when the clip times are wrong. */
    private fun chosenOpts(single: Boolean): Map<String, String>? {
        val m = HashMap<String, String>()
        val url = currentUrl()
        if (pref("sb", false) && url != null && YouTube.isUrl(url)) m["sb"] = "1"
        if (pref("ck", true)) m["ck"] = "1"
        if (single) {
            val a = clipFrom.text.toString().trim(); val b = clipTo.text.toString().trim()
            if (a.isNotEmpty() || b.isNotEmpty()) {
                val from = if (a.isEmpty()) 0 else MediaOpts.parseClock(a) ?: run { toast("Start time should look like 1:20"); return null }
                val to = if (b.isEmpty()) videoDuration.toInt().takeIf { it > 0 } ?: run { toast("Enter an end time"); return null }
                    else MediaOpts.parseClock(b) ?: run { toast("End time should look like 2:45"); return null }
                if (to <= from) { toast("The end must be after the start"); return null }
                if (videoDuration > 0 && from >= videoDuration) { toast("The start is after the end of the video"); return null }
                m["from"] = "$from"; m["to"] = "${if (videoDuration > 0) minOf(to.toLong(), videoDuration).toInt() else to}"
            }
        }
        return m
    }

    /** Media tasks are ordinary Engine items: queued, persisted, notified, paused/resumed and removed centrally. */
    private fun enqueueSingle(kind: String, option: Int) {
        val raw = currentUrl()
        if (raw == null || inspectedUrl != raw || videoTitle.isBlank()) { toast(if (playlistEntries.isNotEmpty()) "Use the playlist buttons for this link" else "Check this link first so its details are loaded"); return }
        val opts = chosenOpts(single = true) ?: return
        if (MediaOpts.clip(opts) == null && alreadyQueued(raw, kind, option)) { toast("This one is already in your list"); return }
        runCatching {
            val subs = if (kind == MediaKinds.VIDEO && pref("subs", false)) subLangs else ""
            val item = engine.addMediaTask(raw, videoTitle, kind, option, queue = queuePick(), subs = subs, opts = opts, thumb = videoThumb)
            remember(kind, option)
            DownloadService.ensureRunning(this, force = true)
            val description = when (kind) { MediaKinds.MP3 -> "MP3 · $option kbps"; MediaKinds.AUDIO -> "original audio"; else -> "video up to ${option}p${if (subs.isNotEmpty()) " with subtitles" else ""}" } +
                (MediaOpts.clip(opts)?.let { " · clip ${MediaOpts.clock(it.first)}–${MediaOpts.clock(it.second)}" } ?: "")
            statusText.text = "Added to the ${item.queue} queue: $description. Progress, pause/resume and notifications are managed with your other VoltFetch downloads."
            toast("Added to ${item.queue} queue")
        }.onFailure { toast("Could not queue media: ${friendlyError(it)}") }
    }

    private fun enqueuePlaylist(kind: String, option: Int, follow: Boolean) {
        val entries = playlistEntries
        if (entries.isEmpty()) return
        val opts = chosenOpts(single = false) ?: return
        val subs = if (kind == MediaKinds.VIDEO && pref("subs", false)) subLangs else ""
        var added = 0; var skipped = 0
        val q = queuePick()
        for (e in entries) {
            if (alreadyQueued(e.url, kind, option)) { skipped++; continue }
            if (runCatching { engine.addMediaTask(e.url, e.title, kind, option, queue = q, subs = subs, playlist = playlistTitle, opts = opts, thumb = e.thumb) }.isSuccess) added++ else skipped++
        }
        if (added > 0) DownloadService.ensureRunning(this, force = true)
        remember(kind, option)
        if (follow) PlaylistSync.follow(this, playlistUrl, playlistTitle, kind, option, subs, opts, q, entries.map { it.key })
        val what = when (kind) { MediaKinds.MP3 -> "MP3 · $option kbps"; MediaKinds.AUDIO -> "original audio"; else -> "up to ${option}p" }
        statusText.text = "Added $added items from \"$playlistTitle\" to the $q queue ($what)." +
            (if (skipped > 0) " $skipped skipped (already in your list or unavailable)." else "") +
            (if (follow) " Following: new videos are added every night." else "")
        toast("Added $added items")
    }

    private fun followOnly(kind: String, option: Int) {
        if (playlistUrl.isEmpty()) return
        val opts = chosenOpts(single = false) ?: return
        val subs = if (kind == MediaKinds.VIDEO && pref("subs", false)) subLangs else ""
        PlaylistSync.follow(this, playlistUrl, playlistTitle, kind, option, subs, opts, queuePick(), playlistEntries.map { it.key })
        statusText.text = "Following \"$playlistTitle\". The ${playlistEntries.size} current items are skipped; videos added from now on are downloaded every night. Manage followed playlists in Settings."
        toast("Following playlist")
    }

    // ------------------------------------------------------------------ helpers

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
