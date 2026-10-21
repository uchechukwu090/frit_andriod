plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.parcelize")
}

import java.io.File
import java.io.FileInputStream
import java.util.Properties

// Bake the production backend address + auth token into the APK at build time.
// The token is read from ../../server/.env (gitignored), NOT committed in source,
// so it can still be rotated without touching app code. Override the URL with
// FRIT_BASE_URL in server/.env if you deploy the backend somewhere else.
fun loadServerEnv(): Map<String, String> {
    val envFile = rootProject.file("../server/.env")
    if (!envFile.exists()) {
        logger.warn("server/.env missing — using default FRIT_BASE_URL with empty AUTH_TOKEN")
        return emptyMap()
    }
    return envFile.readLines().mapNotNull { line ->
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#") || !t.contains("=")) null
        else {
            val i = t.indexOf("=")
            t.substring(0, i).trim() to t.substring(i + 1).trim().trim('"')
        }
    }.toMap()
}

val serverEnv = loadServerEnv()
val bakedBaseUrl = serverEnv["FRIT_BASE_URL"] ?: "https://frit-android.onrender.com"
val bakedAuthToken = serverEnv["AUTH_TOKEN"] ?: ""
if (bakedAuthToken.isEmpty()) {
    logger.warn("server/.env missing or AUTH_TOKEN empty — APK will bake an empty FRIT_AUTH_TOKEN")
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

android {
    namespace = "com.frit.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.frit.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // Production backend defaults baked at build time (from server/.env).
        buildConfigField("String", "FRIT_BASE_URL", "\"$bakedBaseUrl\"")
        buildConfigField("String", "FRIT_AUTH_TOKEN", "\"$bakedAuthToken\"")

        // Real devices are ARM64. x86_64 is emulator-only and added back in
        // debug builds below, so release APKs stay small enough to install
        // on 3GB-RAM phones (ABI filters merge across defaultConfig/buildType).
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildTypes {
        debug {
            // Emulator runs x86_64 — keep it for debug only.
            ndk {
                // F-48: the debug build normally compiles llama.cpp for BOTH
                // arm64-v8a and x86_64, which doubles the (slow) native build.
                // CI never runs an emulator, so it narrows the set with
                // `-PciAbis=arm64-v8a` and finishes in a fraction of the time.
                val ciAbis = (project.findProperty("ciAbis") as String?)
                    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                abiFilters += ciAbis ?: listOf("arm64-v8a", "x86_64")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Play upload: signing is injected from ../keystore.properties
            // (upload-key alias) when present. Without it, Gradle signs with
            // the debug key — fine for local install, NOT for Play upload.
            // See PLAYSTORE_RELEASE.md for the one-time key creation.
            val ksPropsFile = rootProject.file("keystore.properties")
            if (ksPropsFile.exists()) {
                val ksProps = Properties().apply {
                    ksPropsFile.inputStream().use { load(it) }
                }
                signingConfig = signingConfigs.maybeCreate("release").apply {
                    storeFile = rootProject.file(ksProps.getProperty("storeFile", "frit-upload.keystore"))
                    storePassword = ksProps.getProperty("storePassword", "")
                    keyAlias = ksProps.getProperty("keyAlias", "upload")
                    keyPassword = ksProps.getProperty("keyPassword", "")
                }
            }
        }
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    // A bundled GGUF (assets/models/*.gguf) must stay stored, not deflated:
    // it is unpacked at runtime by ModelManager like the Vosk model, and
    // compressing a 1.28GB asset would bloat build time and APK size.
    aaptOptions {
        noCompress += "gguf"
    }

    // Compile the llama.cpp JNI native library into the APK. Requires the NDK
    // (installed as 30.0.15729638). This is what makes real offline local-model
    // inference work — NativeLib.loadModel() runs llama.cpp on-device.
    ndkVersion = "30.0.15729638"
    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "4.1.2"
        }
    }
}

tasks.withType<com.android.build.gradle.tasks.ExternalNativeBuildTask> {
    // The native build compiles llama.cpp, which takes ~45 minutes for one ABI
    // on a fast machine and hours on a CI runner. It is therefore opt-out:
    //   -PskipNative   -> skip it (fast APK; the on-device model cannot run)
    //   -PciAbis=...   -> narrow it to one ABI
    // The Kotlin code and the unit tests do not need the .so, so the normal CI
    // gate builds without it and the native build is verified separately.
    enabled = file("src/main/jni/CMakeLists.txt").exists() && !project.hasProperty("skipNative")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    // F-26: Keystore-backed EncryptedSharedPreferences for the App PIN + admin token (SecureStore.kt)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Network
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Tests
    testImplementation("junit:junit:4.13.2")
    // The android.jar used for unit tests is a STUB: org.json's methods return
    // default values (null) instead of parsing. That made the SSE parser
    // tests fail with "optString(...) must not be null". The real org.json
    // implementation on the test classpath fixes it.
    testImplementation("org.json:json:20231013")

    // Montserrat font via Google Fonts
    implementation("androidx.compose.ui:ui-text-google-fonts")

    // CameraX
    val cameraxVersion = "1.4.0"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    // ML Kit face detection (bundled on-device model) — veil pickup
    // verification: face geometry + blink/turn liveness. Identity itself is
    // our own geometry+thumbnail match (FaceVerifier), no cloud involved.
    implementation("com.google.mlkit:face-detection:16.1.7")
    // OpenCV — continuous frame preprocessing (change detection, scaling, encode).
    // 5.0.0.1 = first artifact with 16 KB page-size aligned .so files
    // (Android 15+ Play requirement; 4.x prebuilts trip the 16KB warning).
    implementation("org.opencv:opencv:5.0.0.1")

    // Vosk Offline Speech Recognition (Maven Central group: com.alphacephei)
    implementation("com.alphacephei:vosk-android:0.3.75")

    // Coil image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
