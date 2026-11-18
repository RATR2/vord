package io.github.r4t2.vord.patcher

import java.io.File

object ApkPatcher {
    const val LOADER_CLASS = "io.github.r4t2.vord.Loader"
    private const val MANIFEST = "AndroidManifest.xml"
    private val DEX_NAME = Regex("classes(\\d*)\\.dex")

    /** Old v1 signature files and the Play source stamp must go; the new signature replaces them. */
    internal fun isSignatureEntry(name: String): Boolean =
        name == "stamp-cert-sha256" || name == "META-INF/MANIFEST.MF" ||
            (name.startsWith("META-INF/") && name.indexOf('/', 9) < 0 &&
                (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC")))

    class Result(val oldFactory: String?, val dexName: String?)

    /**
     * Rewrites [input] into [output] (unsigned): drops old signature entries and realigns. With a
     * [loaderDex] this is the base APK: the manifest's `appComponentFactory` is pointed at the Vord
     * Loader and the dex is added as the next free `classesN.dex`.
     */
    fun rewrite(input: File, output: File, loaderDex: ByteArray?): Result {
        var oldFactory: String? = null
        var dexName: String? = null
        ZipSource(input).use { src ->
            ZipSink(output).use { sink ->
                val highest = src.entries.mapNotNull { DEX_NAME.matchEntire(it.name) }
                    .maxOfOrNull { it.groupValues[1].ifEmpty { "1" }.toInt() } ?: 1
                for (e in src.entries) {
                    if (isSignatureEntry(e.name) || e.name.endsWith("/")) continue
                    if (loaderDex != null && e.name == MANIFEST) {
                        val patched = Axml.setApplicationAttribute(src.readAll(e), "appComponentFactory", LOADER_CLASS)
                        oldFactory = patched.oldValue
                        sink.addStored(e.name, patched.bytes, e.time, e.date)
                    } else {
                        sink.copy(src, e)
                    }
                }
                if (loaderDex != null) {
                    require(oldFactory != null) { "$MANIFEST not found" }
                    dexName = "classes${highest + 1}.dex"
                    sink.addStored(dexName!!, loaderDex)
                }
            }
        }
        return Result(oldFactory, dexName)
    }

    /** Full pipeline for one APK: rewrite to a temp file, sign into [output]. */
    fun process(input: File, output: File, key: SigningKey, loaderDex: ByteArray?, tmpDir: File): Result {
        tmpDir.mkdirs()
        val unsigned = File(tmpDir, input.name + ".unsigned")
        try {
            val r = rewrite(input, unsigned, loaderDex)
            Signer.sign(unsigned, output, key)
            return r
        } finally {
            unsigned.delete()
        }
    }
}
