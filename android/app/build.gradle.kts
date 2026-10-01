plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// one version for all TV apps: the file VERSION in the repository root
val appVersion = rootProject.file("../VERSION").readText().trim()
val (major, minor, patch) = appVersion.split(".").map { it.toInt() }

android {
    namespace = "io.github.lumen.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.lumen.tv"
        minSdk = 23
        targetSdk = 35
        versionCode = major * 10000 + minor * 100 + patch
        versionName = appVersion
    }
    buildTypes {
        release {
            isMinifyEnabled = false
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

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
}
