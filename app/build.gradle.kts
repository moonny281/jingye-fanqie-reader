import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun resolveVersionName(): String {
    val propVer = project.findProperty("versionName")?.toString()?.trim()?.removePrefix("v")
    if (!propVer.isNullOrEmpty()) return propVer

    val rootVersionFile = rootProject.file("VERSION")
    if (rootVersionFile.isFile) {
        val fileVer = rootVersionFile.readText(Charsets.UTF_8).trim().lines().firstOrNull()?.trim()?.removePrefix("v")
        if (!fileVer.isNullOrEmpty()) return fileVer
    }
    return "0.3.0"
}

fun resolveVersionCode(verName: String): Int {
    val propCode = project.findProperty("versionCode")?.toString()?.trim()?.toIntOrNull()
    if (propCode != null && propCode > 0) return propCode

    val parts = verName.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 3 }
    val patch = parts.getOrElse(2) { 0 }
    return (major * 10000 + minor * 100 + patch).coerceAtLeast(1)
}

val appVersionName = resolveVersionName()
val appVersionCode = resolveVersionCode(appVersionName)

android {
    namespace = "app.jingye.reader"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.jingye.reader"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        val storePath = System.getenv("SIGNING_STORE_PATH")
        if (!storePath.isNullOrBlank() && File(storePath).isFile) {
            create("releaseCustom") {
                storeFile = File(storePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: ""
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("releaseCustom") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
