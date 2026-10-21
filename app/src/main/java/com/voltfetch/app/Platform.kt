package com.voltfetch.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.voltfetch.engine.Sink
import com.voltfetch.engine.Storage
import com.voltfetch.engine.Store
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Saves downloads into the public Download/VoltFetch/<Category> folder through MediaStore
 * (no storage permission needed on Android 10+). Files stay hidden as "pending" until finished.
 */
class MediaStoreStorage(private val ctx: Context) : Storage {
    private val resolver get() = ctx.contentResolver

    override fun create(name: String, category: String, mime: String): Pair<String, String> {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            // Android appends its own extension when the type and the name disagree, so the type always
            // comes from the name's extension; for unknown extensions it's left for Android to work out.
            typeFor(name)?.let { put(MediaStore.MediaColumns.MIME_TYPE, it) }
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/VoltFetch/$category")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Couldn't create the file in Download/VoltFetch")
        return uri.toString() to (displayName(uri) ?: name)
    }

    private fun typeFor(name: String): String? {
        val e = com.voltfetch.engine.Names.ext(name)
        if (e.isEmpty()) return null
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(e)
            ?: com.voltfetch.engine.Categories.mime(name).takeIf { it != "application/octet-stream" }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    override fun open(locator: String, fresh: Boolean): Sink {
        val pfd = resolver.openFileDescriptor(Uri.parse(locator), if (fresh) "rwt" else "rw")
            ?: throw IOException("Couldn't open the file for writing")
        val out = FileOutputStream(pfd.fileDescriptor)
        val ch = out.channel
        return object : Sink {
            override fun write(pos: Long, buf: ByteArray, off: Int, len: Int) {
                val bb = ByteBuffer.wrap(buf, off, len)
                var p = pos
                synchronized(ch) { while (bb.hasRemaining()) p += ch.write(bb, p) }
            }
            override fun truncate(size: Long) { synchronized(ch) { if (ch.size() > size) ch.truncate(size) } }
            override fun close() {
                runCatching { ch.force(false) }
                runCatching { out.close() }
                runCatching { pfd.close() }
            }
        }
    }

    override fun publish(locator: String) {
        resolver.update(Uri.parse(locator), ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    override fun delete(locator: String) { runCatching { resolver.delete(Uri.parse(locator), null, null) } }

    override fun exists(locator: String): Boolean = runCatching {
        resolver.query(Uri.parse(locator), arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    override fun read(locator: String): InputStream =
        resolver.openInputStream(Uri.parse(locator)) ?: throw IOException("Couldn't read the file")

    override fun move(locator: String, category: String): Boolean = runCatching {
        resolver.update(Uri.parse(locator), ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/VoltFetch/$category") }, null, null) > 0
    }.getOrDefault(false)

    override fun rename(locator: String, newName: String): String? = runCatching {
        val uri = Uri.parse(locator)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
            typeFor(newName)?.let { put(MediaStore.MediaColumns.MIME_TYPE, it) }
        }
        if (resolver.update(uri, values, null, null) <= 0) return null
        displayName(uri)
    }.getOrNull()
}

/**
 * Keeps the download list and settings as JSON files in the app's private storage.
 * Site passwords and browser cookies inside the list are encrypted with a key held in the Android Keystore,
 * so they are unreadable in backups or copies of the file.
 */
class FileStore(ctx: Context) : Store {
    private val dir = ctx.filesDir

    override fun protect(secret: String): String = if (secret.isEmpty()) "" else SecretBox.seal(secret)
    override fun reveal(stored: String): String = SecretBox.open(stored)
    private fun read(n: String): String? = File(dir, n).takeIf { it.exists() }?.readText()
    private fun write(n: String, s: String) {
        val tmp = File(dir, "$n.tmp")
        tmp.writeText(s)
        if (!tmp.renameTo(File(dir, n))) { File(dir, n).writeText(s); tmp.delete() }
    }
    override fun loadDownloads() = read("downloads.json")
    @Synchronized override fun saveDownloads(json: String) = write("downloads.json", json)
    override fun loadSettings() = read("settings.json")
    @Synchronized override fun saveSettings(json: String) = write("settings.json", json)
}

/** AES-GCM with a non-exportable Android Keystore key. Values look like "ks1:<base64 iv+ciphertext>". */
object SecretBox {
    private const val ALIAS = "voltfetch-secrets"
    private const val PREFIX = "ks1:"

    private fun key(): javax.crypto.SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        val gen = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(android.security.keystore.KeyGenParameterSpec.Builder(ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    @Synchronized fun seal(plain: String): String {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
    }

    /** Old plain values (from before encryption) are returned as they are; anything undecryptable becomes "". */
    @Synchronized fun open(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        return runCatching {
            val all = android.util.Base64.decode(stored.removePrefix(PREFIX), android.util.Base64.NO_WRAP)
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            c.init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, all, 0, 12))
            String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
        }.getOrDefault("")
    }
}
