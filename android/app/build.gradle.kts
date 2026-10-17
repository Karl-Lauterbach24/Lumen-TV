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
        // -Pabis=armeabi-v7a: a smaller package for one kind of television
        (project.findProperty("abis") as String?)?.let { ndk { abiFilters += it.split(",") } }
    }
    buildTypes {
        // -Pmkvtest: a test build that installs beside the released app (own name, own data)
        if (project.hasProperty("mkvtest")) getByName("debug") {
            applicationIdSuffix = ".mkvtest"
            versionNameSuffix = "-mkvtest"
        }
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
    // MakeMKV (test): its Linux programs ship as lib*.so so they land, runnable, in the native library folder
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
    androidResources {
        noCompress += listOf("tar")
    }
}

// The page of the app is Lumen's receiver page (receiver/ in the Lumen repository: the same files Lumen
// serves to browsers), with this app's platform.js beside it. CI clones the Lumen repository next to
// this one ("lumen/"); another place: -Plumen.receiver=<path to its receiver folder>.
val receiverDir: File = (project.findProperty("lumen.receiver") as String?)?.let { File(it) }
    ?: rootProject.file("../lumen/receiver")
val receiverAssets = tasks.register<Copy>("receiverAssets") {
    onlyIf { receiverDir.isDirectory }
    from(receiverDir) { exclude("platform.js") }
    from(rootProject.file("platform.js"))
    into(layout.buildDirectory.dir("generated/receiver"))
    doFirst { if (!receiverDir.isDirectory) throw GradleException("Lumen's receiver folder not found: $receiverDir (see lumen.receiver)") }
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/receiver"))
if (project.hasProperty("mkvtest")) android.sourceSets.getByName("debug").res.srcDir("src/mkvtest-res")
tasks.named("preBuild") { dependsOn(receiverAssets) }

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.webkit:webkit:1.12.1")
    // Dolby, DTS and TrueHD decoded by the app (see libs/README.md)
    implementation(files("libs/media3-decoder-ffmpeg-1.5.1.aar"))
    implementation("org.apache.commons:commons-compress:1.26.1")
    // text recognition for the menu reader: only in builds that carry MakeMKV's files (it is looked up by name, see MenuReader)
    if (file("src/main/jniLibs/armeabi-v7a/libbdm001.so").exists() || file("src/main/jniLibs/arm64-v8a/libbdm001.so").exists())
        implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation("junit:junit:4.13.2")
}
