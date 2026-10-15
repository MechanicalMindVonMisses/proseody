import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// sherpa-onnx (Apache-2.0) ships its Android AAR only as a GitHub release asset, not on Maven.
// It is ~48 MB, so instead of committing it the build fetches it once and checks its SHA-256.
val sherpaVersion = "1.13.8"
val sherpaSha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
val sherpaAar = file("libs/sherpa-onnx-$sherpaVersion.aar")

fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

if (!sherpaAar.exists()) {
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
    logger.lifecycle("Downloading $url")
    sherpaAar.parentFile.mkdirs()
    val partial = File(sherpaAar.path + ".part")
    URI(url).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
    val actual = sha256(partial)
    if (actual != sherpaSha256) {
        partial.delete()
        throw GradleException("sherpa-onnx AAR checksum mismatch: expected $sherpaSha256, got $actual")
    }
    partial.renameTo(sherpaAar)
}

android {
    namespace = "dev.aliberke.sesli"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.aliberke.sesli"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // One APK per CPU: arm64 for the phone, x86_64 for the local emulator.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Personal sideload build: sign with the local debug key.
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
    buildFeatures {
        compose = true
    }
    packaging {
        // sherpa-onnx's JNI library only links libonnxruntime; its C/C++ API libraries are for other bindings.
        jniLibs {
            excludes += setOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
    implementation("com.google.guava:guava:33.3.1-android")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // On-device neural TTS (Kokoro, Piper); fetched above.
    implementation(files(sherpaAar))
    implementation("org.apache.commons:commons-compress:1.27.1")
}
