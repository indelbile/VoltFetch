package com.voltfetch.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.voltfetch.engine.Planner

/**
 * Wakes VoltFetch when a queue is due to start or stop, even if the app was closed.
 * Exact alarms need the "Alarms & reminders" permission on Android 12+; without it the alarm may run a few minutes late.
 */
object Schedules {
    private const val ACTION = "com.voltfetch.SCHEDULE"

    /** Android 10 and 11 allow exact alarms without asking. */
    fun canBeExact(ctx: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun reschedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val engine = VoltApp.engine(ctx)
        val next = Planner.nextEvent(engine.settings.queues, System.currentTimeMillis())
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, ScheduleReceiver::class.java).setAction(ACTION).putExtra("at", next ?: 0L),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.cancel(pi)
        if (next == null) return
        runCatching {
            if (canBeExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                Schedules.reschedule(ctx); runCatching { PlaylistSync.schedule(ctx) }
                if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) runCatching { AppUpdater.onUpdated(ctx) }
                return
            }
        }
        if (PlaylistSync.isSyncIntent(intent)) {
            // nightly playlist check: in the foreground service when Android allows it (no time limit),
            // otherwise briefly in the background; then tomorrow's alarm is set
            val viaService = runCatching { ctx.startForegroundService(Intent(ctx, DownloadService::class.java).setAction(DownloadService.ACTION_SYNC)) }.isSuccess
            if (viaService) return
            val pending = goAsync()
            val app = ctx.applicationContext
            Thread({
                try { runCatching { PlaylistSync.syncAll(app) } } finally {
                    runCatching { PlaylistSync.schedule(app) }
                    pending.finish()
                }
            }, "voltfetch-sync").start()
            return
        }
        val engine = VoltApp.engine(ctx)
        val at = intent.getLongExtra("at", 0L).takeIf { it > 0 } ?: System.currentTimeMillis()
        engine.runSchedulesAt(at)
        if (engine.activeCount() > 0 || engine.views().any { it.status == com.voltfetch.engine.Status.QUEUED }) DownloadService.ensureRunning(ctx, force = true)
        Schedules.reschedule(ctx)
    }
}
