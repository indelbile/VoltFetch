package com.voltfetch.engine

import java.net.URI
import java.util.Calendar

/** Minutes since midnight for "HH:MM", or -1. */
fun minutesOf(hm: String): Int {
    val p = hm.trim().split(":")
    if (p.size != 2) return -1
    val h = p[0].toIntOrNull() ?: return -1
    val m = p[1].toIntOrNull() ?: return -1
    return if (h in 0..23 && m in 0..59) h * 60 + m else -1
}

fun hm(minutes: Int): String = "%02d:%02d".format((minutes / 60) % 24, minutes % 60)

private fun days(m: Map<String, Any?>, k: String): List<Boolean> {
    val l = m.list(k).map { it as? Boolean ?: false }
    return if (l.size == 7) l else List(7) { true }
}

/** Calendar.DAY_OF_WEEK (Sunday = 1) to our index (Sunday = 0). */
private fun dayIndex(c: Calendar) = c.get(Calendar.DAY_OF_WEEK) - 1

/** When a queue starts and stops by itself. days: Sunday = 0. */
data class Schedule(
    val enabled: Boolean = false,
    val start: String = "02:00",
    val days: List<Boolean> = List(7) { true },
    val stopEnabled: Boolean = false,
    val stop: String = "07:00",
) {
    fun toMap() = mapOf("enabled" to enabled, "start" to start, "days" to days, "stopEnabled" to stopEnabled, "stop" to stop)

    fun describe(): String {
        if (!enabled) return "Starts when you tap Start"
        val d = dayText(days)
        return "Starts $start $d" + if (stopEnabled) ", stops $stop" else ""
    }

    companion object {
        fun fromMap(m: Map<String, Any?>) = Schedule(m.bool("enabled"), m.str("start", "02:00"), days(m, "days"), m.bool("stopEnabled"), m.str("stop", "07:00"))
    }
}

val DAY_SHORT = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

fun dayText(days: List<Boolean>): String = when {
    days.all { it } -> "every day"
    days.none { it } -> "on no days"
    days == listOf(false, true, true, true, true, true, false) -> "on weekdays"
    days == listOf(true, false, false, false, false, false, true) -> "at weekends"
    else -> "on " + DAY_SHORT.filterIndexed { i, _ -> days[i] }.joinToString(", ")
}

/** A named queue that runs a few downloads at a time. */
data class Queue(
    val name: String,
    val maxConcurrent: Int = 2,
    val schedule: Schedule = Schedule(),
) {
    fun toMap() = mapOf("name" to name, "maxConcurrent" to maxConcurrent, "schedule" to schedule.toMap())
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(m: Map<String, Any?>) = Queue(
            m.str("name", "Queue"), m.int("maxConcurrent", 2).coerceIn(1, 6),
            (m["schedule"] as? Map<String, Any?>)?.let { Schedule.fromMap(it) } ?: Schedule(),
        )
    }
}

/** A speed limit that applies during a time window. days: the day the window starts (Sunday = 0). */
data class SpeedRule(
    val enabled: Boolean = true,
    val from: String = "09:00",
    val to: String = "18:00",
    val days: List<Boolean> = listOf(false, true, true, true, true, true, false),
    val kbs: Int = 1024,
) {
    fun toMap() = mapOf("enabled" to enabled, "from" to from, "to" to to, "days" to days, "kbs" to kbs)

    fun active(now: Calendar): Boolean {
        if (!enabled || kbs <= 0) return false
        val f = minutesOf(from); val t = minutesOf(to)
        if (f < 0 || t < 0 || f == t) return false
        val cur = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val today = dayIndex(now); val yesterday = (today + 6) % 7
        return if (f < t) days[today] && cur >= f && cur < t
        else (days[today] && cur >= f) || (days[yesterday] && cur < t) // overnight window
    }

    companion object {
        fun fromMap(m: Map<String, Any?>) = SpeedRule(m.bool("enabled", true), m.str("from", "09:00"), m.str("to", "18:00"), days(m, "days"), m.int("kbs", 1024))
    }
}

object Planner {
    /** The strictest active rule, or null. */
    fun activeRule(rules: List<SpeedRule>, now: Calendar): SpeedRule? = rules.filter { it.active(now) }.minByOrNull { it.kbs }

    /** Next moment (ms) any queue schedule starts or stops, within a week; null if none. */
    fun nextEvent(queues: List<Queue>, nowMs: Long): Long? {
        var best: Long? = null
        for (q in queues) {
            val s = q.schedule
            if (!s.enabled) continue
            val times = mutableListOf(minutesOf(s.start) to s.days)
            if (s.stopEnabled) times.add(minutesOf(s.stop) to List(7) { true })
            for ((min, ds) in times) {
                if (min < 0) continue
                for (add in 0..7) {
                    val c = Calendar.getInstance().apply {
                        timeInMillis = nowMs; add(Calendar.DAY_OF_YEAR, add)
                        set(Calendar.HOUR_OF_DAY, min / 60); set(Calendar.MINUTE, min % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }
                    if (c.timeInMillis <= nowMs + 1000 || !ds[dayIndex(c)]) continue
                    if (best == null || c.timeInMillis < best) best = c.timeInMillis
                    break
                }
            }
        }
        return best
    }
}

/** Batch download: a pattern with one '*' (https://site/img*.jpg). */
object Batch {
    fun expand(pattern: String, from: Int, to: Int, width: Int, letters: Boolean): List<String> {
        if (!pattern.contains('*')) return emptyList()
        var a = from; var b = to
        if (b < a) { val t = a; a = b; b = t }
        if (b - a > 10000) b = a + 10000
        val out = ArrayList<String>()
        for (i in a..b) {
            val s = if (letters) { if (i !in 0..25) continue; ('a' + i).toString() } else i.toString().padStart(width, '0')
            out.add(pattern.replaceFirst("*", s))
        }
        return out
    }
}

/** Site grabber: finds links in a web page. */
object Grabber {
    private val linkRe = Regex("""(?:href|src|data-src|data-href)\s*=\s*(?:"([^"]+)"|'([^']+)'|([^\s>]+))""", RegexOption.IGNORE_CASE)

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

    /** exts empty = every link. */
    fun extract(base: String, html: String, exts: Set<String>): List<String> {
        val want = exts.map { it.trim().lowercase().removePrefix(".") }.filter { it.isNotEmpty() }.toSet()
        val baseUri = try { URI(base) } catch (e: Exception) { return emptyList() }
        val seen = LinkedHashSet<String>()
        for (m in linkRe.findAll(html)) {
            val raw = unescape((m.groupValues[1] + m.groupValues[2] + m.groupValues[3]).trim())
            val low = raw.lowercase()
            if (raw.isEmpty() || raw.startsWith("#") || low.startsWith("javascript:") || low.startsWith("mailto:") || low.startsWith("data:")) continue
            val u = try { baseUri.resolve(raw.replace(" ", "%20")) } catch (e: Exception) { continue }
            if (u.scheme != "http" && u.scheme != "https") continue
            val clean = u.toString().substringBefore('#')
            if (want.isNotEmpty() && Names.ext((u.path ?: "").substringAfterLast('/')) !in want) continue
            seen.add(clean)
        }
        return seen.toList().sorted()
    }
}

/** Sites whose terms don't allow downloading their videos. Media capture is turned off there. */
object NoCapture {
    private val hosts = listOf("youtube.com", "youtu.be", "googlevideo.com", "youtube-nocookie.com", "ytimg.com")
    fun blocked(url: String): Boolean {
        val h = try { URI(url).host?.lowercase() ?: return false } catch (e: Exception) { return false }
        return hosts.any { h == it || h.endsWith(".$it") }
    }
}
