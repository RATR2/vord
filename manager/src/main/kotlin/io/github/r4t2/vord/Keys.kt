package io.github.r4t2.vord

import android.content.Context
import android.net.Uri
import io.github.r4t2.vord.patcher.SigningKey
import java.io.File
import java.security.MessageDigest

/**
 * The signing key every patched APK is signed with. It lives in the Manager's private storage.
 * Android only updates an installed app in place (keeping its data and login) when the new APK is
 * signed with the same key, so this key must not change between patches: import the existing one
 * if the patched Discord was installed from another tool.
 */
object Keys {
    private val PASSWORD = "vord".toCharArray()

    fun file(ctx: Context) = File(ctx.filesDir, "vord.p12")

    fun exists(ctx: Context) = file(ctx).exists()

    /** Loads the key, creating a new one on first use (takes a few seconds). */
    fun load(ctx: Context): SigningKey = SigningKey.loadOrCreate(file(ctx), PASSWORD)

    fun fingerprint(key: SigningKey): String =
        MessageDigest.getInstance("SHA-256").digest(key.certificate.encoded)
            .joinToString("") { "%02x".format(it) }.take(16)

    /** Replaces the key with a PKCS12 file the user picked. Returns an error message or null. */
    fun import(ctx: Context, uri: Uri): String? {
        val tmp = File(ctx.cacheDir, "import.p12")
        return try {
            ctx.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            SigningKey.loadOrCreate(tmp, PASSWORD) // throws if it is not a PKCS12 with alias "vord"
            tmp.copyTo(file(ctx), overwrite = true)
            null
        } catch (e: Exception) {
            "Not a usable Vord key (${e.javaClass.simpleName}: ${e.message})"
        } finally {
            tmp.delete()
        }
    }
}
