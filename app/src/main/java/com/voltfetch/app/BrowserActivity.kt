package com.voltfetch.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ContextMenu
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.voltfetch.engine.Links
import com.voltfetch.engine.Names
import com.voltfetch.engine.NoCapture

/**
 * A browser for finding files. It notices video and audio a page loads, offers "Download with VoltFetch"
 * on long-pressed links, and hands browser downloads (with the site's cookies) to VoltFetch.
 */
class BrowserActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var k: Kit
    private val engine get() = VoltApp.engine(this)
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var bar: LinearLayout
    private lateinit var address: EditText
    private lateinit var lock: ImageView
    private lateinit var reload: ImageView
    private lateinit var progress: View
    private lateinit var home: ScrollView
    private lateinit var pill: TextView
    private var insetTop = 0
    private var insetBottom = 0

    private class Media(val url: String, val kind: String, val page: String, val title: String)
    private val media = LinkedHashMap<String, Media>()
    private var pageUrl = ""
    private var pageTitle = ""
    private var loading = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Palette.of(this); k = Kit(this, p)
        applyBars(p)
        root = FrameLayout(this).apply { setBackgroundColor(p.ink) }

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mediaPlaybackRequiresUserGesture = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            if (android.os.Build.VERSION.SDK_INT >= 29) settings.forceDark = if (p.dark) WebSettings.FORCE_DARK_AUTO else WebSettings.FORCE_DARK_OFF
            setBackgroundColor(p.ink)
            webViewClient = Client()
            webChromeClient = Chrome()
            setDownloadListener { url, ua, cd, mime, len ->
                if (blocked(url)) { notAllowed(); return@setDownloadListener }
                // the server's own name if it sent one; Android's guess is only kept when it means something
                val guess = URLUtil.guessFileName(url, cd, mime)
                val n = Names.best(Names.fromDisposition(cd), guess)
                openAdd(url, if (Names.isGeneric(n)) "" else n, len, mime, ua)
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        registerForContextMenu(web)
        root.addView(web, FrameLayout.LayoutParams(-1, -1))

        home = buildHome()
        root.addView(home, FrameLayout.LayoutParams(-1, -1))

        bar = buildBar()
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        progress = View(this).apply { background = gradient(0f, p.violet, p.accent, p.tide, orientation = android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT); pivotX = 0f; scaleX = 0f }
        root.addView(progress, FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP))

        pill = k.text("", 14f, Color.WHITE, Type.medium).apply {
            gravity = Gravity.CENTER; setPadding(dp(18), 0, dp(20), 0); minHeight = dp(50)
            elevation = dpf(10f); visibility = View.GONE
            setOnClickListener { haptic(this, true); if (isYouTubeUrl(pageUrl)) startActivity(Intent(this@BrowserActivity, MediaActivity::class.java).putExtra("url", pageUrl)) else showMedia() }
            pressable()
        }
        root.addView(pill, FrameLayout.LayoutParams(-2, dp(50), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        setContentView(root)
        root.onInsets { top, bottom ->
            insetTop = top; insetBottom = bottom
            bar.setPadding(dp(10), top + dp(6), dp(10), dp(8))
            web.setPadding(0, 0, 0, 0)
            (web.layoutParams as FrameLayout.LayoutParams).apply { topMargin = top + dp(62); bottomMargin = bottom }; web.requestLayout()
            (progress.layoutParams as FrameLayout.LayoutParams).topMargin = top + dp(60); progress.requestLayout()
            home.setPadding(0, top + dp(70), 0, bottom + dp(24))
            (pill.layoutParams as FrameLayout.LayoutParams).bottomMargin = bottom + dp(20); pill.requestLayout()
        }

        val start = intent?.dataString ?: Links.find(intent?.getStringExtra(Intent.EXTRA_TEXT)).firstOrNull()
        if (start != null) go(start) else showHome()
    }

    // ------------------------------------------------------------------ chrome

    private fun buildBar(): LinearLayout {
        val b = k.hbox(dp(8))
        b.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(p.ink, alpha(p.ink, .96f)))
        b.addView(k.orb(R.drawable.ic_back, "Back") { back() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val field = k.hbox(dp(6)).apply { setPadding(dp(12), 0, dp(4), 0); background = glass(p, dpf(22f), this@BrowserActivity) }
        lock = k.icon(R.drawable.ic_lock, p.done, 15)
        field.addView(lock)
        address = EditText(this).apply {
            hint = "Search or type an address"; setHintTextColor(p.faint); setTextColor(p.fg); textSize = 14.5f; background = null
            isSingleLine = true; setSelectAllOnFocus(true); setPadding(dp(4), 0, dp(4), 0)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> go(text.toString()); true }
            setOnFocusChangeListener { _, f -> if (f) setText(pageUrl.ifEmpty { "" }) else showAddress() }
        }
        field.addView(address, lp(0, dp(44), 1f))
        reload = k.iconButton(R.drawable.ic_refresh, "Reload") { if (loading) web.stopLoading() else if (pageUrl.isNotEmpty()) web.reload() }
        field.addView(reload, LinearLayout.LayoutParams(dp(40), dp(40)))
        b.addView(field, lp(0, dp(46), 1f))
        b.addView(k.orb(R.drawable.ic_more, "More") { menu() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        return b
    }

    private fun showAddress() {
        val host = runCatching { Uri.parse(pageUrl).host ?: "" }.getOrDefault("").removePrefix("www.")
        address.setText(host)
        lock.visibility = if (pageUrl.isEmpty()) View.GONE else View.VISIBLE
        lock.setImageResource(if (pageUrl.startsWith("https://")) R.drawable.ic_lock else R.drawable.ic_alert)
        lock.imageTintList = android.content.res.ColorStateList.valueOf(if (pageUrl.startsWith("https://")) p.done else p.hold)
    }

    private fun buildHome(): ScrollView {
        val box = k.vbox(gap = dp(14)).apply { setPadding(dp(20), dp(10), dp(20), dp(10)); gravity = Gravity.CENTER_HORIZONTAL }
        val aur = AuroraView(this, p).apply { animate = engine.settings.motion }
        val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val frame = FrameLayout(this)
        frame.addView(aur, FrameLayout.LayoutParams(-1, -1))
        frame.addView(box, FrameLayout.LayoutParams(-1, -2))
        sv.addView(frame)
        sv.isFillViewport = true
        box.addView(ImageView(this).apply { setImageResource(R.drawable.logo_symbol) }, LinearLayout.LayoutParams(dp(150), dp(106)).apply { topMargin = dp(30) })
        box.addView(k.text("Find files to download", 22f, p.fg, Type.black).apply { gravity = Gravity.CENTER; gradientText(p.fg, p.tide) })
        box.addView(k.text("Video and audio a page plays show up in a button at the bottom. Long-press any link to download it with VoltFetch.", 13.5f, p.mute).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.3f) })
        val search = k.input("Search or type an address").apply {
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { v, _, _ -> go((v as EditText).text.toString()); true }
        }
        box.addView(search, lp(-1, -2).apply { topMargin = dp(6) })
        val recents = recent()
        if (recents.isNotEmpty()) {
            box.addView(k.sectionTitle("Recent sites").apply { gravity = Gravity.START }, lp(-1, -2))
            val list = k.vbox(gap = dp(8))
            for (u in recents) {
                val host = runCatching { Uri.parse(u).host ?: u }.getOrDefault(u).removePrefix("www.")
                val row = k.hbox(dp(12)).apply { setPadding(dp(14), dp(12), dp(12), dp(12)) }
                val badge = k.text(host.take(1).uppercase(), 15f, Color.WHITE, Type.black).apply { gravity = Gravity.CENTER; background = gradient(dpf(12f), p.accent, p.violet) }
                row.addView(badge, LinearLayout.LayoutParams(dp(36), dp(36)))
                val tx = k.vbox(gap = dp(2)); tx.addView(k.text(host, 14.5f, p.fg, Type.medium, lines = 1)); tx.addView(k.text(u, 11.5f, p.mute, lines = 1))
                row.addView(tx, lp(0, -2, 1f))
                row.addView(k.icon(R.drawable.ic_chevron, p.faint, 18))
                val card = k.card(row, 0)
                card.setOnClickListener { haptic(it); go(u) }
                card.pressable(.98f)
                list.addView(card)
            }
            box.addView(list, lp(-1, -2))
        }
        val note = k.hbox(dp(10)).apply { setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(alpha(p.hold, .12f), dpf(16f)) }
        note.addView(k.icon(R.drawable.ic_alert, p.hold, 18))
        note.addView(k.text("For public YouTube videos, open Media downloader to choose a resolution or extract MP3. Downloads and audio extraction run locally on this phone; no backend is required.", 12.5f, p.fg).apply { setLineSpacing(0f, 1.2f) }, lp(0, -2, 1f))
        note.setOnClickListener { startActivity(Intent(this, MediaActivity::class.java)) }
        note.pressable()
        box.addView(note, lp(-1, -2).apply { topMargin = dp(10) })
        return sv
    }

    private fun showHome() {
        home.visibility = View.VISIBLE; home.alpha = 0f; home.animate().alpha(1f).setDuration(250).start()
        pageUrl = ""; address.setText(""); lock.visibility = View.GONE
        media.clear(); updatePill()
    }

    private fun go(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            !t.contains(' ') && t.contains('.') -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
        currentFocus?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0); it.clearFocus() }
        if (home.visibility == View.VISIBLE) home.animate().alpha(0f).setDuration(180).withEndAction { home.visibility = View.GONE }.start()
        web.loadUrl(url)
    }

    private fun back() {
        when {
            web.canGoBack() && home.visibility != View.VISIBLE -> web.goBack()
            home.visibility != View.VISIBLE -> { web.loadUrl("about:blank"); showHome() }
            else -> finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = back()

    private fun menu() {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (pageUrl.isNotEmpty()) {
            if (isYouTubeUrl(pageUrl)) items.add("Download video / extract MP3" to { startActivity(Intent(this, MediaActivity::class.java).putExtra("url", pageUrl)) })
            items.add("Grab files from this page" to { startActivity(Intent(this, GrabberActivity::class.java).putExtra("url", pageUrl)) })
            items.add("Download this address" to { openAdd(pageUrl, "", -1, "", web.settings.userAgentString) })
            items.add("Copy address" to { copy(pageUrl) })
            items.add("Open in another browser" to { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl))) } })
        }
        items.add("Home" to { web.loadUrl("about:blank"); showHome() })
        items.add("Clear cookies and history" to {
            dialogB().setTitle("Clear browsing data?").setMessage("Signs you out of sites in VoltFetch's browser and clears the recent sites list.")
                .setPositiveButton("Clear") { _, _ ->
                    CookieManager.getInstance().removeAllCookies(null); web.clearHistory(); web.clearCache(true)
                    getSharedPreferences("browser", MODE_PRIVATE).edit().remove("recent").apply()
                    toast("Cleared")
                }.setNegativeButton("Cancel", null).show()
        })
        dialogB().setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    // ------------------------------------------------------------------ media detection

    private val mediaExt = setOf("mp4", "m4v", "webm", "mov", "mkv", "3gp", "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac")
    private val streamExt = setOf("m3u8", "mpd")

    private fun blocked(url: String) = NoCapture.blocked(url) || NoCapture.blocked(pageUrl)

    private fun isYouTubeUrl(raw: String): Boolean = com.voltfetch.engine.YouTube.isUrl(raw)

    private fun consider(url: String) {
        if (!url.startsWith("http")) return
        val path = runCatching { Uri.parse(url).path ?: "" }.getOrDefault("")
        val ext = Names.ext(path.substringAfterLast('/'))
        val kind = when {
            ext in streamExt -> "stream"
            ext in setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac") -> "audio"
            ext in mediaExt -> "video"
            else -> return
        }
        // the same file is often requested in pieces with different query strings
        val key = url.substringBefore('?')
        runOnUiThread {
            if (blocked(pageUrl) || media.containsKey(key)) return@runOnUiThread
            media[key] = Media(url, kind, pageUrl, pageTitle)
            updatePill(pulse = true)
        }
    }

    private fun updatePill(pulse: Boolean = false) {
        val n = media.values.count { it.kind != "stream" }
        val streams = media.values.count { it.kind == "stream" }
        when {
            pageUrl.isNotEmpty() && blocked(pageUrl) && !isYouTubeUrl(pageUrl) -> {
                pill.text = "Downloads aren't available on this site"
                pill.background = rounded(alpha(p.deck2, .96f), dpf(25f), p.glassEdge, dp(1)); pill.setTextColor(p.mute)
                pill.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
                if (pill.visibility != View.VISIBLE) { pill.visibility = View.VISIBLE; pill.enter() }
            }
            pageUrl.isNotEmpty() && isYouTubeUrl(pageUrl) -> {
                pill.text = "Video / audio download options"
                pill.background = gradient(dpf(25f), p.violet, p.accent, p.tide, orientation = android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT)
                pill.setTextColor(Color.WHITE)
                pill.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
                if (pill.visibility != View.VISIBLE) { pill.visibility = View.VISIBLE; pill.enter() }
            }
            n + streams > 0 -> {
                pill.text = if (n > 0) "$n ${if (n == 1) "file" else "files"} to download" else "Streamed video found"
                pill.background = gradient(dpf(25f), p.violet, p.accent, p.tide, orientation = android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT)
                pill.setTextColor(Color.WHITE)
                val d = getDrawable(R.drawable.ic_down)!!.mutate(); d.setTint(Color.WHITE); d.setBounds(0, 0, dp(18), dp(18))
                pill.setCompoundDrawables(d, null, null, null); pill.compoundDrawablePadding = dp(8)
                if (pill.visibility != View.VISIBLE) { pill.visibility = View.VISIBLE; pill.enter(0, 30f) }
                else if (pulse) pill.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).withEndAction { pill.animate().scaleX(1f).scaleY(1f).setDuration(220).start() }.start()
            }
            else -> pill.visibility = View.GONE
        }
    }

    private fun showMedia() {
        if (blocked(pageUrl)) { notAllowed(); return }
        val list = media.values.toList()
        val box = k.vbox(gap = dp(8)).apply { setPadding(dp(18), dp(6), dp(18), dp(6)) }
        val dlg = dialogB().setTitle("Found on this page").setView(ScrollView(this).apply { addView(box) }).setNegativeButton("Close", null).create()
        for (m in list) {
            val name = Names.fromUrl(m.url)
            val row = k.hbox(dp(12)).apply { setPadding(dp(12), dp(12), dp(12), dp(12)) }
            val tint = when (m.kind) { "audio" -> p.cat("Music"); "stream" -> p.mute; else -> p.cat("Video") }
            val ic = FrameLayout(this).apply { background = rounded(alpha(tint, .18f), dpf(12f)); addView(k.icon(if (m.kind == "audio") R.drawable.ic_cat_music else R.drawable.ic_film, tint, 20).apply { layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER) }) }
            row.addView(ic, LinearLayout.LayoutParams(dp(40), dp(40)))
            val tx = k.vbox(gap = dp(2))
            tx.addView(k.text(name, 14f, p.fg, Type.medium, lines = 2))
            tx.addView(k.text(if (m.kind == "stream") "Streamed in small pieces. It can't be saved as one file." else (runCatching { Uri.parse(m.url).host ?: "" }.getOrDefault("")), 12f, if (m.kind == "stream") p.hold else p.mute, lines = 2))
            row.addView(tx, lp(0, -2, 1f))
            if (m.kind != "stream") row.addView(k.button("Get", "primary") { dlg.dismiss(); openAdd(m.url, suggested(m), -1, "", web.settings.userAgentString) }.apply { minHeight = dp(38); minimumHeight = dp(38) }, LinearLayout.LayoutParams(-2, dp(38)))
            box.addView(row.apply { background = rounded(alpha(p.fg, .05f), dpf(16f)) })
        }
        dlg.show()
    }

    /** Use the page title for media with meaningless file names. */
    private fun suggested(m: Media): String {
        val n = Names.fromUrl(m.url)
        val ext = Names.ext(n)
        val base = n.substringBeforeLast('.')
        val meaningless = base.length < 4 || base.matches(Regex("[0-9a-f_\\-]{12,}", RegexOption.IGNORE_CASE)) || base in setOf("video", "audio", "media", "index", "playlist")
        return if (meaningless && m.title.isNotBlank() && ext.isNotEmpty()) Names.sanitize(m.title.take(80)) + ".$ext" else ""
    }

    private fun openAdd(url: String, name: String, size: Long, mime: String, ua: String) {
        if (blocked(url)) { notAllowed(); return }
        val cookies = CookieManager.getInstance().getCookie(url) ?: ""
        startActivity(Intent(this, AddActivity::class.java)
            .putExtra(Intent.EXTRA_TEXT, url).putExtra("referer", pageUrl).putExtra("cookies", cookies).putExtra("agent", ua)
            .putExtra("name", name).putExtra("size", size).putExtra("mime", mime).putExtra("title", pageTitle))
    }

    private fun notAllowed() {
        dialogB().setTitle("Not available on this site").setMessage("This site doesn't allow its videos to be downloaded, so VoltFetch doesn't save them. Files from other sites download as usual.")
            .setPositiveButton("OK", null).show()
    }

    // ------------------------------------------------------------------ long-press menu

    override fun onCreateContextMenu(menu: ContextMenu, v: View, info: ContextMenu.ContextMenuInfo?) {
        val r = web.hitTestResult
        val url = r.extra ?: return
        if (!url.startsWith("http")) return
        when (r.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE, WebView.HitTestResult.IMAGE_TYPE -> {
                menu.setHeaderTitle(Names.fromUrl(url))
                menu.add("Download with VoltFetch").setOnMenuItemClickListener { openAdd(url, "", -1, "", web.settings.userAgentString); true }
                menu.add("Copy link").setOnMenuItemClickListener { copy(url); true }
                if (r.type != WebView.HitTestResult.IMAGE_TYPE) menu.add("Grab files from that page").setOnMenuItemClickListener { startActivity(Intent(this, GrabberActivity::class.java).putExtra("url", url)); true }
            }
        }
    }

    // ------------------------------------------------------------------ WebView callbacks

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            val s = req.url.scheme ?: return false
            if (s == "http" || s == "https") return false
            // intent:, market:, tel: and so on go to other apps
            runCatching { startActivity(if (s == "intent") Intent.parseUri(req.url.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, req.url)) }
            return true
        }

        override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
            consider(req.url.toString())
            return null
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (url == "about:blank") return
            loading = true
            if (url.substringBefore('#') != pageUrl.substringBefore('#')) { media.clear() }
            pageUrl = url
            if (!address.hasFocus()) showAddress()
            reload.setImageResource(R.drawable.ic_x)
            updatePill()
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (url == "about:blank") return
            loading = false
            reload.setImageResource(R.drawable.ic_refresh)
            progress.animate().alpha(0f).setDuration(300).start()
            pageTitle = view.title ?: ""
            remember(url)
            // also look at <video>/<audio> elements that point straight at files
            view.evaluateJavascript("(function(){var o=[];document.querySelectorAll('video,audio,source').forEach(function(e){var s=e.currentSrc||e.src;if(s)o.push(s)});return o.join('\\n')})()") { res ->
                val txt = res?.trim('"')?.replace("\\n", "\n") ?: return@evaluateJavascript
                for (u in txt.split('\n')) if (u.startsWith("http")) consider(u)
            }
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.alpha = 1f
            progress.animate().scaleX(newProgress / 100f).setDuration(200).start()
        }
        override fun onReceivedTitle(view: WebView, title: String?) { pageTitle = title ?: "" }
    }

    // ------------------------------------------------------------------ helpers

    private fun recent(): List<String> = getSharedPreferences("browser", MODE_PRIVATE).getString("recent", "")!!.split('\n').filter { it.isNotBlank() }

    private fun remember(url: String) {
        if (url.contains("google.com/search")) return
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return
        val l = recent().filter { runCatching { Uri.parse(it).host }.getOrNull() != host }.toMutableList()
        l.add(0, "https://$host/".let { if (url.startsWith("http://")) "http://$host/" else it })
        getSharedPreferences("browser", MODE_PRIVATE).edit().putString("recent", l.take(6).joinToString("\n")).apply()
    }

    private fun copy(s: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("link", s))
        getSharedPreferences("ui", MODE_PRIVATE).edit().putString("lastClip", s).apply()
        toast("Link copied")
    }

    private fun dialogB() = android.app.AlertDialog.Builder(this, if (p.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onPause() { super.onPause(); web.onPause(); CookieManager.getInstance().flush() }
    override fun onResume() { super.onResume(); web.onResume() }
    override fun onDestroy() { (web.parent as? android.view.ViewGroup)?.removeView(web); web.destroy(); super.onDestroy() }
}
