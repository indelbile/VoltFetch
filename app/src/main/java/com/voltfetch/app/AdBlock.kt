package com.voltfetch.app

import android.content.Context
import android.webkit.WebResourceResponse
import com.voltfetch.engine.AdFilter
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The browser's ad blocker, in three layers (like Brave / uBlock Origin):
 * 1. network: requests to ad and tracker servers are answered with nothing (built-in list + community lists,
 *    refreshed weekly);
 * 2. YouTube: a page script removes the ad data from YouTube's player responses and skips any ad that still plays;
 * 3. cosmetic: leftover ad boxes are hidden; on Facebook/Instagram posts labelled "Sponsored" are hidden.
 * It can be switched off entirely, or for single sites.
 */
object AdBlock {
    private const val PREFS = "browser"
    private const val WEEK = 7L * 24 * 3600 * 1000
    /** Well-known, conservative community lists (ad servers and trackers only). */
    private val LISTS = listOf(
        "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=nohtml&showintro=0&mimetype=plaintext",
        "https://adaway.org/hosts.txt",
    )

    @Volatile private var filter = AdFilter(AdFilter.BUILTIN)
    @Volatile private var loaded = false
    @Volatile private var allowCache: Set<String>? = null
    @Volatile var updating = false
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun file(ctx: Context) = File(File(ctx.filesDir, "adblock").apply { mkdirs() }, "domains.txt")

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("adblock", true)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("adblock", on).apply()

    /** Sites where the user allowed ads (stored by site, e.g. "example.com"). */
    fun allowedSites(ctx: Context): Set<String> = allowCache ?: prefs(ctx).getString("adAllow", "").orEmpty()
        .split('\n').filter { it.isNotBlank() }.toSet().also { allowCache = it }

    fun isAllowed(ctx: Context, host: String) = host.isNotEmpty() && AdFilter.site(host) in allowedSites(ctx)

    fun setAllowed(ctx: Context, host: String, allow: Boolean) {
        val s = allowedSites(ctx).toMutableSet()
        if (allow) s.add(AdFilter.site(host)) else s.remove(AdFilter.site(host))
        prefs(ctx).edit().putString("adAllow", s.joinToString("\n")).apply()
        allowCache = s
    }

    /** Blocking applies on this page? */
    fun active(ctx: Context, pageHost: String) = enabled(ctx) && !isAllowed(ctx, pageHost)

    fun isAd(url: String, pageHost: String) = filter.isAd(url, pageHost.ifEmpty { null })

    fun rules() = filter.size
    fun lastUpdate(ctx: Context) = prefs(ctx).getLong("adListsUpdated", 0L)

    /** An empty answer for a blocked request. */
    fun empty() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /** Loads the saved lists (in the background) and refreshes them if they're over a week old. */
    fun load(ctx: Context) {
        if (loaded) { if (System.currentTimeMillis() - lastUpdate(ctx) > WEEK) refreshAsync(ctx, null); return }
        loaded = true
        val app = ctx.applicationContext
        Thread({
            runCatching {
                val f = file(app)
                if (f.exists()) filter = AdFilter(AdFilter.BUILTIN + AdFilter.parse(f.readText()))
            }
            if (System.currentTimeMillis() - lastUpdate(app) > WEEK) runCatching { refresh(app) }
        }, "voltfetch-adblock").apply { isDaemon = true }.start()
    }

    fun refreshAsync(ctx: Context, done: ((Int) -> Unit)?) {
        val app = ctx.applicationContext
        Thread({ val n = runCatching { refresh(app) }.getOrDefault(-1); done?.invoke(n) }, "voltfetch-adblock").apply { isDaemon = true }.start()
    }

    /** Downloads the community lists. Returns the number of rules now in use, or -1 if nothing could be fetched. */
    @Synchronized fun refresh(ctx: Context): Int {
        updating = true
        try {
            val all = HashSet<String>()
            var any = false
            for (u in LISTS) {
                val text = runCatching { fetch(u) }.getOrNull() ?: continue
                val parsed = AdFilter.parse(text)
                if (parsed.size > 100) { all.addAll(parsed); any = true }
            }
            if (!any) return -1
            file(ctx).writeText(all.joinToString("\n"))
            prefs(ctx).edit().putLong("adListsUpdated", System.currentTimeMillis()).apply()
            filter = AdFilter(AdFilter.BUILTIN + all)
            return filter.size
        } finally {
            updating = false
        }
    }

    private fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", com.voltfetch.engine.Settings.DEFAULT_AGENT)
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            val bytes = c.inputStream.use { inp ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16 * 1024)
                while (out.size() < 8 * 1024 * 1024) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                out.toByteArray()
            }
            return String(bytes, Charsets.UTF_8)
        } finally { c.disconnect() }
    }

    // ------------------------------------------------------------------ page script

    private const val GENERIC_CSS = "ins.adsbygoogle,.adsbygoogle,iframe[id^=\"google_ads_iframe\"],div[id^=\"google_ads_iframe\"],div[id^=\"div-gpt-ad\"]," +
        "[data-google-query-id],[data-ad-slot],iframe[src*=\"doubleclick.net\"],iframe[src*=\"googlesyndication\"],amp-ad,amp-embed[type=\"taboola\"]," +
        "[id^=\"taboola-\"],.trc_related_container,.OUTBRAIN,[data-widget-id^=\"outbrain\"],.ad-slot,.ad-banner,.adBanner,.advertisement-banner"

    private const val YT_CSS = "ytd-ad-slot-renderer,ytd-in-feed-ad-layout-renderer,ytd-promoted-sparkles-web-renderer,ytd-promoted-video-renderer," +
        "ytd-display-ad-renderer,ytd-banner-promo-renderer,ytd-statement-banner-renderer,ytd-player-legacy-desktop-watch-ads-renderer," +
        "#player-ads,#masthead-ad,.ytp-ad-module,.ytp-ad-overlay-container,.ytp-ad-image-overlay,ytm-promoted-sparkles-web-renderer," +
        "ytm-companion-ad-renderer,ytm-promoted-video-renderer,ad-slot-renderer,ytm-ad-slot-renderer,ytm-promoted-sparkles-text-search-renderer," +
        "ytd-rich-item-renderer:has(ytd-ad-slot-renderer),ytm-rich-item-renderer:has(ad-slot-renderer)"

    /** Runs at the start of every page (before the page's own scripts) while blocking is on. */
    fun script(ctx: Context): String {
        val allow = allowedSites(ctx).joinToString(",") { "'" + it.replace("'", "") + "'" }
        return """
(function(){
if (window.__vfAdblock) return; window.__vfAdblock = true;
var host = location.hostname;
var allow = [ALLOW];
for (var i = 0; i < allow.length; i++) { if (host === allow[i] || host.endsWith('.' + allow[i])) return; }
var isYT = /(^|\.)youtube(-nocookie)?\.com/.test(host);
var css = 'GENERIC_CSS' + (isYT ? ',YT_CSS' : '');
var addStyle = function () { try { var s = document.createElement('style'); s.textContent = css + '{display:none!important}'; (document.head || document.documentElement).appendChild(s); } catch (e) {} };
if (document.documentElement) addStyle(); else document.addEventListener('DOMContentLoaded', addStyle);
if (isYT) {
  var KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams', 'adBreakParams'];
  var prune = function (o) { try { if (o && typeof o === 'object') { for (var i = 0; i < KEYS.length; i++) { if (KEYS[i] in o) delete o[KEYS[i]]; } if (o.playerResponse) prune(o.playerResponse); if (Array.isArray(o)) o.forEach(prune); } } catch (e) {} return o; };
  var jp = JSON.parse;
  JSON.parse = function () { return prune(jp.apply(this, arguments)); };
  try { var rj = Response.prototype.json; Response.prototype.json = function () { return rj.apply(this, arguments).then(prune); }; } catch (e) {}
  try { var rt = Response.prototype.text; Response.prototype.text = function () { var u = this.url || ''; return rt.apply(this, arguments).then(function (t) { if (u.indexOf('/youtubei/v1/') >= 0 && t && t.charAt(0) === '{') { try { return JSON.stringify(prune(jp(t))); } catch (e) {} } return t; }); }; } catch (e) {}
  var ipr; try { Object.defineProperty(window, 'ytInitialPlayerResponse', { configurable: true, get: function () { return ipr; }, set: function (v) { ipr = prune(v); } }); } catch (e) {}
  setInterval(function () { try {
    var b = document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.ytp-ad-skip-button-container button'); if (b) b.click();
    var p = document.querySelector('.ad-showing,.ad-interrupting'); if (p) { var v = p.querySelector('video'); if (v && isFinite(v.duration) && v.duration > 0) { v.muted = true; v.currentTime = v.duration; } }
  } catch (e) {} }, 400);
}
if (/(^|\.)(facebook|instagram)\.com/.test(host)) {
  var scan = function () { try {
    var els = document.querySelectorAll('span,a');
    for (var i = 0; i < els.length; i++) { var s = els[i];
      if (s.childElementCount === 0 && (s.textContent === 'Sponsored' || s.getAttribute('aria-label') === 'Sponsored')) {
        var card = s.closest('article,[role="article"],[data-pagelet^="FeedUnit"],[data-tracking-duration-id]'); if (card) card.style.display = 'none'; } }
  } catch (e) {} };
  var t = null; var obs = new MutationObserver(function () { if (t) return; t = setTimeout(function () { t = null; scan(); }, 700); });
  var start = function () { scan(); obs.observe(document.documentElement, { childList: true, subtree: true }); };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
}
})();
""".replace("[ALLOW]", "[$allow]").replace("GENERIC_CSS", GENERIC_CSS.replace("'", "\\'")).replace("YT_CSS", YT_CSS.replace("'", "\\'"))
    }
}
