package com.voltfetch.engine

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.io.InputStream
import java.util.Locale

object Fmt {
    fun size(n: Long): String {
        if (n < 0) return "—"
        if (n < 1024) return "$n B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = n.toDouble()
        var i = -1
        do { v /= 1024; i++ } while (v >= 1024 && i < units.size - 1)
        val s = when {
            v >= 100 -> String.format(Locale.US, "%.0f", v)
            v >= 10 -> String.format(Locale.US, "%.1f", v)
            else -> String.format(Locale.US, "%.2f", v)
        }
        return "$s ${units[i]}"
    }

    /** Returns number and unit separately so the UI can style them. */
    fun speedParts(bps: Double): Pair<String, String> = when {
        bps < 1 -> "0" to "KB/s"
        bps >= 1024.0 * 1024 * 1024 -> String.format(Locale.US, "%.2f", bps / 1073741824) to "GB/s"
        bps >= 1024.0 * 1024 -> { val v = bps / 1048576; (if (v >= 100) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)) to "MB/s" }
        else -> { val v = bps / 1024; (if (v >= 100) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)) to "KB/s" }
    }

    fun speed(bps: Double): String = if (bps > 0) speedParts(bps).let { "${it.first} ${it.second}" } else ""

    fun eta(seconds: Double): String {
        if (seconds <= 0 || seconds.isNaN() || seconds.isInfinite()) return ""
        val s = seconds.toLong()
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${(s % 60).toString().padStart(2, '0')}s"
            s < 86400 -> "${s / 3600}h ${((s % 3600) / 60).toString().padStart(2, '0')}m"
            else -> "> 1 day"
        }
    }
}

object Names {
    private val bad = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")

    fun sanitize(name: String): String {
        var out = name.trim().replace(bad, "_").trimEnd('.', ' ')
        if (out.isEmpty()) out = "download"
        if (out.length > 180) {
            val dot = out.lastIndexOf('.')
            val ext = if (dot > 0 && out.length - dot <= 12) out.substring(dot) else ""
            out = out.substring(0, 180 - ext.length) + ext
        }
        return out
    }

    fun fromUrl(raw: String): String = try {
        val u = URI(raw.trim())
        var p = u.rawPath ?: ""
        p = URLDecoder.decode(p.replace("+", "%2B"), "UTF-8")
        val last = p.substringAfterLast('/')
        sanitize(if (last.isBlank()) (u.host ?: "download") + ".html" else last)
    } catch (e: Exception) {
        "download"
    }

    fun fromDisposition(cd: String?): String? {
        if (cd.isNullOrBlank()) return null
        // RFC 5987 filename*=UTF-8''name.ext wins over plain filename=
        Regex("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.let {
            val cs = it.groupValues[1].ifBlank { "UTF-8" }
            return try { sanitize(URLDecoder.decode(it.groupValues[2].trim(), cs)) } catch (e: Exception) { sanitize(it.groupValues[2]) }
        }
        Regex("filename\\s*=\\s*(\"([^\"]*)\"|([^;]+))", RegexOption.IGNORE_CASE).find(cd)?.let {
            val v = it.groupValues[2].ifEmpty { it.groupValues[3] }.trim()
            if (v.isNotEmpty()) return sanitize(v)
        }
        return null
    }

    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    /** Extensions we recognise as real file types. */
    fun known(e: String) = e.isNotEmpty() && e != "bin" && (Categories.all.values.any { e in it } || e in Categories.extra)

    /**
     * True for names that carry no meaning: "downloadfile.bin", "a8f3c9e1d2b7…", "file", "download".
     * Such names come from links that hide the real file name; the server usually sends the real one.
     */
    fun isGeneric(name: String): Boolean {
        if (name.isBlank()) return true
        val e = ext(name)
        val base = if (e.isNotEmpty() && name.length > e.length + 1) name.dropLast(e.length + 1) else name
        if (e == "bin" || e == "html" || e == "htm" || e == "php" || e == "aspx" || e == "jsp" || e == "cgi") return true
        if (base.lowercase(Locale.ROOT) in setOf("download", "downloadfile", "file", "get", "index", "default", "attachment", "view", "dl", "video", "videoplayback", "media", "stream")) return true
        // an ID or a hash: one long run of jumbled letters and digits that makes up most of the name
        val longest = base.split(' ', '-', '_', '.', '+', '(', ')').maxByOrNull { it.length } ?: ""
        val flips = longest.zipWithNext().count { (x, y) -> x.isDigit() != y.isDigit() }
        val looksId = longest.length >= 12 && longest.all { it.isLetterOrDigit() } && flips >= 3 && longest.length * 10 >= base.length * 7
        val allDigits = base.length >= 6 && base.all { it.isDigit() }
        return looksId || allDigits
    }

    /** Gives a name the right extension for its type, replacing a meaningless ".bin". */
    fun withExtension(name: String, mime: String): String {
        val e = ext(name)
        if (known(e)) return name
        val want = Categories.extFor(mime) ?: return name
        val base = if (e == "bin" && name.length > 4) name.dropLast(4) else name
        return "$base.$want"
    }

    /** Picks the best of several candidate names: the first one that isn't generic, else the first non-blank. */
    fun best(vararg candidates: String?): String {
        val c = candidates.filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }
        return c.firstOrNull { !isGeneric(it) } ?: c.firstOrNull() ?: "download"
    }

    /** Recognises common file types from their first bytes. Returns an extension or null. */
    fun sniff(head: ByteArray): String? {
        fun at(i: Int, vararg b: Int) = head.size >= i + b.size && b.indices.all { head[i + it].toInt() and 0xff == b[it] }
        fun ascii(i: Int, s: String) = head.size >= i + s.length && s.indices.all { head[i + it].toInt().toChar() == s[it] }
        return when {
            ascii(0, "%PDF") -> "pdf"
            at(0, 0x50, 0x4B, 0x03, 0x04) -> when {
                ascii(30, "AndroidManifest.xml") || ascii(30, "classes.dex") || ascii(30, "META-INF/") && String(head, Charsets.ISO_8859_1).contains("AndroidManifest") -> "apk"
                ascii(30, "mimetypeapplication/epub+zip") -> "epub"
                ascii(30, "[Content_Types].xml") || ascii(30, "word/") -> "docx"
                else -> "zip"
            }
            ascii(0, "Rar!") -> "rar"
            at(0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> "7z"
            at(0, 0x1F, 0x8B) -> "gz"
            at(0, 0x89, 0x50, 0x4E, 0x47) -> "png"
            at(0, 0xFF, 0xD8, 0xFF) -> "jpg"
            ascii(0, "GIF8") -> "gif"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "webp"
            ascii(0, "RIFF") && ascii(8, "WAVE") -> "wav"
            ascii(0, "RIFF") && ascii(8, "AVI ") -> "avi"
            ascii(4, "ftyp") -> when {
                ascii(8, "M4A") || ascii(8, "M4B") -> "m4a"
                ascii(8, "qt") -> "mov"
                ascii(8, "3gp") || ascii(8, "3g2") -> "3gp"
                ascii(8, "heic") || ascii(8, "heix") || ascii(8, "mif1") -> "heic"
                else -> "mp4"
            }
            at(0, 0x1A, 0x45, 0xDF, 0xA3) -> if (String(head, Charsets.ISO_8859_1).contains("webm")) "webm" else "mkv"
            ascii(0, "ID3") || at(0, 0xFF, 0xFB) || at(0, 0xFF, 0xF3) || at(0, 0xFF, 0xF2) -> "mp3"
            ascii(0, "OggS") -> if (String(head, Charsets.ISO_8859_1).contains("OpusHead")) "opus" else "ogg"
            ascii(0, "fLaC") -> "flac"
            at(0, 0xFF, 0xF1) || at(0, 0xFF, 0xF9) -> "aac"
            ascii(0, "MZ") -> "exe"
            at(0, 0xD0, 0xCF, 0x11, 0xE0) -> "doc"
            ascii(0, "{\\rtf") -> "rtf"
            ascii(257, "ustar") -> "tar"
            at(0, 0xFD, 0x37, 0x7A, 0x58, 0x5A) -> "xz"
            at(0, 0x42, 0x5A, 0x68) -> "bz2"
            ascii(0x8001, "CD001") -> "iso"
            else -> null
        }
    }
}

object Categories {
    val all = linkedMapOf(
        "Compressed" to setOf("zip", "rar", "7z", "gz", "tar", "tgz", "bz2", "xz"),
        "Documents" to setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "epub", "csv"),
        "Music" to setOf("mp3", "wav", "flac", "aac", "m4a", "ogg", "opus", "wma"),
        "Programs" to setOf("apk", "xapk", "exe", "msi", "iso", "img", "dmg", "deb", "bin"),
        "Video" to setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp", "ts"),
        "Images" to setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg"),
    )
    const val GENERAL = "General"
    fun of(name: String): String {
        val e = Names.ext(name)
        for ((k, v) in all) if (e in v) return k
        return GENERAL
    }
    fun names(): List<String> = all.keys.toList() + GENERAL
    /** Extensions that aren't a download category but are still real types. */
    val extra = setOf("html", "htm", "json", "xml", "srt", "vtt", "torrent", "avi", "heic", "exe", "iso", "dmg", "obb", "ttf", "otf", "md")

    private val byMime = mapOf(
        "application/pdf" to "pdf", "application/zip" to "zip", "application/x-zip-compressed" to "zip", "application/vnd.android.package-archive" to "apk",
        "application/x-rar-compressed" to "rar", "application/vnd.rar" to "rar", "application/x-7z-compressed" to "7z", "application/gzip" to "gz", "application/x-gzip" to "gz",
        "application/x-tar" to "tar", "application/epub+zip" to "epub", "application/msword" to "doc",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx", "application/vnd.ms-excel" to "xls",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx", "application/vnd.ms-powerpoint" to "ppt",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx", "application/x-iso9660-image" to "iso",
        "application/x-msdownload" to "exe", "application/x-bittorrent" to "torrent", "application/json" to "json",
        "video/mp4" to "mp4", "video/webm" to "webm", "video/x-matroska" to "mkv", "video/quicktime" to "mov", "video/3gpp" to "3gp", "video/x-msvideo" to "avi",
        "audio/mpeg" to "mp3", "audio/mp3" to "mp3", "audio/mp4" to "m4a", "audio/x-m4a" to "m4a", "audio/aac" to "aac", "audio/ogg" to "ogg", "audio/opus" to "opus",
        "audio/wav" to "wav", "audio/x-wav" to "wav", "audio/flac" to "flac", "audio/x-flac" to "flac", "audio/webm" to "webm",
        "image/jpeg" to "jpg", "image/png" to "png", "image/gif" to "gif", "image/webp" to "webp", "image/heic" to "heic", "image/svg+xml" to "svg",
        "text/plain" to "txt", "text/csv" to "csv", "text/html" to "html",
    )

    /** Extension for a MIME type, or null for generic types such as application/octet-stream. */
    fun extFor(mime: String?): String? = byMime[(mime ?: "").substringBefore(';').trim().lowercase(Locale.ROOT)]

    fun mime(name: String): String = when (Names.ext(name)) {
        "pdf" -> "application/pdf"; "zip" -> "application/zip"; "apk" -> "application/vnd.android.package-archive"
        "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "ogg", "opus" -> "audio/ogg"; "wav" -> "audio/wav"; "flac" -> "audio/flac"
        "mp4", "m4v" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "mov" -> "video/quicktime"; "3gp" -> "video/3gpp"
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"
        "txt", "csv" -> "text/plain"; "html", "htm" -> "text/html"; "epub" -> "application/epub+zip"
        "doc" -> "application/msword"; "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"; "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"; "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "7z" -> "application/x-7z-compressed"; "rar" -> "application/vnd.rar"; "gz", "tgz" -> "application/gzip"
        else -> "application/octet-stream"
    }
}

object Checksums {
    /** Lower-cases a hex checksum and strips labels like "SHA256:"; returns "" if it isn't hex. */
    fun normalize(input: String): String {
        var s = input.trim().lowercase(Locale.ROOT)
        for (p in listOf("sha-256", "sha256", "sha-512", "sha512", "sha-1", "sha1", "md5")) {
            if (s.startsWith(p)) { s = s.substring(p.length).trimStart(' ', ':', '='); break }
        }
        s = s.split(Regex("\\s+")).firstOrNull() ?: ""
        return if (s.isNotEmpty() && s.all { it in "0123456789abcdef" }) s else ""
    }

    fun algoFor(hex: String): String = when (hex.length) { 32 -> "MD5"; 40 -> "SHA-1"; 128 -> "SHA-512"; else -> "SHA-256" }

    fun hash(input: InputStream, algo: String): String {
        val md = MessageDigest.getInstance(algo)
        val buf = ByteArray(1 shl 16)
        input.use { while (true) { val n = it.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { String.format("%02x", it) }
    }
}

object Links {
    private val urlRe = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
    /** Extracts http(s) links from shared or copied text. */
    fun find(text: String?): List<String> =
        if (text.isNullOrBlank()) emptyList() else urlRe.findAll(text).map { it.value.trimEnd('.', ',', ')', ']') }.distinct().toList()

    fun looksLikeFile(url: String): Boolean {
        val path = try { URI(url).path ?: "" } catch (e: Exception) { "" }
        val e = Names.ext(path.substringAfterLast('/'))
        return e.isNotEmpty() && Categories.all.values.any { e in it }
    }
}
