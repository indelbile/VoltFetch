package com.voltfetch.engine

/**
 * Reads GitHub's "latest release" answer for the app's own repository and picks the APK for this phone.
 * Releases are made by the GitHub workflow when a tag like v1.3.0 is pushed; it attaches one APK per processor
 * type (…-arm64-v8a.apk, …-armeabi-v7a.apk) and a version.json with the build's versionCode.
 */
object AppUpdate {
    data class Release(
        val tag: String, val name: String, val notes: String,
        val apkUrl: String, val apkName: String, val apkSize: Long,
        /** version.json's address; its versionCode is what decides whether the release is newer. */
        val versionUrl: String,
    )

    /** [abis] in the phone's order of preference (Build.SUPPORTED_ABIS). Null if the release has no usable APK. */
    @Suppress("UNCHECKED_CAST")
    fun parseLatest(json: String, abis: List<String>): Release? {
        val m = runCatching { Json.parse(json) as Map<String, Any?> }.getOrNull() ?: return null
        if (m.bool("draft") || m.bool("prerelease")) return null
        val assets = m.list("assets").mapNotNull { it as? Map<String, Any?> }
        val apks = assets.filter { it.str("name").endsWith(".apk", ignoreCase = true) }
        val apk = abis.firstNotNullOfOrNull { abi -> apks.firstOrNull { it.str("name").contains("-$abi") } }
            ?: apks.firstOrNull { it.str("name").contains("universal") }
            ?: apks.singleOrNull()
            ?: return null
        val ver = assets.firstOrNull { it.str("name") == "version.json" } ?: return null
        return Release(
            m.str("tag_name"), m.str("name").ifBlank { m.str("tag_name") }, m.str("body"),
            apk.str("browser_download_url"), apk.str("name"), apk.long("size", -1), ver.str("browser_download_url"),
        )
    }

    /** version.json → versionCode, or -1. */
    fun versionCode(json: String): Long = runCatching { (Json.parse(json) as Map<*, *>)["versionCode"].let { (it as Number).toLong() } }.getOrDefault(-1L)

    /** "owner/repo" from what a user typed: a full github.com address or the short form. Null if it isn't one. */
    fun repo(input: String?): String? {
        val t = input?.trim()?.removeSuffix("/")?.removeSuffix(".git") ?: return null
        val short = t.substringAfter("github.com/", t)
        val parts = short.split('/')
        if (parts.size != 2) return null
        val ok = Regex("[A-Za-z0-9_.-]+")
        return if (parts.all { ok.matches(it) }) short else null
    }

    fun latestUrl(repo: String) = "https://api.github.com/repos/$repo/releases/latest"
}
