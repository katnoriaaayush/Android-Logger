plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sqa.logtest"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sqa.logtest"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // ── Platform signing ─────────────────────────────────────────────────────
    // This APK MUST be signed with the device's platform key to run as
    // android.uid.system (UID 1000). Without platform signing the sharedUserId
    // declaration is accepted by the package manager but the process still
    // gets a regular app UID, so cross-user log access won't work.
    //
    // Option A — AOSP test keys (works on emulator / AOSP builds):
    //   keytool -importkeystore -srckeystore platform.p12 -destkeystore platform.jks
    //   then fill in the block below.
    //
    // Option B — sign after build with apksigner:
    //   apksigner sign --key platform.pk8 --cert platform.x509.pem app-debug.apk
    //   (see sign_platform.sh at the project root)
    //
    // signingConfigs {
    //     create("platform") {
    //         storeFile     = file("../platform.jks")
    //         storePassword = "android"
    //         keyAlias      = "platform"
    //         keyPassword   = "android"
    //     }
    // }

    buildTypes {
        debug {
            isDebuggable = true
            // signingConfig = signingConfigs.getByName("platform")
        }
        release {
            isMinifyEnabled = false
            // signingConfig = signingConfigs.getByName("platform")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
    // NotificationCompat used in LogCaptureService
    implementation("androidx.core:core:1.12.0")
}
