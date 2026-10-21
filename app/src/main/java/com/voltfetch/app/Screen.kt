package com.voltfetch.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Base for the secondary screens: aurora background, a back button and title that stay on top,
 * a scrolling body, and an optional bar of buttons pinned to the bottom.
 */
abstract class Screen : Activity() {
    lateinit var p: Palette
    lateinit var k: Kit
    val engine get() = VoltApp.engine(this)
    lateinit var body: LinearLayout
    lateinit var scroll: ScrollView
    lateinit var topBar: LinearLayout
    lateinit var titleView: TextView
    var bottomBar: LinearLayout? = null
    private lateinit var aurora: AuroraView
    private var themeSig = ""
    var insetTop = 0
    var insetBottom = 0

    abstract val screenTitle: String
    /** Fills [body] (and optionally [bottomBar]). Called again when the theme changes. */
    abstract fun content()
    open val wantsBottomBar = false
    open val topActions: List<Triple<Int, String, () -> Unit>> get() = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        frame()
    }

    fun frame() {
        p = Palette.of(this); k = Kit(this, p); themeSig = "${p.dark}"
        applyBars(p)
        val root = FrameLayout(this)
        aurora = AuroraView(this, p).apply { animate = engine.settings.motion }
        root.addView(aurora, FrameLayout.LayoutParams(-1, -1))
        body = k.vbox()
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = false; addView(body) }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        // floating header
        topBar = k.hbox(dp(8)).apply { setPadding(dp(10), 0, dp(12), dp(8)) }
        topBar.addView(k.orb(R.drawable.ic_back, "Back") { finishScreen() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        titleView = k.text(screenTitle, 18f, p.fg, Type.bold, lines = 1).apply { alpha = 0f }
        topBar.addView(titleView, lp(0, -2, 1f).apply { leftMargin = dp(6) })
        for ((ic, desc, fn) in topActions) topBar.addView(k.orb(ic, desc, p.fg, fn), LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(8) })
        val headBg = View(this).apply { background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(alpha(p.ink, .92f), alpha(p.ink, .6f), Color.TRANSPARENT)); alpha = 0f }
        root.addView(headBg, FrameLayout.LayoutParams(-1, dp(1)))
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        // big title in the body; the small one fades in as it scrolls away
        body.addView(k.title(screenTitle).apply { setPadding(dp(4), 0, 0, dp(6)) })
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            val f = (y / dpf(48f)).coerceIn(0f, 1f)
            titleView.alpha = f; headBg.alpha = f
        }
        if (wantsBottomBar) {
            val bb = k.hbox(dp(10)).apply {
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(alpha(p.ink, .96f), alpha(p.ink, .85f), Color.TRANSPARENT))
            }
            bottomBar = bb
            root.addView(bb, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        content()
        setContentView(root)
        root.onInsets { top, bottom ->
            insetTop = top; insetBottom = bottom
            topBar.setPadding(dp(10), top + dp(6), dp(12), dp(8))
            headBg.layoutParams = FrameLayout.LayoutParams(-1, top + dp(70)); headBg.requestLayout()
            val bbh = if (bottomBar != null) dp(84) else 0
            body.setPadding(dp(16), top + dp(64), dp(16), bottom + dp(32) + bbh)
            bottomBar?.setPadding(dp(16), dp(18), dp(16), bottom + dp(12))
        }
        body.enter(0, 12f)
    }

    override fun onResume() {
        super.onResume()
        if (themeSig != "${Palette.of(this).dark}") frame()
        aurora.animate = engine.settings.motion; aurora.invalidate()
    }

    open fun finishScreen() { finish() }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION") overridePendingTransition(R.anim.hold, R.anim.exit_down)
    }

    // ---------------------------------------------------------------- helpers for subclasses

    fun dialog(): AlertDialog.Builder = AlertDialog.Builder(this, if (p.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    fun padded(v: View) = FrameLayout(this).apply { setPadding(dp(22), dp(8), dp(22), 0); addView(v) }

    fun section(title: String, vararg rows: View): View {
        body.addView(k.sectionTitle(title))
        val box = k.vbox()
        rows.forEachIndexed { i, r -> if (i > 0) box.addView(k.divider()); box.addView(r) }
        val card = k.card(box, dp(2))
        body.addView(card)
        return card
    }

    fun labelBox(title: String, help: String) = k.vbox(gap = dp(3)).apply {
        addView(k.text(title, 15f, p.fg, Type.medium))
        if (help.isNotEmpty()) addView(k.text(help, 12.5f, p.mute).apply { setLineSpacing(0f, 1.15f) })
    }

    fun styleSwitch(sw: Switch) {
        sw.thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Color.WHITE, p.faint))
        sw.trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(p.accent, p.track))
    }

    fun toggleRow(title: String, help: String, on: Boolean, onChange: (Boolean) -> Unit): View {
        val row = k.hbox(dp(12)).apply { setPadding(dp(14), dp(14), dp(10), dp(14)) }
        row.addView(labelBox(title, help), lp(0, -2, 1f))
        val sw = Switch(this).apply { isChecked = on; styleSwitch(this); setOnCheckedChangeListener { v, x -> haptic(v); onChange(x) } }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        row.background = ripple(null, alpha(p.accent, .14f), rounded(Color.WHITE, dpf(18f)))
        return row
    }

    fun stepperRow(title: String, help: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit): View {
        val row = k.hbox(dp(6)).apply { setPadding(dp(14), dp(12), dp(6), dp(12)) }
        row.addView(labelBox(title, help), lp(0, -2, 1f))
        var v = value
        val num = k.text("$v", 19f, p.fg, Type.black).apply { gravity = Gravity.CENTER; minWidth = dp(34) }
        val minus = k.iconButton(R.drawable.ic_minus, "Fewer", p.accent) {}
        val plus = k.iconButton(R.drawable.ic_plus, "More", p.accent) {}
        fun upd() { num.text = "$v"; minus.alpha = if (v > min) 1f else .3f; plus.alpha = if (v < max) 1f else .3f }
        minus.setOnClickListener { if (v > min) { haptic(it); v--; upd(); num.enter(0, -6f); onChange(v) } }
        plus.setOnClickListener { if (v < max) { haptic(it); v++; upd(); num.enter(0, 6f); onChange(v) } }
        upd()
        row.addView(minus); row.addView(num); row.addView(plus)
        return row
    }

    fun navRow(icon: Int, title: String, value: String, tint: Int = p.accent, onClick: () -> Unit): View {
        val row = k.hbox(dp(14)).apply { setPadding(dp(14), dp(14), dp(12), dp(14)) }
        val ic = FrameLayout(this).apply { background = rounded(alpha(tint, .16f), dpf(12f)); addView(k.icon(icon, tint, 19).apply { layoutParams = FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER) }) }
        row.addView(ic, LinearLayout.LayoutParams(dp(36), dp(36)))
        row.addView(labelBox(title, value), lp(0, -2, 1f))
        row.addView(k.icon(R.drawable.ic_chevron, p.faint, 18))
        row.background = ripple(null, alpha(p.accent, .14f), rounded(Color.WHITE, dpf(18f)))
        row.setOnClickListener { haptic(row); onClick() }
        return row
    }

    /** Queue picker chips; returns a getter for the chosen name. */
    fun queueChips(initial: String): Pair<View, () -> String> {
        var chosen = if (initial in engine.settings.queueNames()) initial else com.voltfetch.engine.MAIN_QUEUE
        val (sv, row) = k.chipRow(); row.setPadding(0, 0, 0, 0)
        fun render() {
            row.removeAllViews()
            for (q in engine.settings.queueNames()) row.addView(k.chip(q, q == chosen, if (q == com.voltfetch.engine.MAIN_QUEUE) 0 else p.violet) { chosen = q; render() })
        }
        render()
        return sv to { chosen }
    }
}
