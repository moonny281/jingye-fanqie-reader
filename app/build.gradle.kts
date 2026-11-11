import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun readProjectVersionName(): String {
    val propVer = project.findProperty("appVersionName")?.toString()?.trim()
    if (!propVer.isNullOrEmpty()) return propVer.removePrefix("v").removePrefix("V")
    val envVer = System.getenv("APP_VERSION_NAME")?.trim()
    if (!envVer.isNullOrEmpty()) return envVer.removePrefix("v").removePrefix("V")
    val versionFile = rootProject.file("VERSION")
    if (versionFile.isFile) {
        val fileVer = versionFile.readText().trim()
        if (fileVer.isNotEmpty()) return fileVer.removePrefix("v").removePrefix("V")
    }
    return "0.3.1"
}

fun computeVersionCode(verName: String): Int {
    val propCode = project.findProperty("appVersionCode")?.toString()?.trim()?.toIntOrNull()
    if (propCode != null && propCode > 0) return propCode
    val envCode = System.getenv("APP_VERSION_CODE")?.trim()?.toIntOrNull()
    if (envCode != null && envCode > 0) return envCode
    val parts = verName.split(".").map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 3 }
    val patch = parts.getOrElse(2) { 1 }
    return (major * 10000 + minor * 100 + patch).coerceAtLeast(1)
}

val resolvedVersionName = readProjectVersionName()
val resolvedVersionCode = computeVersionCode(resolvedVersionName)

android {
    namespace = "app.jingye.reader"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.jingye.reader"
        minSdk = 26
        targetSdk = 34
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("KEYSTORE_PATH")
            if (!ksPath.isNullOrBlank() && File(ksPath).exists()) {
                storeFile = File(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
            val ksPath = System.getenv("KEYSTORE_PATH")
            signingConfig = if (!ksPath.isNullOrBlank() && File(ksPath).exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
