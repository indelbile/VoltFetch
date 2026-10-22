import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from environment variables (set by GitHub Actions from repository secrets).
// Without them, release builds are signed with the debug key so the build still works.
val ksFile = System.getenv("VOLTFETCH_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.voltfetch.app"
    // Compiled against Android 15 so the service can handle its background-time limit (onTimeout).
    // targetSdk stays 34 until the Android 15 edge-to-edge layout change has been checked on a device.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.voltfetch.app"
        minSdk = 29
        targetSdk = 34
        versionCode = (System.getenv("VERSION_CODE") ?: "4").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.1.1"
    }

    // Phones only: arm64-v8a (almost every phone) and armeabi-v7a (older 32-bit phones).
    // One APK per processor type keeps each download small; the embedded Python + FFmpeg differ per ABI.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    signingConfigs {
        if (ksFile != null) {
            create("release") {
                storeFile = ksFile
                storePassword = System.getenv("VOLTFETCH_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VOLTFETCH_KEY_ALIAS")
                keyPassword = System.getenv("VOLTFETCH_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (ksFile != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}


dependencies {
    // On-device yt-dlp + Python runtime and bundled FFmpeg. No companion computer/server.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    // runs the ad blocker's script before a page's own scripts (needed to remove YouTube video ads)
    implementation("androidx.webkit:webkit:1.12.1")
}
