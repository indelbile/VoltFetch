package com.voltfetch.app

import android.content.Intent
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.voltfetch.engine.Fmt
import com.voltfetch.engine.Settings

class SettingsActivity : Screen() {
    override val screenTitle = "Settings"
    private lateinit var s: Settings
    @Volatile private var updating = false

    private fun save(rebuild: Boolean = false) {
        engine.updateSettings(s)
        (application as VoltApp).refreshNetworkGate()
        if (rebuild) frame()
    }

    override fun content() {
        s = engine.settings.copy()

        // Appearance
        body.addView(k.sectionTitle("Appearance"))
        val themes = k.hbox(dp(10))
        for ((key, label, icon) in listOf(Triple("system", "Match phone", R.drawable.ic_sparkle), Triple("light", "Light", R.drawable.ic_sparkle), Triple("dark", "Dark", R.drawable.ic_moon))) {
            val on = s.theme == key
            val t = k.vbox(gap = dp(8)).apply {
                gravity = Gravity.CENTER; setPadding(dp(8), dp(14), dp(8), dp(12))
                // a tiny preview of each look
                val prev = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
                    val bg = when (key) { "light" -> 0xFFEEF2F9.toInt(); "dark" -> 0xFF070E1C.toInt(); else -> 0 }
                    background = if (key == "system") gradient(dpf(12f), 0xFFEEF2F9.toInt(), 0xFF070E1C.toInt(), orientation = android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT) else rounded(bg, dpf(12f))
                    addView(View(this@SettingsActivity).apply { background = rounded(p.accent, dpf(3f)) }, LinearLayout.LayoutParams(dp(30), dp(6)))
                    addView(View(this@SettingsActivity).apply { background = rounded(alpha(if (key == "light") Color.BLACK else Color.WHITE, .25f), dpf(3f)) }, LinearLayout.LayoutParams(dp(44), dp(6)).apply { topMargin = dp(5) })
                    addView(View(this@SettingsActivity).apply { background = rounded(alpha(if (key == "light") Color.BLACK else Color.WHITE, .15f), dpf(3f)) }, LinearLayout.LayoutParams(dp(36), dp(6)).apply { topMargin = dp(5) })
                }
                addView(prev, LinearLayout.LayoutParams(dp(70), dp(48)))
                addView(k.text(label, 13f, if (on) p.fg else p.mute, if (on) Type.medium else Type.regular).apply { gravity = Gravity.CENTER })
                background = ripple(if (on) rounded(alpha(p.accent, .16f), dpf(20f), p.accent, dp(2)) else glass(p, dpf(20f), this@SettingsActivity), alpha(p.accent, .25f), rounded(Color.WHITE, dpf(20f)))
                setOnClickListener { haptic(this); s.theme = key; save(rebuild = true) }
                pressable()
            }
            themes.addView(t, lp(0, -2, 1f))
        }
        body.addView(themes)
        val look = k.vbox()
        look.addView(toggleRow("Moving background", "The soft light behind the screens drifts slowly. Turn off to save a little battery.", s.motion) { s.motion = it; save() })
        look.addView(k.divider())
        look.addView(toggleRow("Vibration feedback", "A light tap when you press buttons.", s.haptics) { s.haptics = it; save() })
        body.addView(k.card(look, dp(2)).also { it.layoutParams = lp().apply { topMargin = dp(10) } })

        // Downloads
        section("Downloads",
            stepperRow("Connections per download", "More connections are usually faster. Some servers allow fewer.", s.connections, 1, 32) { s.connections = it; save() },
            stepperRow("Downloads at once (Main queue)", "Other files wait their turn.", s.simultaneous, 1, 8) { s.simultaneous = it; save() },
            stepperRow("Retries per connection", "How often a dropped connection is tried again.", s.retries, 0, 30) { s.retries = it; save() },
            stepperRow("Timeout (seconds)", "How long to wait for a silent server.", s.timeoutSec, 5, 120) { s.timeoutSec = it; save() },
        )

        // Speed
        val speedRows = mutableListOf<View>(
            toggleRow("Limit download speed", "Keeps browsing and calls smooth. Overrides the speed schedule while on.", s.limitOn) { s.limitOn = it; save(rebuild = true) },
        )
        if (s.limitOn) speedRows.add(numberRow("Maximum speed", "KB/s for all downloads together (1024 = 1 MB/s)", s.limitKBs) { s.limitKBs = it.coerceAtLeast(16); save() })
        speedRows.add(navRow(R.drawable.ic_calendar, "Speed schedule", if (s.speedRules.isEmpty()) "Limit speed at set times" else "${s.speedRules.count { it.enabled }} rule(s) on", p.violet) {
            startActivity(Intent(this, MainActivity::class.java).putExtra("tab", "queues").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        })
        section("Speed", *speedRows.toTypedArray())

        // Network
        val netRows = mutableListOf<View>(
            toggleRow("Download on Wi-Fi only", "Downloads wait on mobile data and continue on Wi-Fi.", s.wifiOnly) { s.wifiOnly = it; save() },
            toggleRow("Use a proxy", "Send downloads through an HTTP proxy.", s.proxyOn) { s.proxyOn = it; save(rebuild = true) },
        )
        if (s.proxyOn) {
            val host = k.input("proxy.example.com", s.proxyHost).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
            val port = k.input("8080", "${s.proxyPort}").apply { inputType = InputType.TYPE_CLASS_NUMBER }
            val row = k.hbox(dp(10)).apply { setPadding(dp(14), dp(6), dp(14), dp(14)) }
            row.addView(host, lp(0, -2, 1f)); row.addView(port, LinearLayout.LayoutParams(dp(96), -2))
            val commit = { s.proxyHost = host.text.toString().trim(); s.proxyPort = port.text.toString().toIntOrNull() ?: 8080; save() }
            host.setOnFocusChangeListener { _, f -> if (!f) commit() }; port.setOnFocusChangeListener { _, f -> if (!f) commit() }
            netRows.add(row)
        }
        netRows.add(navRow(R.drawable.ic_globe, "Browser identity (user-agent)", if (s.userAgent == Settings.DEFAULT_AGENT) "Chrome on Android (default)" else s.userAgent.take(60), p.tide) { editAgent() })
        section("Network", *netRows.toTypedArray())

        // Notifications & links
        section("Notifications and links",
            toggleRow("Notify when a download finishes", "With buttons to open the file.", s.notifyDone) { s.notifyDone = it; save() },
            toggleRow("Offer to download copied links", "When you open VoltFetch with a link on the clipboard.", s.watchClipboard) { s.watchClipboard = it; save() },
        )

        // Media engine (yt-dlp): YouTube changes often, so it can be updated without a new app release
        val last = MediaEngine.lastCheck(this)
        section("Media engine",
            navRow(R.drawable.ic_refresh, if (updating) "Updating media engine…" else "Update media engine",
                "yt-dlp ${MediaEngine.version(this)} · ${if (last > 0) "checked ${whenText(last)}" else "not checked yet"}", p.violet) { updateEngine() },
            toggleRow("Check for updates weekly", "Only on Wi-Fi, and never while a media download is running.", MediaEngine.autoUpdate(this)) { MediaEngine.setAutoUpdate(this, it) },
        )

        // Browser ad blocker
        val adLast = AdBlock.lastUpdate(this)
        section("Browser",
            toggleRow("Block ads and trackers", "In VoltFetch's browser: ad and tracker servers, YouTube video ads and ad boxes. The shield in the address bar turns it off for one site.", AdBlock.enabled(this)) { AdBlock.setEnabled(this, it) },
            navRow(R.drawable.ic_shield, "Update ad filter lists", "${"%,d".format(AdBlock.rules())} rules · ${if (adLast > 0) "updated ${whenText(adLast)}" else "built-in list; updates on first browser use"}", p.done) {
                toast("Updating ad filter lists…")
                AdBlock.refreshAsync(this) { n -> runOnUiThread { if (!isFinishing && !isDestroyed) { toast(if (n < 0) "Couldn't update the lists" else "Updated: ${"%,d".format(n)} rules"); frame() } } }
            },
        )

        // Followed playlists: checked every night, new videos are queued
        val follows = PlaylistSync.list(this)
        section("Followed playlists",
            navRow(R.drawable.ic_calendar, if (follows.isEmpty()) "No followed playlists" else "${follows.size} followed",
                if (follows.isEmpty()) "Follow one from the Media downloader: new videos are downloaded every night." else "Checked every night at 3:00. Tap to see, check now or stop following.", p.violet) { showFollows() },
        )

        // Data
        section("Your list",
            navRow(R.drawable.ic_export, "Export links", "Save every download's address to a text file", p.done) { exportList() },
            navRow(R.drawable.ic_import, "Import links", "Add the links from a text file", p.accent) { importList() },
        )

        // About
        body.addView(k.sectionTitle("About"))
        val about = k.vbox(gap = dp(10)).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(18), 0, dp(18)) }
        about.addView(ImageView(this).apply { setImageResource(R.drawable.logo_symbol); scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(170), dp(120)))
        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        about.addView(k.text("VoltFetch", 24f, p.fg, Type.black).apply { gravity = Gravity.CENTER; gradientText(p.fg, p.tide) })
        about.addView(k.badge("Version $ver", p.accent))
        about.addView(k.text("Fast, multi-connection downloads for Android.\nFiles are saved in Download/VoltFetch, sorted by type.", 13f, p.mute).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.3f); setPadding(dp(16), 0, dp(16), 0) })
        body.addView(k.card(about))
    }

    private fun showFollows() {
        val follows = PlaylistSync.list(this)
        if (follows.isEmpty()) { toast("Follow a playlist from the Media downloader first"); return }
        val box = k.vbox(gap = dp(12)).apply { setPadding(dp(22), dp(8), dp(22), dp(4)) }
        lateinit var dlg: android.app.AlertDialog
        for (f in follows) {
            val row = k.hbox(dp(10))
            val tx = k.vbox(gap = dp(2))
            tx.addView(k.text(f.title, 14.5f, p.fg, Type.medium, lines = 2))
            tx.addView(k.text("${com.voltfetch.engine.MediaSites.label(f.url)} · ${f.what} · ${if (f.lastSync > 0) "${f.lastResult}, ${whenText(f.lastSync)}" else "not checked yet"}", 12f, p.mute))
            row.addView(tx, lp(0, -2, 1f))
            row.addView(k.button("Stop", "ghost") {
                dialog().setTitle("Stop following?").setMessage("\"${f.title}\" won't be checked any more. Videos already downloaded stay where they are.")
                    .setPositiveButton("Stop following") { _, _ -> PlaylistSync.unfollow(this, f.id); dlg.dismiss(); frame(); toast("Stopped following") }
                    .setNegativeButton("Cancel", null).show()
            }.apply { minHeight = dp(40); minimumHeight = dp(40) }, android.widget.LinearLayout.LayoutParams(-2, dp(40)))
            box.addView(row)
        }
        dlg = dialog().setTitle("Followed playlists").setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Check now") { _, _ ->
                toast("Checking followed playlists…")
                Thread {
                    val n = runCatching { PlaylistSync.syncAll(this) }.getOrDefault(0)
                    runOnUiThread { if (!isFinishing && !isDestroyed) { toast(if (n == 0) "No new videos" else "Added $n new videos"); frame() } }
                }.start()
            }
            .setNegativeButton("Close", null).create()
        dlg.show()
    }

    private fun updateEngine() {
        if (updating) return
        updating = true; frame()
        toast("Checking for a newer yt-dlp…")
        Thread {
            val r = MediaEngine.updateNow(this)
            runOnUiThread {
                updating = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                toast(r.message); frame()
            }
        }.start()
    }

    private fun numberRow(title: String, help: String, value: Int, onChange: (Int) -> Unit): View {
        val row = k.hbox(dp(12)).apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
        row.addView(labelBox(title, help), lp(0, -2, 1f))
        val ed = k.input("1024", "$value").apply {
            inputType = InputType.TYPE_CLASS_NUMBER; gravity = Gravity.END
            setOnFocusChangeListener { _, has -> if (!has) text.toString().toIntOrNull()?.let { onChange(it) } }
            setOnEditorActionListener { _, _, _ -> text.toString().toIntOrNull()?.let { onChange(it) }; clearFocus(); false }
        }
        row.addView(ed, LinearLayout.LayoutParams(dp(110), -2))
        return row
    }

    private fun editAgent() {
        val input = k.input("User-agent", s.userAgent, multi = true).apply { maxLines = 5 }
        dialog().setTitle("Browser identity").setMessage("Some sites only allow downloads from browsers they recognise. Leave this as it is unless a site refuses VoltFetch.")
            .setView(padded(input))
            .setPositiveButton("Save") { _, _ -> s.userAgent = input.text.toString().trim().ifEmpty { Settings.DEFAULT_AGENT }; save(rebuild = true) }
            .setNeutralButton("Reset") { _, _ -> s.userAgent = Settings.DEFAULT_AGENT; save(rebuild = true) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun exportList() {
        if (engine.views().isEmpty()) { toast("Your list is empty"); return }
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain").putExtra(Intent.EXTRA_TITLE, "voltfetch-links.txt"), REQ_EXPORT)
        }.onFailure { toast("No file picker is available") }
    }

    private fun importList() {
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/*"), REQ_IMPORT)
        }.onFailure { toast("No file picker is available") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_EXPORT -> {
                val text = engine.views().joinToString("\n") { it.url } + "\n"
                val ok = runCatching { contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } != null }.getOrDefault(false)
                toast(if (ok) "Exported ${engine.views().size} links" else "Couldn't write the file")
            }
            REQ_IMPORT -> {
                val text = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
                val links = com.voltfetch.engine.Links.find(text)
                if (links.isEmpty()) toast("No web links in that file")
                else startActivity(Intent(this, AddActivity::class.java).putExtra(Intent.EXTRA_TEXT, links.joinToString("\n")))
            }
        }
    }

    override fun onPause() {
        super.onPause()
        currentFocus?.clearFocus() // commits typed values
    }

    companion object { const val REQ_EXPORT = 21; const val REQ_IMPORT = 22 }
}
