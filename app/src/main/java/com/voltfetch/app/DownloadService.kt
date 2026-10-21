package com.voltfetch.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.voltfetch.engine.Fmt

/**
 * Keeps downloads alive while the app is in the background, with one ongoing
 * notification showing overall progress and a "Pause all" button.
 */
class DownloadService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var idleTicks = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startForeground(NOTIFY_ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            // Android refused (e.g. started from the background); downloads still run while the app is open.
            stopSelf(); return
        }
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoltFetch:downloads").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        @Suppress("DEPRECATION")
        wifi = applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VoltFetch:downloads").apply { setReferenceCounted(false); acquire() }
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = VoltApp.engine(this)
        when (intent?.action) {
            ACTION_PAUSE_ALL -> engine.pauseAll()
            ACTION_RESUME_ALL -> engine.resumeAll()
            ACTION_RETRY -> intent.getLongExtra("id", -1).takeIf { it > 0 }?.let {
                engine.resume(listOf(it))
                getSystemService(android.app.NotificationManager::class.java).cancel(VoltApp.NOTIFY_BASE + it.toInt())
            }
        }
        return START_NOT_STICKY
    }

    private val tick = object : Runnable {
        override fun run() {
            val engine = VoltApp.engine(this@DownloadService)
            if (engine.activeCount() == 0) {
                if (++idleTicks >= 3) { stopSelf(); return }
            } else idleTicks = 0
            getSystemService(android.app.NotificationManager::class.java).notify(NOTIFY_ID, build())
            handler.postDelayed(this, 1000)
        }
    }

    private fun build(): Notification {
        val engine = VoltApp.engine(this)
        val active = engine.views().filter { it.active }
        val speed = active.sumOf { it.speed }
        // every item with a known percentage counts: byte-based for files, stage-based for media
        val known = active.filter { it.percent >= 0 }
        val pct = if (known.isNotEmpty()) known.map { it.percent }.average().toInt() else -1
        val title = when (active.size) {
            0 -> "VoltFetch"
            1 -> active[0].name
            else -> "Downloading ${active.size} files"
        }
        val eta = active.filter { it.speed > 0 || it.isMedia }.maxOfOrNull { it.etaSeconds } ?: 0.0
        val text = buildString {
            if (active.isEmpty()) append("Finishing up") else {
                if (pct >= 0) append("$pct%  ·  ")
                val single = active.singleOrNull()
                append(Fmt.speed(speed).ifEmpty { if (single != null && single.isMedia) single.taskStage.ifEmpty { "Processing media" } else if (active.any { it.isMedia }) "Processing media" else "Connecting" })
                val etaText = if (eta > 0) Fmt.eta(eta) else ""
                if (etaText.isNotEmpty()) append("  ·  $etaText left")
            }
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getForegroundService(this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_ALL), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, VoltApp.CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFF1A8CFF.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, pct.coerceAtLeast(0), pct < 0 && active.isNotEmpty())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Pause all", pause).build())
            .build()
    }

    /**
     * Android 15+ allows a data-sync foreground service about 6 hours a day. When that runs out, pause
     * everything cleanly (progress is kept) and tell the user, instead of being stopped mid-write.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        VoltApp.engine(this).pauseAll()
        runCatching {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(NOTIFY_ID + 1, Notification.Builder(this, VoltApp.CH_DONE).setSmallIcon(R.drawable.ic_stat).setColor(0xFF1A8CFF.toInt())
                .setContentTitle("Downloads paused")
                .setContentText("Android's daily background download time ran out. Open VoltFetch and tap Resume.")
                .setStyle(Notification.BigTextStyle().bigText("Android's daily background download time ran out. Progress is kept: open VoltFetch and tap Resume."))
                .setAutoCancel(true).setContentIntent(open).build())
        }
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        runCatching { wake?.release() }
        runCatching { wifi?.release() }
        super.onDestroy()
    }

    companion object {
        const val NOTIFY_ID = 7
        const val ACTION_PAUSE_ALL = "com.voltfetch.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.voltfetch.RESUME_ALL"
        const val ACTION_RETRY = "com.voltfetch.RETRY"
        @Volatile private var lastStart = 0L

        fun ensureRunning(ctx: Context, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastStart < 3000) return
            lastStart = now
            runCatching { ctx.startForegroundService(Intent(ctx, DownloadService::class.java)) }
        }
    }
}
