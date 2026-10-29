import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---- Firebase: plugin włącza się dopiero, gdy wrzucisz app/google-services.json ----
val firebaseConfigured = file("google-services.json").exists()
if (firebaseConfigured) {
    apply(plugin = "com.google.gms.google-services")
} else {
    logger.warn("Gesty: brak app/google-services.json – logowanie Google wyłączone, apka działa bez konta.")
}

// ---- Web client ID i adres API: jedno miejsce, plik config.properties w katalogu projektu ----
val appConfig = Properties().apply {
    rootProject.file("config.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun cfg(key: String): String = (appConfig.getProperty(key) ?: "").trim()

// ---- Podpis release: keystore.properties (opcjonalnie), domyślnie ten sam klucz co debug ----
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.gesturecontrol.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gesturecontrol.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.6"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }

        buildConfigField("String", "WEB_CLIENT_ID", "\"${cfg("WEB_CLIENT_ID")}\"")
        buildConfigField("String", "API_BASE_URL", "\"${cfg("API_BASE_URL").trimEnd('/')}\"")
        buildConfigField("boolean", "FIREBASE_CONFIGURED", firebaseConfigured.toString())
    }

    // Stały klucz, żeby kolejne wersje instalowały się jako aktualizacja
    signingConfigs {
        getByName("debug") {
            storeFile = file("gesty-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            if (keystoreProps.getProperty("storeFile") != null) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            } else {
                storeFile = file("gesty-debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    // Modele MediaPipe muszą być nieskompresowane w APK
    androidResources { noCompress += "task" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")

    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // Logowanie Google: Credential Manager + Firebase Auth
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Komunikacja z serwerem
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

// Automatyczne pobranie modeli MediaPipe do assets przy pierwszym buildzie
val downloadModels by tasks.registering {
    val assetsDir = file("src/main/assets")
    val models = mapOf(
        "face_landmarker.task" to
            "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task",
        "gesture_recognizer.task" to
            "https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/latest/gesture_recognizer.task",
    )
    doLast {
        assetsDir.mkdirs()
        models.forEach { (name, url) ->
            val target = File(assetsDir, name)
            if (!target.exists() || target.length() < 10_000) {
                println("Pobieram model $name ...")
                URI(url).toURL().openStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadModels) }
