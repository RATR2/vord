// Kotlin support is built into AGP 9.0+ - no separate org.jetbrains.kotlin.android plugin needed.
plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.r4t2.vord"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.r4t2.vord"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = project.version.toString()
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the auto-generated debug key for now so CI can produce an installable
            // APK without a secrets setup step (see ROADMAP.md: persistent release keystore).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // BouncyCastle and apksig ship duplicate metadata files
            excludes += setOf("META-INF/versions/**", "META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    implementation(project(":patcher"))
}

// The pieces that get injected into Discord ship inside the Manager as assets:
// loader.dex (the Loader, built by :loader) and core.js (runtime/core.js).
val vordAssets = layout.buildDirectory.dir("generated/vordAssets")
val copyVordAssets by tasks.registering(Copy::class) {
    dependsOn(":loader:loaderDex")
    from(rootProject.file("loader/build/dex/classes.dex")) { rename { "loader.dex" } }
    from(rootProject.file("runtime/core.js"))
    into(vordAssets)
}
android.sourceSets["main"].assets.srcDir(vordAssets.get().asFile)
tasks.named("preBuild") { dependsOn(copyVordAssets) }
