plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
// This QA identity is intentionally separate from any future production signing key.
// A QA build must not silently fall back to the runner's debug certificate.
val qaStorePath = System.getenv("OPEN_MUSIC_QA_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val qaPassword = System.getenv("OPEN_MUSIC_QA_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val qaSigningReady = qaStorePath != null && qaPassword != null
if (gradle.startParameter.taskNames.any {
        it.endsWith("assembleQa", ignoreCase = true) ||
            it.endsWith("assembleQaOptimized", ignoreCase = true)
    } && !qaSigningReady) {
    throw GradleException("QA signing is not configured; refusing an unsigned QA APK")
}
android {
    namespace = "app.openmusic"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.openmusic"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    signingConfigs {
        if (qaSigningReady) {
            create("qa") {
                storeFile = file(qaStorePath!!)
                storePassword = qaPassword!!
                keyAlias = "openmusic-qa"
                keyPassword = qaPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            // Keep test builds unminified; optimize only the Play-targeted binary.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
        create("qa") {
            initWith(getByName("debug"))
            signingConfig = if (qaSigningReady) signingConfigs.getByName("qa") else null
            matchingFallbacks += listOf("debug")
        }
        // R8 test binary: same QA identity and certificate, release-like code.
        // Never sign this variant with a production key or upload it to Play.
        create("qaOptimized") {
            initWith(getByName("release"))
            signingConfig = if (qaSigningReady) signingConfigs.getByName("qa") else null
            matchingFallbacks += listOf("release")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
