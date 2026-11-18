package io.github.r4t2.vord.patcher

import java.io.File
import java.util.zip.ZipFile

/**
 * Desktop CLI used to exercise the patcher against real Discord bundles:
 *   patcher <bundle.apkm|dir> <outDir> --loader <classes.dex> --key <vord.p12> [--splits a,b,c]
 * Patches base.apk, re-signs the chosen splits with the same key, prints the adb command.
 */
fun main(args: Array<String>) {
    fun opt(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val input = File(args.getOrNull(0) ?: error("usage: <bundle> <outDir> --loader <dex> --key <p12> [--splits a,b]"))
    val outDir = File(args.getOrNull(1) ?: error("missing outDir"))
    val loader = File(opt("--loader") ?: error("missing --loader"))
    val key = SigningKey.loadOrCreate(File(opt("--key") ?: error("missing --key")), "vord".toCharArray())
    val splits = opt("--splits")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
    outDir.mkdirs()
    val work = File(outDir, "work").also { it.mkdirs() }

    val apks: List<File> = if (input.isDirectory) {
        input.listFiles { f -> f.name.endsWith(".apk") }!!.sortedBy { it.name }
    } else {
        ZipFile(input).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".apk") }
                .filter { it.name == "base.apk" || splits == null ||
                    splits.any { s -> it.name == "split_config.$s.apk" } }
                .map { e -> File(work, e.name).also { f -> zip.getInputStream(e).use { i -> f.outputStream().use { i.copyTo(it) } } } }
                .toList()
        }
    }
    val signed = ArrayList<File>()
    for (apk in apks) {
        val out = File(outDir, apk.name)
        val isBase = apk.name == "base.apk"
        val r = ApkPatcher.process(apk, out, key, if (isBase) loader.readBytes() else null, work)
        println("${apk.name}: signed" + if (isBase) ", appComponentFactory ${r.oldFactory} -> ${ApkPatcher.LOADER_CLASS}, added ${r.dexName}" else "")
        signed.add(out)
    }
    work.deleteRecursively()
    println("adb install-multiple " + signed.joinToString(" ") { it.path })
}
