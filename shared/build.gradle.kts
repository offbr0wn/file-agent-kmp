plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget()
    // Add iosArm64() etc. later if you go cross-platform.

    sourceSets {
        commonMain.dependencies {
            api(libs.coroutines.core)
            api(libs.serialization.json)
            api(libs.ktor.client.core)
        }
        androidMain.dependencies {
            api(libs.ktor.client.okhttp)
            // SMB client for NAS shares
            implementation(libs.smbj)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
    jvmToolchain(17)
}

android {
    namespace = "dev.fileagent.shared"
    compileSdk = 36
    defaultConfig { minSdk = 30 }
}
