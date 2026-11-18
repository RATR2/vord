package io.github.r4t2.vord.patcher

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal binary AndroidManifest (AXML) editor. Edits are done by appending new strings to the
 * string pool and repointing the attributes that should change, so existing string indices never
 * move and every other chunk stays valid. New values may be any length.
 *
 * Only pools without styles are supported (Android manifests don't have any).
 */
object Axml {
    private const val RES_XML = 0x0003
    private const val STRING_POOL = 0x0001
    private const val START_ELEMENT = 0x0102
    private const val UTF8_FLAG = 0x100
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val TYPE_STRING = 0x03

    class ManifestException(message: String) : Exception(message)

    /** What to change. All parts are optional. */
    class Edits(
        /** Point `<application android:appComponentFactory>` at this class. */
        val appComponentFactory: String? = null,
        /** Move the app from package [renameFrom] to [renameTo] (see [renamed]). */
        val renameFrom: String? = null,
        val renameTo: String? = null,
    )

    class Result(val bytes: ByteArray, val oldAppComponentFactory: String?, val originalPackage: String?)

    /** A string attribute found in the manifest. */
    private class Attr(
        val element: String, val ns: String?, val name: String, val offset: Int, val value: String,
        val isString: Boolean,
    )

    fun edit(manifest: ByteArray, edits: Edits): Result {
        val src = ByteBuffer.wrap(manifest).order(ByteOrder.LITTLE_ENDIAN)
        if (manifest.size < 8 || (src.getShort(0).toInt() and 0xFFFF) != RES_XML) {
            throw ManifestException("not a binary XML file")
        }
        val poolStart = src.getShort(2).toInt() and 0xFFFF
        if ((src.getShort(poolStart).toInt() and 0xFFFF) != STRING_POOL) {
            throw ManifestException("string pool is not the first chunk")
        }
        val poolHeaderSize = src.getShort(poolStart + 2).toInt() and 0xFFFF
        val poolSize = src.getInt(poolStart + 4)
        val stringCount = src.getInt(poolStart + 8)
        val styleCount = src.getInt(poolStart + 12)
        val flags = src.getInt(poolStart + 16)
        val stringsStart = src.getInt(poolStart + 20)
        if (styleCount != 0) throw ManifestException("string pool has styles, unsupported")
        val utf8 = flags and UTF8_FLAG != 0
        val offsetsAt = poolStart + poolHeaderSize

        fun readString(index: Int): String {
            var p = poolStart + stringsStart + src.getInt(offsetsAt + index * 4)
            return if (utf8) {
                p += if (src.get(p).toInt() and 0x80 != 0) 2 else 1 // char length
                var len = src.get(p).toInt() and 0xFF
                p += 1
                if (len and 0x80 != 0) {
                    len = ((len and 0x7F) shl 8) or (src.get(p).toInt() and 0xFF)
                    p += 1
                }
                String(manifest, p, len, Charsets.UTF_8)
            } else {
                var len = src.getShort(p).toInt() and 0xFFFF
                p += 2
                if (len and 0x8000 != 0) {
                    len = ((len and 0x7FFF) shl 16) or (src.getShort(p).toInt() and 0xFFFF)
                    p += 2
                }
                String(manifest, p, len * 2, Charsets.UTF_16LE)
            }
        }

        // Collect every attribute whose value is a plain string.
        val attrs = ArrayList<Attr>()
        var chunk = poolStart + poolSize
        while (chunk + 8 <= manifest.size) {
            val type = src.getShort(chunk).toInt() and 0xFFFF
            val size = src.getInt(chunk + 4)
            if (size <= 0) throw ManifestException("bad chunk size at $chunk")
            if (type == START_ELEMENT) {
                val element = readString(src.getInt(chunk + 20))
                val body = chunk + 16
                val attrStart = src.getShort(body + 8).toInt() and 0xFFFF
                val attrSize = src.getShort(body + 10).toInt() and 0xFFFF
                val attrCount = src.getShort(body + 12).toInt() and 0xFFFF
                for (i in 0 until attrCount) {
                    val a = body + attrStart + i * attrSize
                    val raw = src.getInt(a + 8)
                    if (raw < 0 || raw >= stringCount) continue // not a string attribute
                    val ns = src.getInt(a).let { if (it < 0) null else readString(it) }
                    val name = src.getInt(a + 4).let { if (it < 0) "" else readString(it) }
                    val isString = (src.get(a + 15).toInt() and 0xFF) == TYPE_STRING
                    attrs.add(Attr(element, ns, name, a, readString(raw), isString))
                }
            }
            chunk += size
        }

        // Decide the new values.
        val changes = LinkedHashMap<Int, String>() // attribute offset -> new value
        var oldFactory: String? = null
        var originalPackage: String? = null
        for (a in attrs) {
            if (a.element == "manifest" && a.ns == null && a.name == "package") originalPackage = a.value
            if (edits.appComponentFactory != null && a.element == "application" &&
                a.name == "appComponentFactory" && a.ns == ANDROID_NS
            ) {
                oldFactory = a.value
                changes[a.offset] = edits.appComponentFactory
            }
            if (edits.renameFrom != null && edits.renameTo != null) {
                val renamed = renamed(a.element, a.ns, a.name, a.value, edits.renameFrom, edits.renameTo)
                if (renamed != null && renamed != a.value) changes[a.offset] = renamed
            }
        }
        if (edits.appComponentFactory != null && oldFactory == null) {
            throw ManifestException("<application> has no 'appComponentFactory' attribute")
        }
        if (changes.isEmpty()) return Result(manifest, oldFactory, originalPackage)

        // Append each distinct new string to the pool once.
        val newStrings = changes.values.distinct()
        val indexOf = newStrings.withIndex().associate { (i, s) -> s to stringCount + i }
        val encoded = newStrings.map { encode(it, utf8) }
        val oldStringsLen = poolSize - stringsStart
        val added = 4 * newStrings.size + encoded.sumOf { it.size }
        val newPoolSize = (poolSize + added + 3) and 3.inv()
        val delta = newPoolSize - poolSize

        val out = ByteBuffer.allocate(manifest.size + delta).order(ByteOrder.LITTLE_ENDIAN)
        out.put(manifest, 0, poolStart)
        out.put(manifest, poolStart, poolHeaderSize)
        out.putInt(poolStart + 4, newPoolSize)
        out.putInt(poolStart + 8, stringCount + newStrings.size)
        out.putInt(poolStart + 16, flags and 1.inv()) // appended strings break the "sorted" flag
        out.putInt(poolStart + 20, stringsStart + 4 * newStrings.size)
        out.put(manifest, offsetsAt, stringCount * 4)
        var rel = oldStringsLen
        for (e in encoded) {
            out.putInt(rel)
            rel += e.size
        }
        out.put(manifest, poolStart + stringsStart, oldStringsLen)
        for (e in encoded) out.put(e)
        while (out.position() < poolStart + newPoolSize) out.put(0)
        out.put(manifest, poolStart + poolSize, manifest.size - poolStart - poolSize)
        out.putInt(4, manifest.size + delta)

        for ((offset, value) in changes) {
            val at = offset + delta
            val index = indexOf.getValue(value)
            out.putInt(at + 8, index)
            if ((out.get(at + 15).toInt() and 0xFF) == TYPE_STRING) out.putInt(at + 16, index)
        }
        return Result(out.array(), oldFactory, originalPackage)
    }

    /**
     * Returns the new value if this attribute is bound to the package name and must change when the
     * app is renamed from [from] to [to], else null. Class names (`android:name` of components) are
     * deliberately left alone: the classes themselves do not move.
     */
    internal fun renamed(element: String, ns: String?, name: String, value: String, from: String, to: String): String? {
        fun swap(v: String) = if (v == from) to else if (v.startsWith("$from.")) to + v.substring(from.length) else null
        return when {
            element == "manifest" && ns == null && name == "package" -> if (value == from) to else null
            name == "authorities" -> value.split(';').map { swap(it) ?: it }.joinToString(";")
            name == "taskAffinity" || name == "permission" || name == "readPermission" ||
                name == "writePermission" -> swap(value)
            name == "name" && (element == "permission" || element == "uses-permission" ||
                element == "uses-permission-sdk-23" || element == "permission-tree" ||
                element == "permission-group") -> swap(value)
            // e.g. PROPERTY_SATELLITE_DATA_OPTIMIZED lists the app's own package name
            element == "meta-data" && name == "value" && value == from -> to
            else -> null
        }
    }

    private fun encode(value: String, utf8: Boolean): ByteArray {
        return if (utf8) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(value.length < 0x80 && bytes.size < 0x80) { "string too long for simple encoding" }
            byteArrayOf(value.length.toByte(), bytes.size.toByte()) + bytes + byteArrayOf(0)
        } else {
            require(value.length < 0x8000) { "string too long for simple encoding" }
            val chars = value.toByteArray(Charsets.UTF_16LE)
            val b = ByteBuffer.allocate(2 + chars.size + 2).order(ByteOrder.LITTLE_ENDIAN)
            b.putShort(value.length.toShort())
            b.put(chars)
            b.putShort(0)
            b.array()
        }
    }
}

/** Edits the package name stored in a compiled resource table (`resources.arsc`), in place. */
object Arsc {
    private const val TABLE = 0x0002
    private const val PACKAGE = 0x0200
    private const val NAME_AT = 12 // package id (u32) sits at 8, then 128 UTF-16 chars
    private const val NAME_CHARS = 128

    /**
     * Renames package [from] to [to]. `Resources.getIdentifier(name, type, getPackageName())` and
     * split-APK merging both compare against this name, so it must match the manifest's package.
     * Returns the number of package chunks renamed.
     */
    fun renamePackage(arsc: ByteArray, from: String, to: String): Int {
        require(to.length < NAME_CHARS) { "package name too long" }
        val b = ByteBuffer.wrap(arsc).order(ByteOrder.LITTLE_ENDIAN)
        require((b.getShort(0).toInt() and 0xFFFF) == TABLE) { "not a resource table" }
        var pos = b.getShort(2).toInt() and 0xFFFF
        var renamed = 0
        while (pos + 8 <= arsc.size) {
            val type = b.getShort(pos).toInt() and 0xFFFF
            val size = b.getInt(pos + 4)
            if (size <= 0) break
            if (type == PACKAGE) {
                val nameAt = pos + NAME_AT
                var len = 0
                while (len < NAME_CHARS && b.getShort(nameAt + len * 2).toInt() != 0) len++
                if (String(arsc, nameAt, len * 2, Charsets.UTF_16LE) == from) {
                    val enc = to.toByteArray(Charsets.UTF_16LE)
                    java.util.Arrays.fill(arsc, nameAt, nameAt + NAME_CHARS * 2, 0)
                    System.arraycopy(enc, 0, arsc, nameAt, enc.size)
                    renamed++
                }
            }
            pos += size
        }
        return renamed
    }
}
