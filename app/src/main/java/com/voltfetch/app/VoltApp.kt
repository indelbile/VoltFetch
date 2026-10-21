package com.voltfetch.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.voltfetch.engine.Engine
import com.voltfetch.engine.Event
import com.voltfetch.engine.Status

/** Owns the engine for the whole process, watches the network and posts notifications. */
class VoltApp : Application() {
    lateinit var engine: Engine
        private set
    private val main = Handler(Looper.getMainLooper())
    @Volatile var unmetered = true
        private set
    @Volatile var online = true
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashLog()
        engine = Engine(MediaStoreStorage(this), FileStore(this), AndroidMediaTransferHandler(this))
        createChannels()
        engine.onEvent { e -> main.post { onEngineEvent(e) } }
        watchNetwork()
        // The on-device media engine (yt-dlp + FFmpeg) unpacks on first run, so it starts in the background
        // instead of holding up launch; media jobs wait for it and report a readable error if it fails.
        MediaEngine.start(this)
        runCatching { Schedules.reschedule(this) }
        // keep the foreground service running whenever something is downloading
        main.post(object : Runnable {
            override fun run() {
                if (engine.activeCount() > 0) DownloadService.ensureRunning(this@VoltApp)
                main.postDelayed(this, 1500)
            }
        })
    }

    /**
     * Saves any crash to crash.txt so MainActivity can show it on the next launch.
     * Crashes on background threads are logged without closing the app.
     */
    private fun installCrashLog() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                java.io.File(filesDir, CRASH_FILE).writeText(
                    "VoltFetch ${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\nThread: ${t.name}\n\n$sw")
            }
            if (t === Looper.getMainLooper().thread) previous?.uncaughtException(t, e)
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Downloads in progress", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows progress while VoltFetch is downloading"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Finished and failed downloads", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Tells you when a download finishes, fails or its checksum is checked"
        })
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        cm.activeNetwork?.let { n -> cm.getNetworkCapabilities(n)?.let { applyCaps(it) } } ?: run { online = false }
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = applyCaps(caps)
            override fun onLost(network: Network) { online = false; refreshNetworkGate() }
        })
        refreshNetworkGate()
    }

    private fun applyCaps(caps: NetworkCapabilities) {
        online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        refreshNetworkGate()
    }

    /** "Wi-Fi only": hold downloads while on mobile data. */
    fun refreshNetworkGate() {
        engine.setNetworkAllowed(!engine.settings.wifiOnly || unmetered)
    }

    val waitingForWifi: Boolean get() = engine.settings.wifiOnly && !unmetered

    private fun onEngineEvent(e: Event) {
        val s = engine.settings
        val nm = getSystemService(NotificationManager::class.java)
        if (e.kind == "settings") { Schedules.reschedule(this); return }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (e.kind.startsWith("queue")) {
            val open = PendingIntent.getActivity(this, 40_000, Intent(this, MainActivity::class.java).putExtra("tab", "queues").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val title = when (e.kind) { "queueStart" -> "Queue \"${e.name}\" started"; "queueStop" -> "Queue \"${e.name}\" stopped"; else -> "Queue \"${e.name}\" finished" }
            val text = when (e.kind) { "queueStart" -> "Started on schedule"; "queueStop" -> "Stopped on schedule. Unfinished files are paused."; else -> "Every file in the queue is done" }
            nm.notify(NOTIFY_BASE - 1 - (e.name.hashCode() and 0xff), Notification.Builder(this, CH_DONE).setSmallIcon(R.drawable.ic_stat).setColor(0xFF1A8CFF.toInt())
                .setContentTitle(title).setContentText(text).setAutoCancel(true).setContentIntent(open).build())
            return
        }
        val v = engine.view(e.id) ?: return
        val details = PendingIntent.getActivity(this, e.id.toInt(), Intent(this, DetailActivity::class.java).putExtra("id", e.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(this, CH_DONE).setSmallIcon(R.drawable.ic_stat).setAutoCancel(true).setColor(0xFF1A8CFF.toInt())
        when (e.kind) {
            "complete" -> {
                if (!s.notifyDone) return
                b.setContentTitle(if (v.taskKind.isNotEmpty()) "Media download complete" else "Download complete").setContentText(v.name)
                val open = Files.openIntent(this, v)
                b.setContentIntent(if (open != null) PendingIntent.getActivity(this, 10_000 + e.id.toInt(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) else details)
                if (open != null) b.addAction(Notification.Action.Builder(null, "Open", PendingIntent.getActivity(this, 20_000 + e.id.toInt(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
                b.addAction(Notification.Action.Builder(null, "Details", details).build())
            }
            "error" -> {
                b.setContentTitle(if (v.taskKind.isNotEmpty()) "Media download failed" else "Couldn't finish ${v.name}").setContentText(e.detail).setStyle(Notification.BigTextStyle().bigText(e.detail)).setContentIntent(details)
                val retry = PendingIntent.getForegroundService(this, 30_000 + e.id.toInt(), Intent(this, DownloadService::class.java).setAction(DownloadService.ACTION_RETRY).putExtra("id", e.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                b.addAction(Notification.Action.Builder(null, "Retry", retry).build())
            }
            "verify" -> {
                when (e.detail) {
                    "ok" -> b.setContentTitle("Checksum matches").setContentText("${v.name} is intact")
                    "mismatch" -> b.setContentTitle("Checksum doesn't match").setContentText("${v.name} may be damaged. Try downloading it again.")
                    else -> return
                }
                b.setContentIntent(details)
            }
            else -> return
        }
        nm.notify(NOTIFY_BASE + e.id.toInt(), b.build())
    }

    companion object {
        lateinit var instance: VoltApp
            private set
        const val CH_PROGRESS = "progress"
        const val CH_DONE = "done"
        const val NOTIFY_BASE = 1000
        const val CRASH_FILE = "crash.txt"
        fun engine(ctx: Context): Engine = (ctx.applicationContext as VoltApp).engine
    }
}

/** Opening and sharing finished files. */
object Files {
    fun openIntent(ctx: Context, v: com.voltfetch.engine.View): Intent? {
        if (v.status != Status.COMPLETE || v.locator.isEmpty()) return null
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(v.locator), v.mime.ifEmpty { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return Intent.createChooser(i, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun shareIntent(v: com.voltfetch.engine.View): Intent {
        val i = Intent(Intent.ACTION_SEND).setType(v.mime.ifEmpty { "*/*" })
            .putExtra(Intent.EXTRA_STREAM, Uri.parse(v.locator)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(i, "Share ${v.name}")
    }

    fun folderLabel(v: com.voltfetch.engine.View) = "Download/VoltFetch/${v.category}"
}
