// Release signing currently uses the AGP debug key (see manager/build.gradle.kts); a
// persistent release keystore is a pre-M1 item in ROADMAP.md.
plugins {
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
}

allprojects {
    group = "io.github.r4t2.vord"
    version = "0.0.1-snapshot"
}
