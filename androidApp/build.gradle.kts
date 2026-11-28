plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.fileagent.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.fileagent.app"
        minSdk = 30 // All-files access needs Android 11+
        targetSdk = 36
        // The CI build number is the version, so the app can tell when a newer build exists.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.0.$build"
        // Modern phones are all 64-bit ARM. Shipping only that keeps the download small.
        ndk { abiFilters += "arm64-v8a" }
    }
    // Two versions of the app:
    // - github: the APK on GitHub releases. Updates itself, takes card payments by Stripe link.
    // - play: Google Play. Play updates it, payments go through Google Play Billing.
    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
            buildConfigField("boolean", "SELF_UPDATE", "true")
            buildConfigField("String", "STORE", "\"github\"")
        }
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "SELF_UPDATE", "false")
            buildConfigField("String", "STORE", "\"play\"")
        }
    }
    // Fixed debug key (decoded by CI from debug.keystore.b64) so every build can
    // update the app already on the phone. Falls back to the normal debug key locally.
    signingConfigs {
        // Play upload key (decoded by CI from PLAY_UPLOAD_KEYSTORE_B64). Google re-signs the app
        // with its own key, so this one only proves an upload came from us.
        create("upload") {
            val ks = file("upload.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = System.getenv("PLAY_UPLOAD_PASSWORD") ?: ""
                keyAlias = "upload"
                keyPassword = System.getenv("PLAY_UPLOAD_PASSWORD") ?: ""
            }
        }
        getByName("debug") {
            val ks = file("debug.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    // People get the release build: it can't be attached to with a debugger to read app data.
    // It is signed with the same fixed key, so it updates over earlier builds.
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }
    // The Play version is signed with the upload key when CI has it.
    if (file("upload.keystore").exists()) {
        productFlavors.getByName("play").signingConfig = signingConfigs.getByName("upload")
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources {
            // Duplicate metadata from the SMB library's crypto dependencies.
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.smbj)
    implementation(libs.litertlm) // on-phone AI
    implementation(libs.smbj.rpc)
    implementation(project(":shared"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.work.runtime)
    implementation(libs.coroutines.core)
    "playImplementation"(libs.billing) // Google Play Billing, Play version only
}
