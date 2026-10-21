package com.voltfetch.engine

enum class Status { QUEUED, CONNECTING, DOWNLOADING, PAUSED, COMPLETE, ERROR }

/** One byte range fetched by one connection. end is inclusive; -1 means "until the server stops". */
class Segment(var start: Long, var end: Long, var done: Long = 0) {
    @Volatile var active = false
    @Volatile var speed = 0.0
    @Volatile var info = ""
    var last = 0L
    val length: Long get() = if (end < 0) -1 else end - start + 1
    val complete: Boolean get() = end >= 0 && done >= length
}

/** Engine settings (the app stores them as JSON). Lists are immutable so copy() is safe. */
data class Settings(
    var connections: Int = 8,
    var simultaneous: Int = 3,        // the Main queue's limit
    var retries: Int = 10,
    var timeoutSec: Int = 30,
    var limitOn: Boolean = false,     // manual limit; overrides time-based rules
    var limitKBs: Int = 1024,
    var wifiOnly: Boolean = false,
    var notifyDone: Boolean = true,
    var watchClipboard: Boolean = true,
    var theme: String = "system", // system, light, dark
    var userAgent: String = DEFAULT_AGENT,
    var queues: List<Queue> = emptyList(),       // besides Main
    var speedRules: List<SpeedRule> = emptyList(),
    var proxyOn: Boolean = false,
    var proxyHost: String = "",
    var proxyPort: Int = 8080,
    var haptics: Boolean = true,
    var motion: Boolean = true,       // animated background
) {
    fun toMap() = mapOf(
        "connections" to connections, "simultaneous" to simultaneous, "retries" to retries, "timeoutSec" to timeoutSec,
        "limitOn" to limitOn, "limitKBs" to limitKBs, "wifiOnly" to wifiOnly, "notifyDone" to notifyDone,
        "watchClipboard" to watchClipboard, "theme" to theme, "userAgent" to userAgent,
        "queues" to queues.map { it.toMap() }, "speedRules" to speedRules.map { it.toMap() },
        "proxyOn" to proxyOn, "proxyHost" to proxyHost, "proxyPort" to proxyPort, "haptics" to haptics, "motion" to motion,
    )

    fun normalized(): Settings = copy(
        connections = connections.coerceIn(1, 32), simultaneous = simultaneous.coerceIn(1, 8),
        retries = retries.coerceIn(0, 99), timeoutSec = timeoutSec.coerceIn(5, 300), limitKBs = limitKBs.coerceAtLeast(16),
        userAgent = userAgent.ifBlank { DEFAULT_AGENT }, proxyPort = proxyPort.coerceIn(1, 65535),
        queues = queues.filter { it.name.isNotBlank() && it.name != MAIN_QUEUE }.distinctBy { it.name },
    )

    fun queue(name: String): Queue? = queues.firstOrNull { it.name == name }
    fun queueNames(): List<String> = listOf(MAIN_QUEUE) + queues.map { it.name }

    companion object {
        const val DEFAULT_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
        @Suppress("UNCHECKED_CAST")
        fun fromMap(m: Map<String, Any?>): Settings {
            val d = Settings()
            return Settings(
                m.int("connections", d.connections), m.int("simultaneous", d.simultaneous), m.int("retries", d.retries),
                m.int("timeoutSec", d.timeoutSec), m.bool("limitOn", d.limitOn), m.int("limitKBs", d.limitKBs),
                m.bool("wifiOnly", d.wifiOnly), m.bool("notifyDone", d.notifyDone), m.bool("watchClipboard", d.watchClipboard),
                m.str("theme", d.theme), m.str("userAgent", d.userAgent),
                m.list("queues").mapNotNull { (it as? Map<String, Any?>)?.let { q -> Queue.fromMap(q) } },
                m.list("speedRules").mapNotNull { (it as? Map<String, Any?>)?.let { r -> SpeedRule.fromMap(r) } },
                m.bool("proxyOn"), m.str("proxyHost"), m.int("proxyPort", 8080), m.bool("haptics", true), m.bool("motion", true),
            ).normalized()
        }
    }
}

const val MAIN_QUEUE = "Main"

/** A download. Fields are guarded by synchronized(this). */
class Download(val id: Long, var url: String) {
    var referer = ""
    var name = ""
    var category = Categories.GENERAL
    var mime = ""
    var size = -1L
    var got = 0L
    var resumable = false
    var probed = false
    var status = Status.QUEUED
    var error = ""
    var segments = ArrayList<Segment>()
    var added = System.currentTimeMillis()
    var lastTry = 0L
    var completed = 0L
    var locator = ""      // where the file lives (content:// URI on Android, a path in tests)
    var checksum = ""     // expected, hex
    var verify = ""       // "", checking, ok, mismatch, done, error
    var hash = ""
    var hashAlgo = ""
    var queuePos = 0L
    var note = ""
    var queue = MAIN_QUEUE
    var cookies = ""      // from the in-app browser
    var agent = ""        // user-agent override (the browser's)
    var auth = ""         // "user:password" for sites that ask for a login
    var autoName = true   // the name wasn't chosen by the user, so the server's real name may replace it
    // Non-empty taskKind marks an engine-managed special transfer (e.g. embedded yt-dlp).
    var taskKind = ""
    var taskOption = 0
    var taskProgress = -1.0
    var taskSubs = ""      // subtitle languages to embed ("" = none)
    var taskOpts = ""      // extra media choices, see MediaOpts
    var thumb = ""         // thumbnail address shown in the list and notifications
    var taskStage = ""     // runtime: what the media engine is doing right now
    var taskEta = -1.0     // runtime: seconds left as reported by the media engine

    // runtime only
    @Volatile var speed = 0.0
    internal var lastBytes = 0L
    internal var lastTick = 0L
    internal var job: Job? = null
    internal var stopAs = Status.PAUSED
    internal var autoRetries = 0      // automatic retries used since the last manual start
    @Volatile var retryAt = 0L        // when the next automatic retry is due (0 = none)
    val running: Boolean get() = job != null

    @Synchronized fun recount() { if (segments.isNotEmpty()) got = segments.sumOf { it.done } }

    @Synchronized fun snapshot(): View = View(
        id, url, referer, name, category, mime, size, got, resumable, status, error, speed,
        added, lastTry, completed, locator, checksum, verify, hash, hashAlgo, queuePos, note, queue, auth.isNotEmpty(),
        segments.map { SegView(it.start, it.end, it.done, it.active, it.speed) }, taskKind, taskOption, taskProgress,
        taskSubs, taskStage, taskEta, taskOpts, thumb, retryAt,
    )

    @Synchronized fun toMap(): Map<String, Any?> = mapOf(
        "id" to id, "url" to url, "referer" to referer, "name" to name, "category" to category, "mime" to mime,
        "size" to size, "got" to got, "resumable" to resumable, "probed" to probed,
        "status" to (if (status == Status.CONNECTING || status == Status.DOWNLOADING) Status.PAUSED else status).name,
        "error" to error, "added" to added, "lastTry" to lastTry, "completed" to completed, "locator" to locator,
        "checksum" to checksum, "verify" to (if (verify == "checking") "" else verify), "hash" to hash, "hashAlgo" to hashAlgo,
        "queuePos" to queuePos, "note" to note, "queue" to queue, "cookies" to cookies, "agent" to agent, "auth" to auth, "autoName" to autoName,
        "segments" to segments.map { listOf(it.start, it.end, it.done) },
        "taskKind" to taskKind, "taskOption" to taskOption, "taskProgress" to taskProgress, "taskSubs" to taskSubs,
        "taskOpts" to taskOpts, "thumb" to thumb,
    )

    companion object {
        fun fromMap(m: Map<String, Any?>): Download {
            val d = Download(m.long("id"), m.str("url"))
            d.referer = m.str("referer"); d.name = m.str("name"); d.category = m.str("category", Categories.GENERAL)
            d.mime = m.str("mime"); d.size = m.long("size", -1); d.got = m.long("got"); d.resumable = m.bool("resumable")
            d.probed = m.bool("probed")
            d.status = runCatching { Status.valueOf(m.str("status")) }.getOrDefault(Status.PAUSED)
            d.error = m.str("error"); d.added = m.long("added"); d.lastTry = m.long("lastTry"); d.completed = m.long("completed")
            d.locator = m.str("locator"); d.checksum = m.str("checksum"); d.verify = m.str("verify"); d.hash = m.str("hash")
            d.hashAlgo = m.str("hashAlgo"); d.queuePos = m.long("queuePos"); d.note = m.str("note")
            d.queue = m.str("queue", MAIN_QUEUE).ifEmpty { MAIN_QUEUE }; d.cookies = m.str("cookies"); d.agent = m.str("agent"); d.auth = m.str("auth"); d.autoName = m.bool("autoName", false)
            d.taskKind = m.str("taskKind"); d.taskOption = m.int("taskOption"); d.taskProgress = (m["taskProgress"] as? Number)?.toDouble() ?: -1.0
            d.taskSubs = m.str("taskSubs"); d.taskOpts = m.str("taskOpts"); d.thumb = m.str("thumb")
            for (s in m.list("segments")) {
                val a = (s as? List<*>) ?: continue
                if (a.size == 3) d.segments.add(Segment((a[0] as Number).toLong(), (a[1] as Number).toLong(), (a[2] as Number).toLong()))
            }
            d.recount()
            return d
        }
    }
}

data class SegView(val start: Long, val end: Long, val done: Long, val active: Boolean, val speed: Double)

/** Immutable snapshot for the UI. */
data class View(
    val id: Long, val url: String, val referer: String, val name: String, val category: String, val mime: String,
    val size: Long, val got: Long, val resumable: Boolean, val status: Status, val error: String, val speed: Double,
    val added: Long, val lastTry: Long, val completed: Long, val locator: String,
    val checksum: String, val verify: String, val hash: String, val hashAlgo: String, val queuePos: Long, val note: String,
    val queue: String, val hasLogin: Boolean,
    val segs: List<SegView>, val taskKind: String = "", val taskOption: Int = 0, val taskProgress: Double = -1.0,
    val taskSubs: String = "", val taskStage: String = "", val taskEta: Double = -1.0,
    val taskOpts: String = "", val thumb: String = "",
    /** When a failed download will be retried automatically (0 = it won't). */
    val retryAt: Long = 0,
) {
    val isMedia: Boolean get() = taskKind.isNotEmpty()
    val active: Boolean get() = status == Status.CONNECTING || status == Status.DOWNLOADING
    val percent: Double get() = when {
        status == Status.COMPLETE -> 100.0
        taskKind.isNotEmpty() && taskProgress >= 0 -> taskProgress.coerceIn(0.0, 100.0)
        size > 0 -> (got.toDouble() * 100 / size).coerceIn(0.0, 100.0)
        else -> -1.0
    }
    val etaSeconds: Double get() = when {
        isMedia -> taskEta.coerceAtLeast(0.0)
        speed > 0 && size > 0 -> (size - got) / speed
        else -> 0.0
    }
    val host: String get() = try { java.net.URI(url).host ?: "" } catch (e: Exception) { "" }
}

/** Something that happened, for notifications. */
data class Event(val kind: String, val id: Long, val name: String, val detail: String = "")
