// JVM self-test for the VoltFetch engine. Not part of the Android build.
// Run: kotlinc tools/EngineSelfTest.kt app/src/main/java/com/voltfetch/engine/*.kt -include-runtime -d /tmp/t.jar && java -jar /tmp/t.jar
import com.sun.net.httpserver.HttpServer
import com.voltfetch.engine.*
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class FileStorage(private val dir: File) : Storage {
    override fun create(name: String, category: String, mime: String): Pair<String, String> {
        var f = File(dir, name); var i = 1
        while (f.exists() || File(f.path + ".part").exists()) f = File(dir, name.substringBeforeLast('.') + " (${i++})." + name.substringAfterLast('.'))
        File(f.path + ".part").createNewFile()
        return f.path + ".part" to f.name
    }
    override fun open(locator: String, fresh: Boolean): Sink {
        val raf = RandomAccessFile(locator, "rw"); if (fresh) raf.setLength(0)
        return object : Sink {
            override fun write(pos: Long, buf: ByteArray, off: Int, len: Int) { synchronized(raf) { raf.seek(pos); raf.write(buf, off, len) } }
            override fun truncate(size: Long) { raf.setLength(size) }
            override fun close() { raf.close() }
        }
    }
    override fun publish(locator: String) {}
    override fun delete(locator: String) { File(locator).delete() }
    override fun exists(locator: String) = File(locator).exists()
    override fun read(locator: String) = File(locator).inputStream()
    override fun rename(locator: String, newName: String): String? {
        val f = File(locator); val to = File(f.parentFile, newName + (if (locator.endsWith(".part")) ".part" else ""))
        return if (f.renameTo(to)) newName else null
    }
}

class MemStore : Store {
    var dl: String? = null; var st: String? = null
    override fun loadDownloads() = dl
    override fun saveDownloads(json: String) { dl = json }
    override fun loadSettings() = st
    override fun saveSettings(json: String) { st = json }
}

val data = Random(7).nextBytes(6 * 1024 * 1024 + 123)
val pdfData = "%PDF-1.7\n".toByteArray() + Random(9).nextBytes(700 * 1024)
val hits = AtomicInteger()
val busyHits = java.util.concurrent.ConcurrentHashMap<String, Int>()
var throttleMs = 0L

fun server(): HttpServer {
    val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    s.executor = java.util.concurrent.Executors.newCachedThreadPool()
    s.createContext("/") { ex ->
        val path = ex.requestURI.path
        val range = ex.requestHeaders.getFirst("Range")
        val n = hits.incrementAndGet()
        try {
            if (path.startsWith("/norange")) {
                ex.sendResponseHeaders(200, 0) // chunked, size unknown
                ex.responseBody.use { it.write(data) }
                return@createContext
            }
            if (path.startsWith("/page.html")) {
                val html = "<a href=\"files/a.zip\">a</a> <a href='/b.PDF#x'>b</a> <img src=\"pic.jpg\"> <a href=\"javascript:x()\">j</a> <a href=\"files/a.zip\">dup</a>".toByteArray()
                ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8"); ex.sendResponseHeaders(200, html.size.toLong()); ex.responseBody.use { it.write(html) }; return@createContext
            }
            if (path.startsWith("/auth") && ex.requestHeaders.getFirst("Authorization") != "Basic " + java.util.Base64.getEncoder().encodeToString("amit:pw".toByteArray())) { ex.sendResponseHeaders(401, -1); ex.close(); return@createContext }
            if (path.startsWith("/cookie") && ex.requestHeaders.getFirst("Cookie") != "sid=42") { ex.sendResponseHeaders(403, -1); ex.close(); return@createContext }
            if (path.startsWith("/busy/") && busyHits.merge(path, 1, Int::plus)!! <= 2) { ex.sendResponseHeaders(503, -1); ex.close(); return@createContext }
            if (path.startsWith("/redirect")) { ex.responseHeaders.add("Location", "/file.zip"); ex.sendResponseHeaders(302, -1); ex.close(); return@createContext }
            if (path.startsWith("/named")) ex.responseHeaders.add("Content-Disposition", "attachment; filename*=UTF-8''Report%202026.pdf")
            if (path.startsWith("/dl/")) ex.responseHeaders.add("Content-Disposition", "attachment; filename=\"Annual Report.pdf\"")
            if (path.startsWith("/dl/") || path.startsWith("/pdfblob")) ex.responseHeaders.add("Content-Type", "application/octet-stream")
            if (path.startsWith("/typed/")) ex.responseHeaders.add("Content-Type", "video/mp4")
            val data = if (path.startsWith("/pdfblob")) pdfData else data
            var start = 0L; var end = data.size - 1L
            if (range != null) {
                val m = Regex("bytes=(\\d+)-(\\d*)").find(range)!!
                start = m.groupValues[1].toLong(); if (m.groupValues[2].isNotEmpty()) end = minOf(end, m.groupValues[2].toLong())
                ex.responseHeaders.add("Content-Range", "bytes $start-$end/${data.size}")
                ex.sendResponseHeaders(206, end - start + 1)
            } else ex.sendResponseHeaders(200, data.size.toLong())
            ex.responseBody.use { out ->
                var p = start
                val flaky = path.startsWith("/flaky") && n % 3 == 0
                while (p <= end) {
                    val len = minOf(16 * 1024L, end - p + 1).toInt()
                    out.write(data, p.toInt(), len); p += len
                    if (throttleMs > 0) Thread.sleep(throttleMs)
                    if (flaky && p - start > 100 * 1024) { ex.close(); return@createContext }
                }
            }
        } catch (e: Exception) { /* client went away */ }
    }
    s.start(); return s
}

fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
var failures = 0
fun check(name: String, ok: Boolean, detail: String = "") { println((if (ok) "PASS " else "FAIL ") + name + if (detail.isNotEmpty()) "  ($detail)" else ""); if (!ok) failures++ }
fun waitFor(e: Engine, id: Long, st: Status, ms: Long = 30000): View {
    val until = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < until) { val v = e.view(id)!!; if (v.status == st) return v; if (v.status == Status.ERROR && st != Status.ERROR) return v; Thread.sleep(20) }
    return e.view(id)!!
}
fun fileOf(v: View) = File(v.locator)

fun flakyLoop() {
    val srv = server(); val base = "http://127.0.0.1:${srv.address.port}"
    val dir = createTempDir("vfflaky"); val e = Engine(FileStorage(dir), MemStore())
    e.updateSettings(e.settings.copy(retries = 5))
    var bad = 0
    for (i in 1..40) {
        val d = e.add(Engine.AddRequest("$base/flaky$i.iso"))
        val v = waitFor(e, d.id, Status.COMPLETE, 60000)
        val got = fileOf(v).readBytes()
        if (!got.contentEquals(data)) {
            bad++
            val ranges = ArrayList<String>(); var j = 0
            while (j < data.size) { if (got[j] != data[j]) { val st = j; while (j < data.size && got[j] != data[j]) j++; ranges.add("$st-${j - 1} (${j - st} B)") } else j++ }
            val first = ranges.first().substringBefore('-').toInt(); val last = ranges.last().substringAfter('-').substringBefore(' ').toInt()
            val zeros = (first..last).count { got[it] == 0.toByte() }
            var shift = "none"
            for (k in -first..(data.size - first - 64)) { if (k == 0) continue; val ok = (first until minOf(first + 64, last)).all { it + k in data.indices && got[it] == data[it + k] }; if (ok) { shift = "$k"; break } }
            println("run $i BAD span $first-$last (${last - first + 1} B) zeros=$zeros shift=$shift  span%16384=${first % 16384} len%16384=${(last-first+1) % 16384}")
        }
    }
    println("bad $bad / 25"); e.shutdown(); srv.stop(0); kotlin.system.exitProcess(0)
}


/** Stands in for the yt-dlp handler: replays real yt-dlp output lines and can fail or block until cancelled. */
class FakeMedia(private val dir: File) : SpecialTransferHandler {
    val discarded = java.util.concurrent.CopyOnWriteArrayList<Long>()
    @Volatile var cleanedKeep: Set<Long>? = null
    @Volatile var mode = "ok"
    @Volatile var lastProxy: String? = null
    @Volatile var lastTask: MediaTask? = null
    val cancelHooks = AtomicInteger()
    override fun execute(task: MediaTask, job: Job, env: MediaEnv, progress: (MediaProgress) -> Unit): SpecialTransferResult {
        lastProxy = env.proxyUrl; lastTask = task
        val stop = java.util.concurrent.CountDownLatch(1)
        job.onCancel { cancelHooks.incrementAndGet(); stop.countDown() }
        val tr = MediaProgressTracker(task.kind)
        for (l in listOf("[youtube] abc: Downloading webpage", "[download] Destination: /w/a.f137.mp4",
                "[download]  50.0% of   10.00MiB at    1.00MiB/s ETA 00:05", "[download] 100% of   10.00MiB in 00:00:10 at 1.00MiB/s",
                "[download] Destination: /w/a.f140.m4a", "[download]  50.0% of    1.00MiB at    2.00MiB/s ETA 00:01",
                "[Merger] Merging formats into \"/w/a.mp4\"")) tr.feed(l)?.let(progress)
        when (mode) {
            "fail" -> throw IllegalStateException("ERROR: [youtube] abc: Video unavailable")
            "block" -> { stop.await(15, java.util.concurrent.TimeUnit.SECONDS); throw java.util.concurrent.CancellationException("cancelled") }
        }
        val f = File(dir, "media-${task.id}.mp4"); f.writeBytes(ByteArray(1234))
        return SpecialTransferResult(f.path, "Real Title [abc].mp4", "video/mp4", f.length())
    }
    override fun discard(id: Long) { discarded.add(id) }
    override fun cleanup(keep: Set<Long>) { cleanedKeep = keep }
}

/** Store that "encrypts" secrets, to prove they never reach disk in the clear. */
class SealedStore : Store {
    var dl: String? = null; var st: String? = null
    override fun loadDownloads() = dl
    override fun saveDownloads(json: String) { dl = json }
    override fun loadSettings() = st
    override fun saveSettings(json: String) { st = json }
    override fun protect(secret: String) = "enc1:" + java.util.Base64.getEncoder().encodeToString(secret.reversed().toByteArray())
    override fun reveal(stored: String) = if (stored.startsWith("enc1:")) String(java.util.Base64.getDecoder().decode(stored.removePrefix("enc1:"))).reversed() else stored
}

fun mediaTests(base: String, port: Int) {
    val dir = createTempDir("vfmedia"); val store = MemStore(); val fake = FakeMedia(dir)
    var e = Engine(FileStorage(dir), store, fake)
    e.updateSettings(e.settings.copy(retries = 2))

    // address helpers
    check("youtube: watch, short, youtu.be, music", YouTube.isUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ") && YouTube.isUrl("https://youtu.be/dQw4w9WgXcQ") && YouTube.isUrl("https://music.youtube.com/watch?v=dQw4w9WgXcQ") && YouTube.isUrl("https://m.youtube.com/shorts/dQw4w9WgXcQ"))
    check("youtube: not other sites or raw streams", !YouTube.isUrl("https://example.com/watch?v=1") && !YouTube.isUrl("https://rr1.googlevideo.com/x") && !YouTube.isUrl("https://notyoutube.com/x") && !YouTube.isUrl("ftp://youtube.com/x") && !YouTube.isUrl(null))
    check("youtube: video ids", YouTube.videoId("https://youtu.be/dQw4w9WgXcQ?t=3") == "dQw4w9WgXcQ" && YouTube.videoId("https://www.youtube.com/shorts/dQw4w9WgXcQ") == "dQw4w9WgXcQ" && YouTube.videoId("https://www.youtube.com/watch?list=PL1&v=dQw4w9WgXcQ") == "dQw4w9WgXcQ" && YouTube.videoId("https://www.youtube.com/playlist?list=PL1") == null)
    check("youtube: playlist ids", YouTube.playlistId("https://www.youtube.com/playlist?list=PLabc123") == "PLabc123" && YouTube.playlistId("https://youtu.be/dQw4w9WgXcQ") == null)
    check("youtube: found in shared text", YouTube.find("Watch this! https://youtu.be/dQw4w9WgXcQ via app") == "https://youtu.be/dQw4w9WgXcQ" && YouTube.find("youtu.be/dQw4w9WgXcQ") == "https://youtu.be/dQw4w9WgXcQ" && YouTube.find("https://example.com/a") == null)

    // progress parsing
    val tv = MediaProgressTracker("youtube_video"); val seen = ArrayList<MediaProgress>()
    for (l in listOf("[youtube] x: Downloading webpage", "[info] Writing video subtitles to: a.en.vtt", "[download] Destination: a.en.vtt", "[download] 100% of 5.00KiB in 00:00:00 at 50.00KiB/s",
            "[download] Destination: a.f137.mp4", "[download]   0.0% of ~  85.50MiB at  Unknown B/s ETA Unknown", "[download]  40.0% of ~  85.50MiB at    2.50MiB/s ETA 00:20 (frag 4/10)", "[download] 100% of   85.50MiB in 00:00:34 at 2.51MiB/s",
            "[download] Destination: a.f140.m4a", "[download]  10.0% of    5.00MiB at  512.00KiB/s ETA 00:09", "[download] 100% of    5.00MiB in 00:00:02 at 2.00MiB/s",
            "[Merger] Merging formats into \"a.mp4\"", "[EmbedSubtitle] Embedding subtitles in \"a.mp4\"", "[Metadata] Adding metadata to \"a.mp4\"")) tv.feed(l)?.let { seen.add(it) }
    val pcts = seen.map { it.percent }
    check("progress never goes backwards", pcts.zipWithNext().all { (a, b) -> b >= a }, pcts.toString())
    check("subtitles don't count as a stream", seen.first { it.stage == "Downloading video" }.percent == 0.0)
    val mid = seen.first { it.stage == "Downloading video" && it.speed > 0 }
    check("speed and eta parsed", Math.abs(mid.speed - 2.5 * 1024 * 1024) < 1 && mid.eta == 20.0 && Math.abs(mid.percent - 34.0) < 0.01, mid.toString())
    check("audio track continues after video", seen.any { it.stage == "Downloading audio track" && it.percent > 85.0 && it.percent < 94.0 })
    check("post-processing stages reported", seen.map { it.stage }.containsAll(listOf("Merging video and audio", "Embedding subtitles", "Writing tags")) && pcts.last() < 100.0)
    val ta = MediaProgressTracker("youtube_audio"); val sa = ArrayList<MediaProgress>()
    for (l in listOf("[download] Destination: a.webm", "[download]  50.0% of 4.00MiB at 1.00MiB/s ETA 00:02", "[download] 100% of 4.00MiB in 00:00:04 at 1.00MiB/s", "[ExtractAudio] Destination: a.mp3", "[ThumbnailsConvertor] Converting thumbnail", "[EmbedThumbnail] ffmpeg: Adding thumbnail")) ta.feed(l)?.let { sa.add(it) }
    check("audio: download then convert", sa.map { it.percent }.zipWithNext().all { (a, b) -> b >= a } && sa.any { it.stage == "Converting to MP3" } && sa.last().stage == "Adding cover art", sa.toString())
    check("resumed stream counts as done", MediaProgressTracker("youtube_video").let { t -> t.feed("[download] /w/a.f137.mp4 has already been downloaded"); t.feed("[download] Destination: /w/a.f140.m4a")!!.percent == 85.0 })
    check("eta formats", MediaProgressTracker.seconds("01:02:03") == 3723.0 && MediaProgressTracker.seconds("Unknown") == -1.0)
    check("title read from the engine's file name", MediaProgressTracker("youtube_audio").feed("[download] Destination: /w/My Song [dQw4w9WgXcQ].f251.webm")!!.title == "My Song")

    // queue integration
    var bad = runCatching { e.addMediaTask("ftp://example.com/v", "x", "youtube_video", 720) }.isFailure && runCatching { e.addMediaTask("not a link", "x", "youtube_video", 720) }.isFailure
    check("media task needs a web address", bad)
    check("original audio must not carry a bitrate", runCatching { e.addMediaTask("https://youtu.be/dQw4w9WgXcQ", "x", MediaKinds.AUDIO, 192, start = false) }.isFailure)
    var d = e.addMediaTask("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "My: video?", "youtube_video", 1080, subs = "en", playlist = "Mix")
    var v = waitFor(e, d.id, Status.COMPLETE)
    check("media task completes", v.status == Status.COMPLETE && v.percent == 100.0 && v.name == "Real Title [abc].mp4", "${v.status} ${v.error} ${v.name}")
    check("media task carries options", fake.lastTask?.subs == "en" && fake.lastTask?.option == 1080 && v.note.contains("subtitles") && v.note.contains("Mix"), v.note)
    check("media traffic goes through the local proxy", fake.lastProxy?.startsWith("http://127.0.0.1:") == true, "${fake.lastProxy}")
    check("media runtime stage cleared at the end", v.taskStage == "" && v.speed == 0.0)
    check("media address change only takes web pages", !e.updateUrl(d.id, "ftp://example.com/x") && e.updateUrl(d.id, "https://vimeo.com/76979871"))

    // other sites, original audio, SponsorBlock, clips, browser login
    check("sites: known ones named", MediaSites.site("https://www.instagram.com/reel/abc/") == "Instagram" && MediaSites.site("https://x.com/a/status/1") == "X" && MediaSites.site("https://m.facebook.com/watch?v=1") == "Facebook" && MediaSites.site("https://vimeo.com/1") == "Vimeo" && MediaSites.site("https://example.com/v") == null)
    check("sites: any web page can be tried", MediaSites.isWeb("https://example.com/v") && !MediaSites.isWeb("ftp://x.com/a") && MediaSites.label("https://www.example.com/v") == "example.com")
    check("sites: known link preferred in shared text", MediaSites.find("see https://example.com/a and https://www.instagram.com/reel/xyz/") == "https://www.instagram.com/reel/xyz/" && MediaSites.find("vimeo.com/76979871") == "https://vimeo.com/76979871")
    check("clock parsing", MediaOpts.parseClock("1:20") == 80 && MediaOpts.parseClock("01:02:03") == 3723 && MediaOpts.parseClock("95") == 95 && MediaOpts.parseClock("1:75") == null && MediaOpts.parseClock("abc") == null && MediaOpts.clock(3723) == "1:02:03")
    val ig = e.addMediaTask("https://www.instagram.com/reel/abc/", "Reel", MediaKinds.AUDIO, 0, start = false, opts = mapOf("sb" to "1", "ck" to "1", "junk" to "x"), thumb = "https://img.example/t.jpg")
    check("non-YouTube task drops SponsorBlock, keeps login", ig.taskOpts == "ck=1" && ig.name == "Reel.m4a" && ig.category == "Music" && ig.note.startsWith("Instagram · original audio"), "${ig.taskOpts} ${ig.name} ${ig.note}")
    val cl = e.addMediaTask("https://youtu.be/dQw4w9WgXcQ", "Song", MediaKinds.MP3, 192, start = false, opts = mapOf("sb" to "1", "from" to "80", "to" to "165"))
    check("clip and SponsorBlock stored", MediaOpts.clip(MediaOpts.parse(cl.taskOpts)) == (80 to 165) && cl.taskOpts.contains("sb=1") && cl.note.contains("clip 1:20–2:45") && cl.name == "Song (1.20-2.45).mp3", "${cl.taskOpts} ${cl.note} ${cl.name}")
    val inv = e.addMediaTask("https://youtu.be/dQw4w9WgXcQ", "S", MediaKinds.MP3, 192, start = false, opts = mapOf("from" to "90", "to" to "30"))
    check("invalid clip ignored", inv.taskOpts == "")
    e.save()
    val again = Engine(FileStorage(dir), store).view(cl.id)!!
    check("media options and thumbnail survive restart", again.taskOpts == cl.taskOpts && e.view(ig.id)!!.thumb == "https://img.example/t.jpg")
    e.remove(listOf(ig.id, cl.id, inv.id), false)
    val tc = MediaProgressTracker(MediaKinds.VIDEO, clipSeconds = 100.0); val sc = ArrayList<MediaProgress>()
    for (l in listOf("[SponsorBlock] Fetching SponsorBlock segments", "[download] Destination: a.f137.mp4", "frame=  10 fps=0.0 q=-1.0 size=    1024KiB time=00:00:50.00 bitrate=N/A speed=2x",
            "[download] Destination: a.f140.m4a", "size=     256KiB time=00:01:40.00 bitrate=N/A", "[Merger] Merging formats into \"a.mp4\"", "[ModifyChapters] Removing chapters")) tc.feed(l)?.let { sc.add(it) }
    check("clip progress from FFmpeg time", sc.any { it.stage == "Downloading video (clip)" && Math.abs(it.percent - 42.5) < 0.01 }, sc.toString())
    check("SponsorBlock stages reported", sc.first().stage == "Checking SponsorBlock" && sc.last().stage == "Cutting out sponsor parts" && sc.map { it.percent }.zipWithNext().all { (a, b) -> b >= a })

    fake.mode = "block"
    d = e.addMediaTask("https://youtu.be/dQw4w9WgXcQ", "Long one", "youtube_audio", 320)
    var until = System.currentTimeMillis() + 5000
    while (System.currentTimeMillis() < until && e.view(d.id)!!.taskStage != "Merging video and audio") Thread.sleep(20)
    v = e.view(d.id)!!
    check("media stage and progress shown while running", v.active && v.taskStage == "Merging video and audio" && v.percent > 0, "${v.status} ${v.taskStage} ${v.percent}")
    val proxyUrl = fake.lastProxy!!
    val qs = e.addMediaTask("https://youtu.be/dQw4w9WgXcQ", "YouTube media", "youtube_video", 720, autoTitle = true)
    until = System.currentTimeMillis() + 5000
    while (System.currentTimeMillis() < until && e.view(qs.id)!!.taskStage != "Merging video and audio") Thread.sleep(20)
    check("quick save takes the real title", e.view(qs.id)!!.name == "a.mp4", e.view(qs.id)!!.name)
    e.remove(listOf(qs.id), false)
    val hooksBefore = fake.cancelHooks.get()
    e.pause(listOf(d.id))
    v = waitFor(e, d.id, Status.PAUSED, 5000)
    check("pausing a media task cancels the engine process", v.status == Status.PAUSED && fake.cancelHooks.get() == hooksBefore + 1, "${v.status} hooks=${fake.cancelHooks.get()}")
    Thread.sleep(200)
    val proxyClosed = runCatching { java.net.Socket("127.0.0.1", java.net.URI(proxyUrl).port).use { } }.isFailure
    check("local proxy closed with the task", proxyClosed)
    e.resume(listOf(d.id)); Thread.sleep(300)
    e.remove(listOf(d.id), false)
    check("removing a media task deletes its working files", d.id in fake.discarded && e.view(d.id) == null)

    fake.mode = "fail"
    d = e.addMediaTask("https://youtu.be/dQw4w9WgXcQ", "Broken", "youtube_video", 720)
    v = waitFor(e, d.id, Status.ERROR)
    check("media failure reported", v.status == Status.ERROR && v.error.contains("Video unavailable"), v.error)
    fake.mode = "ok"
    e.redownload(d.id)
    check("download again discards old working files", d.id in fake.discarded)
    waitFor(e, d.id, Status.COMPLETE)
    fake.mode = "fail"
    val keepId = e.addMediaTask("https://youtu.be/dQw4w9WgXcQ", "Unfinished", "youtube_video", 480, start = false).id
    e.shutdown()
    val fake2 = FakeMedia(dir)
    e = Engine(FileStorage(dir), store, fake2)
    Thread.sleep(300)
    val restored = e.view(keepId)!!
    check("media task survives restart", restored.isMedia && restored.taskOption == 480 && restored.status == Status.PAUSED)
    check("start-up cleanup keeps only unfinished media", fake2.cleanedKeep == setOf(keepId), "${fake2.cleanedKeep}")
    e.shutdown()

    // secrets are encrypted at rest
    val sealed = SealedStore(); val e2 = Engine(FileStorage(dir), sealed)
    val sd = e2.add(Engine.AddRequest("$base/cookie/z.zip", cookies = "sid=42", auth = "amit:pw", start = false))
    e2.save()
    check("cookies and passwords not stored in the clear", !sealed.dl!!.contains("sid=42") && !sealed.dl!!.contains("amit:pw") && sealed.dl!!.contains("enc1:"))
    e2.shutdown()
    val e3 = Engine(FileStorage(dir), sealed)
    e3.resume(listOf(sd.id))
    check("encrypted cookies work after restart", waitFor(e3, sd.id, Status.COMPLETE).status == Status.COMPLETE, e3.view(sd.id)!!.error)
    e3.shutdown()

    // local proxy: plain HTTP, HTTPS-style CONNECT tunnel, chaining, speed limit
    val lim = Limiter()
    val px = ThrottleProxy(lim, { null }, 10000)
    val pport = java.net.URI(px.url).port
    val viaProxy = { url: String, p: Int -> (java.net.URL(url).openConnection(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", p))) as java.net.HttpURLConnection).inputStream.use { it.readBytes() } }
    check("proxy: plain http", sha(viaProxy("$base/file.zip", pport)) == sha(data))
    fun tunnel(p: Int): String = java.net.Socket("127.0.0.1", p).use { s ->
        s.soTimeout = 10000
        val o = s.getOutputStream(); val i = s.getInputStream()
        o.write("CONNECT 127.0.0.1:$port HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n".toByteArray()); o.flush()
        val head = StringBuilder(); while (!head.endsWith("\r\n\r\n")) { val b = i.read(); if (b < 0) break; head.append(b.toChar()) }
        if (!head.startsWith("HTTP/1.1 200")) return head.toString()
        o.write("GET /named HTTP/1.1\r\nHost: 127.0.0.1\r\nRange: bytes=0-9\r\nConnection: close\r\n\r\n".toByteArray()); o.flush()
        String(i.readBytes(), Charsets.ISO_8859_1)
    }
    val t1 = tunnel(pport)
    check("proxy: CONNECT tunnel", t1.startsWith("HTTP/1.1 206") && t1.contains("Report%202026.pdf"), t1.take(80))
    val chained = ThrottleProxy(Limiter(), { "127.0.0.1" to pport }, 10000)
    val cport = java.net.URI(chained.url).port
    check("proxy: chained through user's proxy (http)", sha(viaProxy("$base/file.zip", cport)) == sha(data))
    check("proxy: chained through user's proxy (CONNECT)", tunnel(cport).startsWith("HTTP/1.1 206"))
    lim.setRate(1024.0 * 1024)
    val t0 = System.nanoTime(); val got = viaProxy("$base/file.zip", pport); val secs = (System.nanoTime() - t0) / 1e9
    check("proxy: shares the speed limit", got.size == data.size && secs >= 4.5, "%.1fs for 6 MB at 1 MB/s".format(secs))
    lim.setRate(0.0)
    px.close(); chained.close()
    check("proxy: refuses after close", runCatching { viaProxy("$base/file.zip", pport) }.isFailure)
}

fun main(args: Array<String>) {
    if (args.isNotEmpty() && args[0] == "flaky") flakyLoop()
    val srv = server(); val base = "http://127.0.0.1:${srv.address.port}"
    val dir = createTempDir("vftest"); val store = MemStore(); val storage = FileStorage(dir)
    var e = Engine(storage, store)
    e.updateSettings(e.settings.copy(retries = 5))

    // 1. segmented download, integrity, category
    hits.set(0)
    var d = e.add(Engine.AddRequest("$base/file.zip"))
    var v = waitFor(e, d.id, Status.COMPLETE)
    check("segmented download completes", v.status == Status.COMPLETE, v.error)
    check("content identical", sha(fileOf(v).readBytes()) == sha(data))
    check("used several connections", hits.get() >= 5, "${hits.get()} requests")
    check("category from extension", v.category == "Compressed")

    // 2. probe + Content-Disposition (RFC 5987) + redirect
    val p = e.probe("$base/named")
    check("probe name from filename*", p.name == "Report 2026.pdf", p.name)
    check("probe size + resumable", p.size == data.size.toLong() && p.resumable)
    check("redirect followed", e.probe("$base/redirect").finalUrl.endsWith("/file.zip"))

    // 3. no range support, unknown size
    d = e.add(Engine.AddRequest("$base/norange.bin"))
    v = waitFor(e, d.id, Status.COMPLETE)
    check("non-resumable download", v.status == Status.COMPLETE && !v.resumable, v.error)
    check("non-resumable content", fileOf(v).readBytes().contentEquals(data))

    // 4. flaky server, retries
    d = e.add(Engine.AddRequest("$base/flaky.iso"))
    v = waitFor(e, d.id, Status.COMPLETE, 60000)
    check("survives dropped connections", v.status == Status.COMPLETE && sha(fileOf(v).readBytes()) == sha(data), "${v.status} ${v.error} same=${v.status == Status.COMPLETE && sha(fileOf(v).readBytes()) == sha(data)} got=${v.got}")

    // 5. pause, persist, restart engine, resume
    throttleMs = 40
    d = e.add(Engine.AddRequest("$base/slow.zip"))
    Thread.sleep(1500)
    e.pause(listOf(d.id))
    Thread.sleep(300)
    v = e.view(d.id)!!
    check("paused mid-way", v.status == Status.PAUSED && v.got > 0 && v.got < data.size, "${v.got}")
    e.shutdown()
    throttleMs = 0
    e = Engine(storage, store)
    val restored = e.view(d.id)!!
    check("progress restored after restart", restored.got == v.got && restored.status == Status.PAUSED, "${restored.got}")
    e.resume(listOf(d.id))
    v = waitFor(e, d.id, Status.COMPLETE)
    check("resumed download is intact", v.status == Status.COMPLETE && sha(fileOf(v).readBytes()) == sha(data), v.error)

    // 6. speed limit
    e.updateSettings(e.settings.copy(limitOn = true, limitKBs = 1024))
    val t0 = System.currentTimeMillis()
    d = e.add(Engine.AddRequest("$base/limited.zip"))
    v = waitFor(e, d.id, Status.COMPLETE, 60000)
    val secs = (System.currentTimeMillis() - t0) / 1000.0
    check("speed limit respected", secs >= 4.5, "%.1fs for 6 MB at 1 MB/s".format(secs))
    e.updateSettings(e.settings.copy(limitOn = false))

    // 7. queue: at most N at once
    throttleMs = 20
    e.updateSettings(e.settings.copy(simultaneous = 2))
    val ids = (1..4).map { e.add(Engine.AddRequest("$base/q$it.zip")).id }
    Thread.sleep(700)
    val running = e.views().count { it.id in ids && it.active }
    check("simultaneous limit", running == 2, "$running running")
    ids.forEach { waitFor(e, it, Status.COMPLETE, 60000) }
    check("queue drains", ids.all { e.view(it)!!.status == Status.COMPLETE })
    throttleMs = 0

    // 8. checksum
    d = e.add(Engine.AddRequest("$base/sum.zip", checksum = "SHA256: " + sha(data).uppercase()))
    waitFor(e, d.id, Status.COMPLETE)
    var until = System.currentTimeMillis() + 5000
    while (System.currentTimeMillis() < until && e.view(d.id)!!.verify != "ok") Thread.sleep(20)
    check("checksum verified automatically", e.view(d.id)!!.verify == "ok", e.view(d.id)!!.verify)
    e.verify(d.id, "0123456789abcdef0123456789abcdef")
    check("checksum mismatch detected (MD5)", e.view(d.id)!!.verify == "mismatch" && e.view(d.id)!!.hashAlgo == "MD5")

    // 9. Wi-Fi only gate
    e.setNetworkAllowed(false)
    d = e.add(Engine.AddRequest("$base/wifi.zip"))
    Thread.sleep(400)
    check("waits while network not allowed", e.view(d.id)!!.status == Status.QUEUED)
    e.setNetworkAllowed(true)
    check("starts when network allowed", waitFor(e, d.id, Status.COMPLETE).status == Status.COMPLETE)

    // 10. queues
    e.updateSettings(e.settings.copy(queues = listOf(Queue("Night", 1)), simultaneous = 3))
    throttleMs = 15
    val n1 = e.add(Engine.AddRequest("$base/n1.zip", queue = "Night")).id
    val n2 = e.add(Engine.AddRequest("$base/n2.zip", queue = "Night")).id
    Thread.sleep(400)
    check("queued files wait until their queue starts", e.view(n1)!!.status == Status.QUEUED && e.view(n2)!!.status == Status.QUEUED)
    check("place in line", e.placeInLine(n1) == 1 && e.placeInLine(n2) == 2)
    e.move(n2, "top")
    check("move to top", e.placeInLine(n2) == 1)
    e.startQueue("Night")
    Thread.sleep(500)
    check("queue limit 1", e.view(n2)!!.active && !e.view(n1)!!.active, "${e.view(n1)!!.status} ${e.view(n2)!!.status}")
    waitFor(e, n1, Status.COMPLETE, 60000)
    check("queue runs to the end", e.view(n1)!!.status == Status.COMPLETE && e.view(n2)!!.status == Status.COMPLETE)
    run { val t = System.currentTimeMillis() + 3000; while (e.queueRunning("Night") && System.currentTimeMillis() < t) Thread.sleep(20) }
    check("queue stops when empty", !e.queueRunning("Night"))
    val n3 = e.add(Engine.AddRequest("$base/n3.zip", queue = "Night", start = false)).id
    e.resume(listOf(n3))
    check("resume by hand starts despite stopped queue", waitFor(e, n3, Status.COMPLETE, 60000).status == Status.COMPLETE)
    e.moveToQueue(listOf(n3), MAIN_QUEUE)
    check("move to queue", e.view(n3)!!.queue == MAIN_QUEUE)
    e.saveQueue("Night", Queue("Late", 2))
    check("rename queue moves its files", e.view(n1)!!.queue == "Late" && e.settings.queueNames() == listOf(MAIN_QUEUE, "Late"))
    e.deleteQueue("Late")
    check("delete queue moves files to Main", e.view(n1)!!.queue == MAIN_QUEUE)
    throttleMs = 0

    // 11. schedule + time-based speed limits
    val cal = java.util.Calendar.getInstance().apply { set(2026, 9, 21, 23, 30, 0) } // Wed 23:30
    val wk = listOf(false, true, true, true, true, true, false)
    val night = SpeedRule(true, "22:00", "06:00", wk, 300)
    check("overnight rule active late", night.active(cal))
    val early = (cal.clone() as java.util.Calendar).apply { set(2026, 9, 22, 5, 0, 0) } // Thu 05:00
    check("overnight rule active early next day", night.active(early))
    val sat = (cal.clone() as java.util.Calendar).apply { set(2026, 9, 25, 5, 0, 0) } // Sun 05:00, window began Sat
    check("overnight rule respects start day", !night.active(sat))
    check("strictest rule wins", Planner.activeRule(listOf(night, SpeedRule(true, "23:00", "23:59", List(7) { true }, 100)), cal)?.kbs == 100)
    e.clock = { cal.timeInMillis }
    e.updateSettings(e.settings.copy(speedRules = listOf(night)))
    check("engine applies rule", e.limitNow == (300 to "rule"), "${e.limitNow}")
    e.updateSettings(e.settings.copy(limitOn = true, limitKBs = 2048))
    check("manual limit overrides rule", e.limitNow == (2048 to "manual"))
    e.updateSettings(e.settings.copy(limitOn = false, speedRules = emptyList()))
    check("no limit", e.limitNow.first == 0)
    e.clock = { System.currentTimeMillis() }
    val q = Queue("Q", 1, Schedule(true, "02:00", List(7) { true }, true, "07:00"))
    val next = Planner.nextEvent(listOf(q), cal.timeInMillis)!!
    val nc = java.util.Calendar.getInstance().apply { timeInMillis = next }
    check("next schedule event", nc.get(java.util.Calendar.HOUR_OF_DAY) == 2 && nc.get(java.util.Calendar.DAY_OF_MONTH) == 22, nc.time.toString())
    check("settings json round trip", Settings.fromMap(Json.parse(Json.encode(e.settings.copy(queues = listOf(q), speedRules = listOf(night)).toMap())) as Map<String, Any?>).let { it.queues == listOf(q) && it.speedRules == listOf(night) })

    // 12. batch, grabber, login, cookies, rename
    check("batch numbers", Batch.expand("http://h/img*.jpg", 1, 3, 3, false) == listOf("http://h/img001.jpg", "http://h/img002.jpg", "http://h/img003.jpg"))
    check("batch letters", Batch.expand("http://h/*.jpg", 0, 2, 0, true) == listOf("http://h/a.jpg", "http://h/b.jpg", "http://h/c.jpg"))
    val grabbed = e.grab("$base/page.html", setOf("zip", "pdf"))
    check("grabber", grabbed == listOf("$base/b.PDF", "$base/files/a.zip"), grabbed.toString())
    check("grabber all links", e.grab("$base/page.html", emptySet()).size == 3)
    d = e.add(Engine.AddRequest("$base/auth/x.zip"))
    check("login needed is explained", waitFor(e, d.id, Status.ERROR).error.contains("login"), e.view(d.id)!!.error)
    check("a login error is not retried automatically", e.view(d.id)!!.retryAt == 0L)
    e.setLogin(d.id, "amit:pw"); e.resume(listOf(d.id))
    check("download with login", waitFor(e, d.id, Status.COMPLETE).status == Status.COMPLETE, e.view(d.id)!!.error)
    d = e.add(Engine.AddRequest("$base/cookie/c.zip", cookies = "sid=42"))
    check("download with browser cookies", waitFor(e, d.id, Status.COMPLETE).status == Status.COMPLETE, e.view(d.id)!!.error)
    check("rename finished file", e.rename(d.id, "renamed.zip") && e.view(d.id)!!.name == "renamed.zip" && File(dir, "renamed.zip.part").exists())
    check("youtube capture blocked", NoCapture.blocked("https://m.youtube.com/watch?v=1") && NoCapture.blocked("https://rr1.googlevideo.com/x") && !NoCapture.blocked("https://example.com/v.mp4"))

    // 12b. automatic retry of network failures
    e.retryDelays = listOf(300L, 300L, 300L)
    val events = java.util.concurrent.CopyOnWriteArrayList<String>()
    e.onEvent { ev -> events.add(ev.kind) }
    d = e.add(Engine.AddRequest("$base/busy/a.zip"))
    run { val t = System.currentTimeMillis() + 20000; while (e.view(d.id)!!.status != Status.COMPLETE && System.currentTimeMillis() < t) Thread.sleep(50) }
    v = e.view(d.id)!!
    check("busy server: retried automatically until it worked", v.status == Status.COMPLETE && sha(fileOf(v).readBytes()) == sha(data), "${v.status} ${v.error}")
    check("no failure alert while a retry is pending", events.contains("retrying") && !events.contains("error"), events.toString())
    e.retryDelays = listOf(60_000L)
    d = e.add(Engine.AddRequest("http://127.0.0.1:1/x.zip"))
    v = waitFor(e, d.id, Status.ERROR)
    check("connection failure schedules a retry", v.status == Status.ERROR && v.retryAt > System.currentTimeMillis(), "${v.status} ${v.retryAt}")
    e.pause(listOf(d.id))
    check("pausing cancels the automatic retry", e.view(d.id)!!.retryAt == 0L)
    e.remove(listOf(d.id), false)
    e.retryDelays = listOf(30_000L, 120_000L, 300_000L)

    // 13. file names
    check("generic: id", Names.isGeneric("8f3a9c2e7b1d4f6a.bin") && Names.isGeneric("aZ3kP9qW2xL7mN4v") && Names.isGeneric("downloadfile.bin") && Names.isGeneric("1698765432"))
    check("not generic: real names", !Names.isGeneric("Annual Report.pdf") && !Names.isGeneric("ubuntu-24.04-desktop-amd64.iso") && !Names.isGeneric("IMG_20240501.jpg") && !Names.isGeneric("song.mp3"))
    check("extension from type", Names.withExtension("7Hq2xK9m.bin", "video/mp4") == "7Hq2xK9m.mp4" && Names.withExtension("movie", "video/webm") == "movie.webm" && Names.withExtension("a.zip", "text/plain") == "a.zip")
    check("sniff", Names.sniff("%PDF-1.4".toByteArray()) == "pdf" && Names.sniff(byteArrayOf(0x50, 0x4B, 3, 4)) == "zip" && Names.sniff(byteArrayOf(0, 0, 0, 0x20) + "ftypisom".toByteArray()) == "mp4" && Names.sniff("ID3xx".toByteArray()) == "mp3")
    d = e.add(Engine.AddRequest("$base/dl/8f3a9c2e7b1d4f6a9e0c"))
    v = waitFor(e, d.id, Status.COMPLETE)
    check("server's real name replaces a hidden one", v.name == "Annual Report.pdf" && v.category == "Documents", "${v.name} ${v.category}")
    d = e.add(Engine.AddRequest("$base/pdfblob/7Hq2xK9mPz4wLr8v"))
    v = waitFor(e, d.id, Status.COMPLETE)
    check("unnamed file recognised from its contents", v.name.endsWith(".pdf") && v.category == "Documents" && File(dir, v.name + ".part").exists(), v.name)
    d = e.add(Engine.AddRequest("$base/typed/a8F3kZ9q", name = "Holiday clip", userNamed = true))
    v = waitFor(e, d.id, Status.COMPLETE)
    check("typed name kept, extension added", v.name == "Holiday clip.mp4", v.name)
    d = e.add(Engine.AddRequest("$base/dl/x9Y8z7W6v5U4", name = "My copy.pdf", userNamed = true))
    v = waitFor(e, d.id, Status.COMPLETE)
    check("typed name not replaced by server's", v.name == "My copy.pdf", v.name)
    check("probe picks server name", e.probe("$base/dl/8f3a9c2e7b1d4f6a").name == "Annual Report.pdf")

    // 14. helpers
    check("json round trip", (Json.parse(Json.encode(mapOf("a" to "x\"y\n", "b" to listOf(1L, 2L), "c" to true))) as Map<*, *>)["a"] == "x\"y\n")
    check("links from shared text", Links.find("Look: https://ex.com/a.zip, and http://b.org/x.pdf.") == listOf("https://ex.com/a.zip", "http://b.org/x.pdf"))
    check("sanitize", Names.sanitize("a<b>:c?.zip") == "a_b__c_.zip")
    check("fmt", Fmt.size(1536) == "1.50 KB" && Fmt.eta(65.0) == "1m 05s", Fmt.size(1536))

    // 15. embedded media tasks, local proxy, encrypted secrets
    mediaTests(base, srv.address.port)

    e.shutdown(); srv.stop(0)
    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
