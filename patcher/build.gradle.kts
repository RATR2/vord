import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure-JVM APK patching core (no Android APIs): binary manifest edit, zip rewrite/align, v2+v3
// signing. Runs on the Manager's device and on a desktop JVM, so it can be tested against real
// Discord APKs from the CLI (`./gradlew :patcher:run --args=...`).
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api("com.android.tools.build:apksig:9.4.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.87")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("io.github.r4t2.vord.patcher.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
