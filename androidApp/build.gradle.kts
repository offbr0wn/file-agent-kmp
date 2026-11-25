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
    // Fixed debug key (decoded by CI from debug.keystore.b64) so every build can
    // update the app already on the phone. Falls back to the normal debug key locally.
    signingConfigs {
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
    lint { checkReleaseBuilds = false; abortOnError = false }
    buildFeatures { compose = true }
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
}
