plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
            // APK without a secrets setup step. This key is regenerated per CI run, so releases
            // won't upgrade cleanly in place until a persistent release keystore replaces this
            // (tracked in ROADMAP.md before M1 ships anything users are expected to keep updated).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
