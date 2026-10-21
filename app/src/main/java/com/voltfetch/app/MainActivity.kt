package com.voltfetch.app

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SysSettings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LayoutAnimationController
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.voltfetch.engine.DAY_SHORT
import com.voltfetch.engine.Fmt
import com.voltfetch.engine.Links
import com.voltfetch.engine.MAIN_QUEUE
import com.voltfetch.engine.SpeedRule
import com.voltfetch.engine.Status
import com.voltfetch.engine.dayText
import com.voltfetch.engine.hm
import com.voltfetch.engine.minutesOf
import com.voltfetch.engine.View as DView

class MainActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var k: Kit
    private val handler = Handler(Looper.getMainLooper())
    private val engine get() = VoltApp.engine(this)

    private lateinit var root: FrameLayout
    private lateinit var aurora: AuroraView
    private lateinit var pages: FrameLayout
    private lateinit var downloadsPage: FrameLayout
    private lateinit var queuesPage: ScrollView
    private lateinit var queuesBody: LinearLayout
    private lateinit var nav: LinearLayout
    private val navItems = HashMap<String, Pair<ImageView, TextView>>()
    private var tab = "downloads"
    private var insetTop = 0
    private var insetBottom = 0

    // downloads page
    private lateinit var header: LinearLayout
    private lateinit var titleRow: LinearLayout
    private lateinit var searchRow: LinearLayout
    private lateinit var searchIn: EditText
    private lateinit var gauge: SpeedGauge
    private lateinit var flow: FlowView
    private lateinit var stats: LinearLayout
    private lateinit var limitBadge: TextView
    private lateinit var pauseOrb: ImageView
    private lateinit var chipRow: LinearLayout
    private lateinit var banners: LinearLayout
    private lateinit var selBar: LinearLayout
    private lateinit var selCount: TextView
    private lateinit var list: ListView
    private lateinit var empty: LinearLayout
    private val adapter = Rows()

    private var filter = "all"
    private var query = ""
    private val selected = LinkedHashSet<Long>()
    private val history = ArrayList<Map<String, Double>>()
    private var chipSig = ""
    private var themeSig = ""
    private var queuesSig = ""
    private var firstRows = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.getStringExtra("tab")?.let { tab = it }
        build()
        showLastCrash()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("tab")?.let { select(it) }
    }

    private fun build() {
        p = Palette.of(this); k = Kit(this, p); themeSig = "${p.dark}"
        applyBars(p)
        root = FrameLayout(this)
        aurora = AuroraView(this, p).apply { animate = engine.settings.motion }
        root.addView(aurora, FrameLayout.LayoutParams(-1, -1))
        pages = FrameLayout(this)
        root.addView(pages, FrameLayout.LayoutParams(-1, -1))
        downloadsPage = buildDownloads()
        queuesPage = buildQueues()
        pages.addView(downloadsPage, FrameLayout.LayoutParams(-1, -1))
        pages.addView(queuesPage, FrameLayout.LayoutParams(-1, -1))
        nav = buildNav()
        root.addView(nav, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        root.onInsets { top, bottom ->
            insetTop = top; insetBottom = bottom
            header.setPadding(0, top + dp(8), 0, 0)
            list.setPadding(0, 0, 0, bottom + dp(110))
            queuesBody.setPadding(dp(16), top + dp(14), dp(16), bottom + dp(120))
            (nav.layoutParams as FrameLayout.LayoutParams).bottomMargin = bottom + dp(12)
            nav.requestLayout()
        }
        select(tab, animate = false)
        chipSig = ""; queuesSig = ""; firstRows = true
    }

    // ------------------------------------------------------------------ bottom bar

    private fun buildNav(): LinearLayout {
        val bar = k.hbox().apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = android.graphics.drawable.LayerDrawable(arrayOf(rounded(alpha(p.deck, .55f), dpf(30f)), glass(p, dpf(30f), this@MainActivity)))
            elevation = dpf(10f)
            clipToPadding = false; clipChildren = false
        }
        fun item(key: String, icon: Int, label: String, onClick: () -> Unit) {
            val b = k.vbox().apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(2)) }
            val ic = ImageView(this).apply { setImageResource(icon); setPadding(dp(14), dp(5), dp(14), dp(5)) }
            val tx = k.text(label, 10.5f, p.mute, Type.medium).apply { gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0) }
            b.addView(ic, LinearLayout.LayoutParams(dp(54), dp(32)).apply { gravity = Gravity.CENTER_HORIZONTAL }); b.addView(tx)
            b.background = ripple(null, alpha(p.accent, .2f))
            b.setOnClickListener { haptic(b); onClick() }
            b.pressable(0.9f)
            navItems[key] = ic to tx
            bar.addView(b, lp(0, -2, 1f))
        }
        item("downloads", R.drawable.ic_down, "Downloads") { select("downloads") }
        item("queues", R.drawable.ic_calendar, "Queues") { select("queues") }
        // centre action
        val fab = FrameLayout(this).apply {
            background = gradient(dpf(26f), p.violet, p.accent, p.tide)
            elevation = dpf(12f); outlineSpotShadowColor = p.accent; outlineAmbientShadowColor = p.accent
            addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_plus); imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            contentDescription = "Add"
            setOnClickListener { haptic(this, true); openAddMenu() }
            pressable(0.88f)
        }
        bar.addView(fab, LinearLayout.LayoutParams(dp(56), dp(56)).apply { setMargins(dp(6), 0, dp(6), 0) })
        item("browser", R.drawable.ic_globe, "Browser") { startActivity(Intent(this, BrowserActivity::class.java)) }
        item("settings", R.drawable.ic_gear, "Settings") { startActivity(Intent(this, SettingsActivity::class.java)) }
        return k.hbox().apply {
            setPadding(dp(14), 0, dp(14), 0); clipChildren = false; clipToPadding = false
            addView(bar, lp(-1, -2))
        }
    }

    private fun select(t: String, animate: Boolean = true) {
        tab = if (t == "queues") "queues" else "downloads"
        val show = if (tab == "queues") queuesPage else downloadsPage
        val hide = if (tab == "queues") downloadsPage else queuesPage
        if (animate && show.visibility != View.VISIBLE) {
            show.alpha = 0f; show.translationY = dpf(14f); show.visibility = View.VISIBLE
            show.animate().alpha(1f).translationY(0f).setDuration(320).setInterpolator(Ease).start()
            hide.animate().alpha(0f).setDuration(160).withEndAction { hide.visibility = View.GONE; hide.alpha = 1f }.start()
        } else { show.visibility = View.VISIBLE; hide.visibility = View.GONE }
        for ((key, pair) in navItems) {
            val on = key == tab
            pair.first.imageTintList = android.content.res.ColorStateList.valueOf(if (on) p.accent else p.mute)
            pair.first.background = if (on) rounded(alpha(p.accent, .16f), dpf(16f)) else null
            pair.second.setTextColor(if (on) p.fg else p.mute)
        }
        if (tab == "queues") { queuesSig = ""; renderQueues() }
    }

    // ------------------------------------------------------------------ add menu

    private fun openAddMenu() {
        val scrim = FrameLayout(this).apply { setBackgroundColor(alpha(Color.BLACK, if (p.dark) .55f else .3f)); alpha = 0f; isClickable = true }
        val sheet = k.vbox(gap = dp(10)).apply { setPadding(dp(18), dp(18), dp(18), dp(18)) }
        sheet.background = android.graphics.drawable.LayerDrawable(arrayOf(rounded(p.deck, dpf(28f)), glass(p, dpf(28f), this)))
        sheet.isClickable = true
        sheet.addView(k.text("Add downloads", 18f, p.fg, Type.bold).apply { setPadding(dp(4), 0, 0, dp(4)) })
        fun close() {
            scrim.animate().alpha(0f).setDuration(180).withEndAction { root.removeView(scrim) }.start()
            sheet.animate().translationY(dpf(40f)).alpha(0f).setDuration(180).start()
        }
        val grid = k.vbox(gap = dp(10))
        val rows = listOf(
            listOf(Triple(R.drawable.ic_link, "Paste a link", "One or many") to { startActivity(Intent(this, AddActivity::class.java)) },
                Triple(R.drawable.ic_globe, "Browse", "Find files on a site") to { startActivity(Intent(this, BrowserActivity::class.java)) }),
            listOf(Triple(R.drawable.ic_scan, "Site grabber", "Every file on a page") to { startActivity(Intent(this, GrabberActivity::class.java)) },
                Triple(R.drawable.ic_layers, "Batch", "img001…img100") to { startActivity(Intent(this, BatchActivity::class.java)) }),
            listOf(Triple(R.drawable.ic_film, "Media downloader", "Video resolutions & MP3 bitrates") to { startActivity(Intent(this, MediaActivity::class.java)) }),
        )
        val tints = listOf(p.accent, p.tide, p.violet, p.cat("Programs"), p.tide, p.violet)
        var ti = 0
        for (r in rows) {
            val row = k.hbox(dp(10))
            for ((info, action) in r) {
                val tint = tints[ti++]
                val tile = k.vbox(gap = dp(6)).apply {
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    background = ripple(rounded(alpha(tint, if (p.dark) .14f else .10f), dpf(20f), alpha(tint, .3f), dp(1)), alpha(tint, .3f), rounded(Color.WHITE, dpf(20f)))
                    addView(k.icon(info.first, tint, 26))
                    addView(k.text(info.second, 15f, p.fg, Type.medium).apply { setPadding(0, dp(6), 0, 0) })
                    addView(k.text(info.third, 12f, p.mute))
                    setOnClickListener { haptic(this); close(); action() }
                    pressable()
                }
                row.addView(tile, lp(0, -2, 1f))
            }
            grid.addView(row)
        }
        sheet.addView(grid)
        val imp = k.button("Import a list of links", "ghost", R.drawable.ic_import) { close(); importList() }
        sheet.addView(imp, lp(-1, dp(50)))
        scrim.addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(12), 0, dp(12), insetBottom + dp(96)) })
        scrim.setOnClickListener { close() }
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        scrim.animate().alpha(1f).setDuration(200).start()
        sheet.translationY = dpf(60f); sheet.scaleX = .94f; sheet.scaleY = .94f
        sheet.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(Ease).start()
    }

    private fun importList() {
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/csv", "text/html", "text/*")), REQ_IMPORT)
        }.onFailure { toast("No file picker is available") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val text = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            val links = Links.find(text)
            if (links.isEmpty()) { toast("No web links in that file"); return }
            startActivity(Intent(this, AddActivity::class.java).putExtra(Intent.EXTRA_TEXT, links.joinToString("\n")))
        }
    }

    // ------------------------------------------------------------------ downloads page

    private fun buildDownloads(): FrameLayout {
        val page = FrameLayout(this)
        header = k.vbox()

        // title row
        titleRow = k.hbox(dp(10)).apply { setPadding(dp(16), dp(6), dp(12), dp(6)) }
        val mark = ImageView(this).apply { setImageResource(R.drawable.logo_symbol); scaleType = ImageView.ScaleType.FIT_CENTER }
        titleRow.addView(mark, lp(dp(50), dp(36)))
        val word = k.text("VoltFetch", 24f, p.fg, Type.black).apply { letterSpacing = -0.01f; gradientText(p.fg, mix(p.fg, p.tide, .6f)) }
        titleRow.addView(word, lp(0, -2, 1f))
        titleRow.addView(k.orb(R.drawable.ic_search, "Search") { openSearch() }, lp(dp(44), dp(44)))
        pauseOrb = k.orb(R.drawable.ic_pause, "Pause all") { toggleAll() }
        titleRow.addView(pauseOrb, lp(dp(44), dp(44)).apply { leftMargin = dp(8) })
        // search row (hidden until asked for)
        searchRow = k.hbox(dp(8)).apply { setPadding(dp(16), dp(6), dp(12), dp(6)); visibility = View.GONE }
        searchIn = k.input("Search downloads").apply {
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim() ?: ""; refresh() }
            })
        }
        searchRow.addView(searchIn, lp(0, -2, 1f))
        searchRow.addView(k.orb(R.drawable.ic_x, "Close search") { closeSearch() }, lp(dp(44), dp(44)))
        header.addView(titleRow); header.addView(searchRow)

        // hero: speedometer + flow + stats
        val hero = k.vbox().apply { setPadding(dp(14), dp(10), dp(14), dp(14)) }
        gauge = SpeedGauge(this, p)
        hero.addView(gauge, lp(-1, dp(196)))
        limitBadge = k.badge("", p.hold, R.drawable.ic_gauge).apply { visibility = View.GONE; setOnClickListener { select("queues") } }
        hero.addView(limitBadge, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = -dp(4); bottomMargin = dp(8) })
        flow = FlowView(this, p)
        hero.addView(flow, lp(-1, dp(40)))
        stats = k.hbox().apply { setPadding(0, dp(12), 0, 0) }
        hero.addView(stats)
        header.addView(k.card(hero, 0).also { it.layoutParams = lp().apply { setMargins(dp(14), dp(6), dp(14), dp(10)) } })

        banners = k.vbox(gap = dp(8)).apply { setPadding(dp(14), 0, dp(14), 0) }
        header.addView(banners)

        val slot = FrameLayout(this)
        val (sv, row) = k.chipRow()
        chipRow = row
        slot.addView(sv, FrameLayout.LayoutParams(-1, dp(52)))
        selBar = k.hbox(dp(4)).apply {
            setPadding(dp(8), 0, dp(8), 0); visibility = View.GONE
            background = android.graphics.drawable.LayerDrawable(arrayOf(rounded(p.deck, dpf(26f)), rounded(alpha(p.accent, .16f), dpf(26f), alpha(p.accent, .5f), dp(1))))
        }
        selCount = k.text("", 15f, p.fg, Type.medium)
        selBar.addView(k.iconButton(R.drawable.ic_x, "Clear selection") { clearSelection() })
        selBar.addView(selCount); selBar.addView(k.grow())
        selBar.addView(k.iconButton(R.drawable.ic_play, "Resume") { engine.resume(selected.toList()); DownloadService.ensureRunning(this); clearSelection() })
        selBar.addView(k.iconButton(R.drawable.ic_pause, "Pause") { engine.pause(selected.toList()); clearSelection() })
        selBar.addView(k.iconButton(R.drawable.ic_calendar, "Move to queue") { pickQueue(selected.toList()) })
        selBar.addView(k.iconButton(R.drawable.ic_trash, "Delete", p.fault) { confirmDelete(selected.toList()) })
        slot.addView(selBar, FrameLayout.LayoutParams(-1, dp(52)).apply { setMargins(dp(14), 0, dp(14), 0) })
        header.addView(slot, lp(-1, dp(56)))

        list = ListView(this).apply {
            divider = null; dividerHeight = 0; selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            clipToPadding = false; overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false
            addHeaderView(header, null, false)
            layoutAnimation = LayoutAnimationController(android.view.animation.AnimationUtils.loadAnimation(this@MainActivity, R.anim.row_in), .08f)
        }
        empty = emptyState()
        list.addFooterView(FrameLayout(this).apply { addView(empty) }, null, false)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> adapter.items.getOrNull(pos - list.headerViewsCount)?.let { onRowClick(it) } }
        list.setOnItemLongClickListener { v, _, pos, _ -> adapter.items.getOrNull(pos - list.headerViewsCount)?.let { haptic(v, true); toggle(it.id) }; true }
        page.addView(list, FrameLayout.LayoutParams(-1, -1))
        return page
    }

    private fun emptyState(): LinearLayout = k.vbox(gap = dp(10)).apply {
        gravity = Gravity.CENTER; setPadding(dp(32), dp(36), dp(32), dp(20))
        val badge = FrameLayout(this@MainActivity).apply {
            background = gradient(dpf(32f), alpha(p.accent, .3f), alpha(p.violet, .18f))
            addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_down); imageTintList = android.content.res.ColorStateList.valueOf(p.tide) }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
        }
        addView(badge, LinearLayout.LayoutParams(dp(68), dp(68)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        addView(k.text("No downloads yet", 18f, p.fg, Type.bold).apply { gravity = Gravity.CENTER })
        addView(k.text("Tap + to paste a link, open the browser to find files, or share a link to VoltFetch from any app.", 14f, p.mute).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.3f) })
    }

    private fun openSearch() {
        titleRow.visibility = View.GONE; searchRow.visibility = View.VISIBLE; searchRow.enter(0, -8f)
        searchIn.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(searchIn, 0)
    }

    private fun closeSearch() {
        searchIn.setText(""); query = ""
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(searchIn.windowToken, 0)
        searchRow.visibility = View.GONE; titleRow.visibility = View.VISIBLE; titleRow.enter(0, -8f)
        refresh()
    }

    private fun toggleAll() {
        if (engine.activeCount() > 0 || engine.views().any { it.status == Status.QUEUED }) { engine.pauseAll(); toast("Paused everything") }
        else { engine.resumeAll(); DownloadService.ensureRunning(this); toast("Resumed") }
        refresh()
    }

    // ------------------------------------------------------------------ refresh

    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 500) }
    }

    override fun onResume() {
        super.onResume()
        if (themeSig != "${Palette.of(this).dark}") build()
        aurora.animate = engine.settings.motion; aurora.invalidate()
        queuesSig = ""
        handler.post(tick)
    }

    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }

    private fun refresh() {
        val all = engine.views()
        val perCat = HashMap<String, Double>()
        all.filter { it.active }.forEach { perCat[it.category] = (perCat[it.category] ?: 0.0) + it.speed }
        history.add(perCat); if (history.size > 120) history.removeAt(0)
        flow.history = history.toList()
        val total = all.filter { it.active }.sumOf { it.speed }
        val n = all.count { it.active }
        gauge.set(total, if (n > 0) "across $n ${if (n == 1) "download" else "downloads"}" else if (engine.networkAllowed) "idle" else "waiting for Wi-Fi")
        val lim = engine.limitNow
        if (lim.first > 0) {
            limitBadge.text = "Limited to ${Fmt.speed(lim.first * 1024.0)}" + if (lim.second == "rule") " · speed schedule" else ""
            limitBadge.visibility = View.VISIBLE
        } else limitBadge.visibility = View.GONE
        val busy = n > 0 || all.any { it.status == Status.QUEUED }
        pauseOrb.setImageResource(if (busy) R.drawable.ic_pause else R.drawable.ic_play)
        pauseOrb.contentDescription = if (busy) "Pause all" else "Resume all"

        renderStats(all)
        if (tab == "queues") renderQueues()
        renderChips(all)
        renderBanners(all)
        val q = query.lowercase()
        val shown = all.filter { matches(it) && (q.isEmpty() || it.name.lowercase().contains(q) || it.host.lowercase().contains(q) || it.note.lowercase().contains(q)) }
            .sortedWith(Comparator { a, b ->
                val ra = rank(a); val rb = rank(b)
                if (ra != rb) ra.compareTo(rb) else b.added.compareTo(a.added)
            })
        adapter.items = shown
        adapter.notifyDataSetChanged()
        if (firstRows && shown.isNotEmpty()) { firstRows = false; list.scheduleLayoutAnimation() }
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        (empty.getChildAt(1) as TextView).text = when { all.isEmpty() -> "No downloads yet"; q.isNotEmpty() -> "Nothing matches “$query”"; else -> "Nothing in this view" }
        (empty.getChildAt(2) as TextView).visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        selected.retainAll(all.map { it.id }.toSet())
        updateSelBar()
    }

    private fun rank(v: DView) = when { v.active -> 0; v.status == Status.QUEUED -> 1; v.status == Status.ERROR -> 2; v.status == Status.PAUSED -> 3; else -> 4 }

    private var statSig = ""
    private fun renderStats(all: List<DView>) {
        val active = all.count { it.active }
        val waiting = all.count { it.status == Status.QUEUED || it.status == Status.PAUSED }
        val done = all.count { it.status == Status.COMPLETE }
        val dayAgo = System.currentTimeMillis() - 86_400_000
        val today = all.filter { it.status == Status.COMPLETE && it.completed > dayAgo }.sumOf { it.size.coerceAtLeast(0) }
        val sig = "$active|$waiting|$done|$today|${p.dark}"
        if (sig == statSig) return
        statSig = sig
        stats.removeAllViews()
        fun stat(value: String, label: String, color: Int) {
            val b = k.vbox(gap = dp(3)).apply { gravity = Gravity.CENTER }
            b.addView(k.text(value, 18f, color, Type.black).apply { gravity = Gravity.CENTER })
            b.addView(k.text(label, 11f, p.mute).apply { gravity = Gravity.CENTER })
            stats.addView(b, lp(0, -2, 1f))
        }
        stat("$active", "Active", if (active > 0) p.tide else p.fg)
        stat("$waiting", "Waiting", if (waiting > 0) p.hold else p.fg)
        stat("$done", "Finished", if (done > 0) p.done else p.fg)
        stat(if (today > 0) Fmt.size(today) else "0", "Today", p.fg)
    }

    private fun matches(v: DView): Boolean = when (filter) {
        "all" -> true
        "active" -> v.active
        "waiting" -> v.status == Status.QUEUED || v.status == Status.PAUSED
        "done" -> v.status == Status.COMPLETE
        "failed" -> v.status == Status.ERROR
        else -> v.category == filter
    }

    private fun renderChips(all: List<DView>) {
        val counts = listOf(
            "all" to "All", "active" to "Downloading", "waiting" to "Waiting", "done" to "Finished", "failed" to "Failed",
        ).map { (key, label) -> Triple(key, label, all.count { v -> filterOf(key, v) }) }
        val cats = allCategories.map { c -> Triple(c, c, all.count { it.category == c }) }.filter { it.third > 0 }
        val sig = filter + counts.joinToString() + cats.joinToString() + p.dark
        if (sig == chipSig) return
        chipSig = sig
        chipRow.removeAllViews()
        for ((key, label, cnt) in counts) {
            if ((key == "failed" || key == "active" || key == "waiting" || key == "done") && cnt == 0 && filter != key) continue
            chipRow.addView(k.chip(if (cnt > 0 && key != "all") "$label  $cnt" else label, filter == key) { filter = key; chipSig = ""; firstRows = true; refresh() })
        }
        for ((key, label, cnt) in cats) chipRow.addView(k.chip("$label  $cnt", filter == key, p.cat(key)) { filter = key; chipSig = ""; firstRows = true; refresh() })
    }

    private fun filterOf(key: String, v: DView) = when (key) {
        "all" -> true; "active" -> v.active; "waiting" -> v.status == Status.QUEUED || v.status == Status.PAUSED
        "done" -> v.status == Status.COMPLETE; "failed" -> v.status == Status.ERROR; else -> false
    }

    // ------------------------------------------------------------------ banners: Wi-Fi wait, clipboard

    private var clipOffer: String? = null
    private var bannerSig = ""

    private fun renderBanners(all: List<DView>) {
        val app = application as VoltApp
        val waiting = all.count { it.status == Status.QUEUED }
        val wifi = app.waitingForWifi && waiting > 0
        val sig = "$wifi|$waiting|$clipOffer|${p.dark}"
        if (sig == bannerSig) return
        bannerSig = sig
        banners.removeAllViews()
        if (wifi) banners.addView(banner(R.drawable.ic_wifi, p.hold, "Waiting for Wi-Fi", "$waiting ${if (waiting == 1) "download starts" else "downloads start"} when you connect. You can turn off Wi-Fi only in Settings.", null, null))
        clipOffer?.let { url ->
            val name = com.voltfetch.engine.Names.fromUrl(url)
            banners.addView(banner(R.drawable.ic_link, p.accent, "Download the link you copied?", name, "Download") {
                clipOffer = null; bannerSig = ""
                startActivity(Intent(this, AddActivity::class.java).putExtra(Intent.EXTRA_TEXT, url))
            })
        }
    }

    private fun banner(icon: Int, tint: Int, title: String, text: String, action: String?, onAction: (() -> Unit)?): View {
        val row = k.hbox(dp(12))
        val ic = FrameLayout(this).apply { background = rounded(alpha(tint, .18f), dpf(14f)); addView(k.icon(icon, tint, 20).apply { layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER) }) }
        row.addView(ic, LinearLayout.LayoutParams(dp(40), dp(40)))
        val tx = k.vbox(gap = dp(3))
        tx.addView(k.text(title, 14f, p.fg, Type.medium))
        tx.addView(k.text(text, 12.5f, p.mute, lines = 2))
        row.addView(tx, lp(0, -2, 1f))
        if (action != null && onAction != null) row.addView(k.button(action, "primary") { onAction() }.apply { minHeight = dp(40); minimumHeight = dp(40) }, LinearLayout.LayoutParams(-2, dp(40)))
        val holder = arrayOfNulls<View>(1)
        row.addView(k.iconButton(R.drawable.ic_x, "Dismiss") {
            clipOffer = null; bannerSig = ""
            holder[0]?.let { c -> c.animate().alpha(0f).setDuration(160).withEndAction { banners.removeView(c) }.start() }
        })
        val card = k.card(row, dp(12)).also { it.layoutParams = lp().apply { bottomMargin = dp(8) } }
        holder[0] = card
        card.enter()
        return card
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !engine.settings.watchClipboard) return
        // Android only lets the focused app read the clipboard.
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull() ?: return
        val url = Links.find(text).firstOrNull() ?: return
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (prefs.getString("lastClip", "") == url) return
        prefs.edit().putString("lastClip", url).apply()
        if (engine.views().any { it.url == url }) return
        clipOffer = url
        bannerSig = ""
        refresh()
    }

    // ------------------------------------------------------------------ selection

    private fun onRowClick(v: DView) {
        if (selected.isNotEmpty()) { toggle(v.id); return }
        startActivity(Intent(this, DetailActivity::class.java).putExtra("id", v.id))
        @Suppress("DEPRECATION") overridePendingTransition(R.anim.enter_up, R.anim.hold)
    }

    private fun toggle(id: Long) {
        if (!selected.add(id)) selected.remove(id)
        updateSelBar(); adapter.notifyDataSetChanged()
    }

    private fun clearSelection() { selected.clear(); updateSelBar(); adapter.notifyDataSetChanged() }

    private fun updateSelBar() {
        val show = selected.isNotEmpty()
        if (show && selBar.visibility != View.VISIBLE) { selBar.visibility = View.VISIBLE; selBar.enter(0, 8f) }
        if (!show) selBar.visibility = View.GONE
        selCount.text = "${selected.size} selected"
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            selected.isNotEmpty() -> clearSelection()
            searchRow.visibility == View.VISIBLE -> closeSearch()
            tab != "downloads" -> select("downloads")
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    private fun pickQueue(ids: List<Long>) {
        val names = engine.settings.queueNames()
        dialog().setTitle("Move to queue").setItems(names.map { if (it == MAIN_QUEUE) "Main (starts right away)" else it }.toTypedArray()) { _, i ->
            engine.moveToQueue(ids, names[i]); clearSelection(); toast("Moved to ${names[i]}")
        }.setNegativeButton("Cancel", null).show()
    }

    private fun confirmDelete(ids: List<Long>) {
        if (ids.isEmpty()) return
        val anyDone = ids.any { engine.view(it)?.status == Status.COMPLETE }
        val cb = CheckBox(this).apply { text = "Also delete the files from the phone"; setTextColor(p.fg); buttonTintList = android.content.res.ColorStateList.valueOf(p.accent) }
        val box = k.vbox().apply { setPadding(dp(22), dp(8), dp(22), 0) }
        box.addView(k.text("They're removed from your list. Unfinished parts are cleaned up.", 14f, p.mute))
        if (anyDone) box.addView(cb)
        dialog().setTitle(if (ids.size == 1) "Delete this download?" else "Delete ${ids.size} downloads?")
            .setView(box)
            .setPositiveButton("Delete") { _, _ ->
                Thread { engine.remove(ids, cb.isChecked) }.start()
                clearSelection()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun dialog() = AlertDialog.Builder(this, if (p.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /** If the app crashed last time, show the error so it can be reported. */
    private fun showLastCrash() {
        val f = java.io.File(filesDir, VoltApp.CRASH_FILE)
        val report = runCatching { f.takeIf { it.exists() }?.readText() }.getOrNull() ?: return
        f.delete()
        val tv = k.text(report, 11f, p.fg).apply { setTextIsSelectable(true); setPadding(dp(22), dp(8), dp(22), dp(8)) }
        val sv = ScrollView(this).apply { addView(tv) }
        dialog().setTitle("VoltFetch had a problem last time")
            .setView(sv)
            .setPositiveButton("Copy report") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("VoltFetch crash report", report))
                toast("Report copied")
            }
            .setNegativeButton("Close", null).show()
    }

    // ------------------------------------------------------------------ rows

    private inner class Holder(val root: FrameLayout, val card: LinearLayout) {
        val tile = FrameLayout(this@MainActivity)
        val tileIcon = ImageView(this@MainActivity)
        val dot = View(this@MainActivity)
        val name = k.text("", 15f, p.fg, Type.medium, lines = 1)
        val status = k.text("", 12.5f, p.mute, lines = 1)
        val tags = k.hbox(dp(6))
        val bar = SegmentBar(this@MainActivity, p)
        val left = k.text("", 12f, p.mute, lines = 1)
        val right = k.text("", 12f, p.fg, Type.medium)
        val action = k.orb(R.drawable.ic_play, "Resume") {}
        var id = 0L
        var tagSig = ""
    }

    private inner class Rows : BaseAdapter() {
        var items: List<DView> = emptyList()
        override fun getCount() = items.size
        override fun getItem(i: Int) = items[i]
        override fun getItemId(i: Int) = items[i].id

        override fun getView(pos: Int, convert: View?, parent: ViewGroup): View {
            val h = (convert?.tag as? Holder) ?: newHolder()
            bind(h, items[pos])
            return h.root
        }

        private fun newHolder(): Holder {
            val outer = FrameLayout(this@MainActivity).apply { setPadding(dp(14), dp(5), dp(14), dp(5)); layoutParams = AbsListView.LayoutParams(-1, -2) }
            val row = k.hbox(dp(14)).apply { setPadding(dp(14), dp(14), dp(10), dp(14)) }
            outer.addView(row, FrameLayout.LayoutParams(-1, -2))
            val h = Holder(outer, row)
            h.tile.addView(h.tileIcon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            h.tile.addView(h.dot, FrameLayout.LayoutParams(dp(13), dp(13), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, -dp(2), -dp(2)) })
            h.tile.clipChildren = false
            row.addView(h.tile, LinearLayout.LayoutParams(dp(46), dp(46)))
            val mid = k.vbox(gap = dp(5))
            mid.addView(h.name)
            val st = k.hbox(dp(6)); st.addView(h.status, LinearLayout.LayoutParams(0, -2, 1f)); st.addView(h.tags)
            mid.addView(st)
            mid.addView(h.bar, lp(-1, dp(6)).apply { topMargin = dp(4) })
            val bottom = k.hbox(dp(8))
            bottom.addView(h.left, lp(0, -2, 1f)); bottom.addView(h.right)
            mid.addView(bottom)
            row.addView(mid, lp(0, -2, 1f))
            row.addView(h.action, LinearLayout.LayoutParams(dp(42), dp(42)))
            outer.tag = h
            return h
        }

        private fun bind(h: Holder, v: DView) {
            h.id = v.id
            val c = p.cat(v.category)
            h.tile.background = gradient(dpf(15f), alpha(c, .36f), alpha(c, .10f))
            h.tileIcon.setImageResource(categoryIcon(v.category)); h.tileIcon.imageTintList = android.content.res.ColorStateList.valueOf(c)
            h.dot.background = rounded(p.status(v.status), dpf(7f), p.ink, dp(2))
            val sel = v.id in selected
            h.card.background = if (sel) android.graphics.drawable.LayerDrawable(arrayOf(glass(p, dpf(22f), this@MainActivity), rounded(alpha(p.accent, .14f), dpf(22f), p.accent, dp(2))))
                else glass(p, dpf(22f), this@MainActivity)
            h.name.text = v.name
            val place = if (v.status == Status.QUEUED) engine.placeInLine(v.id) else 0
            val st = when {
                v.status == Status.DOWNLOADING && v.isMedia -> v.taskStage.ifEmpty { "Downloading media" }
                v.status == Status.DOWNLOADING -> "${v.segs.count { it.active }} connections"
                v.status == Status.CONNECTING -> "Connecting…"
                v.status == Status.QUEUED && (application as VoltApp).waitingForWifi -> "Waiting for Wi-Fi"
                v.status == Status.QUEUED && !engine.queueRunning(v.queue) -> "Waits for its queue"
                v.status == Status.QUEUED -> if (place > 0) "#$place in line" else "Waiting to start"
                v.status == Status.COMPLETE && v.verify == "ok" -> "Verified · ${whenText(v.completed)}"
                v.status == Status.COMPLETE && v.verify == "mismatch" -> "Checksum doesn't match"
                v.status == Status.COMPLETE -> "Finished ${whenText(v.completed)}"
                v.status == Status.ERROR -> v.error.ifEmpty { "Failed" }
                else -> v.status.label()
            }
            h.status.text = st
            h.status.setTextColor(when {
                v.status == Status.ERROR || v.verify == "mismatch" -> p.fault
                v.status == Status.COMPLETE -> p.done
                v.status == Status.PAUSED -> p.hold
                v.active -> p.tide
                else -> p.mute
            })
            val tagSig = "${v.queue}|${p.dark}"
            if (tagSig != h.tagSig) {
                h.tagSig = tagSig; h.tags.removeAllViews()
                if (v.queue != MAIN_QUEUE) h.tags.addView(k.badge(v.queue, p.violet, R.drawable.ic_calendar).apply { textSize = 10.5f; setPadding(dp(7), dp(2), dp(8), dp(2)) })
            }
            h.bar.tint = c
            h.bar.data = v
            h.bar.visibility = if (v.status == Status.COMPLETE) View.GONE else View.VISIBLE
            val pct = v.percent
            h.left.text = when {
                v.status == Status.COMPLETE -> listOf(Fmt.size(v.size), if (v.taskKind.isNotEmpty()) v.note else v.host).filter { it.isNotEmpty() }.joinToString("  ·  ")
                v.taskKind.isNotEmpty() && pct >= 0 -> "${if (pct < 10) String.format("%.1f", pct) else pct.toInt().toString()}% complete  ·  ${v.note}"
                v.size > 0 -> "${if (pct < 10) String.format("%.1f", pct) else pct.toInt().toString()}%  ·  ${Fmt.size(v.got)} of ${Fmt.size(v.size)}"
                v.got > 0 -> Fmt.size(v.got)
                else -> v.host
            }
            h.right.text = if (v.active) listOf(Fmt.speed(v.speed), Fmt.eta(v.etaSeconds)).filter { it.isNotEmpty() }.joinToString("  ·  ") else ""
            h.right.setTextColor(p.tide)
            val (res, desc) = when {
                v.active -> R.drawable.ic_pause to "Pause"
                v.status == Status.COMPLETE -> R.drawable.ic_open to "Open"
                v.status == Status.ERROR -> R.drawable.ic_refresh to "Retry"
                v.status == Status.QUEUED -> R.drawable.ic_pause to "Pause"
                else -> R.drawable.ic_play to "Resume"
            }
            h.action.setImageResource(res); h.action.contentDescription = desc; h.action.tooltipText = desc
            h.action.imageTintList = android.content.res.ColorStateList.valueOf(when { v.status == Status.COMPLETE -> p.accent; v.active -> p.fg; v.status == Status.ERROR -> p.fault; else -> p.accent })
            h.action.setOnClickListener {
                haptic(it)
                when {
                    v.active || v.status == Status.QUEUED -> engine.pause(listOf(v.id))
                    v.status == Status.COMPLETE -> Files.openIntent(this@MainActivity, v)?.let { i -> runCatching { startActivity(i) } }
                    else -> { engine.resume(listOf(v.id)); DownloadService.ensureRunning(this@MainActivity) }
                }
                refresh()
            }
        }
    }

    // ------------------------------------------------------------------ queues & scheduler page

    private fun buildQueues(): ScrollView {
        queuesBody = k.vbox()
        return ScrollView(this).apply { addView(queuesBody); isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; visibility = View.GONE }
    }

    private fun renderQueues() {
        val s = engine.settings
        val all = engine.views()
        val sig = s.queues.toString() + s.speedRules + s.simultaneous + s.limitOn + s.limitKBs + p.dark + Schedules.canBeExact(this) +
            s.queueNames().joinToString { q -> "$q:${engine.queueRunning(q)}:" + all.filter { it.queue == q }.joinToString(",") { "${it.status}${(it.percent).toInt()}" } }
        if (sig == queuesSig) return
        val first = queuesSig.isEmpty()
        queuesSig = sig
        val b = queuesBody
        b.removeAllViews()
        b.addView(k.title("Queues"))
        b.addView(k.text("Run downloads in batches, on a timetable, at a speed that suits you.", 13.5f, p.mute).apply { setPadding(dp(2), dp(6), 0, dp(4)); setLineSpacing(0f, 1.2f) })

        if (s.queues.any { it.schedule.enabled } && !Schedules.canBeExact(this)) {
            val row = k.hbox(dp(12))
            row.addView(k.icon(R.drawable.ic_clock, p.hold, 22))
            row.addView(k.text("Allow “Alarms & reminders” so scheduled queues start on the minute, even when the phone is asleep.", 13f, p.fg).apply { setLineSpacing(0f, 1.2f) }, lp(0, -2, 1f))
            row.addView(k.button("Allow", "tonal") {
                runCatching { startActivity(Intent(SysSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(android.net.Uri.parse("package:$packageName"))) }
            }.apply { minHeight = dp(40); minimumHeight = dp(40) }, LinearLayout.LayoutParams(-2, dp(40)))
            b.addView(k.card(row, dp(14)).also { it.layoutParams = lp().apply { topMargin = dp(12) } })
        }

        b.addView(k.sectionTitle("Queues"))
        var i = 0
        for (name in s.queueNames()) {
            val card = queueCard(name, all)
            b.addView(card, lp().apply { bottomMargin = dp(10) })
            if (first) card.enter(40L * i++)
        }
        b.addView(k.button("New queue", "ghost", R.drawable.ic_plus) { startActivity(Intent(this, QueueActivity::class.java)) }, lp(-1, dp(52)))

        b.addView(k.sectionTitle("Speed schedule"))
        b.addView(k.text(if (s.limitOn) "A manual limit of ${Fmt.speed(s.limitKBs * 1024.0)} is on in Settings. It overrides these rules until you turn it off."
            else "Slow downloads down at set times, for example during work hours. When rules overlap, the lowest limit wins.", 13f, if (s.limitOn) p.hold else p.mute).apply { setPadding(dp(4), 0, 0, dp(10)); setLineSpacing(0f, 1.2f) })
        val now = java.util.Calendar.getInstance()
        for ((idx, r) in s.speedRules.withIndex()) {
            val row = k.hbox(dp(12)).apply { setPadding(dp(14), dp(12), dp(8), dp(12)) }
            val active = r.active(now)
            val ic = FrameLayout(this).apply { background = rounded(alpha(if (active) p.tide else p.mute, .16f), dpf(14f)); addView(k.icon(R.drawable.ic_gauge, if (active) p.tide else p.mute, 20).apply { layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER) }) }
            row.addView(ic, LinearLayout.LayoutParams(dp(42), dp(42)))
            val tx = k.vbox(gap = dp(3))
            tx.addView(k.text("${Fmt.speed(r.kbs * 1024.0)}  ·  ${r.from}–${r.to}", 15f, p.fg, Type.medium))
            tx.addView(k.text(dayText(r.days).replaceFirstChar { it.uppercase() } + if (active) "  ·  in force now" else "", 12.5f, if (active) p.tide else p.mute))
            row.addView(tx, lp(0, -2, 1f))
            val sw = Switch(this).apply {
                isChecked = r.enabled; styleSwitch(this)
                setOnCheckedChangeListener { _, on -> val l = engine.settings.speedRules.toMutableList(); if (idx < l.size) { l[idx] = l[idx].copy(enabled = on); engine.updateSettings(engine.settings.copy(speedRules = l)) } }
            }
            row.addView(sw)
            val card = k.card(row, 0)
            card.setOnClickListener { editRule(idx) }
            card.foreground = ripple(null, alpha(p.accent, .18f), rounded(Color.WHITE, dpf(22f)))
            card.pressable(.98f)
            b.addView(card, lp().apply { bottomMargin = dp(10) })
        }
        b.addView(k.button("Add a speed rule", "ghost", R.drawable.ic_plus) { editRule(-1) }, lp(-1, dp(52)))
    }

    private fun queueCard(name: String, all: List<DView>): View {
        val s = engine.settings
        val q = s.queue(name)
        val files = all.filter { it.queue == name }
        val unfinished = files.filter { it.status != Status.COMPLETE }
        val running = engine.queueRunning(name)
        val active = files.count { it.active }
        val box = k.vbox(gap = dp(10)).apply { setPadding(dp(16), dp(16), dp(12), dp(16)) }
        val top = k.hbox(dp(12))
        val tint = if (name == MAIN_QUEUE) p.accent else p.violet
        val ic = FrameLayout(this).apply { background = gradient(dpf(15f), alpha(tint, .38f), alpha(tint, .12f)); addView(k.icon(if (name == MAIN_QUEUE) R.drawable.ic_bolt else R.drawable.ic_calendar, tint, 21).apply { layoutParams = FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER) }) }
        top.addView(ic, LinearLayout.LayoutParams(dp(44), dp(44)))
        val tx = k.vbox(gap = dp(3))
        tx.addView(k.text(name, 16.5f, p.fg, Type.bold))
        tx.addView(k.text(when {
            name == MAIN_QUEUE -> "Starts downloads right away · ${s.simultaneous} at a time"
            else -> "${q?.schedule?.describe() ?: ""} · ${q?.maxConcurrent ?: 1} at a time"
        }, 12.5f, p.mute, lines = 2))
        top.addView(tx, lp(0, -2, 1f))
        if (name != MAIN_QUEUE) {
            val btn = k.button(if (running) "Stop" else "Start", if (running) "ghost" else "primary", if (running) R.drawable.ic_pause else R.drawable.ic_play) {
                if (running) engine.stopQueue(name) else { engine.startQueue(name); DownloadService.ensureRunning(this) }
                queuesSig = ""; renderQueues()
            }.apply { minHeight = dp(40); minimumHeight = dp(40); setPadding(dp(14), 0, dp(16), 0) }
            top.addView(btn, LinearLayout.LayoutParams(-2, dp(40)))
        }
        box.addView(top)
        val totalSize = unfinished.filter { it.size > 0 }.sumOf { it.size }
        val got = unfinished.filter { it.size > 0 }.sumOf { it.got }
        val line = k.hbox(dp(8))
        line.addView(k.badge(when {
            running && active > 0 -> "Running · $active active"
            running && name != MAIN_QUEUE -> "Running"
            name == MAIN_QUEUE -> "Always on"
            else -> "Stopped"
        }, if (running && (active > 0 || name != MAIN_QUEUE)) p.done else p.mute))
        line.addView(k.text("${unfinished.size} to do · ${files.size - unfinished.size} done" + if (totalSize > 0) " · ${Fmt.size(totalSize - got)} left" else "", 12.5f, p.mute, lines = 1), lp(0, -2, 1f))
        box.addView(line)
        if (totalSize > 0) {
            val frac = (got.toDouble() / totalSize).toFloat().coerceIn(0f, 1f)
            val track = LinearLayout(this).apply { background = rounded(p.track, dpf(3f)) }
            val bar = View(this).apply { background = gradient(dpf(3f), tint, p.tide, orientation = android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT) }
            track.addView(bar, LinearLayout.LayoutParams(0, -1, frac.coerceAtLeast(0.001f)))
            track.addView(View(this), LinearLayout.LayoutParams(0, -1, (1f - frac).coerceAtLeast(0.001f)))
            box.addView(track, lp(-1, dp(6)))
        }
        val card = k.card(box, 0)
        card.foreground = ripple(null, alpha(p.accent, .18f), rounded(Color.WHITE, dpf(22f)))
        card.setOnClickListener { startActivity(Intent(this, QueueActivity::class.java).putExtra("name", name)) }
        card.pressable(.985f)
        return card
    }

    private fun styleSwitch(sw: Switch) {
        sw.thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Color.WHITE, p.faint))
        sw.trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(p.accent, p.track))
    }

    /** Add (index -1) or edit a time-based speed rule. */
    private fun editRule(index: Int) {
        val rules = engine.settings.speedRules
        var r = rules.getOrNull(index) ?: SpeedRule()
        val box = k.vbox(gap = dp(12)).apply { setPadding(dp(22), dp(10), dp(22), 0) }
        val times = k.hbox(dp(10))
        val fromBtn = k.button("From ${r.from}", "ghost", R.drawable.ic_clock) {}
        val toBtn = k.button("To ${r.to}", "ghost", R.drawable.ic_clock) {}
        fun pick(isFrom: Boolean) {
            val m = minutesOf(if (isFrom) r.from else r.to).coerceAtLeast(0)
            TimePickerDialog(this, { _, h, mi -> val t = hm(h * 60 + mi); r = if (isFrom) r.copy(from = t) else r.copy(to = t); fromBtn.text = "From ${r.from}"; toBtn.text = "To ${r.to}" },
                m / 60, m % 60, android.text.format.DateFormat.is24HourFormat(this)).show()
        }
        fromBtn.setOnClickListener { pick(true) }; toBtn.setOnClickListener { pick(false) }
        times.addView(fromBtn, lp(0, dp(48), 1f)); times.addView(toBtn, lp(0, dp(48), 1f))
        box.addView(times)
        box.addView(k.text("Overnight windows such as 22:00–06:00 work. Days are the day the window starts.", 12f, p.mute))
        val days = k.hbox(dp(5))
        val picks = r.days.toMutableList()
        fun renderDays() {
            days.removeAllViews()
            for (d in 0..6) days.addView(k.chip(DAY_SHORT[d].take(2), picks[d]) { picks[d] = !picks[d]; renderDays() }.apply { setPadding(0, 0, 0, 0) }, lp(0, dp(38), 1f))
        }
        renderDays()
        box.addView(days)
        val kbs = k.input("KB/s", "${r.kbs}").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val kRow = k.hbox(dp(10)); kRow.addView(k.text("Limit (KB/s)", 14f, p.fg), lp(0, -2, 1f)); kRow.addView(kbs, LinearLayout.LayoutParams(dp(130), -2))
        box.addView(kRow)
        box.addView(k.text("1024 KB/s = 1 MB/s", 12f, p.faint))
        val d = dialog().setTitle(if (index < 0) "New speed rule" else "Speed rule").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                val nr = r.copy(days = picks.toList(), kbs = (kbs.text.toString().toIntOrNull() ?: r.kbs).coerceAtLeast(16))
                val l = engine.settings.speedRules.toMutableList()
                if (index in l.indices) l[index] = nr else l.add(nr)
                engine.updateSettings(engine.settings.copy(speedRules = l)); queuesSig = ""; renderQueues()
            }
            .setNegativeButton("Cancel", null)
        if (index >= 0) d.setNeutralButton("Delete") { _, _ ->
            dialog().setTitle("Delete this speed rule?").setPositiveButton("Delete") { _, _ ->
                engine.updateSettings(engine.settings.copy(speedRules = engine.settings.speedRules.filterIndexed { i, _ -> i != index })); queuesSig = ""; renderQueues()
            }.setNegativeButton("Cancel", null).show()
        }
        d.show()
    }

    companion object { const val REQ_IMPORT = 7 }
}
