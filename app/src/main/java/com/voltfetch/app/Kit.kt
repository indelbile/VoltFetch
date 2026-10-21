package com.voltfetch.app

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.voltfetch.engine.Categories
import com.voltfetch.engine.Status

/** Colours, shared with the desktop app: ink navy, logo azure, cyan for live activity, violet for depth. */
class Palette(val dark: Boolean) {
    val ink = if (dark) 0xFF070E1C.toInt() else 0xFFEEF2F9.toInt()
    val deck = if (dark) 0xFF101B2F.toInt() else 0xFFFFFFFF.toInt()
    val deck2 = if (dark) 0xFF16243D.toInt() else 0xFFF4F7FB.toInt()
    /** Translucent card surface that lets the aurora show through. */
    val glass = if (dark) 0xB8111D33.toInt() else 0xD9FFFFFF.toInt()
    val glassHi = if (dark) 0x26FFFFFF else 0xB3FFFFFF.toInt()
    val glassEdge = if (dark) 0x14FFFFFF else 0x1A0B2550
    val rule = if (dark) 0xFF213150.toInt() else 0xFFD8E0EC.toInt()
    val ruleSoft = if (dark) 0x1FFFFFFF else 0x140B2550
    val fg = if (dark) 0xFFEAF0FA.toInt() else 0xFF0F1D36.toInt()
    val mute = if (dark) 0xFF93A3BF.toInt() else 0xFF56698A.toInt()
    val faint = if (dark) 0xFF5F7192.toInt() else 0xFF8D9AB0.toInt()
    val accent = if (dark) 0xFF2E93FF.toInt() else 0xFF0A6FE0.toInt()
    val tide = if (dark) 0xFF39D0FF.toInt() else 0xFF00A6D6.toInt()
    val violet = if (dark) 0xFF7B5CFF.toInt() else 0xFF6A4CF0.toInt()
    val done = if (dark) 0xFF3FD69A.toInt() else 0xFF1F9D68.toInt()
    val hold = if (dark) 0xFFF5BD52.toInt() else 0xFFC98A0F.toInt()
    val fault = if (dark) 0xFFFF6B66.toInt() else 0xFFD43F3A.toInt()
    val track = if (dark) 0x1FFFFFFF else 0x1A0B2550
    val select = if (dark) 0x332E93FF else 0x220A6FE0

    fun cat(c: String): Int = when (c) {
        "Compressed" -> 0xFFF0B45A.toInt(); "Documents" -> 0xFF5AAEFF.toInt(); "Music" -> 0xFFC584FF.toInt()
        "Programs" -> 0xFF3FD6B0.toInt(); "Video" -> 0xFFFF7A70.toInt(); "Images" -> 0xFF8BD764.toInt()
        else -> if (dark) 0xFF93A3BF.toInt() else 0xFF6F7F9B.toInt()
    }

    fun status(s: Status): Int = when (s) {
        Status.COMPLETE -> done; Status.ERROR -> fault; Status.PAUSED -> hold
        Status.QUEUED -> faint; else -> accent
    }

    companion object {
        fun of(ctx: Context): Palette {
            val pref = VoltApp.engine(ctx).settings.theme
            val sysDark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            return Palette(when (pref) { "dark" -> true; "light" -> false; else -> sysDark })
        }
    }
}

fun Context.dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())
fun Context.dpf(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

fun alpha(color: Int, a: Float): Int = Color.argb((a * 255).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

/** Mixes two colours; t = 0 gives a, 1 gives b. */
fun mix(a: Int, b: Int, t: Float): Int = Color.argb(
    (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(), (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
    (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
)

object Type {
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val black: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
    val condensed: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
}

/** Material "emphasized" easing. */
val Ease = PathInterpolator(0.2f, 0f, 0f, 1f)

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0): GradientDrawable = GradientDrawable().apply {
    setColor(color); cornerRadius = radius
    if (strokeW > 0) setStroke(strokeW, stroke)
}

fun gradient(radius: Float, vararg colors: Int, orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.TL_BR): GradientDrawable =
    GradientDrawable(orientation, colors).apply { cornerRadius = radius }

fun ripple(content: Drawable?, ripple: Int, mask: Drawable? = null): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(ripple), content, mask ?: GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 9999f })

/** Glass surface: translucent fill, faint edge and a light catch along the top. */
fun glass(p: Palette, radius: Float, ctx: Context): Drawable {
    val body = rounded(p.glass, radius, p.glassEdge, ctx.dp(1))
    val sheen = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(alpha(Color.WHITE, if (p.dark) .06f else .5f), Color.TRANSPARENT)).apply { cornerRadius = radius }
    return LayerDrawable(arrayOf(body, sheen))
}

fun haptic(v: View, strong: Boolean = false) {
    if (!VoltApp.engine(v.context).settings.haptics) return
    v.performHapticFeedback(if (strong && Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP)
}

/** Springy press feedback that leaves click handling alone. */
fun View.pressable(scale: Float = 0.965f): View {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(scale).scaleY(scale).setDuration(90).setInterpolator(Ease).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
        }
        false
    }
    return this
}

/** Fades and lifts a view into place. */
fun View.enter(delay: Long = 0, dy: Float = 18f) {
    alpha = 0f; translationY = context.dpf(dy)
    animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(420).setInterpolator(Ease).start()
}

/** Paints a TextView's glyphs with a horizontal gradient. */
fun TextView.gradientText(vararg colors: Int) {
    addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
        (v as TextView).paint.shader = LinearGradient(0f, 0f, (r - l).toFloat().coerceAtLeast(1f), 0f, colors, null, Shader.TileMode.CLAMP)
        v.invalidate()
    }
}

fun animateInt(from: Int, to: Int, ms: Long = 500, onValue: (Int) -> Unit): ValueAnimator =
    ValueAnimator.ofInt(from, to).apply { duration = ms; interpolator = Ease; addUpdateListener { onValue(it.animatedValue as Int) }; start() }

/** Builders that keep layout code short. */
class Kit(val ctx: Context, val p: Palette) {
    fun text(s: CharSequence = "", size: Float = 14f, color: Int = p.fg, face: Typeface = Type.regular, lines: Int = 0): TextView =
        TextView(ctx).apply {
            text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color); typeface = face
            if (lines > 0) { maxLines = lines; ellipsize = android.text.TextUtils.TruncateAt.END }
            includeFontPadding = false
            fontFeatureSettings = "tnum"
        }

    fun vbox(pad: Int = 0, gap: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        if (gap > 0) { showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE; dividerDrawable = GradientDrawable().apply { setSize(1, gap); setColor(Color.TRANSPARENT) } }
    }

    fun hbox(gap: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        if (gap > 0) { showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE; dividerDrawable = GradientDrawable().apply { setSize(gap, 1); setColor(Color.TRANSPARENT) } }
    }

    fun icon(res: Int, color: Int = p.mute, size: Int = 22): ImageView = ImageView(ctx).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(ctx.dp(size), ctx.dp(size))
    }

    fun iconButton(res: Int, desc: String, color: Int = p.mute, onClick: () -> Unit): ImageView = ImageView(ctx).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(color); contentDescription = desc
        val pad = ctx.dp(10); setPadding(pad, pad, pad, pad)
        background = ripple(null, alpha(p.accent, .25f))
        layoutParams = LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44))
        setOnClickListener { haptic(this); onClick() }
        tooltipText = desc
        pressable(0.88f)
    }

    /** Round glass icon button for headers. */
    fun orb(res: Int, desc: String, color: Int = p.fg, onClick: () -> Unit): ImageView = iconButton(res, desc, color, onClick).apply {
        background = ripple(glass(p, ctx.dpf(22f), ctx), alpha(p.accent, .3f), rounded(Color.WHITE, ctx.dpf(22f)))
        val pad = ctx.dp(11); setPadding(pad, pad, pad, pad)
    }

    /** kind: "primary", "ghost", "danger", "tonal" */
    fun button(label: String, kind: String = "ghost", iconRes: Int = 0, onClick: () -> Unit): TextView = text(label, 14.5f, face = Type.medium).apply {
        gravity = Gravity.CENTER
        val r = ctx.dpf(16f)
        val fgc = when (kind) { "primary" -> Color.WHITE; "danger" -> p.fault; "tonal" -> p.accent; else -> p.fg }
        val bg: Drawable = when (kind) {
            "primary" -> gradient(r, p.accent, mix(p.accent, p.tide, .55f), orientation = GradientDrawable.Orientation.LEFT_RIGHT)
            "danger" -> rounded(alpha(p.fault, .12f), r, alpha(p.fault, .35f), ctx.dp(1))
            "tonal" -> rounded(alpha(p.accent, .14f), r)
            else -> glass(p, r, ctx)
        }
        if (kind == "primary") { elevation = ctx.dpf(6f); outlineSpotShadowColor = p.accent; outlineAmbientShadowColor = p.accent }
        setTextColor(fgc)
        background = ripple(bg, alpha(if (kind == "primary") Color.WHITE else p.accent, .25f), rounded(Color.WHITE, r))
        setPadding(ctx.dp(18), 0, ctx.dp(18), 0)
        minHeight = ctx.dp(48); minimumHeight = ctx.dp(48)
        if (iconRes != 0) {
            val d = ctx.getDrawable(iconRes)!!.mutate(); d.setTint(fgc); d.setBounds(0, 0, ctx.dp(18), ctx.dp(18))
            setCompoundDrawables(d, null, null, null); compoundDrawablePadding = ctx.dp(8)
        }
        setOnClickListener { haptic(this, kind == "primary"); onClick() }
        pressable()
    }

    fun chip(label: String, on: Boolean, dot: Int = 0, onClick: () -> Unit): TextView = text(label, 13f, if (on) (if (p.dark) Color.WHITE else p.fg) else p.mute, if (on) Type.medium else Type.regular).apply {
        gravity = Gravity.CENTER
        setPadding(ctx.dp(15), 0, ctx.dp(15), 0)
        minHeight = ctx.dp(36); minimumHeight = ctx.dp(36)
        val c = if (dot != 0) dot else p.accent
        val r = ctx.dpf(18f)
        val bg: Drawable = if (on) rounded(alpha(c, if (p.dark) .26f else .16f), r, alpha(c, .7f), ctx.dp(1)) else glass(p, r, ctx)
        background = ripple(bg, alpha(c, .3f), rounded(Color.WHITE, r))
        if (dot != 0) {
            val d = GradientDrawable().apply { setColor(dot); shape = GradientDrawable.OVAL; setSize(ctx.dp(8), ctx.dp(8)) }
            d.setBounds(0, 0, ctx.dp(8), ctx.dp(8))
            setCompoundDrawables(d, null, null, null); compoundDrawablePadding = ctx.dp(7)
        }
        setOnClickListener { haptic(this); onClick() }
        pressable(0.93f)
    }

    fun chipRow(): Pair<HorizontalScrollView, LinearLayout> {
        val row = hbox(ctx.dp(8)).apply { setPadding(ctx.dp(16), 0, ctx.dp(16), 0) }
        val sv = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(row) }
        return sv to row
    }

    fun input(hint: String, value: String = "", multi: Boolean = false): EditText = EditText(ctx).apply {
        setText(value); this.hint = hint; setHintTextColor(p.faint); setTextColor(p.fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        val r = ctx.dpf(14f)
        val idle = rounded(if (p.dark) 0x33000000 else 0x0D0B2550, r, p.glassEdge, ctx.dp(1))
        val focus = rounded(if (p.dark) 0x33000000 else 0x0D0B2550, r, p.accent, ctx.dp(2))
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focus); addState(intArrayOf(), idle)
        }
        setPadding(ctx.dp(14), ctx.dp(13), ctx.dp(14), ctx.dp(13))
        inputType = if (multi) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_URI
        else InputType.TYPE_CLASS_TEXT
        if (!multi) isSingleLine = true
        highlightColor = alpha(p.accent, .35f)
        if (Build.VERSION.SDK_INT >= 29) textCursorDrawable = rounded(p.accent, 2f).apply { setSize(ctx.dp(2), ctx.dp(20)) }
    }

    fun card(child: View, pad: Int = ctx.dp(16)): FrameLayout = FrameLayout(ctx).apply {
        background = glass(p, ctx.dpf(22f), ctx)
        setPadding(pad, pad, pad, pad)
        addView(child)
    }

    fun tile(category: String, sizeDp: Int = 42): FrameLayout = FrameLayout(ctx).apply {
        val c = p.cat(category)
        background = gradient(ctx.dpf(sizeDp * .3f), alpha(c, .34f), alpha(c, .12f))
        val ic = ImageView(ctx).apply { setImageResource(categoryIcon(category)); imageTintList = ColorStateList.valueOf(c) }
        addView(ic, FrameLayout.LayoutParams(ctx.dp(sizeDp / 2), ctx.dp(sizeDp / 2), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
    }

    fun space(w: Int = 0, h: Int = 0): View = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(w, h) }
    fun grow(): View = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }

    fun sectionTitle(s: String): TextView = text(s.uppercase(), 11.5f, p.mute, Type.medium).apply {
        letterSpacing = 0.12f; setPadding(ctx.dp(6), ctx.dp(22), 0, ctx.dp(10))
    }

    /** Big screen title with a gradient. */
    fun title(s: String): TextView = text(s, 28f, p.fg, Type.black).apply {
        letterSpacing = -0.01f
        gradientText(p.fg, mix(p.fg, p.accent, .55f))
    }

    fun divider(inset: Int = ctx.dp(14)): View = View(ctx).apply { setBackgroundColor(p.ruleSoft); layoutParams = lp(-1, 1).apply { setMargins(inset, 0, inset, 0) } }

    /** Pill with an icon and label, for small facts. */
    fun badge(label: String, color: Int, iconRes: Int = 0): TextView = text(label, 12f, color, Type.medium).apply {
        background = rounded(alpha(color, .14f), ctx.dpf(11f))
        setPadding(ctx.dp(9), ctx.dp(4), ctx.dp(10), ctx.dp(4))
        if (iconRes != 0) {
            val d = ctx.getDrawable(iconRes)!!.mutate(); d.setTint(color); d.setBounds(0, 0, ctx.dp(13), ctx.dp(13))
            setCompoundDrawables(d, null, null, null); compoundDrawablePadding = ctx.dp(5)
        }
    }
}

fun categoryIcon(c: String): Int = when (c) {
    "Compressed" -> R.drawable.ic_cat_archive; "Documents" -> R.drawable.ic_cat_doc; "Music" -> R.drawable.ic_cat_music
    "Programs" -> R.drawable.ic_cat_app; "Video" -> R.drawable.ic_cat_video; "Images" -> R.drawable.ic_cat_image
    else -> R.drawable.ic_cat_file
}

/** Draws behind the status and navigation bars, with icons that suit the palette. */
fun Activity.applyBars(p: Palette) {
    window.statusBarColor = Color.TRANSPARENT
    window.navigationBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= 29) { window.isNavigationBarContrastEnforced = false; window.isStatusBarContrastEnforced = false }
    window.decorView.setBackgroundColor(p.ink)
    if (Build.VERSION.SDK_INT >= 30) {
        window.setDecorFitsSystemWindows(false)
        val flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (p.dark) 0 else flags, flags)
    } else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            (if (p.dark) 0 else (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR))
    }
}

/** Calls back with (top, bottom) system-bar insets, including the keyboard. */
fun View.onInsets(cb: (top: Int, bottom: Int) -> Unit) {
    setOnApplyWindowInsetsListener { _, insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            cb(bars.top, maxOf(bars.bottom, ime.bottom))
        } else {
            @Suppress("DEPRECATION")
            cb(insets.systemWindowInsetTop, insets.systemWindowInsetBottom)
        }
        insets
    }
    requestApplyInsets()
}

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(w, h, weight)

fun Status.label(): String = when (this) {
    Status.QUEUED -> "Waiting"; Status.CONNECTING -> "Connecting"; Status.DOWNLOADING -> "Downloading"
    Status.PAUSED -> "Paused"; Status.COMPLETE -> "Finished"; Status.ERROR -> "Failed"
}

fun whenText(ms: Long): String {
    if (ms <= 0) return ""
    val diff = (System.currentTimeMillis() - ms) / 1000
    return when {
        diff < 60 -> "just now"
        diff < 3600 -> "${diff / 60} min ago"
        diff < 86400 -> "${diff / 3600} h ago"
        else -> java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(ms))
    }
}

val allCategories: List<String> get() = Categories.names()
