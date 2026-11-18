package io.github.r4t2.vord.patcher

import java.io.File

object ApkPatcher {
    const val LOADER_CLASS = "io.github.r4t2.vord.Loader"
    /** Where the Loader looks for the JS core inside the APK (assets://vord/core.js). */
    const val CORE_ASSET = "assets/vord/core.js"
    private const val MANIFEST = "AndroidManifest.xml"
    private val DEX_NAME = Regex("classes(\\d*)\\.dex")

    /** Old v1 signature files and the Play source stamp must go; the new signature replaces them. */
    internal fun isSignatureEntry(name: String): Boolean =
        name == "stamp-cert-sha256" || name == "META-INF/MANIFEST.MF" ||
            (name.startsWith("META-INF/") && name.indexOf('/', 9) < 0 &&
                (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC")))

    class Result(val oldFactory: String?, val dexName: String?, val originalPackage: String?)

    /** Move the app to a different package name so it installs next to the original. */
    class Rename(val to: String)

    /**
     * Rewrites [input] into [output] (unsigned): drops old signature entries and realigns. With a
     * [loaderDex] this is the base APK: the manifest's `appComponentFactory` is pointed at the Vord
     * Loader and the dex is added as the next free `classesN.dex`.
     */
    fun rewrite(
        input: File, output: File, loaderDex: ByteArray?, rename: Rename? = null, from: String = "com.discord",
        coreJs: ByteArray? = null,
    ): Result {
        var oldFactory: String? = null
        var dexName: String? = null
        var originalPackage: String? = null
        ZipSource(input).use { src ->
            ZipSink(output).use { sink ->
                val highest = src.entries.mapNotNull { DEX_NAME.matchEntire(it.name) }
                    .maxOfOrNull { it.groupValues[1].ifEmpty { "1" }.toInt() } ?: 1
                for (e in src.entries) {
                    if (isSignatureEntry(e.name) || e.name.endsWith("/")) continue
                    if (e.name == MANIFEST && (loaderDex != null || rename != null)) {
                        val patched = Axml.edit(
                            src.readAll(e),
                            Axml.Edits(
                                appComponentFactory = if (loaderDex != null) LOADER_CLASS else null,
                                renameFrom = if (rename != null) from else null,
                                renameTo = rename?.to,
                            ),
                        )
                        oldFactory = patched.oldAppComponentFactory
                        originalPackage = patched.originalPackage
                        sink.addStored(e.name, patched.bytes, e.time, e.date)
                    } else if (e.name == "resources.arsc" && rename != null) {
                        val arsc = src.readAll(e)
                        Arsc.renamePackage(arsc, from, rename.to)
                        sink.addStored(e.name, arsc, e.time, e.date)
                    } else {
                        sink.copy(src, e)
                    }
                }
                if (loaderDex != null) {
                    require(oldFactory != null) { "$MANIFEST not found" }
                    dexName = "classes${highest + 1}.dex"
                    sink.addStored(dexName!!, loaderDex)
                    if (coreJs != null) sink.addStored(CORE_ASSET, coreJs)
                }
            }
        }
        return Result(oldFactory, dexName, originalPackage)
    }

    /** Full pipeline for one APK: rewrite to a temp file, sign into [output]. */
    fun process(
        input: File, output: File, key: SigningKey, loaderDex: ByteArray?, tmpDir: File,
        rename: Rename? = null, coreJs: ByteArray? = null,
    ): Result {
        tmpDir.mkdirs()
        val unsigned = File(tmpDir, input.name + ".unsigned")
        try {
            val r = rewrite(input, unsigned, loaderDex, rename, coreJs = coreJs)
            Signer.sign(unsigned, output, key)
            return r
        } finally {
            unsigned.delete()
        }
    }
}
