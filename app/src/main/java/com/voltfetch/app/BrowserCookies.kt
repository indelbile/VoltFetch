package com.voltfetch.app

import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Hands the login a user made in VoltFetch's own browser to yt-dlp, for sites that only show videos to
 * signed-in users (Instagram, Facebook, X…). Only that site's cookies are written, to a file inside the
 * task's private working folder, and the file is deleted as soon as yt-dlp finishes.
 */
object BrowserCookies {
    /** The browser's cookie header for [url] ("a=1; b=2"), or null. CookieManager is read on the main thread. */
    fun header(url: String): String? {
        var out: String? = null
        val read = Runnable { out = runCatching { android.webkit.CookieManager.getInstance().getCookie(url) }.getOrNull() }
        if (Looper.myLooper() == Looper.getMainLooper()) read.run()
        else {
            val done = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { read.run(); done.countDown() }
            done.await(3, TimeUnit.SECONDS)
        }
        return out?.takeIf { it.isNotBlank() }
    }

    fun hasLogin(url: String): Boolean = header(url) != null

    /** Writes the site's cookies in the Netscape format yt-dlp reads. Returns false when there are none. */
    fun writeNetscape(url: String, file: File): Boolean {
        val cookies = header(url) ?: return false
        val host = runCatching { java.net.URI(url).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return false
        // "www.instagram.com" / "m.facebook.com" → ".instagram.com" / ".facebook.com"
        val domain = "." + host.split('.').takeLast(2).joinToString(".")
        val expires = System.currentTimeMillis() / 1000 + 30L * 24 * 3600
        val lines = cookies.split(';').mapNotNull { part ->
            val name = part.substringBefore('=').trim()
            val value = part.substringAfter('=', "").trim()
            if (name.isEmpty() || name.contains('\t') || value.contains('\t')) null
            else "$domain\tTRUE\t/\tTRUE\t$expires\t$name\t$value"
        }
        if (lines.isEmpty()) return false
        return runCatching { file.writeText("# Netscape HTTP Cookie File\n" + lines.joinToString("\n") + "\n"); true }.getOrDefault(false)
    }
}
