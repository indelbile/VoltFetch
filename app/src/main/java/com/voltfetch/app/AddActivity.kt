package com.voltfetch.app

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.voltfetch.engine.Categories
import com.voltfetch.engine.Engine
import com.voltfetch.engine.Fmt
import com.voltfetch.engine.Links
import com.voltfetch.engine.MAIN_QUEUE
import com.voltfetch.engine.Names
import com.voltfetch.engine.NoCapture
import com.voltfetch.engine.Probe
import com.voltfetch.engine.MediaSites

/** The "New download" sheet. Also receives links shared from browsers, other apps and VoltFetch's own browser. */
class AddActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var k: Kit
    private val handler = Handler(Looper.getMainLooper())
    private val engine get() = VoltApp.engine(this)

    private lateinit var sheet: LinearLayout
    private lateinit var urlIn: EditText
    private lateinit var card: LinearLayout
    private lateinit var options: LinearLayout
    private lateinit var chips: LinearLayout
    private lateinit var queueRow: LinearLayout
    private lateinit var sumIn: EditText
    private lateinit var userIn: EditText
    private lateinit var passIn: EditText
    private lateinit var more: LinearLayout
    private lateinit var moreToggle: TextView
    private lateinit var startBtn: TextView
    private lateinit var laterBtn: TextView
    private var nameIn: EditText? = null

    private var probe: Probe? = null
    private var probeError: String? = null
    private var probing = 0
    private var category = Categories.GENERAL
    private var queue = MAIN_QUEUE
    private var nameEdited = false
    private var customName = ""
    private var catPicked = false
    private var referer = ""
    private var cookies = ""
    private var agent = ""
    private var hintName = ""
    private var hintSize = -1L
    private var hintMime = ""
    private var pageTitle = ""
    // the file card, built once so typing isn't interrupted when the link check finishes
    private var fileCard: View? = null
    private lateinit var fileTile: FrameLayout
    private lateinit var metaText: TextView
    private lateinit var sizeText: TextView
    private lateinit var genericHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Palette.of(this); k = Kit(this, p)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(alpha(Color.BLACK, if (p.dark) .6f else .35f)))
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.navigationBarColor = p.deck
        queue = intent?.getStringExtra("queue")?.takeIf { it in engine.settings.queueNames() } ?: MAIN_QUEUE

        sheet = k.vbox(gap = dp(14)).apply {
            setPadding(dp(20), dp(10), dp(20), dp(20))
            val r = dpf(28f)
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                android.graphics.drawable.GradientDrawable().apply { setColor(p.deck); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) },
                android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(alpha(p.accent, if (p.dark) .16f else .08f), Color.TRANSPARENT)).apply { cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) },
            ))
            isClickable = true
        }
        sheet.addView(View(this).apply { background = gradient(dpf(3f), p.accent, p.tide, orientation = android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT) }, LinearLayout.LayoutParams(dp(44), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val head = k.hbox(dp(10))
        val fromApp = intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_VIEW
        head.addView(k.text(if (fromApp || intent?.hasExtra("cookies") == true) "Download with VoltFetch" else "New download", 20f, p.fg, Type.black).apply { gradientText(p.fg, mix(p.fg, p.tide, .5f)) }, lp(0, -2, 1f))
        head.addView(k.orb(R.drawable.ic_x, "Close") { finish() }, LinearLayout.LayoutParams(dp(42), dp(42)))
        sheet.addView(head)

        urlIn = k.input("Paste a link, or several on separate lines", multi = true).apply { minLines = 1; maxLines = 4 }
        sheet.addView(urlIn)
        card = k.vbox(gap = dp(10))
        sheet.addView(card)

        options = k.vbox(gap = dp(10))
        options.addView(k.text("Category", 12.5f, p.mute, Type.medium))
        val (sv, row) = k.chipRow(); chips = row; row.setPadding(0, 0, 0, 0)
        options.addView(sv)
        sheet.addView(options)

        val qbox = k.vbox(gap = dp(10))
        qbox.addView(k.text("Queue", 12.5f, p.mute, Type.medium))
        val qs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        queueRow = k.hbox(dp(8)); qs.addView(queueRow)
        qbox.addView(qs)
        sheet.addView(qbox)
        renderQueues()
        if (engine.settings.queues.isEmpty()) qbox.visibility = View.GONE

        moreToggle = k.text("More options  ▾", 13.5f, p.accent, Type.medium).apply {
            setPadding(dp(2), dp(4), dp(2), dp(4))
            setOnClickListener { haptic(this); toggleMore() }
        }
        sheet.addView(moreToggle)
        more = k.vbox(gap = dp(10)).apply { visibility = View.GONE }
        more.addView(k.text("Checksum", 12.5f, p.mute, Type.medium))
        sumIn = k.input("SHA-256, SHA-1 or MD5 from the download page")
        more.addView(sumIn)
        more.addView(k.text("VoltFetch checks the finished file against it.", 12f, p.faint))
        more.addView(k.text("Site login", 12.5f, p.mute, Type.medium).apply { setPadding(0, dp(6), 0, 0) })
        val creds = k.hbox(dp(10))
        userIn = k.input("User name")
        passIn = k.input("Password").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        creds.addView(userIn, lp(0, -2, 1f)); creds.addView(passIn, lp(0, -2, 1f))
        more.addView(creds)
        more.addView(k.text("Only for sites that ask for a user name and password before downloading.", 12f, p.faint))
        sheet.addView(more)

        val btns = k.hbox(dp(10)).apply { setPadding(0, dp(6), 0, 0) }
        laterBtn = k.button("Save for later", "ghost") { submit(false) }
        startBtn = k.button("Download", "primary", R.drawable.ic_down) { submit(true) }
        btns.addView(laterBtn, lp(0, dp(54), 1f)); btns.addView(startBtn, lp(0, dp(54), 1.3f))
        sheet.addView(btns)

        val scroll = ScrollView(this).apply { addView(sheet); isFillViewport = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val root = FrameLayout(this).apply {
            setOnClickListener { finish() } // tap outside closes
            addView(scroll, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        setContentView(root)
        sheet.translationY = dpf(320f)
        sheet.animate().translationY(0f).setDuration(420).setInterpolator(Ease).start()

        urlIn.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { handler.removeCallbacks(probeRun); handler.postDelayed(probeRun, 400) }
        })
        handleIntent(intent)
        renderChips(); render()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    private var clipTried = false

    /**
     * Android only lets the app in front read the clipboard. Opened from the home-screen "Paste link"
     * shortcut, the sheet isn't in front yet in onCreate, so the copied link is picked up here instead.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || clipTried) return
        clipTried = true
        if (urlIn.text.toString().isNotBlank() || intent?.action == Intent.ACTION_SEND) return
        val clip = runCatching { getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()
        val links = Links.find(clip)
        if (links.isEmpty()) { if (intent?.action == Intent.ACTION_MAIN) toast("Copy a link first, then use Paste link"); return }
        if (links.size == 1 && MediaSites.isKnown(links[0]) && !Links.looksLikeFile(links[0])) { openMedia(links[0]); return }
        urlIn.setText(links.joinToString("\n"))
        urlIn.setSelection(urlIn.text.length)
    }

    private fun toggleMore() {
        val show = more.visibility != View.VISIBLE
        more.visibility = if (show) View.VISIBLE else View.GONE
        if (show) more.enter(0, -8f)
        moreToggle.text = if (show) "Fewer options  ▴" else "More options  ▾"
    }

    private fun handleIntent(i: Intent?) {
        val text = when (i?.action) {
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> i.dataString
            else -> i?.getStringExtra(Intent.EXTRA_TEXT)
        }
        referer = i?.getStringExtra("referer") ?: ""
        cookies = i?.getStringExtra("cookies") ?: ""
        agent = i?.getStringExtra("agent") ?: ""
        hintName = i?.getStringExtra("name")?.let { Names.sanitize(it) }?.takeIf { it != "download" } ?: ""
        hintSize = i?.getLongExtra("size", -1L) ?: -1L
        hintMime = i?.getStringExtra("mime") ?: ""
        pageTitle = i?.getStringExtra("title")?.trim() ?: ""
        nameEdited = false; customName = ""
        if (hintName.isNotBlank()) category = Categories.of(hintName)
        var links = Links.find(text)
        if (links.isEmpty() && i?.action == null && text == null) {
            // opened from the app: offer whatever link is on the clipboard
            val clip = runCatching { getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()
            links = Links.find(clip)
        }
        // A single video page from a known site (YouTube, Instagram, Facebook, X, Vimeo…) goes straight to the
        // Media downloader, which handles quality, audio, clips and playlists.
        if (links.size == 1 && MediaSites.isKnown(links[0]) && !Links.looksLikeFile(links[0]) && (i?.action == Intent.ACTION_SEND || i?.action == Intent.ACTION_VIEW)) {
            openMedia(links[0]); return
        }
        if (links.isNotEmpty()) urlIn.setText(links.joinToString("\n"))
        else if (i?.action == Intent.ACTION_SEND) toast("There's no web link in what you shared")
        urlIn.setSelection(urlIn.text.length)
    }

    private fun urls(): List<String> = Links.find(urlIn.text.toString())

    private fun openMedia(url: String) {
        startActivity(Intent(this, MediaActivity::class.java).putExtra("url", url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private val probeRun = Runnable { runProbe() }

    private fun auth(): String {
        val u = if (::userIn.isInitialized) userIn.text.toString() else ""
        return if (u.isBlank()) "" else "$u:${passIn.text}"
    }

    private fun runProbe() {
        val list = urls()
        probe = null; probeError = null
        if (list.size != 1 || NoCapture.blocked(list[0])) { render(); return }
        val url = list[0]
        if (!catPicked && !nameEdited) { category = Categories.of(Names.fromUrl(url)); renderChips() }
        val my = ++probing
        render()
        val a = auth()
        Thread {
            val r = runCatching { engine.probe(url, referer, cookies, agent, a) }
            runOnUiThread {
                if (my != probing || isFinishing) return@runOnUiThread
                r.onSuccess { pr -> probe = pr; if (!catPicked) { category = Categories.of(if (nameEdited) customName else suggestedName(url)); renderChips() } }
                r.onFailure { probeError = it.message ?: "Couldn't reach the server" }
                render()
            }
        }.start()
    }

    private fun render() {
        val list = urls()
        val blocked = list.any { NoCapture.blocked(it) }
        val ok = list.isNotEmpty() && !blocked
        startBtn.isEnabled = ok; laterBtn.isEnabled = ok
        startBtn.alpha = if (ok) 1f else .45f; laterBtn.alpha = if (ok) 1f else .45f
        options.visibility = if (list.size == 1 && !blocked) View.VISIBLE else View.GONE
        (fileCard?.parent as? android.view.ViewGroup)?.removeView(fileCard)
        card.removeAllViews()
        if (blocked) {
            val yt = list.firstOrNull { MediaSites.isKnown(it) }
            if (yt != null) {
                card.addView(notice("${MediaSites.label(yt)} links are downloaded with the Media downloader, where you choose the quality or audio. Add them one link at a time.", false))
                card.addView(k.button("Open Media downloader", "primary", R.drawable.ic_film) { openMedia(yt) }, lp(-1, dp(48)))
            } else card.addView(notice("This site's video streams can't be saved directly. Open the video's page in the Media downloader instead.", true))
            return
        }
        if (list.size > 1) {
            card.addView(notice("${list.size} links will be added. Names and sizes are read from each server when it starts.", false))
            startBtn.text = "Download ${list.size}"
            return
        }
        startBtn.text = "Download"
        if (!ok) return
        val url = list[0]
        val fc = fileCard ?: buildFileCard().also { fileCard = it }
        card.addView(fc)
        fileTile.removeAllViews()
        fileTile.addView(k.tile(category, 50))
        val ed = nameIn!!
        if (!nameEdited && !ed.hasFocus()) { val n = suggestedName(url); if (ed.text.toString() != n) ed.setText(n) }
        genericHint.visibility = if (!nameEdited && Names.isGeneric(ed.text.toString())) View.VISIBLE else View.GONE
        val needsLogin = probeError?.contains("login") == true
        metaText.text = when {
            probeError != null -> "Couldn't check the link: $probeError"
            probe == null -> "Checking the link…"
            probe!!.resumable -> "Resumable  ·  multi-connection  ·  ${hostOf(probe!!.finalUrl)}"
            else -> "No resume support  ·  one connection  ·  ${hostOf(probe!!.finalUrl)}"
        }
        metaText.setTextColor(when { probeError != null -> p.hold; probe?.resumable == true -> p.done; probe != null -> p.hold; else -> p.mute })
        val size = probe?.size?.takeIf { it > 0 } ?: hintSize.takeIf { it > 0 }
        sizeText.text = size?.let { Fmt.size(it) } ?: "—"
        if (needsLogin && more.visibility != View.VISIBLE) toggleMore()
        // a web page rather than a file: the Media downloader can usually pull the video out of it
        if (probe?.mime == "text/html" || (probe == null && MediaSites.isKnown(url))) {
            card.addView(notice("This link is a web page, not a file. If it has a video or audio on it, the Media downloader can probably save it.", false))
            card.addView(k.button("Open in Media downloader", "tonal", R.drawable.ic_film) { openMedia(url) }, lp(-1, dp(46)))
        }
        engine.views().firstOrNull { it.url == url && it.status != com.voltfetch.engine.Status.COMPLETE }?.let {
            card.addView(notice("This link is already in your list as \"${it.name}\". Adding it again downloads a second copy.", true))
        }
    }

    /**
     * The name to offer: the server's real name, then the browser's, then the address. A meaningless one
     * (an ID, "downloadfile.bin") is replaced with the page title when there is one.
     */
    private fun suggestedName(url: String): String {
        val mime = probe?.mime ?: hintMime
        var n = Names.withExtension(Names.best(probe?.name, hintName, Names.fromUrl(url)), mime)
        if (Names.isGeneric(n) && pageTitle.isNotBlank()) {
            val e = Names.ext(n).takeIf { Names.known(it) } ?: Categories.extFor(mime)
            if (e != null) n = Names.sanitize(pageTitle.take(80)) + ".$e"
        }
        return n
    }

    private fun buildFileCard(): View {
        val box = k.vbox(gap = dp(10))
        val top = k.hbox(dp(14))
        fileTile = FrameLayout(this)
        top.addView(fileTile, LinearLayout.LayoutParams(dp(50), dp(50)))
        val mid = k.vbox(gap = dp(4))
        mid.addView(k.text("SAVE AS", 11f, p.mute, Type.medium).apply { letterSpacing = .1f })
        metaText = k.text("", 12.5f, p.mute, lines = 2)
        sizeText = k.text("—", 22f, p.fg, Type.black)
        mid.addView(metaText)
        top.addView(mid, lp(0, -2, 1f))
        top.addView(sizeText)
        box.addView(top)
        val ed = k.input("File name").apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f); typeface = Type.medium
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            val pen = getDrawable(R.drawable.ic_edit)!!.mutate(); pen.setTint(p.accent); pen.setBounds(0, 0, dp(18), dp(18))
            setCompoundDrawables(null, null, pen, null); compoundDrawablePadding = dp(8)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                    if (hasFocus()) {
                        nameEdited = true; customName = s?.toString() ?: ""
                        genericHint.visibility = View.GONE
                        if (!catPicked) { val c2 = Categories.of(customName); if (c2 != category && Names.known(Names.ext(customName))) { category = c2; renderChips(); fileTile.removeAllViews(); fileTile.addView(k.tile(category, 50)) } }
                    }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            // select the name without its extension, ready to type over
            setOnFocusChangeListener { v, f ->
                if (f) (v as EditText).post { val t = v.text.toString(); val dot = t.lastIndexOf('.'); v.setSelection(0, if (dot > 0 && t.length - dot <= 6) dot else t.length) }
            }
        }
        nameIn = ed
        box.addView(ed)
        genericHint = k.text("This link doesn't include a proper file name. Tap the name to change it.", 12f, p.hold).apply { visibility = View.GONE }
        box.addView(genericHint)
        return k.card(box, dp(14))
    }

    private fun hostOf(u: String) = runCatching { java.net.URI(u).host ?: "" }.getOrDefault("")

    private fun notice(text: String, warn: Boolean): View {
        val c = if (warn) p.hold else p.accent
        val row = k.hbox(dp(10)).apply { setPadding(dp(12), dp(10), dp(12), dp(10)); background = rounded(alpha(c, .13f), dpf(14f), alpha(c, .25f), dp(1)) }
        row.addView(k.icon(if (warn) R.drawable.ic_alert else R.drawable.ic_queue, c, 18))
        row.addView(k.text(text, 13f, p.fg).apply { setLineSpacing(0f, 1.2f) }, lp(0, -2, 1f))
        return row
    }

    private fun renderChips() {
        chips.removeAllViews()
        for (c in allCategories) chips.addView(k.chip(c, c == category, p.cat(c)) { category = c; catPicked = true; renderChips(); render() })
    }

    private fun renderQueues() {
        queueRow.removeAllViews()
        for (q in engine.settings.queueNames()) queueRow.addView(k.chip(if (q == MAIN_QUEUE) "Main · start now" else q, q == queue, if (q == MAIN_QUEUE) 0 else p.violet) { queue = q; renderQueues() })
    }

    private fun submit(start: Boolean) {
        val list = urls()
        if (list.isEmpty() || list.any { NoCapture.blocked(it) }) return
        val a = auth()
        if (list.size == 1) {
            val pr = probe
            engine.add(Engine.AddRequest(
                url = list[0], name = nameIn?.text?.toString()?.trim().orEmpty().ifEmpty { pr?.name ?: "" },
                category = category, referer = referer, size = pr?.size ?: -1, resumable = pr?.resumable ?: false,
                probed = pr != null, checksum = sumIn.text.toString(), start = start, queue = queue, cookies = cookies, agent = agent, auth = a,
                // a name the user typed is kept; otherwise the server's real name may still replace it
                userNamed = if (nameEdited) true else if (pr == null) false else null,
            ))
            toast(when { !start -> "Saved for later"; queue != MAIN_QUEUE && !engine.queueRunning(queue) -> "Added to $queue"; else -> "Download started" })
        } else {
            for (u in list) engine.add(Engine.AddRequest(url = u, referer = referer, start = start, queue = queue, cookies = cookies, agent = agent, auth = a))
            toast(if (start) "Added ${list.size} downloads" else "Saved ${list.size} downloads for later")
        }
        if (start) DownloadService.ensureRunning(this)
        finish()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, R.anim.exit_down)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
