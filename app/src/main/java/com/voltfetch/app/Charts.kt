package com.voltfetch.app

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import com.voltfetch.engine.Fmt
import com.voltfetch.engine.Status
import kotlin.math.cos
import kotlin.math.sin
import com.voltfetch.engine.View as DView

/** Slow-drifting light behind every screen. Pauses when hidden; static when animation is turned off. */
class AuroraView(ctx: Context, private val p: Palette) : View(ctx) {
    private class Blob(val color: Int, val bx: Float, val by: Float, val r: Float, val speed: Float, val phase: Float) { var paint = Paint(Paint.ANTI_ALIAS_FLAG) }
    private val blobs = if (p.dark) listOf(
        Blob(alpha(p.accent, .42f), .15f, .05f, .75f, .11f, 0f),
        Blob(alpha(p.violet, .30f), .95f, .30f, .70f, .08f, 2f),
        Blob(alpha(p.tide, .20f), .40f, .85f, .80f, .07f, 4f),
    ) else listOf(
        Blob(alpha(p.accent, .20f), .10f, .02f, .75f, .11f, 0f),
        Blob(alpha(p.violet, .13f), .95f, .35f, .70f, .08f, 2f),
        Blob(alpha(p.tide, .16f), .35f, .90f, .80f, .07f, 4f),
    )
    private val start = System.nanoTime()
    private var lastFrame = 0L
    private var visible = true
    var animate = true

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val m = maxOf(w, h).toFloat()
        for (b in blobs) b.paint.shader = RadialGradient(0f, 0f, b.r * m, intArrayOf(b.color, alpha(b.color, Color.alpha(b.color) / 255f * .35f), Color.TRANSPARENT), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onWindowVisibilityChanged(v: Int) { super.onWindowVisibilityChanged(v); visible = v == VISIBLE; if (visible) invalidate() }

    override fun onDraw(c: Canvas) {
        c.drawColor(p.ink)
        val t = if (animate) (System.nanoTime() - start) / 1e9f else 0f
        val w = width.toFloat(); val h = height.toFloat()
        for (b in blobs) {
            val x = w * (b.bx + .16f * sin(t * b.speed + b.phase)); val y = h * (b.by + .10f * cos(t * b.speed * 1.3f + b.phase))
            c.save(); c.translate(x, y); c.drawCircle(0f, 0f, b.r * maxOf(w, h), b.paint); c.restore()
        }
        if (animate && visible) {
            // ~30 fps is plenty for something this slow
            val now = System.nanoTime()
            val wait = (33_000_000L - (now - lastFrame)).coerceAtLeast(0) / 1_000_000
            lastFrame = now
            postInvalidateDelayed(wait)
        }
    }
}

/** Speedometer: a 240° arc with a glowing gradient sweep and the live speed in the middle. */
class SpeedGauge(ctx: Context, private val p: Palette) : View(ctx) {
    private var target = 0.0
    private var shown = 0.0
    private var scale = 1024.0 * 1024
    var caption = ""
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Type.black }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Type.medium }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private val startAngle = 150f
    private val sweepAll = 240f

    fun set(bytesPerSec: Double, caption: String) {
        target = bytesPerSec; this.caption = caption
        // grow the scale with peaks, shrink it slowly
        val steps = doubleArrayOf(256.0, 512.0, 1024.0, 2048.0, 5120.0, 10240.0, 20480.0, 51200.0, 102400.0, 204800.0, 512000.0).map { it * 1024 }
        val want = steps.firstOrNull { it >= bytesPerSec * 1.15 } ?: steps.last()
        if (want > scale || want < scale / 2.5) scale = maxOf(want, 1024.0 * 1024)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val s = minOf(width.toFloat(), height * 1.18f); val sw = context.dpf(12f)
        val cx = width / 2f; val cy = height * .56f; val r = s / 2f - sw * 1.4f
        oval.set(cx - r, cy - r, cx + r, cy + r)
        // ease toward the live value
        shown += (target - shown) * .14
        if (kotlin.math.abs(target - shown) > 1) postInvalidateOnAnimation()
        val frac = (shown / scale).coerceIn(0.0, 1.0).toFloat()

        track.strokeWidth = sw; track.color = p.track
        c.drawArc(oval, startAngle, sweepAll, false, track)
        // ticks
        tick.strokeWidth = context.dpf(1.6f)
        val n = 40
        for (i in 0..n) {
            val f = i.toFloat() / n
            val a = Math.toRadians((startAngle + sweepAll * f).toDouble())
            val r1 = r - sw * 1.25f; val r2 = r1 - (if (i % 5 == 0) context.dpf(7f) else context.dpf(3.5f))
            tick.color = if (f <= frac && frac > 0) alpha(p.tide, .9f) else alpha(p.mute, .35f)
            c.drawLine(cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(), cx + r2 * cos(a).toFloat(), cy + r2 * sin(a).toFloat(), tick)
        }
        if (frac > 0.002f) {
            val shader = SweepGradient(cx, cy, intArrayOf(p.violet, p.accent, p.tide, p.tide), floatArrayOf(0f, .35f, .67f, 1f))
            shader.setLocalMatrix(Matrix().apply { setRotate(startAngle - 4, cx, cy) })
            glow.shader = shader; glow.strokeWidth = sw * 1.9f; glow.alpha = 110
            glow.maskFilter = BlurMaskFilter(sw * 1.3f, BlurMaskFilter.Blur.NORMAL)
            c.drawArc(oval, startAngle, sweepAll * frac, false, glow)
            arc.shader = shader; arc.strokeWidth = sw
            c.drawArc(oval, startAngle, sweepAll * frac, false, arc)
            // bright head
            val a = Math.toRadians((startAngle + sweepAll * frac).toDouble())
            val hx = cx + r * cos(a).toFloat(); val hy = cy + r * sin(a).toFloat()
            dot.color = alpha(Color.WHITE, .35f); dot.maskFilter = BlurMaskFilter(sw, BlurMaskFilter.Blur.NORMAL); c.drawCircle(hx, hy, sw * 1.1f, dot)
            dot.maskFilter = null; dot.color = Color.WHITE; c.drawCircle(hx, hy, sw * .32f, dot)
        }
        val (value, unit) = Fmt.speedParts(shown)
        num.color = p.fg; num.textSize = r * .52f
        c.drawText(value, cx, cy + num.textSize * .18f, num)
        small.color = p.mute; small.textSize = context.dpf(13f)
        c.drawText(unit, cx, cy + num.textSize * .18f + context.dpf(20f), small)
        small.textSize = context.dpf(11.5f); small.color = p.faint
        c.drawText(caption, cx, cy + r * .78f, small)
        // scale end labels
        small.textAlign = Paint.Align.LEFT
        val la = Math.toRadians(startAngle.toDouble()); val ra = Math.toRadians((startAngle + sweepAll).toDouble())
        c.drawText("0", cx + (r + sw) * cos(la).toFloat() - context.dpf(2f), cy + (r + sw) * sin(la).toFloat() + context.dpf(14f), small)
        small.textAlign = Paint.Align.RIGHT
        c.drawText(Fmt.speed(scale).ifEmpty { "" }, cx + (r + sw) * cos(ra).toFloat() + context.dpf(2f), cy + (r + sw) * sin(ra).toFloat() + context.dpf(14f), small)
        small.textAlign = Paint.Align.CENTER
    }
}

/** Progress bar: each connection's range fills as data arrives, with a gradient and a moving sheen on active downloads. */
class SegmentBar(ctx: Context, private val p: Palette) : View(ctx) {
    var data: DView? = null
        set(v) { field = v; invalidate() }
    var tint: Int = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val clip = Path()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.dp(6))

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val rad = h / 2
        paint.shader = null; paint.color = p.track; r.set(0f, 0f, w, h); c.drawRoundRect(r, rad, rad, paint)
        val d = data ?: return
        val base = when (d.status) {
            Status.COMPLETE -> p.done; Status.ERROR -> p.fault; Status.PAUSED, Status.QUEUED -> p.hold; else -> if (tint != 0) tint else p.accent
        }
        val hi = if (d.active) p.tide else mix(base, Color.WHITE, .25f)
        c.save()
        clip.reset(); clip.addRoundRect(r, rad, rad, Path.Direction.CW); c.clipPath(clip)
        paint.shader = LinearGradient(0f, 0f, w, 0f, base, hi, Shader.TileMode.CLAMP)
        var filledTo = 0f
        when {
            d.status == Status.COMPLETE -> { c.drawRect(0f, 0f, w, h, paint); filledTo = w }
            d.size > 0 && d.segs.isNotEmpty() -> {
                val k = w / d.size
                for (s in d.segs) {
                    val x = s.start * k; val ww = s.done * k
                    if (s.done > 0) c.drawRect(x, 0f, x + ww.coerceAtLeast(1.5f), h, paint)
                    filledTo = maxOf(filledTo, x + ww)
                }
            }
            d.size > 0 -> { filledTo = (d.got.toFloat() / d.size) * w; c.drawRect(0f, 0f, filledTo, h, paint) }
            d.active -> { val t = (System.currentTimeMillis() % 1400) / 1400f; c.drawRect((t * 1.4f - .4f) * w, 0f, (t * 1.4f) * w, h, paint); filledTo = 0f }
        }
        if (d.active) {
            val t = (System.currentTimeMillis() % 1600) / 1600f
            val bandW = w * .22f; val x = -bandW + (w + bandW) * t
            sheen.shader = LinearGradient(x, 0f, x + bandW, 0f, intArrayOf(Color.TRANSPARENT, alpha(Color.WHITE, .55f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            if (filledTo > 0) { c.save(); c.clipRect(0f, 0f, filledTo, h); c.drawRect(0f, 0f, w, h, sheen); c.restore() }
            postInvalidateOnAnimation()
        }
        c.restore()
    }
}

/** Big progress ring for the details screen: gradient sweep, glow and an orbiting spark while active. */
class RingView(ctx: Context, private val p: Palette) : View(ctx) {
    var percent = 0.0
    var status = Status.QUEUED
    var center = ""
    var caption = ""
    private var shown = 0.0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val spark = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()

    fun set(percent: Double, status: Status, center: String, caption: String) {
        this.percent = percent; this.status = status; this.center = center; this.caption = caption; invalidate()
    }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat(); val sw = context.dpf(11f)
        val cx = width / 2f; val cy = height / 2f; val rr = s / 2 - sw * 1.3f
        oval.set(cx - rr, cy - rr, cx + rr, cy + rr)
        shown += (percent - shown) * .15
        if (kotlin.math.abs(percent - shown) > .05) postInvalidateOnAnimation()
        paint.shader = null; paint.strokeWidth = sw; paint.color = p.track; c.drawArc(oval, 0f, 360f, false, paint)
        val (c1, c2) = when (status) {
            Status.COMPLETE -> p.done to mix(p.done, p.tide, .5f); Status.ERROR -> p.fault to mix(p.fault, p.hold, .4f)
            Status.PAUSED, Status.QUEUED -> p.hold to mix(p.hold, Color.WHITE, .2f); else -> p.accent to p.tide
        }
        val sweep = (shown.coerceIn(0.0, 100.0) / 100 * 360).toFloat()
        if (sweep > 0) {
            val sh = SweepGradient(cx, cy, intArrayOf(c1, c2, c1), floatArrayOf(0f, .5f, 1f)).apply { setLocalMatrix(Matrix().apply { setRotate(-90f, cx, cy) }) }
            glow.shader = sh; glow.strokeWidth = sw * 2f; glow.alpha = 100; glow.maskFilter = BlurMaskFilter(sw * 1.2f, BlurMaskFilter.Blur.NORMAL)
            c.drawArc(oval, -90f, sweep, false, glow)
            paint.shader = sh; c.drawArc(oval, -90f, sweep, false, paint)
        }
        if (status == Status.DOWNLOADING || status == Status.CONNECTING) {
            val a = Math.toRadians(((System.currentTimeMillis() % 2400) / 2400.0) * 360 - 90)
            val ox = cx + (rr + sw * 1.1f) * cos(a).toFloat(); val oy = cy + (rr + sw * 1.1f) * sin(a).toFloat()
            spark.color = alpha(p.tide, .5f); spark.maskFilter = BlurMaskFilter(sw * .6f, BlurMaskFilter.Blur.NORMAL); c.drawCircle(ox, oy, sw * .55f, spark)
            spark.maskFilter = null; spark.color = Color.WHITE; c.drawCircle(ox, oy, sw * .2f, spark)
            postInvalidateOnAnimation()
        }
        txt.color = p.fg; txt.typeface = Type.black; txt.textSize = s * .2f
        c.drawText(center, cx, cy + txt.textSize * .3f, txt)
        txt.color = p.mute; txt.typeface = Type.medium; txt.textSize = context.dpf(12.5f)
        c.drawText(caption, cx, cy + s * .2f, txt)
    }
}

/** The "loom": one lane per connection, positioned on the file's byte axis. */
class LoomView(ctx: Context, private val p: Palette) : View(ctx) {
    var data: DView? = null
        set(v) { field = v; requestLayout(); invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT; textSize = ctx.dpf(11.5f) }
    private val r = RectF()
    private val lane get() = context.dpf(15f)
    private val gap get() = context.dpf(7f)

    override fun onMeasure(w: Int, h: Int) {
        val n = data?.segs?.size ?: 0
        setMeasuredDimension(MeasureSpec.getSize(w), if (n == 0) 0 else (n * (lane + gap) - gap).toInt())
    }

    override fun onDraw(c: Canvas) {
        val d = data ?: return
        if (d.size <= 0) return
        val segs = d.segs.sortedWith(Comparator { a, b -> a.start.compareTo(b.start) })
        val labelW = context.dpf(78f)
        val aw = width - labelW
        val k = aw / d.size
        segs.forEachIndexed { i, s ->
            val y = i * (lane + gap)
            paint.shader = null
            paint.color = p.ruleSoft; c.drawRect(0f, y + lane / 2 - .5f, aw, y + lane / 2 + .5f, paint)
            val x = s.start * k; val rw = ((s.end - s.start + 1) * k).coerceAtLeast(3f)
            r.set(x, y + 3, x + rw, y + lane - 3); paint.color = p.track; c.drawRoundRect(r, 5f, 5f, paint)
            val dw = (s.done * k).coerceAtMost(rw)
            if (dw > 0) {
                r.set(x, y + 3, x + dw, y + lane - 3)
                paint.shader = if (d.active) LinearGradient(x, 0f, x + dw, 0f, p.accent, p.tide, Shader.TileMode.CLAMP) else null
                paint.color = p.hold
                c.drawRoundRect(r, 5f, 5f, paint)
            }
            paint.shader = null
            if (s.active && d.active) { paint.color = Color.WHITE; c.drawRect(x + dw - 1.5f, y + 1, x + dw + 1.5f, y + lane - 1, paint) }
            val len = s.end - s.start + 1
            txt.color = if (s.active) p.fg else p.mute
            val label = when {
                s.done >= len -> "done"
                s.active && s.speed > 0 -> Fmt.speed(s.speed)
                s.active -> "…"
                else -> "${(s.done * 100 / len.coerceAtLeast(1))}%"
            }
            c.drawText(label, width.toFloat(), y + lane / 2 + txt.textSize * .35f, txt)
        }
    }
}

/** Area chart of a speed history (bytes/s), newest on the right. */
class ChartView(ctx: Context, private val p: Palette, private val samples: Int = 120) : View(ctx) {
    var values: List<Double> = emptyList()
        set(v) { field = v; invalidate() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dpf(2f); strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint().apply { strokeWidth = 1f }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        grid.color = p.ruleSoft
        for (i in 1..3) { val y = h * i / 4; c.drawLine(0f, y, w, y, grid) }
        if (values.size < 2) return
        val max = (values.maxOrNull() ?: 0.0) * 1.15
        if (max <= 0) return
        val step = w / (samples - 1)
        val x0 = w - (values.size - 1) * step
        val path = Path()
        values.forEachIndexed { i, v -> val x = x0 + i * step; val y = h - (v / max * (h - 4)).toFloat(); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        val area = Path(path).apply { lineTo(x0 + (values.size - 1) * step, h); lineTo(x0, h); close() }
        fill.shader = LinearGradient(0f, 0f, 0f, h, alpha(p.accent, .45f), alpha(p.accent, 0f), Shader.TileMode.CLAMP)
        c.drawPath(area, fill)
        line.shader = LinearGradient(0f, 0f, w, 0f, p.violet, p.tide, Shader.TileMode.CLAMP); c.drawPath(path, line)
    }
}

/** Throughput over the last minute, stacked by file category (the header graph). */
class FlowView(ctx: Context, private val p: Palette, private val samples: Int = 120) : View(ctx) {
    var history: List<Map<String, Double>> = emptyList()
        set(v) { field = v; invalidate() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dpf(1.6f) }
    private val base = Paint().apply { strokeWidth = 1f }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        base.color = p.ruleSoft; c.drawLine(0f, h - .5f, w, h - .5f, base)
        val data = history
        if (data.size < 2) return
        val max = data.maxOf { it.values.sum() } * 1.12
        if (max <= 0) return
        val cats = data.flatMap { it.keys }.distinct()
        val step = w / (samples - 1)
        val x0 = w - (data.size - 1) * step
        val bottom = DoubleArray(data.size)
        for (cat in cats) {
            val top = DoubleArray(data.size) { bottom[it] + (data[it][cat] ?: 0.0) }
            val path = Path()
            for (i in data.indices) { val x = x0 + i * step; val y = (h - top[i] / max * (h - 2)).toFloat(); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
            val area = Path(path)
            for (i in data.indices.reversed()) area.lineTo(x0 + i * step, (h - bottom[i] / max * (h - 2)).toFloat())
            area.close()
            val col = p.cat(cat)
            fill.shader = LinearGradient(0f, 0f, 0f, h, alpha(col, .45f), alpha(col, .05f), Shader.TileMode.CLAMP); c.drawPath(area, fill)
            line.color = col; c.drawPath(path, line)
            for (i in data.indices) bottom[i] = top[i]
        }
    }
}
