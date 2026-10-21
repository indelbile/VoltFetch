package com.voltfetch.app

import android.content.Context
import android.net.Uri
import com.voltfetch.engine.Job
import com.voltfetch.engine.MediaEnv
import com.voltfetch.engine.MediaKinds
import com.voltfetch.engine.MediaOpts
import com.voltfetch.engine.MediaSites
import com.voltfetch.engine.MediaProgress
import com.voltfetch.engine.MediaProgressTracker
import com.voltfetch.engine.MediaTask
import com.voltfetch.engine.Names
import com.voltfetch.engine.SpecialTransferHandler
import com.voltfetch.engine.SpecialTransferResult
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs persisted YouTube jobs inside the engine's normal queue with the embedded yt-dlp.
 * Working files live in filesDir/media-work/<id> until the finished file is copied to Downloads.
 */
class AndroidMediaTransferHandler(private val ctx: Context) : SpecialTransferHandler {
    private val root by lazy { File(ctx.filesDir, "media-work").apply { mkdirs() } }
    private val storage by lazy { MediaStoreStorage(ctx) }

    private val mediaExt = setOf("mp4", "m4a", "mp3", "webm", "mkv", "mov", "opus", "aac", "ogg", "m4v")

    override fun execute(task: MediaTask, job: Job, env: MediaEnv, progress: (MediaProgress) -> Unit): SpecialTransferResult {
        if (!MediaSites.isWeb(task.url)) throw IllegalArgumentException("Media downloads need a web page address")
        if (job.cancelled.get()) throw CancellationException("Cancelled before start")
        val dir = File(root, task.id.toString()).apply { mkdirs() }
        var extras = true
        while (true) {
            try {
                return MediaEngine.use(ctx) { runOnce(task, job, env, dir, extras, progress) }
            } catch (e: Throwable) {
                if (job.cancelled.get() || e is CancellationException || e is YoutubeDL.CanceledException) throw CancellationException("Media task cancelled")
                val msg = e.message ?: ""
                // Subtitles, cover art and tags are nice to have; never lose the download over them.
                if (extras && Regex("subtitle|thumbnail|metadata|postprocessing|Embed|SponsorBlock|ModifyChapters|mutagen|AtomicParsley", RegexOption.IGNORE_CASE).containsMatchIn(msg)) { extras = false; continue }
                throw IllegalStateException(readable(msg.ifBlank { e.javaClass.simpleName }))
            }
        }
    }

    private fun runOnce(task: MediaTask, job: Job, env: MediaEnv, dir: File, extras: Boolean, progress: (MediaProgress) -> Unit): SpecialTransferResult {
        val processId = "voltfetch-media-${task.id}-${System.nanoTime()}"
        val before = dir.listFiles()?.associate { it.name to it.lastModified() }.orEmpty()
        val clip = MediaOpts.clip(task.opts)
        val cookieFile = File(dir, "cookies.txt")
        val useCookies = task.opts["ck"] == "1" && BrowserCookies.writeNetscape(task.url, cookieFile)
        val request = YoutubeDLRequest(task.url).apply {
            addOption("-o", File(dir, "%(title).150B [%(id)s].%(ext)s").absolutePath)
            addOption("--no-playlist"); addOption("--newline"); addOption("--no-mtime"); addOption("--continue")
            addOption("--retries", "10"); addOption("--fragment-retries", "10")
            env.proxyUrl?.let { addOption("--proxy", it) }
            // the login from VoltFetch's own browser, for sites that hide videos behind one (Instagram, Facebook, X…)
            if (useCookies) addOption("--cookies", cookieFile.absolutePath)
            if (clip != null) {
                addOption("--download-sections", "*${clip.first}-${clip.second}")
                // cut exactly at the chosen times rather than the nearest keyframe (re-encodes just the clip)
                if (task.kind == MediaKinds.VIDEO) addOption("--force-keyframes-at-cuts")
            }
            // SponsorBlock: community-marked sponsor, self-promo, "subscribe" and non-music parts are removed
            if (extras && task.opts["sb"] == "1") addOption("--sponsorblock-remove", "sponsor,selfpromo,interaction,music_offtopic")
        }
        when (task.kind) {
            MediaKinds.VIDEO -> request.apply {
                // Best quality up to the chosen height; prefer H.264 + AAC (plays everywhere), fall back to VP9/AV1 at 1440p+.
                addOption("-f", "bv*+ba/b")
                addOption("-S", "res:${task.option},vcodec:h264,acodec:aac")
                addOption("--merge-output-format", "mp4")
                if (extras) {
                    addOption("--embed-metadata")
                    if (task.subs.isNotBlank()) {
                        val langs = task.subs.split(',').map { it.trim() }.filter { it.matches(Regex("[A-Za-z]{2,3}")) }
                        if (langs.isNotEmpty()) {
                            addOption("--write-subs"); addOption("--write-auto-subs")
                            addOption("--sub-langs", langs.joinToString(",") { "$it(-[A-Za-z]+)?" } + ",-live_chat")
                            addOption("--convert-subs", "srt"); addOption("--embed-subs")
                        }
                    }
                }
            }
            MediaKinds.MP3 -> request.apply {
                addOption("-f", "ba/b")
                addOption("-x"); addOption("--audio-format", "mp3"); addOption("--audio-quality", "${task.option}K")
                if (extras) { addOption("--embed-metadata"); addOption("--embed-thumbnail"); addOption("--convert-thumbnails", "jpg") }
            }
            MediaKinds.AUDIO -> request.apply {
                // the site's own audio, copied as it is (no re-encoding): AAC in .m4a when offered, else e.g. Opus
                addOption("-f", "ba[acodec^=mp4a]/ba[ext=m4a]/ba/b")
                addOption("-x"); addOption("--audio-format", "best")
                if (extras) { addOption("--embed-metadata"); addOption("--embed-thumbnail"); addOption("--convert-thumbnails", "jpg") }
            }
            else -> throw IllegalArgumentException("Unknown media task type: ${task.kind}")
        }

        val tracker = MediaProgressTracker(task.kind, clip?.let { (it.second - it.first).toDouble() } ?: 0.0)
        val running = AtomicBoolean(true)
        val ytdl = YoutubeDL.getInstance()
        job.onCancel { runCatching { ytdl.destroyProcessById(processId) } }
        // The process may not be registered yet when a cancel arrives; keep trying until it is gone.
        Thread({
            while (running.get()) {
                if (job.cancelled.get()) runCatching { ytdl.destroyProcessById(processId) }
                try { Thread.sleep(300) } catch (e: InterruptedException) { break }
            }
        }, "voltfetch-media-watch").apply { isDaemon = true }.start()
        try {
            ytdl.execute(request, processId) { _, _, line -> tracker.feed(line)?.let(progress) }
        } finally {
            running.set(false)
            runCatching { cookieFile.delete() } // never leave a copy of the login behind
        }
        if (job.cancelled.get()) throw CancellationException("Media task cancelled")

        val wanted = when (task.kind) { MediaKinds.MP3 -> "mp3"; MediaKinds.AUDIO -> "m4a"; else -> "mp4" }
        // skip the per-stream parts yt-dlp leaves before merging ("Title [id].f137.mp4")
        val outputs = dir.listFiles()?.filter { f ->
            val ext = f.extension.lowercase(Locale.ROOT)
            f.isFile && f.length() > 0L && ext in mediaExt && !f.name.matches(Regex(".*]\\.f.+\\.\\w+$"))
        }.orEmpty()
        val changed = outputs.filter { it.lastModified() > (before[it.name] ?: 0L) }
        val output = (changed.ifEmpty { outputs }).sortedWith(compareBy<File>({ it.extension.lowercase(Locale.ROOT) != wanted }, { -it.lastModified() })).firstOrNull()
            ?: throw IllegalStateException("The media engine finished without producing a file")
        progress(MediaProgress(99.0, "Saving to Downloads", 0.0, -1.0))
        val result = publish(output, task, job)
        // The public copy is authoritative now; free the private working space.
        discard(task.id)
        return result
    }

    /** Copies the finished file into Download/VoltFetch/<category> and returns the name Android actually gave it. */
    private fun publish(output: File, task: MediaTask, job: Job): SpecialTransferResult {
        val ext = output.extension.lowercase(Locale.ROOT)
        val title = output.nameWithoutExtension.replace(Regex("\\s*\\[[A-Za-z0-9_-]{6,}]$"), "").trim()
        val name = Names.sanitize("${title.ifBlank { task.name.substringBeforeLast('.') }.take(150)}.$ext")
        val mime = mimeFor(ext)
        val (locator, finalName) = storage.create(name, task.category, mime)
        try {
            ctx.contentResolver.openOutputStream(Uri.parse(locator), "w")?.use { out ->
                FileInputStream(output).use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        if (job.cancelled.get()) throw CancellationException("Media task cancelled")
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                }
            } ?: throw IllegalStateException("Could not open the destination file in Downloads")
            if (job.cancelled.get()) throw CancellationException("Media task cancelled")
            storage.publish(locator)
        } catch (e: Throwable) {
            storage.delete(locator)
            throw e
        }
        return SpecialTransferResult(locator, finalName, mime, output.length())
    }

    override fun discard(id: Long) {
        runCatching { File(root, id.toString()).deleteRecursively() }
    }

    override fun cleanup(keep: Set<Long>) {
        root.listFiles()?.forEach { f ->
            if (f.name.toLongOrNull() !in keep) runCatching { f.deleteRecursively() }
        }
    }

    /** yt-dlp's stderr → one line a person can act on. */
    private fun readable(raw: String): String {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val err = lines.lastOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim() ?: lines.lastOrNull() ?: raw
        val hint = when {
            Regex("Unsupported URL", RegexOption.IGNORE_CASE).containsMatchIn(err) ->
                " · The media engine doesn't support this site. Try the normal downloader (+) instead."
            Regex("login required|log in|logged-in|cookies|confirm your age|age-restricted|age restricted|inappropriate for some users|Private video|members-only|rate-limit", RegexOption.IGNORE_CASE).containsMatchIn(err) ->
                " · This needs a login. Sign in to the site in VoltFetch's Browser tab, then retry with \"Use my browser login\" on."
            Regex("Sign in to confirm|not a bot|HTTP Error 403|Unable to extract|nsig|Precondition", RegexOption.IGNORE_CASE).containsMatchIn(err) ->
                " · The site may have changed. Try Settings › Media engine › Update media engine."
            Regex("Errno|timed out|Connection (refused|reset|aborted)|Temporary failure|Network is unreachable|Unable to download", RegexOption.IGNORE_CASE).containsMatchIn(err) -> " · Check the connection and tap Retry."
            else -> ""
        }
        return (err.take(400) + hint)
    }

    private fun mimeFor(ext: String): String = when (ext) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "mp4", "m4v" -> "video/mp4"
        "aac" -> "audio/aac"
        "opus" -> "audio/opus"
        "ogg" -> "audio/ogg"
        else -> "application/octet-stream"
    }
}
