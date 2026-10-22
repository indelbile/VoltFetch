package com.voltfetch.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.voltfetch.engine.AppUpdate
import com.voltfetch.engine.Engine
import com.voltfetch.engine.Status
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates VoltFetch from the GitHub Releases of its own (public) repository.
 *
 * - Checks once a day when the app is used (and on "Check now" in Settings).
 * - The new APK for this phone's processor is downloaded by VoltFetch's own download manager (progress,
 *   resume, Download/VoltFetch/Programs), then installed with Android's PackageInstaller.
 * - The APK must be signed with the same key as the installed app; that's checked before installing.
 * - Android asks to confirm the first self-update; on Android 12+ later ones can install without asking,
 *   because VoltFetch installed the version it is replacing.
 * The repository is built in by the GitHub workflow (BuildConfig.UPDATE_REPO) and can be changed in Settings.
 */
object AppUpdater {
    private const val PREFS = "app_update"
    private const val DAY = 24L * 3600 * 1000
    const val ACTION_STATUS = "com.voltfetch.UPDATE_STATUS"
    const val ACTION_DOWNLOAD = "com.voltfetch.UPDATE_DOWNLOAD"
    private const val NOTIFY_ID = 5100

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun repo(ctx: Context): String = prefs(ctx).getString("repo", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.UPDATE_REPO
    fun setRepo(ctx: Context, repo: String) = prefs(ctx).edit().putString("repo", repo).apply()
    fun autoCheck(ctx: Context) = prefs(ctx).getBoolean("autoCheck", true)
    fun setAutoCheck(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("autoCheck", on).apply()
    fun autoInstall(ctx: Context) = prefs(ctx).getBoolean("autoInstall", true)
    fun setAutoInstall(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("autoInstall", on).apply()
    fun lastCheck(ctx: Context) = prefs(ctx).getLong("lastCheck", 0L)
    fun lastResult(ctx: Context) = prefs(ctx).getString("lastResult", "").orEmpty()

    fun installedCode(ctx: Context): Long = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode }.getOrDefault(0L)
    fun installedName(ctx: Context): String = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

    sealed class Result(val message: String) {
        class Available(val release: AppUpdate.Release, val code: Long) : Result("${release.name} is available")
        object UpToDate : Result("VoltFetch is up to date")
        object NoRepo : Result("No update source set. Add your GitHub repository in Settings › App updates.")
        class Failed(why: String) : Result("Couldn't check for updates: $why")
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "VoltFetch-updater")
        try {
            when (c.responseCode) {
                200 -> return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                404 -> throw java.io.IOException("no release found (is the repository public, with at least one release?)")
                403 -> throw java.io.IOException("GitHub is limiting requests; try again later")
                else -> throw java.io.IOException("GitHub answered ${c.responseCode}")
            }
        } finally { c.disconnect() }
    }

    /** Asks GitHub for the latest release. Call off the main thread. */
    fun check(ctx: Context): Result {
        val repo = repo(ctx)
        if (repo.isBlank()) return Result.NoRepo
        val r = runCatching {
            val rel = AppUpdate.parseLatest(get(AppUpdate.latestUrl(repo)), Build.SUPPORTED_ABIS.toList())
                ?: return@runCatching Result.Failed("the latest release has no APK for this phone, or no version.json")
            val code = AppUpdate.versionCode(get(rel.versionUrl))
            if (code > installedCode(ctx)) Result.Available(rel, code) else Result.UpToDate
        }.getOrElse { Result.Failed(it.message ?: it.javaClass.simpleName) }
        prefs(ctx).edit().putLong("lastCheck", System.currentTimeMillis()).putString("lastResult", r.message).apply()
        return r
    }

    /** Daily check while the app is used; downloads straight away when automatic updates are on. */
    fun maybeCheck(ctx: Context) {
        if (!autoCheck(ctx) || System.currentTimeMillis() - lastCheck(ctx) < DAY || repo(ctx).isBlank()) return
        val app = ctx.applicationContext
        Thread({
            val r = check(app)
            if (r is Result.Available) {
                val va = app as? VoltApp
                if (autoInstall(app) && (va == null || va.unmetered)) download(app, r) else notifyAvailable(app, r)
            }
        }, "voltfetch-update").apply { isDaemon = true }.start()
    }

    /** Queues the APK in VoltFetch's own download list; [onDownloaded] installs it when it finishes. */
    fun download(ctx: Context, r: Result.Available) {
        val engine = VoltApp.engine(ctx)
        val existing = prefs(ctx).getLong("downloadId", -1L)
        engine.view(existing)?.let { v -> if (v.url == r.release.apkUrl && v.status != Status.ERROR) { if (v.status == Status.COMPLETE) onDownloaded(ctx, existing); return } }
        val d = engine.add(Engine.AddRequest(url = r.release.apkUrl, name = r.release.apkName, category = "Programs", size = r.release.apkSize,
            note = "VoltFetch update ${r.release.tag}", userNamed = true))
        prefs(ctx).edit().putLong("downloadId", d.id).putLong("downloadCode", r.code).apply()
        DownloadService.ensureRunning(ctx, force = true)
    }

    /** Called for every finished download; acts only on the update APK. */
    fun onDownloaded(ctx: Context, id: Long) {
        if (id != prefs(ctx).getLong("downloadId", -1L)) return
        val v = VoltApp.engine(ctx).view(id) ?: return
        Thread({ install(ctx.applicationContext, Uri.parse(v.locator)) }, "voltfetch-update-install").apply { isDaemon = true }.start()
    }

    private fun certs(pkg: android.content.pm.PackageInfo?): Set<String> {
        val signers = pkg?.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory } ?: return emptySet()
        return signers.map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    /** Checks the APK (package, newer, same signing key) and hands it to Android's installer. */
    private fun install(ctx: Context, apk: Uri) {
        val tmp = File(ctx.cacheDir, "update.apk")
        try {
            ctx.contentResolver.openInputStream(apk)?.use { inp -> tmp.outputStream().use { inp.copyTo(it) } } ?: throw java.io.IOException("can't read the downloaded file")
            val pm = ctx.packageManager
            @Suppress("DEPRECATION")
            val archive = pm.getPackageArchiveInfo(tmp.path, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: throw java.io.IOException("the downloaded file isn't a valid app")
            if (archive.packageName != ctx.packageName) throw java.io.IOException("the downloaded file is a different app")
            if (archive.longVersionCode <= installedCode(ctx)) throw java.io.IOException("the downloaded version isn't newer")
            val mine = certs(pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES))
            val theirs = certs(archive)
            if (mine.isNotEmpty() && theirs.isNotEmpty() && mine.intersect(theirs).isEmpty())
                throw java.io.IOException("it was signed with a different key. Check the signing secrets on GitHub; until then install it by hand after uninstalling")
            if (!pm.canRequestPackageInstalls()) { notifyPermission(ctx); return }

            val installer = pm.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(ctx.packageName)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { s ->
                s.openWrite("voltfetch.apk", 0, tmp.length()).use { out -> tmp.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                val status = PendingIntent.getBroadcast(ctx, 7001, Intent(ctx, UpdateReceiver::class.java).setAction(ACTION_STATUS),
                    PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
                s.commit(status.intentSender)
            }
        } catch (e: Exception) {
            notify(ctx, "Update couldn't be installed", e.message ?: e.javaClass.simpleName, null)
        } finally {
            runCatching { tmp.delete() }
        }
    }

    fun notifyAvailable(ctx: Context, r: Result.Available) {
        val go = PendingIntent.getBroadcast(ctx, 7002, Intent(ctx, UpdateReceiver::class.java).setAction(ACTION_DOWNLOAD), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        notify(ctx, "${r.release.name} is available", "You have ${installedName(ctx)}. Tap to download and install.", go)
    }

    private fun notifyPermission(ctx: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        notify(ctx, "Allow VoltFetch to install its updates", "Turn on \"Allow from this source\", then tap Check now in Settings › App updates.",
            PendingIntent.getActivity(ctx, 7003, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    }

    fun notify(ctx: Context, title: String, text: String, tap: PendingIntent?) = runCatching {
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return@runCatching
        val b = Notification.Builder(ctx, VoltApp.CH_DONE).setSmallIcon(R.drawable.ic_stat).setColor(0xFF1A8CFF.toInt())
            .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true)
        if (tap != null) b.setContentIntent(tap)
        ctx.getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, b.build())
    }

    fun clearDownload(ctx: Context) = prefs(ctx).edit().remove("downloadId").apply()

    /** After the app has been replaced: say so if it was our own update. */
    fun onUpdated(ctx: Context) {
        val code = prefs(ctx).getLong("downloadCode", -1L)
        if (code > 0 && installedCode(ctx) >= code) {
            prefs(ctx).edit().remove("downloadCode").remove("downloadId").apply()
            notify(ctx, "VoltFetch updated", "Now running version ${installedName(ctx)}.", null)
        }
    }
}

/** Receives the installer's answer, and "Download" taps on the update notification. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            AppUpdater.ACTION_DOWNLOAD -> {
                val pending = goAsync()
                Thread({
                    try { (AppUpdater.check(ctx) as? AppUpdater.Result.Available)?.let { AppUpdater.download(ctx, it) } } finally { pending.finish() }
                }, "voltfetch-update").start()
            }
            AppUpdater.ACTION_STATUS -> when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // Android wants the user to confirm: open its dialog, and leave a notification in case it can't show now
                    @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { ctx.startActivity(confirm) }
                    AppUpdater.notify(ctx, "Tap to finish updating VoltFetch", "Android asks you to confirm the update once.",
                        PendingIntent.getActivity(ctx, 7004, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                }
                PackageInstaller.STATUS_SUCCESS -> AppUpdater.clearDownload(ctx)
                else -> AppUpdater.notify(ctx, "Update couldn't be installed", intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "The installer stopped.", null)
            }
        }
    }
}
