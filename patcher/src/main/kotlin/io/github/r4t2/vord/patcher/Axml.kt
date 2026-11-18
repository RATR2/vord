package io.github.r4t2.vord.patcher

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal binary AndroidManifest (AXML) editor. It does exactly one thing: repoint a string
 * attribute of `<application>` at a new value, by appending the new string to the string pool
 * and rewriting the attribute's references. Existing string indices never move, so every other
 * chunk stays valid, and the new value may be any length (no in-place size limit).
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

    /** Returns the previous value of the attribute. */
    data class Result(val bytes: ByteArray, val oldValue: String)

    fun setApplicationAttribute(manifest: ByteArray, attribute: String, newValue: String): Result {
        val src = ByteBuffer.wrap(manifest).order(ByteOrder.LITTLE_ENDIAN)
        if (manifest.size < 8 || (src.getShort(0).toInt() and 0xFFFF) != RES_XML) {
            throw ManifestException("not a binary XML file")
        }
        val fileHeaderSize = src.getShort(2).toInt() and 0xFFFF

        // String pool is the first chunk after the file header.
        val poolStart = fileHeaderSize
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
                // u8 char length (maybe 2 bytes), u8 byte length (maybe 2 bytes), bytes
                if (src.get(p).toInt() and 0x80 != 0) p += 2 else p += 1
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

        // Walk node chunks looking for <application>'s attribute.
        var attrOffset = -1
        var chunk = poolStart + poolSize
        while (chunk + 8 <= manifest.size) {
            val type = src.getShort(chunk).toInt() and 0xFFFF
            val size = src.getInt(chunk + 4)
            if (size <= 0) throw ManifestException("bad chunk size at $chunk")
            if (type == START_ELEMENT) {
                val nameIdx = src.getInt(chunk + 20)
                if (readString(nameIdx) == "application") {
                    val body = chunk + 16
                    val attrStart = src.getShort(body + 8).toInt() and 0xFFFF
                    val attrSize = src.getShort(body + 10).toInt() and 0xFFFF
                    val attrCount = src.getShort(body + 12).toInt() and 0xFFFF
                    for (i in 0 until attrCount) {
                        val a = body + attrStart + i * attrSize
                        val ns = src.getInt(a)
                        val name = src.getInt(a + 4)
                        if (name >= 0 && readString(name) == attribute &&
                            (ns < 0 || readString(ns) == ANDROID_NS)
                        ) {
                            attrOffset = a
                            break
                        }
                    }
                    break
                }
            }
            chunk += size
        }
        if (attrOffset < 0) throw ManifestException("<application> has no '$attribute' attribute")
        val oldIndex = src.getInt(attrOffset + 8)
        if (oldIndex < 0 || oldIndex >= stringCount) {
            throw ManifestException("attribute '$attribute' is not a plain string")
        }
        val oldValue = readString(oldIndex)

        // Encode the new string the same way the pool encodes the others.
        val encoded = encode(newValue, utf8)
        val oldStringsLen = poolSize - stringsStart
        val newOffsetEntryLen = 4
        val newPoolSizeUnpadded = poolSize + newOffsetEntryLen + encoded.size
        val newPoolSize = (newPoolSizeUnpadded + 3) and 3.inv()
        val delta = newPoolSize - poolSize

        val out = ByteBuffer.allocate(manifest.size + delta).order(ByteOrder.LITTLE_ENDIAN)
        // file header (size field patched below)
        out.put(manifest, 0, poolStart)
        // pool header, with updated size/count/stringsStart
        out.put(manifest, poolStart, poolHeaderSize)
        out.putInt(poolStart + 4, newPoolSize)
        out.putInt(poolStart + 8, stringCount + 1)
        out.putInt(poolStart + 16, flags and 1.inv()) // appended string breaks the "sorted" flag
        out.putInt(poolStart + 20, stringsStart + newOffsetEntryLen)
        // existing offsets, then the new one, then old string data, then the new string
        out.put(manifest, offsetsAt, stringCount * 4)
        out.putInt(oldStringsLen) // new string starts right after the old data
        out.put(manifest, poolStart + stringsStart, oldStringsLen)
        out.put(encoded)
        while (out.position() < poolStart + newPoolSize) out.put(0)
        // everything after the pool
        out.put(manifest, poolStart + poolSize, manifest.size - poolStart - poolSize)
        out.putInt(4, manifest.size + delta)

        // repoint the attribute at the new string (rawValue and typed data)
        val newIndex = stringCount
        val shiftedAttr = attrOffset + delta
        out.putInt(shiftedAttr + 8, newIndex)
        val dataType = out.get(shiftedAttr + 15).toInt() and 0xFF
        if (dataType == TYPE_STRING) out.putInt(shiftedAttr + 16, newIndex)

        return Result(out.array(), oldValue)
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
