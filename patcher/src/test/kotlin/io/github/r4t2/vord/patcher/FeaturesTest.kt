package io.github.r4t2.vord.patcher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeaturesTest {
    private val core = "x\n  var FEATURES = { a: true };\ny".toByteArray()

    @Test
    fun injectsOnlyEnabledFlags() {
        val out = String(Features.inject(core, setOf("noPasskeys", "noProximity")))
        assertTrue(out.contains("noPasskeys: true"))
        assertTrue(out.contains("proximityGuard: true") && out.contains("proximityNever: true"))
        assertTrue(out.contains("integrity: false"))
        assertTrue(out.contains("commOverride: false"))
        assertTrue(out.startsWith("x\n") && out.endsWith("\ny"))
    }

    @Test
    fun everyFeatureFlagAppearsInTheRealCoreJs() {
        val real = java.io.File("../runtime/core.js")
        if (!real.exists()) return
        val text = real.readText()
        for (f in Features.all.flatMap { it.flags }) {
            assertTrue(text.contains("FEATURES.$f"), "core.js never reads FEATURES.$f")
        }
        assertEquals(1, Regex("var FEATURES = \\{[^}]*\\};").findAll(text).count())
    }
}
