package com.voltfetch.engine

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Random-access destination for one download. */
interface Sink {
    fun write(pos: Long, buf: ByteArray, off: Int, len: Int)
    fun truncate(size: Long)
    fun close()
}

/** Where files go. The Android app stores them in Download/VoltFetch via MediaStore. */
interface Storage {
    /** Creates the destination and returns (locator, final file name). */
    fun create(name: String, category: String, mime: String): Pair<String, String>
    fun open(locator: String, fresh: Boolean): Sink
    /** Called when the download has finished; makes the file visible. */
    fun publish(locator: String)
    fun delete(locator: String)
    fun exists(locator: String): Boolean
    fun read(locator: String): InputStream
    fun rename(locator: String, newName: String): String?
    /** Moves a file to another category folder. */
    fun move(locator: String, category: String): Boolean = false
}

/** Persists the download list and settings. */
interface Store {
    fun loadDownloads(): String?
    fun saveDownloads(json: String)
    fun loadSettings(): String?
    fun saveSettings(json: String)
    /** Encrypts a secret (site password, cookies) before it is written to disk. Default: stored as is. */
    fun protect(secret: String): String = secret
    /** Reverses [protect]. Must return "" (not throw) for a secret that can no longer be decrypted. */
    fun reveal(stored: String): String = stored
}

/** Shared token bucket for the global speed limit. */
class Limiter {
    private var rate = 0.0
    private var tokens = 0.0
    private var last = System.nanoTime()

    @Synchronized fun setRate(bytesPerSec: Double) { rate = bytesPerSec; tokens = 0.0; last = System.nanoTime() }

    fun acquire(n: Int, cancelled: AtomicBoolean) {
        while (!cancelled.get()) {
            val wait: Long
            synchronized(this) {
                if (rate <= 0) return
                val now = System.nanoTime()
                tokens = (tokens + (now - last) / 1e9 * rate).coerceAtMost(rate / 4)
                last = now
                if (tokens >= n || (tokens > 0 && n > rate / 4)) { tokens -= n; return }
                wait = (((n - tokens) / rate) * 1000).toLong().coerceIn(5, 250)
            }
            Thread.sleep(wait)
        }
    }
}

class Job(val d: Download) {
    val cancelled = AtomicBoolean(false)
    val conns = java.util.Collections.synchronizedSet(HashSet<HttpURLConnection>())
    private val cancelActions = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Runs [action] when the job is cancelled; straight away if it already was, so nothing can slip past. */
    fun onCancel(action: () -> Unit) {
        cancelActions.add(action)
        if (cancelled.get() && cancelActions.remove(action)) runCatching { action() }
    }

    fun cancel() {
        cancelled.set(true)
        for (a in cancelActions) if (cancelActions.remove(a)) runCatching { a() }
        synchronized(conns) { for (c in conns) runCatching { c.disconnect() } }
    }
}

class Probe(val finalUrl: String, val name: String, val size: Long, val resumable: Boolean, val mime: String)

private class FatalError(msg: String) : IOException(msg)

data class SpecialTransferResult(val locator: String, val name: String, val mime: String, val size: Long)

/** Adapter for transfers implemented by a platform-specific engine such as embedded yt-dlp. */
interface SpecialTransferHandler {
    /** Runs the job to completion; throws on failure. Must stop promptly once [job] is cancelled (see [Job.onCancel]). */
    fun execute(task: MediaTask, job: Job, env: MediaEnv, progress: (MediaProgress) -> Unit): SpecialTransferResult
    /** Deletes any private working files of a task that was removed or is being downloaded again. */
    fun discard(id: Long) {}
    /** Called once at start-up: deletes working files that belong to no unfinished task. */
    fun cleanup(keep: Set<Long>) {}
}

/**
 * The download manager: segmented multi-connection transfers with dynamic splitting,
 * retries, a queue, a global speed limit, persistence and checksum verification.
 */
class Engine(private val storage: Storage, private val store: Store, private val specialHandler: SpecialTransferHandler? = null) {
    private val list = ArrayList<Download>()
    private val nextId = AtomicLong(1)
    @Volatile var settings: Settings = Settings()
        private set
    private val limiter = Limiter()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "voltfetch").apply { isDaemon = true } }
    private val ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "voltfetch-tick").apply { isDaemon = true } }
    @Volatile private var dirty = false
    @Volatile var networkAllowed = true
        private set
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Event) -> Unit>()
    /** Queues that are processing. Main always is; others start by hand or on schedule. */
    private val runningQueues = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val schedLast = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** The limit in force: KB/s (0 = none) and why ("manual", "rule", ""). */
    @Volatile var limitNow: Pair<Int, String> = 0 to ""
        private set
    /** Clock, replaceable in tests. */
    var clock: () -> Long = { System.currentTimeMillis() }

    init {
        store.loadSettings()?.let { runCatching { settings = Settings.fromMap(Json.parse(it) as Map<String, Any?>) } }
        store.loadDownloads()?.let { s ->
            runCatching {
                @Suppress("UNCHECKED_CAST")
                for (m in Json.parse(s) as List<Map<String, Any?>>) {
                    val d = Download.fromMap(m)
                    d.cookies = runCatching { store.reveal(d.cookies) }.getOrDefault("")
                    d.auth = runCatching { store.reveal(d.auth) }.getOrDefault("")
                    list.add(d)
                    if (d.id >= nextId.get()) nextId.set(d.id + 1)
                }
            }
        }
        applyLimit()
        specialHandler?.let { h ->
            val keep = synchronized(list) { list.filter { it.taskKind.isNotEmpty() && it.status != Status.COMPLETE }.map { it.id }.toSet() }
            pool.execute { runCatching { h.cleanup(keep) } }
        }
        var n = 0
        ticker.scheduleAtFixedRate({
            runCatching {
                tick()
                n++
                if (n % 5 == 0 && (dirty || activeCount() > 0)) save()
                if (n % 5 == 0) applyLimit()
                if (n % 10 == 0) checkSchedule()
                if (n % 2 == 0) autoRetry()
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    fun onEvent(l: (Event) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Event) -> Unit) { listeners.remove(l) }
    private fun emit(e: Event) { for (l in listeners) runCatching { l(e) } }

    // ------------------------------------------------------------------ queries

    fun views(): List<View> = synchronized(list) { list.toList() }.map { it.snapshot() }
    fun view(id: Long): View? = get(id)?.snapshot()
    fun get(id: Long): Download? = synchronized(list) { list.firstOrNull { it.id == id } }
    fun activeCount(): Int = synchronized(list) { list.count { it.running } }
    fun totalSpeed(): Double = synchronized(list) { list.filter { it.running }.sumOf { it.speed } }

    // ------------------------------------------------------------------ settings

    fun updateSettings(s: Settings) {
        settings = s.normalized()
        store.saveSettings(Json.encode(settings.toMap()))
        applyLimit()
        processQueue()
        emit(Event("settings", 0, ""))
    }

    /** Manual limit wins; otherwise the strictest time-based rule active now. */
    fun applyLimit() {
        val s = settings
        val now = java.util.Calendar.getInstance().apply { timeInMillis = clock() }
        val next = when {
            s.limitOn -> s.limitKBs to "manual"
            else -> Planner.activeRule(s.speedRules, now)?.let { it.kbs to "rule" } ?: (0 to "")
        }
        if (next != limitNow) { limitNow = next; limiter.setRate(next.first * 1024.0) }
    }

    /** Wi-Fi only: the app reports whether downloading is allowed on the current network. */
    fun setNetworkAllowed(allowed: Boolean) {
        if (networkAllowed == allowed) return
        networkAllowed = allowed
        if (!allowed) {
            for (d in synchronized(list) { list.filter { it.running } }) stop(d, Status.QUEUED)
        }
        processQueue()
    }

    // ------------------------------------------------------------------ probing

    private fun open(url: String, d: Download?, range: String?): HttpURLConnection {
        var u = URL(url)
        var redirects = 0
        val s = settings
        val proxy = if (s.proxyOn && s.proxyHost.isNotBlank()) java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress.createUnresolved(s.proxyHost.trim(), s.proxyPort)) else null
        val (agent, cookies, auth, referer) = if (d == null) listOf("", "", "", "") else synchronized(d) { listOf(d.agent, d.cookies, d.auth, d.referer) }
        val origin = u.host
        while (true) {
            val c = (if (proxy != null) u.openConnection(proxy) else u.openConnection()) as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = s.timeoutSec * 1000
            c.readTimeout = s.timeoutSec * 1000
            c.setRequestProperty("User-Agent", agent.ifEmpty { s.userAgent })
            c.setRequestProperty("Accept", "*/*")
            c.setRequestProperty("Accept-Encoding", "identity")
            if (referer.isNotEmpty()) c.setRequestProperty("Referer", referer)
            // cookies and logins only go to the site they came from
            if (cookies.isNotEmpty() && u.host == origin) c.setRequestProperty("Cookie", cookies)
            if (auth.isNotEmpty() && u.host == origin) c.setRequestProperty("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString(auth.toByteArray(Charsets.UTF_8)))
            if (range != null) c.setRequestProperty("Range", range)
            val code = c.responseCode
            if (code in 300..399 && code != 304) {
                val loc = c.getHeaderField("Location") ?: return c
                c.disconnect()
                if (++redirects > 10) throw IOException("Too many redirects")
                u = URL(u, loc)
                continue
            }
            return c
        }
    }

    fun probe(url: String, referer: String = "", cookies: String = "", agent: String = "", auth: String = ""): Probe {
        val tmp = Download(0, url).also { it.referer = referer; it.cookies = cookies; it.agent = agent; it.auth = auth }
        val c = open(url, tmp, "bytes=0-")
        try {
            val code = c.responseCode
            if (code == 401) throw IOException("This site needs a login. Add your user name and password.")
            if (code == 407) throw IOException("The proxy needs a login, or the proxy settings are wrong")
            if (code >= 400) throw IOException("The server answered $code ${c.responseMessage ?: ""}".trim())
            var size = -1L
            var resumable = false
            if (code == 206) {
                resumable = true
                c.getHeaderField("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()?.let { size = it }
                if (size < 0) size = c.contentLengthLong
            } else if (c.contentLengthLong >= 0) size = c.contentLengthLong
            val mime = (c.contentType ?: "").substringBefore(';').trim()
            val final = c.url.toString()
            // the server's own name wins; otherwise the most meaningful part of either address
            val name = Names.withExtension(Names.best(Names.fromDisposition(c.getHeaderField("Content-Disposition")), Names.fromUrl(final), Names.fromUrl(url)), mime)
            return Probe(final, name, size, resumable && size > 0, mime)
        } finally {
            c.disconnect()
        }
    }

    // ------------------------------------------------------------------ list changes

    data class AddRequest(
        val url: String, val name: String = "", val category: String = "", val referer: String = "",
        val size: Long = -1, val resumable: Boolean = false, val probed: Boolean = false,
        val checksum: String = "", val start: Boolean = true, val note: String = "",
        val queue: String = MAIN_QUEUE, val cookies: String = "", val agent: String = "", val auth: String = "",
        /** true when the user typed the name; null = decide from the name itself. */
        val userNamed: Boolean? = null,
    )

    fun add(r: AddRequest): Download {
        val chosen = r.name.isNotBlank() && (r.userNamed ?: !Names.isGeneric(r.name))
        val name = Names.sanitize(if (chosen) r.name else Names.best(r.name, Names.fromUrl(r.url)))
        val d = Download(nextId.getAndIncrement(), r.url.trim())
        d.name = name
        d.autoName = !chosen
        d.category = r.category.ifBlank { Categories.of(name) }
        d.referer = r.referer
        d.note = r.note
        d.mime = Categories.mime(name)
        if (r.probed) { d.size = r.size; d.resumable = r.resumable; d.probed = true }
        d.checksum = Checksums.normalize(r.checksum)
        d.queue = if (r.queue in settings.queueNames()) r.queue else MAIN_QUEUE
        d.cookies = r.cookies; d.agent = r.agent; d.auth = r.auth
        d.status = if (r.start) Status.QUEUED else Status.PAUSED
        synchronized(list) {
            d.queuePos = (list.filter { it.queue == d.queue }.maxOfOrNull { it.queuePos } ?: 0) + 1
            list.add(d)
        }
        markDirty()
        processQueue()
        return d
    }

    /** Adds an embedded media-engine job (any site yt-dlp supports) to the same persistent queue as ordinary downloads. */
    fun addMediaTask(
        url: String, title: String, kind: String, option: Int, queue: String = MAIN_QUEUE, start: Boolean = true,
        /** Subtitle languages to embed in a video ("" = none). */
        subs: String = "",
        /** Playlist title, shown in the note when the task came from a playlist. */
        playlist: String = "",
        /** Extra choices, see [MediaOpts]. */
        opts: Map<String, String> = emptyMap(),
        /** Thumbnail address for the list and notifications. */
        thumb: String = "",
        /** [title] is only a placeholder (quick save): replace it with the real title when the engine reports it. */
        autoTitle: Boolean = false,
    ): Download {
        require(kind in MediaKinds.all) { "Unsupported media task" }
        require(MediaSites.isWeb(url)) { "Media tasks need a web page address (http or https)" }
        val validOption = when (kind) { MediaKinds.VIDEO -> option in 144..4320; MediaKinds.MP3 -> option in 64..320; else -> option == 0 }
        require(validOption) { "Invalid media option" }
        val site = MediaSites.label(url)
        val o = opts.filterKeys { it in MediaOpts.keys }.toMutableMap()
        if (!YouTube.isUrl(url)) o.remove("sb") // SponsorBlock only knows YouTube
        val clip = MediaOpts.clip(o)
        if (clip == null) { o.remove("from"); o.remove("to") }
        val base = Names.sanitize(title.trim().ifBlank { "$site media" }.take(140))
        val clipTag = clip?.let { " (${MediaOpts.clock(it.first)}-${MediaOpts.clock(it.second)})".replace(':', '.') } ?: ""
        val d = Download(nextId.getAndIncrement(), url.trim())
        d.name = Names.sanitize(base + clipTag) + when (kind) { MediaKinds.MP3 -> ".mp3"; MediaKinds.AUDIO -> ".m4a"; else -> ".mp4" }
        d.autoName = autoTitle && clip == null
        d.category = if (MediaKinds.isAudio(kind)) "Music" else "Video"
        d.mime = when (kind) { MediaKinds.MP3 -> "audio/mpeg"; MediaKinds.AUDIO -> "audio/mp4"; else -> "video/mp4" }
        d.taskKind = kind
        d.taskOption = option
        d.taskSubs = if (kind == MediaKinds.VIDEO) subs.trim() else ""
        d.taskOpts = MediaOpts.encode(o)
        d.thumb = thumb.trim().takeIf { MediaSites.isWeb(it) } ?: ""
        d.taskProgress = 0.0
        d.note = buildString {
            append(site).append(" · ")
            append(when (kind) { MediaKinds.MP3 -> "MP3 · $option kbps"; MediaKinds.AUDIO -> "original audio"; else -> "up to ${option}p" })
            if (d.taskSubs.isNotEmpty()) append(" · subtitles")
            if (o["sb"] == "1") append(" · SponsorBlock")
            if (clip != null) append(" · clip ${MediaOpts.clock(clip.first)}–${MediaOpts.clock(clip.second)}")
            if (playlist.isNotBlank()) append(" · ${playlist.trim().take(60)}")
        }
        d.queue = if (queue in settings.queueNames()) queue else MAIN_QUEUE
        d.status = if (start) Status.QUEUED else Status.PAUSED
        synchronized(list) {
            d.queuePos = (list.filter { it.queue == d.queue }.maxOfOrNull { it.queuePos } ?: 0) + 1
            list.add(d)
        }
        markDirty(); processQueue()
        return d
    }

    /** Resume by hand: Main-queue files wait their turn; files in a stopped queue start straight away. */
    fun resume(ids: Collection<Long>) = resume(ids, auto = false)

    private fun resume(ids: Collection<Long>, auto: Boolean) {
        val now = ArrayList<Download>()
        for (id in ids) get(id)?.let { d ->
            synchronized(d) {
                if (!auto) { d.autoRetries = 0; d.retryAt = 0 }
                if (!d.running && d.status != Status.COMPLETE) {
                    d.status = Status.QUEUED; d.error = ""
                    if (d.queue != MAIN_QUEUE && d.queue !in runningQueues) now.add(d)
                }
            }
        }
        markDirty()
        if (networkAllowed) for (d in now) start(d)
        processQueue()
    }

    fun pause(ids: Collection<Long>) {
        for (id in ids) get(id)?.let { d ->
            if (d.running) stop(d, Status.PAUSED)
            else synchronized(d) { d.retryAt = 0; if (d.status == Status.QUEUED) d.status = Status.PAUSED } // pausing also cancels an automatic retry
        }
        markDirty()
        processQueue()
    }

    fun pauseAll() = pause(synchronized(list) { list.map { it.id } })
    fun resumeAll() = resume(synchronized(list) { list.filter { it.status == Status.PAUSED || it.status == Status.ERROR }.map { it.id } })

    fun remove(ids: Collection<Long>, deleteFiles: Boolean) {
        val removed = ArrayList<Download>()
        for (id in ids) get(id)?.let { d -> if (d.running) stop(d, Status.PAUSED, wait = true); removed.add(d) }
        synchronized(list) { list.removeAll(removed.toSet()) }
        for (d in removed) {
            val (st, loc) = synchronized(d) { d.status to d.locator }
            if (loc.isNotEmpty() && (st != Status.COMPLETE || deleteFiles)) runCatching { storage.delete(loc) }
            if (d.taskKind.isNotEmpty()) runCatching { specialHandler?.discard(d.id) }
        }
        markDirty()
        processQueue()
    }

    fun redownload(id: Long) {
        val d = get(id) ?: return
        if (d.running) stop(d, Status.PAUSED, wait = true)
        synchronized(d) {
            if (d.locator.isNotEmpty() && d.status != Status.COMPLETE) runCatching { storage.delete(d.locator) }
            d.locator = ""; d.segments.clear(); d.got = 0; d.probed = false; d.completed = 0
            if (d.taskKind.isNotEmpty()) { d.taskProgress = 0.0; d.size = -1 }
            d.verify = ""; d.hash = ""; d.status = Status.QUEUED; d.error = ""
        }
        if (d.taskKind.isNotEmpty()) runCatching { specialHandler?.discard(d.id) }
        markDirty()
        processQueue()
    }

    /** Changes the address (for expired links), keeping progress. Media tasks only accept web page addresses. */
    fun updateUrl(id: Long, url: String): Boolean {
        val d = get(id) ?: return false
        val clean = url.trim()
        if (clean.isEmpty()) return false
        val ok = synchronized(d) {
            if (d.running || (d.taskKind.isNotEmpty() && !MediaSites.isWeb(clean))) false
            else { d.url = clean; d.error = ""; true }
        }
        if (ok) markDirty()
        return ok
    }

    fun setNote(id: Long, note: String) { get(id)?.let { synchronized(it) { it.note = note } }; markDirty() }

    fun rename(id: Long, newName: String): Boolean {
        val d = get(id) ?: return false
        val clean = Names.sanitize(newName.trim())
        if (clean.isEmpty()) return false
        val loc = synchronized(d) { d.locator }
        val final = if (loc.isNotEmpty()) storage.rename(loc, clean) ?: return false else clean
        synchronized(d) { d.name = final; if (d.mime.isEmpty() || d.mime == "application/octet-stream") d.mime = Categories.mime(final) }
        markDirty(); save()
        return true
    }

    fun setCategory(id: Long, category: String) {
        val d = get(id) ?: return
        val loc = synchronized(d) { d.locator }
        if (loc.isNotEmpty()) runCatching { storage.move(loc, category) }
        synchronized(d) { d.category = category }
        markDirty(); save()
    }

    fun setLogin(id: Long, auth: String) { get(id)?.let { synchronized(it) { it.auth = auth } }; markDirty() }

    // ------------------------------------------------------------------ queues

    fun queueRunning(name: String) = name == MAIN_QUEUE || name in runningQueues

    fun startQueue(name: String) {
        if (name != MAIN_QUEUE) runningQueues.add(name)
        for (d in synchronized(list) { list.filter { it.queue == name } }) synchronized(d) {
            if (!d.running && (d.status == Status.PAUSED || d.status == Status.ERROR)) { d.status = Status.QUEUED; d.error = "" }
        }
        markDirty()
        processQueue()
    }

    fun stopQueue(name: String) {
        runningQueues.remove(name)
        pause(synchronized(list) { list.filter { it.queue == name && it.status != Status.COMPLETE }.map { it.id } })
    }

    fun moveToQueue(ids: Collection<Long>, q: String) {
        val target = if (q in settings.queueNames()) q else MAIN_QUEUE
        for (id in ids) get(id)?.let { d ->
            val pos = synchronized(list) { (list.filter { it.queue == target }.maxOfOrNull { it.queuePos } ?: 0) + 1 }
            synchronized(d) { if (d.queue != target) { d.queue = target; d.queuePos = pos } }
        }
        markDirty()
        processQueue()
    }

    /** Adds or replaces a queue (old = previous name, or null for new). */
    fun saveQueue(old: String?, q: Queue) {
        val s = settings
        val list2 = s.queues.toMutableList()
        val i = if (old == null) -1 else list2.indexOfFirst { it.name == old }
        if (i >= 0) list2[i] = q else list2.add(q)
        if (old != null && old != q.name) {
            for (d in synchronized(list) { list.filter { it.queue == old } }) synchronized(d) { d.queue = q.name }
            if (runningQueues.remove(old)) runningQueues.add(q.name)
        }
        updateSettings(s.copy(queues = list2))
        markDirty()
    }

    /** Deletes a queue; its downloads move to Main. */
    fun deleteQueue(name: String) {
        if (name == MAIN_QUEUE) return
        runningQueues.remove(name)
        for (d in synchronized(list) { list.filter { it.queue == name } }) synchronized(d) { d.queue = MAIN_QUEUE }
        updateSettings(settings.copy(queues = settings.queues.filter { it.name != name }))
        markDirty()
    }

    /** 1-based place among the files waiting in the same queue, or 0. */
    fun placeInLine(id: Long): Int {
        val d = get(id) ?: return 0
        val waiting = synchronized(list) { list.filter { it.queue == d.queue && !it.running && it.status == Status.QUEUED }.sortedWith(Comparator { a, b -> if (a.queuePos != b.queuePos) a.queuePos.compareTo(b.queuePos) else a.id.compareTo(b.id) }) }
        return waiting.indexOf(d) + 1
    }

    private fun checkSchedule(at: Long = clock()) {
        val now = java.util.Calendar.getInstance().apply { timeInMillis = at }
        val hmNow = hm(now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE))
        val day = "${now.get(java.util.Calendar.YEAR)}-${now.get(java.util.Calendar.DAY_OF_YEAR)}"
        val dow = now.get(java.util.Calendar.DAY_OF_WEEK) - 1
        for (q in settings.queues) {
            val sc = q.schedule
            if (!sc.enabled) continue
            val st = minutesOf(sc.start)
            if (st >= 0 && hm(st) == hmNow && sc.days[dow] && schedLast.put("start|${q.name}", day + hmNow) != day + hmNow) {
                startQueue(q.name); emit(Event("queueStart", 0, q.name))
            }
            val sp = minutesOf(sc.stop)
            if (sc.stopEnabled && sp >= 0 && hm(sp) == hmNow && schedLast.put("stop|${q.name}", day + hmNow) != day + hmNow) {
                stopQueue(q.name); emit(Event("queueStop", 0, q.name))
            }
        }
    }

    /** Called by the app's alarm with the moment it was set for, in case it fired late. */
    fun runSchedulesAt(at: Long) = checkSchedule(at)

    // ------------------------------------------------------------------ grabber

    /** Reads a web page (up to 8 MB) and returns its text and final address. */
    fun fetchPage(url: String, cookies: String = "", agent: String = ""): Pair<String, String> {
        val tmp = Download(0, url).also { it.cookies = cookies; it.agent = agent }
        val c = open(url, tmp, null)
        try {
            if (c.responseCode >= 400) throw IOException("The server answered ${c.responseCode}")
            val cs = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(c.contentType ?: "")?.groupValues?.get(1) ?: "UTF-8"
            val bytes = c.inputStream.use { inp ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16 * 1024)
                while (out.size() < 8 * 1024 * 1024) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                out.toByteArray()
            }
            return String(bytes, runCatching { charset(cs) }.getOrDefault(Charsets.UTF_8)) to c.url.toString()
        } finally { c.disconnect() }
    }

    fun grab(pageUrl: String, exts: Set<String>): List<String> {
        val (html, final) = fetchPage(pageUrl)
        return Grabber.extract(final, html, exts)
    }

    /** Moves downloads within their queue's waiting order: "top", "up", "down", "bottom". */
    fun move(id: Long, where: String) {
        val q = get(id)?.let { synchronized(it) { it.queue } } ?: return
        synchronized(list) {
            val order = list.filter { it.queue == q }.sortedBy { it.queuePos }.toMutableList()
            val i = order.indexOfFirst { it.id == id }
            if (i < 0) return
            val d = order.removeAt(i)
            val j = when (where) { "top" -> 0; "up" -> (i - 1).coerceAtLeast(0); "down" -> (i + 1).coerceAtMost(order.size); else -> order.size }
            order.add(j, d)
            order.forEachIndexed { k, x -> synchronized(x) { x.queuePos = (k + 1).toLong() } }
        }
        markDirty()
        processQueue()
    }

    // ------------------------------------------------------------------ queue

    @Synchronized fun processQueue() {
        if (!networkAllowed) return
        val s = settings
        val snapshot = synchronized(list) { list.toList() }
        val finished = ArrayList<String>()
        for (name in s.queueNames()) {
            if (!queueRunning(name)) continue
            val mine = snapshot.filter { it.queue == name }
            val active = mine.count { it.running }
            val waiting = mine.filter { !it.running && it.status == Status.QUEUED }
                .sortedWith(Comparator { a, b -> if (a.queuePos != b.queuePos) a.queuePos.compareTo(b.queuePos) else a.id.compareTo(b.id) })
            var slots = (if (name == MAIN_QUEUE) s.simultaneous else s.queue(name)?.maxConcurrent ?: 1) - active
            for (d in waiting) {
                if (slots <= 0) break
                start(d)
                slots--
            }
            if (name != MAIN_QUEUE && active == 0 && waiting.isEmpty()) finished.add(name)
        }
        for (q in finished) if (runningQueues.remove(q)) emit(Event("queueDone", 0, q))
        runningQueues.retainAll(s.queueNames().toSet())
    }

    private fun start(d: Download) {
        val job: Job
        synchronized(d) {
            if (d.running || d.status == Status.COMPLETE) return
            job = Job(d)
            d.job = job
            d.stopAs = Status.PAUSED
            d.status = Status.CONNECTING
            d.lastTick = 0
        }
        markDirty()
        pool.execute { run(job) }
    }

    private fun stop(d: Download, as_: Status, wait: Boolean = false) {
        val job = synchronized(d) { d.stopAs = as_; d.job } ?: return
        job.cancel()
        if (wait) {
            val deadline = System.currentTimeMillis() + 5000
            while (d.running && System.currentTimeMillis() < deadline) Thread.sleep(20)
        }
    }

    // ------------------------------------------------------------------ transfer

    private fun run(job: Job) {
        val d = job.d
        try {
            if (d.taskKind.isNotEmpty()) runMedia(job) else transfer(job)
        } catch (e: Throwable) {
            synchronized(d) {
                if (job.cancelled.get()) d.status = d.stopAs
                else {
                    d.status = Status.ERROR; d.error = friendly(e)
                    // network trouble: try again by itself a few times, sooner if the connection comes back
                    d.retryAt = if (isTransient(e) && d.autoRetries < retryDelays.size) clock() + retryDelays[d.autoRetries] else 0
                }
            }
        } finally {
            synchronized(d) {
                d.job = null
                d.speed = 0.0
                d.taskStage = ""; d.taskEta = -1.0
                for (s in d.segments) { s.active = false; s.speed = 0.0 }
            }
            markDirty()
            val (st, name, id, err) = synchronized(d) { listOf(d.status, d.name, d.id, d.error) }
            when (st) {
                Status.COMPLETE -> {
                    emit(Event("complete", id as Long, name as String))
                    if (synchronized(d) { d.checksum.isNotEmpty() }) pool.execute { verify(d.id, "") }
                }
                Status.ERROR -> emit(Event(if (synchronized(d) { d.retryAt } > 0) "retrying" else "error", id as Long, name as String, err as String))
                else -> {}
            }
            save()
            processQueue()
        }
    }

    /** The user's HTTP proxy, if one is switched on. */
    private fun upstreamProxy(): Pair<String, Int>? = settings.let { s -> if (s.proxyOn && s.proxyHost.isNotBlank()) s.proxyHost.trim() to s.proxyPort else null }

    private fun runMedia(job: Job) {
        val d = job.d
        val handler = specialHandler ?: throw IOException("The embedded media engine is unavailable")
        val task = synchronized(d) {
            d.lastTry = System.currentTimeMillis(); d.error = ""; d.status = Status.DOWNLOADING; d.taskStage = "Starting"
            if (d.taskProgress < 0) d.taskProgress = 0.0
            MediaTask(d.id, d.url, d.taskKind, d.taskOption, d.taskSubs, d.category, d.name, MediaOpts.parse(d.taskOpts))
        }
        markDirty()
        // yt-dlp's traffic goes through a local proxy so it shares the global speed limit and the user's proxy.
        val proxy = runCatching { ThrottleProxy(limiter, { upstreamProxy() }, settings.timeoutSec * 1000) }.getOrNull()
        if (proxy != null) job.onCancel { proxy.close() }
        val result = try {
            handler.execute(task, job, MediaEnv(proxy?.url)) { mp ->
                synchronized(d) {
                    if (job.cancelled.get()) return@synchronized
                    // quick saves start with a placeholder name; use the real title once the engine reports it
                    if (d.autoName && mp.title.isNotBlank() && d.locator.isEmpty()) {
                        d.name = Names.sanitize(mp.title.take(140)) + "." + d.name.substringAfterLast('.', "mp4")
                        d.autoName = false
                    }
                    d.taskProgress = maxOf(d.taskProgress, mp.percent.coerceIn(0.0, 99.9))
                    d.taskStage = mp.stage; d.speed = mp.speed; d.taskEta = mp.eta
                }
                markDirty()
            }
        } finally {
            proxy?.close()
        }
        if (job.cancelled.get()) throw java.util.concurrent.CancellationException("Media task cancelled")
        synchronized(d) {
            d.locator = result.locator; d.name = result.name; d.mime = result.mime
            d.size = result.size; d.got = result.size.coerceAtLeast(0); d.taskProgress = 100.0
            d.status = Status.COMPLETE; d.completed = System.currentTimeMillis(); d.error = ""
        }
    }

    /** Waits before each automatic retry of a download that failed because of the network. Replaceable in tests. */
    var retryDelays: List<Long> = listOf(30_000L, 120_000L, 300_000L)

    private fun isTransient(e: Throwable): Boolean = when (e) {
        is FatalError -> false
        is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.net.ConnectException,
        is java.net.SocketException, is javax.net.ssl.SSLException, is java.io.EOFException -> true
        else -> Regex("closed early|server answered 5\\d\\d|stopped before it finished|Check the connection|Connection (refused|reset)|timed out|Temporary failure|Network is unreachable", RegexOption.IGNORE_CASE)
            .containsMatchIn(e.message ?: "")
    }

    /** Retries downloads whose network-related failure is due for another try. */
    private fun autoRetry() {
        if (!networkAllowed) return
        val now = clock()
        val due = synchronized(list) { list.filter { synchronized(it) { it.status == Status.ERROR && it.retryAt in 1..now } } }
        if (due.isEmpty()) return
        for (d in due) synchronized(d) { d.autoRetries++; d.retryAt = 0 }
        resume(due.map { it.id }, auto = true)
    }

    /** The connection is back: retry network failures now instead of waiting out their delay. */
    fun retryTransient() {
        val now = clock()
        val due = synchronized(list) { list.filter { synchronized(it) { it.status == Status.ERROR && it.retryAt > 0 } } }
        for (d in due) synchronized(d) { d.retryAt = now }
        autoRetry()
    }

    private fun friendly(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "No internet connection, or the server name couldn't be found"
        is java.net.SocketTimeoutException -> "The server stopped responding"
        is java.net.ConnectException -> "Couldn't connect to the server"
        is javax.net.ssl.SSLException -> "Secure connection failed"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun transfer(job: Job) {
        val d = job.d
        synchronized(d) { d.lastTry = System.currentTimeMillis(); d.error = "" }
        if (!synchronized(d) { d.probed } || synchronized(d) { d.segments.isEmpty() && d.got == 0L && d.size < 0 }) {
            val (u, r, ck, ag, au) = synchronized(d) { listOf(d.url, d.referer, d.cookies, d.agent, d.auth) }
            val p = probe(u, r, ck, ag, au)
            synchronized(d) {
                d.size = p.size; d.resumable = p.resumable; d.probed = true
                if (d.locator.isEmpty() && d.autoName) {
                    // take the server's real name when ours was only a placeholder
                    val better = Names.withExtension(Names.best(p.name, d.name), p.mime)
                    if (better != d.name) {
                        if (d.category == Categories.of(d.name)) d.category = Categories.of(better)
                        d.name = Names.sanitize(better)
                    }
                } else if (d.locator.isEmpty()) d.name = Names.withExtension(d.name, p.mime)
                if (d.mime.isEmpty() || d.mime == "application/octet-stream") d.mime = p.mime.ifEmpty { Categories.mime(d.name) }
            }
        }
        if (job.cancelled.get()) return

        // Destination
        val fresh: Boolean
        synchronized(d) {
            fresh = d.segments.isEmpty() || (!d.resumable && d.got > 0) || d.locator.isEmpty() || !storage.exists(d.locator)
            if (fresh) {
                d.got = 0
                d.segments = ArrayList()
                if (d.resumable && d.size > 0) {
                    val n = settings.connections.coerceAtMost((d.size / MIN_SEGMENT).toInt()).coerceAtLeast(1)
                    val step = d.size / n
                    for (i in 0 until n) {
                        val st = i * step
                        val en = if (i == n - 1) d.size - 1 else st + step - 1
                        d.segments.add(Segment(st, en))
                    }
                } else d.segments.add(Segment(0, if (d.size > 0) d.size - 1 else -1))
            }
        }
        if (fresh) {
            val old = synchronized(d) { d.locator }
            if (old.isNotEmpty()) runCatching { storage.delete(old) }
            val (loc, finalName) = storage.create(synchronized(d) { d.name }, d.category, d.mime.ifEmpty { Categories.mime(d.name) })
            synchronized(d) { d.locator = loc; d.name = finalName }
            markDirty()
        }
        val sink = storage.open(synchronized(d) { d.locator }, fresh)
        try {
            synchronized(d) { d.status = Status.DOWNLOADING }
            val errors = java.util.Collections.synchronizedList(ArrayList<Throwable>())
            val threads = java.util.Collections.synchronizedList(ArrayList<Thread>())
            lateinit var launch: (Segment) -> Unit
            launch = { seg ->
                synchronized(d) { seg.active = true; seg.info = "Connecting" }
                val t = Thread({
                    try {
                        worker(job, seg, sink)
                        synchronized(d) { seg.active = false; seg.speed = 0.0; seg.info = "Done" }
                        if (!job.cancelled.get()) splitLargest(d)?.let { launch(it) }
                    } catch (e: Throwable) {
                        synchronized(d) { seg.active = false; seg.speed = 0.0; seg.info = e.message ?: "error" }
                        errors.add(e)
                    }
                }, "voltfetch-seg")
                t.isDaemon = true
                threads.add(t)
                t.start()
            }
            // Claim every starting range in one step. If ranges were claimed one by one as threads start,
            // a fast connection could finish, pick up a range that hasn't been claimed yet, and two
            // threads would end up writing the same range.
            val initial = synchronized(d) { d.segments.filter { !it.complete && !it.active }.take(settings.connections).onEach { it.active = true } }
            for (s in initial) launch(s)
            if (synchronized(d) { d.resumable }) {
                for (k in initial.size until settings.connections) splitLargest(d)?.let { launch(it) } ?: break
            }
            // wait for all workers, including ones started by splitting
            while (true) {
                val t = synchronized(threads) { threads.firstOrNull { it.isAlive } } ?: break
                t.join(500)
            }
            if (job.cancelled.get()) { synchronized(d) { d.status = d.stopAs }; return }
            val (allDone, finalSize) = synchronized(d) {
                val ok = d.segments.all { s -> if (s.end < 0) errors.isEmpty() else s.complete }
                if (ok && d.size < 0) d.size = d.got
                ok to d.size
            }
            if (!allDone) throw errors.firstOrNull() ?: IOException("The download stopped before it finished")
            if (finalSize >= 0) sink.truncate(finalSize)
        } finally {
            runCatching { sink.close() }
        }
        storage.publish(synchronized(d) { d.locator })
        fixUnknownType(d)
        synchronized(d) { d.status = Status.COMPLETE; d.completed = System.currentTimeMillis(); d.segments.clear() }
    }

    /** When neither the address nor the server said what the file is, look at its first bytes and name it properly. */
    private fun fixUnknownType(d: Download) {
        val (name, loc) = synchronized(d) { d.name to d.locator }
        val e = Names.ext(name)
        if (Names.known(e)) return
        val head = runCatching { storage.read(loc).use { inp -> val b = ByteArray(0x8010); var n = 0; while (n < b.size) { val r = inp.read(b, n, b.size - n); if (r < 0) break; n += r }; b.copyOf(n) } }.getOrNull() ?: return
        val ext = Names.sniff(head) ?: return
        val base = if (e == "bin") name.dropLast(4) else name
        val newName = "$base.$ext"
        val final = runCatching { storage.rename(loc, newName) }.getOrNull() ?: return
        val cat = Categories.of(final)
        synchronized(d) {
            d.name = final; d.mime = Categories.mime(final)
            if (d.autoName || d.category == Categories.GENERAL) d.category = cat
        }
        if (cat != Categories.GENERAL) runCatching { storage.move(loc, cat) }
    }

    /** Splits the biggest remaining range so a finished connection can help with it. */
    private fun splitLargest(d: Download): Segment? = synchronized(d) {
        if (!d.resumable) return null
        if (d.segments.count { it.active } >= settings.connections) return null
        d.segments.firstOrNull { !it.active && !it.complete && it.end >= 0 }?.let { it.active = true; return it }
        val best = d.segments.filter { it.active && it.end >= 0 }.maxByOrNull { it.length - it.done } ?: return null
        val rem = best.length - best.done
        if (rem < 2 * MIN_SEGMENT) return null
        val mid = best.start + best.done + rem / 2
        val ns = Segment(mid, best.end).also { it.active = true }
        best.end = mid - 1
        d.segments.add(ns)
        ns
    }

    private fun worker(job: Job, seg: Segment, sink: Sink) {
        val d = job.d

        var last: Throwable? = null
        for (attempt in 0..settings.retries) {
            if (job.cancelled.get()) return
            if (attempt > 0) {
                synchronized(d) { seg.info = "Retrying ($attempt)" }
                val until = System.currentTimeMillis() + 2000L * attempt.coerceAtMost(5)
                while (System.currentTimeMillis() < until && !job.cancelled.get()) Thread.sleep(100)
            }
            try {
                if (fetch(job, seg, sink)) return
            } catch (e: FatalError) {
                throw e
            } catch (e: IOException) {
                if (job.cancelled.get()) return
                last = e
            }
        }
        throw last ?: IOException("Connection failed")
    }

    /** Returns true when the segment is finished. */
    private fun fetch(job: Job, seg: Segment, sink: Sink): Boolean {
        val d = job.d
        val from: Long
        val end: Long
        val resumable: Boolean
        synchronized(d) {
            if (seg.complete) return true
            resumable = d.resumable
            if (!resumable && seg.done > 0) { d.got -= seg.done; seg.done = 0 }
            from = seg.start + seg.done
            end = seg.end
        }
        val range = if (resumable) (if (end >= 0) "bytes=$from-$end" else "bytes=$from-") else null
        val c = open(synchronized(d) { d.url }, d, range)
        job.conns.add(c)
        try {
            val code = c.responseCode
            when {
                code == 206 -> {}
                code == 200 -> if (resumable && from > 0) throw FatalError("The server no longer allows resuming. Use \"Download again\".")
                code == 416 -> throw FatalError("The file on the server has changed. Use \"Download again\".")
                code == 403 || code == 404 || code == 410 -> throw FatalError("The server answered $code. The link may have expired; paste a fresh one in Details.")
                else -> throw IOException("The server answered $code")
            }
            if (code == 206 && resumable) {
                // the server must send exactly the range we asked for, or the bytes would land in the wrong place
                val cr = c.getHeaderField("Content-Range") ?: ""
                val got = Regex("bytes\\s+(\\d+)-").find(cr)?.groupValues?.get(1)?.toLongOrNull()
                if (got != from) throw IOException("The server sent a different part of the file than requested")
            }
            synchronized(d) { seg.info = "Receiving" }
            val input = c.inputStream
            val buf = ByteArray(32 * 1024)
            while (true) {
                if (job.cancelled.get()) return false
                val n = input.read(buf)
                // charge the limiter for what actually arrived (charging a full buffer up front made it run slow)
                if (n > 0) limiter.acquire(n, job.cancelled)
                if (job.cancelled.get()) return false
                if (n < 0) {
                    synchronized(d) {
                        if (seg.end >= 0 && !seg.complete) throw IOException("The connection closed early")
                        if (seg.end < 0) seg.end = seg.start + seg.done - 1
                    }
                    return true
                }
                if (n == 0) continue
                var count = n.toLong()
                val pos: Long
                synchronized(d) {
                    pos = seg.start + seg.done
                    if (seg.end >= 0) {
                        val rem = seg.end - pos + 1
                        if (rem <= 0) return true
                        if (count > rem) count = rem
                    }
                }
                try {
                    sink.write(pos, buf, 0, count.toInt())
                } catch (e: IOException) {
                    throw FatalError("Couldn't write the file: ${e.message}. Is the phone's storage full?")
                }
                synchronized(d) {
                    // the range may have been split while we wrote; only count our part
                    if (seg.end >= 0) count = count.coerceAtMost(seg.end - pos + 1).coerceAtLeast(0)
                    seg.done += count
                    d.got += count
                    if (seg.complete) return true
                }
            }
        } finally {
            job.conns.remove(c)
            runCatching { c.disconnect() }
        }
    }

    // ------------------------------------------------------------------ verification

    fun verify(id: Long, expected: String) {
        val d = get(id) ?: return
        val loc: String
        val want: String
        synchronized(d) {
            if (d.status != Status.COMPLETE || d.verify == "checking") return
            if (expected.isNotBlank()) d.checksum = Checksums.normalize(expected)
            want = d.checksum
            loc = d.locator
            d.verify = "checking"
        }
        markDirty()
        val algo = Checksums.algoFor(want)
        val result = runCatching { Checksums.hash(storage.read(loc), algo) }
        synchronized(d) {
            d.hashAlgo = algo
            result.onSuccess { h ->
                d.hash = h
                d.verify = when { want.isEmpty() -> "done"; h == want -> "ok"; else -> "mismatch" }
            }.onFailure { d.verify = "error"; d.hash = "" }
        }
        markDirty()
        emit(Event("verify", d.id, d.name, synchronized(d) { d.verify }))
    }

    // ------------------------------------------------------------------ housekeeping

    private fun tick() {
        val now = System.nanoTime()
        for (d in synchronized(list) { list.filter { it.running && it.taskKind.isEmpty() } }) synchronized(d) {
            if (d.lastTick == 0L) {
                d.lastTick = now; d.lastBytes = d.got
                for (s in d.segments) s.last = s.done
                return@synchronized
            }
            val dt = (now - d.lastTick) / 1e9
            if (dt <= 0) return@synchronized
            val inst = ((d.got - d.lastBytes) / dt).coerceAtLeast(0.0)
            d.speed = if (d.speed == 0.0) inst else d.speed * 0.6 + inst * 0.4
            for (s in d.segments) {
                val si = ((s.done - s.last) / dt).coerceAtLeast(0.0)
                s.speed = s.speed * 0.5 + si * 0.5
                s.last = s.done
            }
            d.lastTick = now; d.lastBytes = d.got
        }
    }

    private fun markDirty() { dirty = true }

    @Synchronized fun save() {
        dirty = false
        val maps = synchronized(list) { list.toList() }.map { d ->
            val m = d.toMap().toMutableMap()
            for (k in listOf("cookies", "auth")) {
                val v = m[k] as? String ?: ""
                if (v.isNotEmpty()) m[k] = runCatching { store.protect(v) }.getOrDefault("")
            }
            m
        }
        runCatching { store.saveDownloads(Json.encode(maps)) }
    }

    fun shutdown() {
        for (d in synchronized(list) { list.filter { it.running } }) stop(d, Status.QUEUED, wait = true)
        save()
        ticker.shutdownNow()
    }

    companion object {
        const val MIN_SEGMENT = 256 * 1024L
    }
}
