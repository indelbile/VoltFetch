package com.voltfetch.engine

import java.net.URI
import java.util.Locale

/**
 * Decides whether a request a web page makes is an ad or tracker. Domain rules come from a built-in list plus
 * community filter lists (hosts files, AdBlock "||domain^" rules or plain domain lists); a few path rules catch
 * ads served from a site's own domain (e.g. YouTube's /pagead/ and ad statistics).
 * A domain rule never blocks a request to the page's own site, so visiting a site directly still works.
 */
class AdFilter(private val domains: Set<String>) {
    val size: Int get() = domains.size

    private val pathRules = listOf("/pagead/", "/api/stats/ads", "/get_midroll_", "/ptracking", "/pcs/activeview", "/adview")

    fun isAd(url: String, pageHost: String? = null): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = u.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return false
        val host = u.host?.lowercase(Locale.ROOT)?.removeSuffix(".") ?: return false
        val path = u.rawPath ?: ""
        if (pathRules.any { path.startsWith(it) } && (isGoogle(host))) return true
        if (pageHost != null && sameSite(host, pageHost.lowercase(Locale.ROOT))) return false
        var h = host
        while (true) {
            if (h in domains) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
            if (!h.contains('.')) return false
        }
    }

    private fun isGoogle(host: String) = listOf("youtube.com", "google.com", "googlevideo.com", "youtube-nocookie.com").any { host == it || host.endsWith(".$it") } ||
        Regex("(^|\\.)google\\.[a-z.]{2,6}$").containsMatchIn(host)

    companion object {
        private val domainRe = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

        /** "news.example.co.uk" and "www.example.co.uk" are the same site; so are "m.youtube.com" and "youtube.com". */
        fun site(host: String): String {
            val parts = host.split('.')
            if (parts.size <= 2) return host
            val twoLevel = parts[parts.size - 2] in setOf("co", "com", "net", "org", "gov", "ac", "edu") && parts.last().length == 2
            return parts.takeLast(if (twoLevel) 3 else 2).joinToString(".")
        }

        fun sameSite(a: String, b: String) = site(a) == site(b)

        /** Reads hosts files ("0.0.0.0 ads.example.com"), AdBlock domain rules ("||ads.example.com^") and plain domain lists. */
        fun parse(text: String): Set<String> {
            val out = HashSet<String>()
            for (raw in text.lineSequence()) {
                var line = raw.substringBefore('#').trim()
                if (line.isEmpty() || line.startsWith("!") || line.startsWith("[") || line.startsWith("@@")) continue
                if (line.startsWith("||")) {
                    // only whole-domain rules: ||ads.example.com^  or  ||ads.example.com^$third-party
                    val body = line.removePrefix("||")
                    val end = body.indexOf('^')
                    if (end <= 0) continue
                    val rest = body.substring(end + 1)
                    if (rest.isNotEmpty() && !rest.matches(Regex("\\$(third-party|3p|all|important)(,(third-party|3p|all|important))*"))) continue
                    line = body.substring(0, end)
                } else {
                    val parts = line.split(Regex("\\s+"))
                    line = if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1" || parts[0] == "::" || parts[0] == "::1")) parts[1] else if (parts.size == 1) parts[0] else continue
                }
                val d = line.lowercase(Locale.ROOT).removePrefix("www.").trim('.')
                if (d == "localhost" || d.endsWith(".local") || d == "0.0.0.0" || !domainRe.matches(d)) continue
                out.add(d)
            }
            return out
        }

        /** Major ad networks and trackers, so blocking works offline before any list is downloaded. */
        val BUILTIN: Set<String> = setOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com", "googletagmanager.com",
            "googletagservices.com", "adservice.google.com", "pagead2.googlesyndication.com", "ade.googlesyndication.com",
            "adnxs.com", "adnxs-simple.com", "taboola.com", "taboolasyndication.com", "outbrain.com", "outbrainimg.com", "criteo.com", "criteo.net",
            "amazon-adsystem.com", "adsrvr.org", "rubiconproject.com", "pubmatic.com", "openx.net", "casalemedia.com", "indexww.com",
            "scorecardresearch.com", "quantserve.com", "quantcount.com", "moatads.com", "adsafeprotected.com", "doubleverify.com", "2mdn.net",
            "media.net", "zedo.com", "popads.net", "popcash.net", "propellerads.com", "propellerclick.com", "adcash.com", "exoclick.com",
            "exosrv.com", "juicyads.com", "trafficjunky.net", "mgid.com", "revcontent.com", "smartadserver.com", "yieldmo.com", "teads.tv",
            "sharethrough.com", "bidswitch.net", "3lift.com", "triplelift.com", "contextweb.com", "lijit.com", "sovrn.com", "spotxchange.com",
            "spotx.tv", "springserve.com", "adform.net", "serving-sys.com", "flashtalking.com", "advertising.com", "adtechus.com", "adroll.com",
            "hotjar.com", "mouseflow.com", "crazyegg.com", "an.facebook.com", "unityads.unity3d.com", "applovin.com", "applvn.com", "inmobi.com",
            "vungle.com", "chartboost.com", "adcolony.com", "startappservice.com", "ads.twitter.com", "static.ads-twitter.com",
            "analytics.twitter.com", "ads-api.tiktok.com", "analytics.tiktok.com", "ads.linkedin.com", "bat.bing.com", "adsterra.com",
            "hilltopads.net", "a-ads.com", "bidvertiser.com", "clickadu.com", "onclickads.net", "onclickperformance.com", "popmyads.com",
            "histats.com", "an.yandex.ru", "mc.yandex.ru", "adfox.ru", "yieldlove.com", "adition.com", "admixer.net", "gumgum.com",
            "33across.com", "connatix.com", "primis.tech", "vidazoo.com", "undertone.com", "kargo.com", "nativo.com", "zemanta.com",
            "infolinks.com", "adskeeper.com", "adskeeper.co.uk", "lockerdome.com", "ezoic.net", "ezodn.com", "adthrive.com", "mediavine.com",
            "amung.us", "clicky.com", "imrworldwide.com", "chartbeat.com", "chartbeat.net", "parsely.com", "nr-data.net",
            "app-measurement.com", "adjust.com", "appsflyer.com", "kochava.com",
        )
    }
}
