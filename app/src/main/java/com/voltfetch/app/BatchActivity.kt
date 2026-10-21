package com.voltfetch.app

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.voltfetch.engine.Batch
import com.voltfetch.engine.Engine

/** Batch download: one address with a * that counts through numbers or letters. */
class BatchActivity : Screen() {
    override val screenTitle = "Batch download"
    override val wantsBottomBar = true
    private lateinit var pattern: EditText
    private lateinit var from: EditText
    private lateinit var to: EditText
    private lateinit var width: EditText
    private lateinit var preview: LinearLayout
    private lateinit var countText: TextView
    private var letters = false
    private lateinit var modeRow: LinearLayout
    private lateinit var queuePick: () -> String

    override fun content() {
        body.addView(k.text("Put a * where the number or letter changes, for example https://site.com/photos/img*.jpg", 13.5f, p.mute).apply { setLineSpacing(0f, 1.25f); setPadding(dp(4), 0, dp(4), dp(4)) })
        body.addView(k.sectionTitle("Address"))
        pattern = k.input("https://site.com/img*.jpg", intent.getStringExtra("pattern") ?: "", multi = true).apply { maxLines = 3 }
        body.addView(pattern)

        body.addView(k.sectionTitle("Counting"))
        modeRow = k.hbox(dp(8))
        body.addView(modeRow)
        renderMode()
        val nums = k.hbox(dp(10)).apply { setPadding(0, dp(12), 0, 0) }
        from = k.input("From", "1").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        to = k.input("To", "10").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        width = k.input("Digits", "0").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        fun labelled(label: String, e: EditText) = k.vbox(gap = dp(6)).apply { addView(k.text(label, 12f, p.mute, Type.medium)); addView(e) }
        nums.addView(labelled("From", from), lp(0, -2, 1f)); nums.addView(labelled("To", to), lp(0, -2, 1f)); nums.addView(labelled("Zero-pad to", width), lp(0, -2, 1f))
        body.addView(nums)
        body.addView(k.text("Zero-pad 3 turns 7 into 007. With letters, use a to z.", 12f, p.faint).apply { setPadding(dp(4), dp(8), 0, 0) })

        body.addView(k.sectionTitle("Queue"))
        val (qv, qget) = queueChips(com.voltfetch.engine.MAIN_QUEUE); queuePick = qget
        body.addView(qv)

        body.addView(k.sectionTitle("Preview"))
        countText = k.text("", 14f, p.fg, Type.medium).apply { setPadding(dp(4), 0, 0, dp(8)) }
        body.addView(countText)
        preview = k.vbox(gap = dp(6))
        body.addView(k.card(preview, dp(14)))

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { renderPreview() }
        }
        for (e in listOf(pattern, from, to, width)) e.addTextChangedListener(watcher)
        bottomBar?.addView(k.button("Save for later", "ghost") { submit(false) }, lp(0, dp(52), 1f))
        bottomBar?.addView(k.button("Download all", "primary", R.drawable.ic_down) { submit(true) }, lp(0, dp(52), 1.3f))
        renderPreview()
    }

    private fun renderMode() {
        modeRow.removeAllViews()
        modeRow.addView(k.chip("Numbers 1, 2, 3", !letters) { letters = false; if (::from.isInitialized) { from.setText("1"); to.setText("10") }; renderMode(); renderPreview() })
        modeRow.addView(k.chip("Letters a, b, c", letters) { letters = true; if (::from.isInitialized) { from.setText("a"); to.setText("z") }; renderMode(); renderPreview() })
        if (::from.isInitialized) {
            val t = if (letters) InputType.TYPE_CLASS_TEXT else InputType.TYPE_CLASS_NUMBER
            from.inputType = t; to.inputType = t; width.isEnabled = !letters; width.alpha = if (letters) .4f else 1f
        }
    }

    private fun bound(e: EditText, def: Int): Int {
        val s = e.text.toString().trim()
        return if (letters) (s.lowercase().firstOrNull()?.let { it - 'a' } ?: def).coerceIn(0, 25) else s.toIntOrNull() ?: def
    }

    private fun urls(): List<String> = Batch.expand(pattern.text.toString().trim(), bound(from, 0), bound(to, 0), if (letters) 0 else (width.text.toString().toIntOrNull() ?: 0).coerceIn(0, 12), letters)

    private fun renderPreview() {
        if (!::preview.isInitialized) return
        val p0 = pattern.text.toString().trim()
        preview.removeAllViews()
        val list = urls()
        when {
            p0.isEmpty() -> { countText.text = "Type an address with a *"; preview.addView(k.text("img*.jpg  →  img1.jpg, img2.jpg …", 13f, p.faint)) }
            !p0.contains('*') -> { countText.text = "Add a * where the number goes"; countText.setTextColor(p.hold) }
            !p0.startsWith("http://") && !p0.startsWith("https://") -> { countText.text = "The address should start with https://"; countText.setTextColor(p.hold) }
            else -> {
                countText.setTextColor(p.fg)
                countText.text = "${list.size} ${if (list.size == 1) "file" else "files"}"
                val show = if (list.size <= 6) list else list.take(3) + listOf("…") + list.takeLast(2)
                for (u in show) preview.addView(k.text(if (u == "…") "…" else com.voltfetch.engine.Names.fromUrl(u), 13.5f, if (u == "…") p.faint else p.fg, lines = 1))
            }
        }
        if (preview.childCount == 0) preview.addView(k.text("—", 13f, p.faint))
    }

    private fun submit(start: Boolean) {
        val p0 = pattern.text.toString().trim()
        val list = urls()
        if (list.isEmpty() || !(p0.startsWith("http://") || p0.startsWith("https://"))) { toast("Check the address and the range"); return }
        if (list.size > 500) {
            dialog().setTitle("Add ${list.size} files?").setMessage("That's a lot of downloads.")
                .setPositiveButton("Add them") { _, _ -> add(list, start) }.setNegativeButton("Cancel", null).show()
        } else add(list, start)
    }

    private fun add(list: List<String>, start: Boolean) {
        val q = queuePick()
        for (u in list) engine.add(Engine.AddRequest(url = u, start = start, queue = q))
        if (start) DownloadService.ensureRunning(this)
        toast("${if (start) "Downloading" else "Saved"} ${list.size} files")
        finish()
    }
}
