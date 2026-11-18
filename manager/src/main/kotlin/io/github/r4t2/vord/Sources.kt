package io.github.r4t2.vord

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/** Where the Discord APKs to patch come from: the installed app, or a file the user picked. */
object Sources {
    const val DISCORD = "com.discord"
    const val PATCHED = "io.github.r4t2.vord.discord"
    /** The only version the patches have been tested against. */
    const val TESTED_VERSION = "353.11"

    class Installed(val version: String, val code: Long, val paths: List<File>)

    fun installed(ctx: Context, pkg: String): Installed? = try {
        val info = ctx.packageManager.getPackageInfo(pkg, 0)
        val app = info.applicationInfo ?: return null
        val paths = listOf(app.sourceDir) + (app.splitSourceDirs?.toList() ?: emptyList())
        Installed(info.versionName ?: "?", info.longVersionCode, paths.map { File(it) })
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** Copies the installed Discord's APKs (base and splits) into [dir], keeping their names. */
    fun stageInstalled(src: Installed, dir: File): List<File> {
        dir.mkdirs()
        return src.paths.map { it.copyTo(File(dir, it.name), overwrite = true) }
    }

    /** Copies a picked `.apk` or bundle (`.apkm`, `.xapk`, `.zip`) and keeps only the APKs this device needs. */
    fun stagePicked(ctx: Context, uri: Uri, dir: File, log: (String) -> Unit): List<File> {
        dir.mkdirs()
        val raw = File(ctx.cacheDir, "picked.bin")
        ctx.contentResolver.openInputStream(uri)!!.use { i -> raw.outputStream().use { i.copyTo(it, 1 shl 20) } }
        try {
            val head = raw.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
            require(head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) { "Not an APK or bundle (no zip header)" }
            val names = ZipFile(raw).use { z -> z.entries().asSequence().map { it.name }.toList() }
            if (names.any { it == "AndroidManifest.xml" }) {
                log("Single APK picked")
                return listOf(raw.copyTo(File(dir, "base.apk"), overwrite = true))
            }
            val wanted = chooseSplits(ctx, names.filter { it.endsWith(".apk") })
            require("base.apk" in wanted) { "The bundle has no base.apk" }
            log("Bundle: using ${wanted.size} of ${names.count { it.endsWith(".apk") }} APKs")
            ZipFile(raw).use { z ->
                return wanted.map { n ->
                    File(dir, File(n).name).also { out ->
                        z.getInputStream(z.getEntry(n)).use { i -> out.outputStream().use { i.copyTo(it, 1 shl 20) } }
                    }
                }
            }
        } finally {
            raw.delete()
        }
    }

    /** base.apk plus this device's ABI, screen density and languages. */
    fun chooseSplits(ctx: Context, apks: List<String>): List<String> {
        val abi = (Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a").replace('-', '_')
        val dpi = ctx.resources.displayMetrics.densityDpi
        val buckets = listOf("ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240, "xhdpi" to 320,
            "xxhdpi" to 480, "xxxhdpi" to 640)
        val density = buckets.minByOrNull { Math.abs(it.second - dpi) }!!.first
        val langs = mutableSetOf("en")
        Locale.getDefault().language.let { langs += if (it == "id") "in" else it }
        return apks.filter { n ->
            val key = n.substringBeforeLast(".apk").substringAfter("split_config.", "")
            n == "base.apk" || (key.isNotEmpty() && (key == abi || key == density || key in langs))
        }.sortedBy { if (it == "base.apk") 0 else 1 }
    }
}
