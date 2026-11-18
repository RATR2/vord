package io.github.r4t2.vord.patcher

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ZipIoTest {
    private fun tempZip(block: (ZipOutputStream) -> Unit): File {
        val f = File.createTempFile("vord", ".zip")
        f.deleteOnExit()
        ZipOutputStream(f.outputStream()).use(block)
        return f
    }

    @Test
    fun rewriteDropsSignaturesKeepsDataAndAligns() {
        val payload = ByteArray(5000) { (it % 251).toByte() }
        val input = tempZip { z ->
            z.putNextEntry(ZipEntry("META-INF/CERT.RSA")); z.write(1); z.closeEntry()
            z.putNextEntry(ZipEntry("META-INF/MANIFEST.MF")); z.write(2); z.closeEntry()
            z.putNextEntry(ZipEntry("assets/a.bin")); z.write(payload); z.closeEntry()
            val so = ZipEntry("lib/arm64-v8a/libx.so").apply {
                method = ZipEntry.STORED; size = 3; compressedSize = 3
                crc = java.util.zip.CRC32().also { it.update(byteArrayOf(7, 8, 9)) }.value
            }
            z.putNextEntry(so); z.write(byteArrayOf(7, 8, 9)); z.closeEntry()
        }
        val out = File.createTempFile("vord", ".out.zip").also { it.deleteOnExit() }
        val dex = byteArrayOf(0x64, 0x65, 0x78)
        val result = ApkPatcherZipOnly.rewrite(input, out, dex)
        assertEquals("classes2.dex", result)
        ZipFile(out).use { z ->
            assertNull(z.getEntry("META-INF/CERT.RSA"))
            assertNull(z.getEntry("META-INF/MANIFEST.MF"))
            assertContentEquals(payload, z.getInputStream(z.getEntry("assets/a.bin")).readBytes())
            assertContentEquals(byteArrayOf(7, 8, 9), z.getInputStream(z.getEntry("lib/arm64-v8a/libx.so")).readBytes())
            assertContentEquals(dex, z.getInputStream(assertNotNull(z.getEntry("classes2.dex"))).readBytes())
        }
        // native library data must start on a 16 KB boundary
        ZipSource(out).use { src ->
            val so = src.find("lib/arm64-v8a/libx.so")!!
            val data = out.readBytes()
            val at = data.indexOfSlice(byteArrayOf(7, 8, 9), so.localOffset.toInt())
            assertEquals(0, at % 16384)
        }
    }

    private fun ByteArray.indexOfSlice(s: ByteArray, from: Int): Int {
        for (i in from..size - s.size) if (s.indices.all { this[i + it] == s[it] }) return i
        return -1
    }
}

/** Test seam: the zip half of [ApkPatcher.rewrite] without a manifest to patch. */
private object ApkPatcherZipOnly {
    fun rewrite(input: File, output: File, dex: ByteArray): String {
        var name = ""
        ZipSource(input).use { src ->
            ZipSink(output).use { sink ->
                for (e in src.entries) {
                    if (ApkPatcher.isSignatureEntry(e.name)) continue
                    sink.copy(src, e)
                }
                name = "classes2.dex"
                sink.addStored(name, dex)
            }
        }
        return name
    }
}
