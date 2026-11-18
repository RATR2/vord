package io.github.r4t2.vord.patcher

/**
 * The selectable patches, in the spirit of ReVanced's patch list: each one has a name, a
 * description and a default. A selection is applied by rewriting the `var FEATURES = {...};` line
 * of the bundled core.js, so the flags are fixed when the APK is patched.
 */
data class Feature(
    val id: String,
    val name: String,
    val description: String,
    val defaultOn: Boolean,
    /** Flag names in core.js's FEATURES object that this patch switches. */
    val flags: List<String>,
)

object Features {
    val all: List<Feature> = listOf(
        Feature(
            "noPasskeys", "Hide passkey sign-in",
            "Removes the \"Log in with Passkey\" button and its OR divider. Passkeys cannot work in a re-signed app.",
            true, listOf("noPasskeys"),
        ),
        Feature(
            "integrity", "Stub Play Integrity",
            "Play Integrity requests fail cleanly instead of breaking. Nothing is ever forged.",
            true, listOf("integrity"),
        ),
        Feature(
            "voiceKeepsMedia", "Keep headset music quality in calls",
            "Stops Discord putting Android into phone-call mode, so Bluetooth headsets keep music quality and the phone mic is used.",
            true, listOf("commOverride"),
        ),
        Feature(
            "audioChooser", "Audio device chooser",
            "Separate Output and Microphone lists in the call's audio sheet (speaker, earpiece, headsets).",
            true, listOf("chooser"),
        ),
        Feature(
            "noProximity", "Never blank the screen in calls",
            "Ignores Discord's proximity-sensor screen-off, which flashes the screen when audio is not on the earpiece.",
            true, listOf("proximityGuard", "proximityNever"),
        ),
    )

    private val flagsLine = Regex("var FEATURES = \\{[^}]*\\};")

    /** Returns [core] with FEATURES set so only the [enabled] feature ids are on. */
    fun inject(core: ByteArray, enabled: Set<String>): ByteArray {
        val text = String(core, Charsets.UTF_8)
        require(flagsLine.containsMatchIn(text)) { "core.js has no FEATURES line" }
        val on = all.filter { it.id in enabled }.flatMap { it.flags }.toSet()
        val flags = all.flatMap { it.flags }.distinct()
            .joinToString(", ") { "$it: ${it in on}" }
        return flagsLine.replace(text) { "var FEATURES = { $flags };" }.toByteArray(Charsets.UTF_8)
    }
}
