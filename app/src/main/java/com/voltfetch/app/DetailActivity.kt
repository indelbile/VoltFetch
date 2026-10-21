package com.voltfetch.app

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.voltfetch.engine.Checksums
import com.voltfetch.engine.Fmt
import com.voltfetch.engine.MAIN_QUEUE
import com.voltfetch.engine.Status
import com.voltfetch.engine.View as DView

class DetailActivity : Screen() {
    override val screenTitle = "Details"
    override val wantsBottomBar = true
    override val topActions get() = listOf(Triple(R.drawable.ic_trash, "Delete") { confirmDelete() })
    private val handler = Handler(Looper.getMainLooper())
    private var id = 0L
    private val speeds = ArrayList<Double>()

    private lateinit var tile: FrameLayout
    private lateinit var title: TextView
    private lateinit var sub: LinearLayout
    private lateinit var ring: RingView
    private lateinit var stats: GridLayout
    private lateinit var loomTitle: TextView
    private lateinit var loom: LoomView
    private lateinit var loomBox: View
    private lateinit var chart: ChartView
    private lateinit var chartTitle: TextView
    private lateinit var chartBox: View
    private lateinit var facts: LinearLayout
    private var actionSig = ""
    private var factSig = ""
    private var headSig = ""

    override fun content() {
        id = intent.getLongExtra("id", 0)
        if (engine.view(id) == null) { finish(); return }
        // the big title is replaced by the file header
        body.removeAllViews()
        actionSig = ""; factSig = ""; headSig = ""

        val head = k.hbox(dp(14)).apply { setPadding(dp(2), dp(4), 0, dp(10)) }
        tile = FrameLayout(this)
        head.addView(tile, LinearLayout.LayoutParams(dp(56), dp(56)))
        val tt = k.vbox(gap = dp(6))
        title = k.text("", 19f, p.fg, Type.bold, lines = 3).apply { setLineSpacing(0f, 1.1f) }
        sub = k.hbox(dp(6))
        tt.addView(title); tt.addView(sub)
        head.addView(tt, lp(0, -2, 1f))
        head.addView(k.iconButton(R.drawable.ic_edit, "Rename") { rename() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        body.addView(head)

        val heroBox = k.hbox(dp(10)).apply { setPadding(dp(8), dp(12), dp(12), dp(12)) }
        ring = RingView(this, p)
        heroBox.addView(ring, LinearLayout.LayoutParams(dp(150), dp(150)))
        stats = GridLayout(this).apply { columnCount = 2 }
        heroBox.addView(stats, lp(0, -2, 1f))
        body.addView(k.card(heroBox, 0))

        val loomInner = k.vbox(gap = dp(12))
        loomTitle = k.text("", 13f, p.mute, Type.medium)
        loom = LoomView(this, p)
        loomInner.addView(loomTitle); loomInner.addView(loom, lp())
        loomBox = k.card(loomInner).also { it.layoutParams = lp().apply { topMargin = dp(12) } }
        body.addView(loomBox)

        val chartInner = k.vbox(gap = dp(10))
        chartTitle = k.text("", 13f, p.mute, Type.medium)
        chart = ChartView(this, p)
        chartInner.addView(chartTitle); chartInner.addView(chart, lp(-1, dp(96)))
        chartBox = k.card(chartInner).also { it.layoutParams = lp().apply { topMargin = dp(12) } }
        body.addView(chartBox)

        body.addView(k.sectionTitle("Details"))
        facts = k.vbox()
        body.addView(k.card(facts, dp(2)))
        refresh()
    }

    private val tick = object : Runnable { override fun run() { refresh(); handler.postDelayed(this, 500) } }
    override fun onResume() { super.onResume(); if (::ring.isInitialized) handler.post(tick) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }

    private fun refresh() {
        val v = engine.view(id) ?: run { finish(); return }
        if (v.active || speeds.isNotEmpty()) { speeds.add(if (v.active) v.speed else 0.0); if (speeds.size > 120) speeds.removeAt(0) }

        val c = p.cat(v.category)
        val hs = "${v.name}|${v.category}|${v.queue}|${v.host}|${p.dark}"
        if (hs != headSig) {
            headSig = hs
            tile.background = gradient(dpf(17f), alpha(c, .4f), alpha(c, .12f))
            tile.removeAllViews()
            tile.addView(k.icon(categoryIcon(v.category), c, 28).apply { layoutParams = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER) })
            title.text = v.name
            titleView.text = v.name
            sub.removeAllViews()
            sub.addView(k.badge(v.category, c))
            if (v.queue != MAIN_QUEUE) sub.addView(k.badge(v.queue, p.violet, R.drawable.ic_calendar))
            if (v.host.isNotEmpty()) sub.addView(k.text(v.host, 12.5f, p.mute, lines = 1))
        }

        val pct = v.percent
        ring.set(pct.coerceAtLeast(0.0), v.status, if (pct >= 0) "${pct.toInt()}%" else Fmt.size(v.got), if (v.isMedia && v.active && v.taskStage.isNotEmpty()) v.taskStage else v.status.label())
        val conns = v.segs.count { it.active }
        stats.removeAllViews()
        stat(if (v.active) Fmt.speed(v.speed).ifEmpty { "—" } else "—", "Speed", if (v.active) p.tide else p.fg)
        stat(if (v.active) Fmt.eta(v.etaSeconds).ifEmpty { "—" } else if (v.status == Status.COMPLETE) "Done" else "—", "Time left")
        stat(if (v.size > 0) Fmt.size(v.size) else "Unknown", "Size")
        if (v.isMedia) stat(if (v.taskKind == "youtube_audio") "${v.taskOption} kbps" else "≤ ${v.taskOption}p",
            if (v.status == Status.QUEUED) engine.placeInLine(id).takeIf { it > 0 }?.let { "Quality · #$it in line" } ?: "Quality" else "Quality")
        else stat(when {
            v.active -> "$conns"
            v.status == Status.QUEUED -> engine.placeInLine(id).takeIf { it > 0 }?.let { "#$it" } ?: "—"
            v.status == Status.COMPLETE -> "—"
            else -> "${v.segs.size}"
        }, when { v.active -> "Connections"; v.status == Status.QUEUED -> "In line"; else -> "Ranges" })

        renderActions(v)
        loomBox.visibility = if (v.status != Status.COMPLETE && v.segs.isNotEmpty() && v.size > 0) View.VISIBLE else View.GONE
        loomTitle.text = "CONNECTIONS  ·  $conns of ${v.segs.size} active"
        loom.data = v
        chartBox.visibility = if (speeds.size > 1) View.VISIBLE else View.GONE
        chartTitle.text = "SPEED  ·  peak ${Fmt.speed(speeds.maxOrNull() ?: 0.0)}"
        chart.values = speeds.toList()
        renderFacts(v)
    }

    private fun stat(value: String, label: String, color: Int = p.fg) {
        val b = k.vbox(gap = dp(3)).apply { setPadding(dp(6), dp(8), dp(4), dp(8)) }
        b.addView(k.text(value, 18f, color, Type.black, lines = 1))
        b.addView(k.text(label, 11.5f, p.mute))
        stats.addView(b, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 })
    }

    private fun renderActions(v: DView) {
        val sig = "${v.status}|${v.active}"
        if (sig == actionSig) return
        actionSig = sig
        val bb = bottomBar ?: return
        bb.removeAllViews()
        when {
            v.status == Status.COMPLETE -> {
                bb.addView(k.button("Share", "ghost", R.drawable.ic_share) { runCatching { startActivity(Files.shareIntent(v)) } }, lp(0, dp(54), 1f))
                bb.addView(k.button("Open", "primary", R.drawable.ic_open) { Files.openIntent(this, v)?.let { runCatching { startActivity(it) }.onFailure { toast("No app on this phone can open this file") } } }, lp(0, dp(54), 1.4f))
            }
            v.active || v.status == Status.QUEUED -> {
                bb.addView(k.button("Download again", "ghost", R.drawable.ic_refresh) { confirmRedownload() }, lp(0, dp(54), 1f))
                bb.addView(k.button("Pause", "primary", R.drawable.ic_pause) { engine.pause(listOf(id)) }, lp(0, dp(54), 1f))
            }
            else -> {
                bb.addView(k.button("Download again", "ghost", R.drawable.ic_refresh) { confirmRedownload() }, lp(0, dp(54), 1f))
                bb.addView(k.button(if (v.status == Status.ERROR) "Retry" else "Resume", "primary", R.drawable.ic_play) { engine.resume(listOf(id)); DownloadService.ensureRunning(this) }, lp(0, dp(54), 1f))
            }
        }
    }

    private fun renderFacts(v: DView) {
        val sig = listOf(v.url, v.name, v.status, v.resumable, v.error, v.note, v.verify, v.hash, v.checksum, v.completed, v.category, v.queue, v.hasLogin, v.taskSubs, p.dark).joinToString("|")
        if (sig == factSig) return
        factSig = sig
        facts.removeAllViews()
        if (v.status == Status.ERROR && v.error.isNotEmpty()) fact("Problem", v.error, R.drawable.ic_alert, if (v.error.contains("login")) ({ editLogin(v) }) else null, p.fault)
        fact("Address", v.url, R.drawable.ic_link, { addressMenu(v) })
        fact("Saved to", Files.folderLabel(v), R.drawable.ic_folder, null)
        fact("Category", v.category, R.drawable.ic_tag, { pickCategory(v) }, p.cat(v.category))
        fact("Queue", if (v.queue == MAIN_QUEUE) "Main" else v.queue, R.drawable.ic_calendar, if (v.status == Status.COMPLETE) null else ({ queueMenu(v) }))
        if (v.isMedia) {
            fact("Format", if (v.taskKind == "youtube_audio") "MP3 · ${v.taskOption} kbps · cover art and tags"
                else "MP4 · best quality up to ${v.taskOption}p" + if (v.taskSubs.isNotEmpty()) " · subtitles (${v.taskSubs})" else "", R.drawable.ic_film, null)
            fact("Resume", "Pausing keeps what's downloaded; the media engine continues from there.", R.drawable.ic_refresh, null)
        } else fact("Resume", when {
            v.resumable -> "Supported. Pausing keeps your progress."
            v.size > 0 || v.status == Status.COMPLETE -> "Not supported by this server. Pausing starts the file over."
            else -> "Checked when the download starts"
        }, R.drawable.ic_refresh, null)
        if (!v.isMedia || v.status == Status.COMPLETE) fact("Integrity", integrityText(v), R.drawable.ic_shield, { integrityMenu(v) }, when (v.verify) { "ok" -> p.done; "mismatch" -> p.fault; else -> p.fg })
        if (v.status != Status.COMPLETE && !v.isMedia) fact("Site login", if (v.hasLogin) "Saved for this download" else "None. Add one if the site asks for it.", R.drawable.ic_user, { editLogin(v) }, if (v.hasLogin) p.fg else p.faint)
        fact("Note", v.note.ifEmpty { "Add a note" }, R.drawable.ic_edit, { editNote(v) }, if (v.note.isEmpty()) p.faint else p.fg)
        fact("Added", whenText(v.added), R.drawable.ic_clock, null)
        if (v.completed > 0) fact("Finished", whenText(v.completed), R.drawable.ic_check, null)
    }

    private fun integrityText(v: DView): String {
        val algo = v.hashAlgo.ifEmpty { Checksums.algoFor(v.checksum) }
        return when (v.verify) {
            "checking" -> "Checking the file…"
            "ok" -> "Matches the $algo you provided\n${v.hash}"
            "mismatch" -> "Doesn't match the $algo you provided. The file may be damaged.\n${v.hash}"
            "done" -> "$algo  ${v.hash}"
            "error" -> "Couldn't read the file to check it"
            else -> if (v.status != Status.COMPLETE) (if (v.checksum.isNotEmpty()) "Will be checked when the download finishes" else "Tap to add a checksum to check after downloading")
            else "Tap to verify with a checksum or calculate SHA-256"
        }
    }

    private fun fact(label: String, value: String, icon: Int, onClick: (() -> Unit)?, color: Int = p.fg) {
        val row = k.hbox(dp(14)).apply { setPadding(dp(12), dp(13), dp(12), dp(13)) }
        val ic = FrameLayout(this).apply { background = rounded(alpha(p.fg, .06f), dpf(11f)); addView(k.icon(icon, p.mute, 18).apply { layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER) }) }
        row.addView(ic, LinearLayout.LayoutParams(dp(34), dp(34)))
        val tx = k.vbox(gap = dp(3))
        tx.addView(k.text(label, 11.5f, p.mute, Type.medium))
        tx.addView(k.text(value, 14f, color).apply { setTextIsSelectable(onClick == null); setLineSpacing(0f, 1.15f) })
        row.addView(tx, lp(0, -2, 1f))
        if (onClick != null) {
            row.addView(k.icon(R.drawable.ic_chevron, p.faint, 18))
            row.background = ripple(null, alpha(p.accent, .16f), rounded(Color.WHITE, dpf(16f)))
            row.setOnClickListener { haptic(it); onClick() }
        }
        if (facts.childCount > 0) facts.addView(k.divider(dp(60)))
        facts.addView(row)
    }

    // ------------------------------------------------------------------ dialogs

    private fun rename() {
        val v = engine.view(id) ?: return
        val input = k.input("File name", v.name)
        dialog().setTitle("Rename").setView(padded(input))
            .setPositiveButton("Rename") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isEmpty() || n == v.name) return@setPositiveButton
                Thread { val ok = engine.rename(id, n); runOnUiThread { toast(if (ok) "Renamed" else "Couldn't rename the file") } }.start()
            }
            .setNegativeButton("Cancel", null).show()
        input.post { val dot = v.name.lastIndexOf('.'); input.requestFocus(); input.setSelection(0, if (dot > 0) dot else v.name.length) }
    }

    private fun pickCategory(v: DView) {
        val cats = allCategories
        dialog().setTitle("Category").setSingleChoiceItems(cats.toTypedArray(), cats.indexOf(v.category)) { d, i ->
            d.dismiss()
            Thread { engine.setCategory(id, cats[i]) }.start()
            if (v.status == Status.COMPLETE) toast("Moved to Download/VoltFetch/${cats[i]}")
        }.setNegativeButton("Cancel", null).show()
    }

    private fun queueMenu(v: DView) {
        val names = engine.settings.queueNames()
        val items = ArrayList<Pair<String, () -> Unit>>()
        for (q in names) if (q != v.queue) items.add("Move to ${if (q == MAIN_QUEUE) "Main" else q}" to { engine.moveToQueue(listOf(id), q) })
        if (v.status == Status.QUEUED || v.status == Status.PAUSED) {
            items.add("Move to the top of the line" to { engine.move(id, "top") })
            items.add("Move up one place" to { engine.move(id, "up") })
            items.add("Move to the end of the line" to { engine.move(id, "bottom") })
        }
        dialog().setTitle("Queue").setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second(); factSig = "" }.show()
    }

    private fun editLogin(v: DView) {
        val box = k.vbox(gap = dp(10)).apply { setPadding(dp(22), dp(8), dp(22), 0) }
        val user = k.input("User name")
        val pass = k.input("Password").apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        box.addView(k.text("For sites that ask for a user name and password. It's only sent to ${v.host.ifEmpty { "this site" }}.", 13f, p.mute))
        box.addView(user); box.addView(pass)
        val d = dialog().setTitle("Site login").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val u = user.text.toString()
                engine.setLogin(id, if (u.isBlank()) "" else "$u:${pass.text}")
                if (v.status == Status.ERROR) { engine.resume(listOf(id)); DownloadService.ensureRunning(this) }
                factSig = ""
            }
            .setNegativeButton("Cancel", null)
        if (v.hasLogin) d.setNeutralButton("Remove") { _, _ -> engine.setLogin(id, ""); factSig = "" }
        d.show()
    }

    private fun addressMenu(v: DView) {
        val items = if (v.active) arrayOf("Copy address") else arrayOf("Copy address", "Change address (if the link expired)")
        dialog().setTitle("Address").setItems(items) { _, which ->
            if (which == 0) {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("address", v.url))
                getSharedPreferences("ui", MODE_PRIVATE).edit().putString("lastClip", v.url).apply()
                toast("Address copied")
            } else {
                val input = k.input("https://…", v.url, multi = true)
                dialog().setTitle("Change address").setMessage(if (v.isMedia) "Paste the YouTube link for the same video." else "Paste a fresh link to the same file. Progress so far is kept.")
                    .setView(padded(input))
                    .setPositiveButton("Save") { _, _ ->
                        toast(if (engine.updateUrl(id, input.text.toString())) "Address updated. Tap Resume to continue."
                            else if (v.isMedia) "That isn't a YouTube link" else "Couldn't change the address while downloading")
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        }.show()
    }

    private fun integrityMenu(v: DView) {
        if (v.status != Status.COMPLETE) {
            val input = k.input("Paste the SHA-256, SHA-1 or MD5", v.checksum)
            dialog().setTitle("Checksum").setMessage("VoltFetch checks the file against it when the download finishes.")
                .setView(padded(input))
                .setPositiveButton("Save") { _, _ ->
                    val s = input.text.toString()
                    if (s.isNotBlank() && Checksums.normalize(s).isEmpty()) { toast("That doesn't look like a checksum"); return@setPositiveButton }
                    engine.get(id)?.let { d -> synchronized(d) { d.checksum = Checksums.normalize(s) } }
                    factSig = ""
                }
                .setNegativeButton("Cancel", null).show()
            return
        }
        val input = k.input("Paste the SHA-256, SHA-1 or MD5", if (v.verify == "ok" || v.verify == "mismatch") v.checksum else "")
        dialog().setTitle("Check this file")
            .setMessage("Paste the checksum shown on the download page to confirm the file arrived intact. Leave it empty to just calculate the SHA-256.")
            .setView(padded(input))
            .setPositiveButton("Check") { _, _ ->
                val s = input.text.toString()
                if (s.isNotBlank() && Checksums.normalize(s).isEmpty()) { toast("That doesn't look like a checksum"); return@setPositiveButton }
                Thread { engine.verify(id, s) }.start()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun editNote(v: DView) {
        val input = k.input("Note", v.note)
        dialog().setTitle("Note").setView(padded(input))
            .setPositiveButton("Save") { _, _ -> engine.setNote(id, input.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirmRedownload() {
        dialog().setTitle("Download again?").setMessage("The file is downloaded again from the start.")
            .setPositiveButton("Download again") { _, _ -> Thread { engine.redownload(id); runOnUiThread { DownloadService.ensureRunning(this) } }.start() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirmDelete() {
        val v = engine.view(id) ?: return
        val cb = CheckBox(this).apply { text = "Also delete the file from the phone"; setTextColor(p.fg); buttonTintList = android.content.res.ColorStateList.valueOf(p.accent) }
        val box = k.vbox().apply { setPadding(dp(22), dp(8), dp(22), 0) }
        box.addView(k.text("It's removed from your list.", 14f, p.mute))
        if (v.status == Status.COMPLETE) box.addView(cb)
        dialog().setTitle("Delete this download?").setView(box)
            .setPositiveButton("Delete") { _, _ -> Thread { engine.remove(listOf(id), cb.isChecked) }.start(); finish() }
            .setNegativeButton("Cancel", null).show()
    }
}
