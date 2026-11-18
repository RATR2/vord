// Vord's Loader: the one class injected into Discord as classes4.dex. Plain Java compiled
// against android.jar only (it runs inside Discord's process, so it cannot depend on Vord's
// own libraries), then converted to a dex with D8 and published as `loader.dex`.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Signatures of Discord's own React Native classes, compile-time only (never in the dex).
val stubs: SourceSet = sourceSets.create("stubs")

val sdkDir: String = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    ?: rootProject.file("local.properties").takeIf { it.exists() }?.readLines()
        ?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=")
    ?: "${System.getProperty("user.home")}/Android/Sdk"

val androidJar: File = file("$sdkDir/platforms").listFiles()
    ?.filter { File(it, "android.jar").exists() }
    ?.maxByOrNull { it.name.substringAfter("android-").substringBefore('.').toIntOrNull() ?: 0 }
    ?.let { File(it, "android.jar") }
    ?: error("No android.jar under $sdkDir/platforms (set ANDROID_HOME)")

val d8: Configuration by configurations.creating

dependencies {
    compileOnly(files(androidJar))
    compileOnly(stubs.output)
    "stubsCompileOnly"(files(androidJar))
    d8("com.android.tools:r8:9.5.33")
}

val loaderDex by tasks.registering(JavaExec::class) {
    val outDir = layout.buildDirectory.dir("dex")
    val classesDir = tasks.compileJava.flatMap { it.destinationDirectory }
    dependsOn(tasks.compileJava)
    inputs.dir(classesDir)
    outputs.dir(outDir)
    classpath = d8
    mainClass.set("com.android.tools.r8.D8")
    doFirst {
        val classFiles = classesDir.get().asFileTree.matching { include("**/*.class") }.files
            .map { it.absolutePath }
        args(
            listOf("--release", "--min-api", "24", "--lib", androidJar.absolutePath,
                "--output", outDir.get().asFile.also { it.mkdirs() }.absolutePath) + classFiles
        )
    }
}

tasks.assemble { dependsOn(loaderDex) }
