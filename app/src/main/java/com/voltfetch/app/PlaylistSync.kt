package com.voltfetch.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.voltfetch.engine.Json
import com.voltfetch.engine.MAIN_QUEUE
import com.voltfetch.engine.MediaKinds
import com.voltfetch.engine.MediaOpts
import com.voltfetch.engine.MediaSites
import com.voltfetch.engine.bool
import com.voltfetch.engine.int
import com.voltfetch.engine.list
import com.voltfetch.engine.long
import com.voltfetch.engine.str
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * Followed playlists (or channels): every night, and when the app opens after a missed night, each one is
 * checked and videos added since the last check are put in the queue with the quality chosen when following.
 * Stored in filesDir/follows.json.
 */
object PlaylistSync {
    private const val FILE = "follows.json"
    private const val ACTION = "com.voltfetch.SYNC"
    private const val HOUR = 3 // nightly check at 03:00

    data class Follow(
        val id: String, val url: String, val title: String, val kind: String, val option: Int,
        val subs: String, val opts: String, val queue: String,
        val seen: Set<String>, val added: Long, val lastSync: Long, val lastResult: String,
    ) {
        fun toMap() = mapOf(
            "id" to id, "url" to url, "title" to title, "kind" to kind, "option" to option, "subs" to subs, "opts" to opts,
            "queue" to queue, "seen" to seen.toList(), "added" to added, "lastSync" to lastSync, "lastResult" to lastResult,
        )
        val what: String get() = when (kind) { MediaKinds.MP3 -> "MP3 · $option kbps"; MediaKinds.AUDIO -> "original audio"; else -> "video up to ${option}p" }

        companion object {
            fun fromMap(m: Map<String, Any?>) = Follow(
                m.str("id"), m.str("url"), m.str("title"), m.str("kind", MediaKinds.VIDEO), m.int("option", 1080),
                m.str("subs"), m.str("opts"), m.str("queue", MAIN_QUEUE),
                m.list("seen").mapNotNull { it as? String }.toSet(), m.long("added"), m.long("lastSync"), m.str("lastResult"),
            )
        }
    }

    @Volatile var running = false
        private set

    private val lock = Any()

    fun list(ctx: Context): List<Follow> = synchronized(lock) {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return emptyList()
        runCatching {
            @Suppress("UNCHECKED_CAST")
            (Json.parse(f.readText()) as List<Map<String, Any?>>).map { Follow.fromMap(it) }
        }.getOrDefault(emptyList())
    }

    private fun save(ctx: Context, all: List<Follow>) = synchronized(lock) {
        val f = File(ctx.filesDir, FILE)
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.writeText(Json.encode(all.map { it.toMap() }))
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
    }

    fun isFollowed(ctx: Context, url: String) = list(ctx).any { it.url == url }

    /** Starts following [url]. [seen] are the entries present now (they're downloaded now or skipped on purpose). */
    fun follow(ctx: Context, url: String, title: String, kind: String, option: Int, subs: String, opts: Map<String, String>, queue: String, seen: Collection<String>) {
        synchronized(lock) {
            val all = list(ctx).filter { it.url != url }
            save(ctx, all + Follow(java.util.UUID.randomUUID().toString(), url, title, kind, option, subs, MediaOpts.encode(opts), queue,
                seen.toSet(), System.currentTimeMillis(), System.currentTimeMillis(), "Following"))
        }
        schedule(ctx)
    }

    /** Stops following; nothing that was already downloaded is touched. */
    fun unfollow(ctx: Context, id: String) {
        synchronized(lock) { save(ctx, list(ctx).filter { it.id != id }) }
        schedule(ctx)
    }

    private data class Entry(val key: String, val url: String, val title: String)

    /** The playlist's current entries (id + address + title), read quickly with --flat-playlist. */
    private fun entries(ctx: Context, f: Follow): List<Entry> = MediaEngine.use(ctx) {
        val s = VoltApp.engine(ctx).settings
        val dir = File(ctx.filesDir, "media-work").apply { mkdirs() }
        val cookies = File(dir, "sync-cookies-${System.nanoTime()}.txt")
        try {
            val req = YoutubeDLRequest(f.url).apply {
                addOption("--dump-single-json"); addOption("--flat-playlist"); addOption("--no-warnings")
                if (s.proxyOn && s.proxyHost.isNotBlank()) addOption("--proxy", "http://${s.proxyHost.trim()}:${s.proxyPort}")
                if (MediaOpts.parse(f.opts)["ck"] == "1" && BrowserCookies.writeNetscape(f.url, cookies)) addOption("--cookies", cookies.absolutePath)
            }
            val json = JSONObject(YoutubeDL.getInstance().execute(req, "voltfetch-sync-${System.nanoTime()}").out)
            val arr = json.optJSONArray("entries") ?: return@use emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                val title = e.optString("title", "")
                if (title == "[Private video]" || title == "[Deleted video]") return@mapNotNull null
                val id = e.optString("id", "")
                val url = e.optString("url", "").takeIf { MediaSites.isWeb(it) }
                    ?: e.optString("webpage_url", "").takeIf { MediaSites.isWeb(it) }
                    ?: if (id.isNotBlank() && com.voltfetch.engine.YouTube.isUrl(f.url)) com.voltfetch.engine.YouTube.watchUrl(id) else return@mapNotNull null
                Entry(id.ifBlank { url }, url, title.ifBlank { id })
            }
        } finally {
            runCatching { cookies.delete() }
        }
    }

    /** Checks every followed playlist and queues what's new. Returns how many videos were added. Call off the main thread. */
    fun syncAll(ctx: Context): Int {
        if (running) return 0
        running = true
        var total = 0
        try {
            val engine = VoltApp.engine(ctx)
            for (f in list(ctx)) {
                val result = runCatching {
                    val now = entries(ctx, f)
                    val fresh = now.filter { it.key !in f.seen }
                    var added = 0
                    for (e in fresh) {
                        if (engine.views().any { it.url == e.url && it.taskKind == f.kind }) continue
                        if (runCatching { engine.addMediaTask(e.url, e.title, f.kind, f.option, queue = f.queue, subs = f.subs, playlist = f.title, opts = MediaOpts.parse(f.opts)) }.isSuccess) added++
                    }
                    added to (f.seen + now.map { it.key })
                }
                synchronized(lock) {
                    val all = list(ctx).map { x ->
                        if (x.id != f.id) x
                        else result.fold(
                            { (added, seen) -> x.copy(seen = seen, lastSync = System.currentTimeMillis(), lastResult = if (added == 0) "No new videos" else "$added new added") },
                            { err -> x.copy(lastSync = System.currentTimeMillis(), lastResult = "Check failed: ${(err.message ?: "error").lines().lastOrNull { it.isNotBlank() }?.take(80)}") },
                        )
                    }
                    save(ctx, all)
                }
                val added = result.getOrNull()?.first ?: 0
                total += added
                if (added > 0) notifyAdded(ctx, f.title, added)
            }
        } finally {
            running = false
        }
        if (total > 0) DownloadService.ensureRunning(ctx, force = true)
        return total
    }

    private fun notifyAdded(ctx: Context, title: String, n: Int) = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return@runCatching
        val open = PendingIntent.getActivity(ctx, 41_000, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        ctx.getSystemService(NotificationManager::class.java).notify(42_000 + (title.hashCode() and 0xfff),
            Notification.Builder(ctx, VoltApp.CH_DONE).setSmallIcon(R.drawable.ic_stat).setColor(0xFF1A8CFF.toInt())
                .setContentTitle("$n new ${if (n == 1) "video" else "videos"} from \"$title\"")
                .setContentText("Added to your downloads by playlist sync").setAutoCancel(true).setContentIntent(open).build())
    }

    /** When the app opens: catch up if last night's check was missed. */
    fun catchUp(ctx: Context) {
        val follows = list(ctx)
        if (follows.isEmpty()) return
        if (System.currentTimeMillis() - follows.minOf { it.lastSync } < 20L * 3600 * 1000) return
        Thread({ runCatching { syncAll(ctx.applicationContext) } }, "voltfetch-sync").apply { isDaemon = true }.start()
    }

    /** Sets (or clears) the nightly alarm. */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, ScheduleReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.cancel(pi)
        if (list(ctx).isEmpty()) return
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, HOUR); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 60_000) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        runCatching {
            if (Schedules.canBeExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
    }

    fun isSyncIntent(i: Intent) = i.action == ACTION
}
