package io.github.r4t2.vord

import android.content.Context
import io.github.r4t2.vord.patcher.ApkPatcher
import io.github.r4t2.vord.patcher.Features
import java.io.File

/**
 * The patching pipeline, step by step like ReVanced's Session: read, prepare the key, patch the
 * base APK, re-sign the splits. The heavy lifting is in the :patcher module (plain JVM code).
 */
class PatchRunner(private val ctx: Context) {
    enum class State { PENDING, RUNNING, DONE, FAILED }

    interface Listener {
        fun step(index: Int, state: State, note: String = "")
        fun log(line: String)
    }

    companion object {
        val STEPS = listOf("Read Discord", "Prepare signing key", "Patch base APK", "Re-sign splits", "Install")
        const val PATCH_STEPS = 4
    }

    /** Returns the patched, signed APKs (base first). Throws on failure after reporting the step. */
    fun run(inputs: List<File>, enabled: Set<String>, l: Listener): List<File> {
        var current = 0
        try {
            current = 0
            l.step(0, State.RUNNING)
            val base = inputs.firstOrNull { it.name == "base.apk" } ?: error("No base.apk among the inputs")
            val splits = inputs.filter { it != base }
            l.log("${inputs.size} APKs, ${inputs.sumOf { it.length() } / 1_048_576} MB")
            l.step(0, State.DONE, "${inputs.size} APKs")

            current = 1
            l.step(1, State.RUNNING, if (Keys.exists(ctx)) "" else "creating a new key, this takes a few seconds")
            val key = Keys.load(ctx)
            l.log("Signing key ${Keys.fingerprint(key)}")
            l.step(1, State.DONE, Keys.fingerprint(key))

            val out = File(ctx.cacheDir, "vord/out").also { it.deleteRecursively(); it.mkdirs() }
            val work = File(ctx.cacheDir, "vord/work").also { it.mkdirs() }
            val dex = ctx.assets.open("loader.dex").use { it.readBytes() }
            val core = Features.inject(ctx.assets.open("core.js").use { it.readBytes() }, enabled)
            l.log("Patches: ${enabled.sorted().joinToString()}")

            current = 2
            l.step(2, State.RUNNING)
            val rename = ApkPatcher.Rename(Sources.PATCHED)
            val r = ApkPatcher.process(base, File(out, "base.apk"), key, dex, work, rename, core)
            l.log("appComponentFactory ${r.oldFactory} -> ${ApkPatcher.LOADER_CLASS}, ${r.dexName} added, package ${r.originalPackage} -> ${Sources.PATCHED}")
            l.step(2, State.DONE)

            current = 3
            l.step(3, State.RUNNING, "${splits.size} splits")
            for (s in splits) {
                ApkPatcher.process(s, File(out, s.name), key, null, work, rename, null)
                l.log("signed ${s.name}")
            }
            l.step(3, State.DONE)
            work.deleteRecursively()
            return listOf(File(out, "base.apk")) + splits.map { File(out, it.name) }
        } catch (e: Throwable) {
            l.step(current, State.FAILED, e.message ?: e.javaClass.simpleName)
            l.log("FAILED: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }
}
