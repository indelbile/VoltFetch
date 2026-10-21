package com.voltfetch.app

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.voltfetch.engine.Categories
import com.voltfetch.engine.Engine
import com.voltfetch.engine.Links
import com.voltfetch.engine.Names

/** Site grabber: lists every file of the chosen kinds linked from a web page. */
class GrabberActivity : Screen() {
    override val screenTitle = "Site grabber"
    override val wantsBottomBar = true
    private lateinit var urlIn: EditText
    private lateinit var extIn: EditText
    private lateinit var kindRow: LinearLayout
    private lateinit var results: LinearLayout
    private lateinit var summary: TextView
    private lateinit var scanBtn: TextView
    private lateinit var startBtn: TextView
    private lateinit var queuePick: () -> String
    private val kinds = linkedSetOf("Compressed", "Programs", "Video", "Music", "Documents")
    private var found: List<String> = emptyList()
    private val picked = LinkedHashSet<String>()
    private var gen = 0

    override fun content() {
        body.addView(k.text("Paste a web page address. VoltFetch reads the page and lists the files it links to, so you can pick the ones you want.", 13.5f, p.mute).apply { setLineSpacing(0f, 1.25f); setPadding(dp(4), 0, dp(4), dp(4)) })
        body.addView(k.sectionTitle("Page"))
        val start = intent.getStringExtra("url") ?: Links.find(intent.getStringExtra(android.content.Intent.EXTRA_TEXT)).firstOrNull() ?: ""
        urlIn = k.input("https://site.com/downloads/", start).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ -> scan(); true }
        }
        body.addView(urlIn)
        body.addView(k.sectionTitle("Find these kinds of files"))
        kindRow = k.hbox(dp(8))
        val (sv, row) = k.chipRow(); row.setPadding(0, 0, 0, 0); kindRow = row
        body.addView(sv)
        renderKinds()
        extIn = k.input("Other extensions, e.g. torrent, srt")
        body.addView(extIn, lp().apply { topMargin = dp(10) })
        scanBtn = k.button("Scan page", "tonal", R.drawable.ic_scan) { scan() }
        body.addView(scanBtn, lp(-1, dp(50)).apply { topMargin = dp(12) })

        body.addView(k.sectionTitle("Queue"))
        val (qv, qget) = queueChips(com.voltfetch.engine.MAIN_QUEUE); queuePick = qget
        body.addView(qv)

        val head = k.hbox(dp(8)).apply { setPadding(dp(4), dp(22), 0, dp(10)) }
        summary = k.text("", 14f, p.fg, Type.medium)
        head.addView(summary, lp(0, -2, 1f))
        head.addView(k.button("All", "tonal") { picked.addAll(found); renderResults() }.apply { minHeight = dp(36); minimumHeight = dp(36) }, LinearLayout.LayoutParams(-2, dp(36)))
        head.addView(k.button("None", "ghost") { picked.clear(); renderResults() }.apply { minHeight = dp(36); minimumHeight = dp(36) }, LinearLayout.LayoutParams(-2, dp(36)))
        body.addView(head)
        results = k.vbox(gap = dp(6))
        body.addView(results)

        startBtn = k.button("Download", "primary", R.drawable.ic_down) { submit(true) }
        bottomBar?.addView(k.button("Save for later", "ghost") { submit(false) }, lp(0, dp(52), 1f))
        bottomBar?.addView(startBtn, lp(0, dp(52), 1.3f))
        renderResults()
        if (start.isNotEmpty()) scan()
    }

    private fun renderKinds() {
        kindRow.removeAllViews()
        for (c in Categories.all.keys) kindRow.addView(k.chip(c, c in kinds, p.cat(c)) { if (!kinds.remove(c)) kinds.add(c); renderKinds() })
    }

    private fun exts(): Set<String> {
        val s = HashSet<String>()
        for (c in kinds) Categories.all[c]?.let { s.addAll(it) }
        extIn.text.toString().split(',', ' ', ';').map { it.trim().removePrefix(".").lowercase() }.filter { it.isNotEmpty() }.forEach { s.add(it) }
        return s
    }

    private fun scan() {
        var url = urlIn.text.toString().trim()
        if (url.isEmpty()) { toast("Paste a page address first"); return }
        if (!url.startsWith("http://") && !url.startsWith("https://")) { url = "https://$url"; urlIn.setText(url) }
        val want = exts()
        if (want.isEmpty()) { toast("Pick at least one kind of file"); return }
        val my = ++gen
        summary.text = "Reading the page…"
        results.removeAllViews()
        results.addView(FrameLayout(this).apply { addView(ProgressBar(this@GrabberActivity).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(p.accent) }, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER)) }, lp(-1, dp(80)))
        currentFocus?.let { getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
        Thread {
            val r = runCatching { engine.grab(url, want) }
            runOnUiThread {
                if (my != gen || isFinishing) return@runOnUiThread
                r.onSuccess { found = it; picked.clear(); picked.addAll(it); renderResults() }
                    .onFailure { found = emptyList(); picked.clear(); renderResults(); summary.text = "Couldn't read the page: ${it.message ?: "network error"}"; summary.setTextColor(p.fault) }
            }
        }.start()
    }

    private fun renderResults() {
        results.removeAllViews()
        summary.setTextColor(p.fg)
        summary.text = when {
            found.isEmpty() && gen == 0 -> "Results appear here"
            found.isEmpty() -> "No matching files on that page"
            else -> "${found.size} files found · ${picked.size} selected"
        }
        for ((i, u) in found.withIndex()) {
            val name = Names.fromUrl(u)
            val cat = Categories.of(name)
            val row = k.hbox(dp(12)).apply { setPadding(dp(10), dp(8), dp(12), dp(8)) }
            val cb = CheckBox(this).apply { isChecked = u in picked; buttonTintList = android.content.res.ColorStateList.valueOf(p.accent); isClickable = false }
            row.addView(cb)
            row.addView(k.tile(cat, 34))
            val tx = k.vbox(gap = dp(2))
            tx.addView(k.text(name, 14f, p.fg, Type.medium, lines = 1))
            tx.addView(k.text(runCatching { java.net.URI(u).host ?: "" }.getOrDefault(""), 11.5f, p.mute, lines = 1))
            row.addView(tx, lp(0, -2, 1f))
            val card = k.card(row, 0)
            card.setOnClickListener { haptic(it); if (!picked.remove(u)) picked.add(u); cb.isChecked = u in picked; summary.text = "${found.size} files found · ${picked.size} selected"; updateStart() }
            results.addView(card)
            if (i < 12) card.enter(i * 25L)
        }
        updateStart()
    }

    private fun updateStart() {
        startBtn.text = if (picked.size > 1) "Download ${picked.size}" else "Download"
        startBtn.alpha = if (picked.isEmpty()) .45f else 1f
    }

    private fun submit(start: Boolean) {
        if (picked.isEmpty()) { toast("Select some files first"); return }
        val q = queuePick()
        val referer = urlIn.text.toString().trim()
        for (u in picked) engine.add(Engine.AddRequest(url = u, referer = referer, start = start, queue = q))
        if (start) DownloadService.ensureRunning(this)
        toast("${if (start) "Downloading" else "Saved"} ${picked.size} files")
        finish()
    }
}
