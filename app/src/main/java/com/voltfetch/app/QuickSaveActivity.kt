package com.voltfetch.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.voltfetch.engine.Links
import com.voltfetch.engine.MediaKinds
import com.voltfetch.engine.MediaSites
import com.voltfetch.engine.Status
import com.voltfetch.engine.YouTube
import java.util.Locale

/**
 * "Save video" / "Save audio" in the share menu. Queues the shared link at once with the choices last used in
 * the Media downloader (quality, MP3 or original audio, subtitles, SponsorBlock, browser login) and shows a
 * short message; no screen opens. The real title replaces the placeholder name as soon as the download starts.
 */
class QuickSaveActivity : Activity() {
    private var queued = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val audio = componentName.className.endsWith("QuickSaveAudio")
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.dataString
        val url = MediaSites.find(text)
        if (url == null || !MediaSites.isWeb(url)) { toast("There's no web link in what you shared"); return }
        // a direct file link (zip, pdf, apk…) belongs in the normal downloader
        if (Links.looksLikeFile(url) && !MediaSites.isKnown(url)) {
            startActivity(Intent(this, AddActivity::class.java).setAction(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val prefs = getSharedPreferences("media_ui", MODE_PRIVATE)
        val kind = if (!audio) MediaKinds.VIDEO else prefs.getString("lastAudioKind", MediaKinds.MP3)?.takeIf { it == MediaKinds.MP3 || it == MediaKinds.AUDIO } ?: MediaKinds.MP3
        val option = when (kind) {
            MediaKinds.VIDEO -> prefs.getInt("lastHeight", 1080).coerceIn(144, 4320)
            MediaKinds.MP3 -> prefs.getInt("lastRate", 192).coerceIn(64, 320)
            else -> 0
        }
        val engine = VoltApp.engine(this)
        if (engine.views().any { it.url == url && it.taskKind == kind && it.taskOption == option && it.status != Status.COMPLETE }) {
            toast("Already in your list"); return
        }
        val opts = HashMap<String, String>()
        if (prefs.getBoolean("ck", true)) opts["ck"] = "1"
        if (prefs.getBoolean("sb", false) && YouTube.isUrl(url)) opts["sb"] = "1"
        val subs = if (kind == MediaKinds.VIDEO && prefs.getBoolean("subs", false))
            listOf(Locale.getDefault().language, "en").filter { it.matches(Regex("[a-z]{2,3}")) }.distinct().joinToString(",") else ""
        // apps often put the title in the subject; it's only a placeholder until the engine reports the real one
        val subject = intent?.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.takeIf { it.isNotBlank() && !it.contains("://") }?.take(120)
        val site = MediaSites.label(url)
        runCatching {
            engine.addMediaTask(url, subject ?: "$site media", kind, option, subs = subs, opts = opts, autoTitle = true)
            queued = true
            val what = when (kind) { MediaKinds.MP3 -> "MP3 · $option kbps"; MediaKinds.AUDIO -> "original audio"; else -> "video up to ${option}p" }
            toast("Saving $what from $site")
        }.onFailure { toast("Couldn't add it: ${it.message ?: "error"}") }
    }

    override fun onResume() {
        super.onResume()
        // started while this (invisible) activity is in front, so Android allows the background service
        if (queued) DownloadService.ensureRunning(this, force = true)
        finish()
    }

    private fun toast(s: String) = Toast.makeText(applicationContext, s, Toast.LENGTH_SHORT).show()
}
