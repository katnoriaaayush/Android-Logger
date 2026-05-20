plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sqa.logdaemon"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sqa.logdaemon"
        minSdk = 34   // Android 14+
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        externalNativeBuild {
            cmake {
                cFlags("-O2", "-Wall", "-Wextra")
            }
        }
        ndk {
            // Target the ABIs you expect on the device. Samsung Android 14
            // devices are arm64-v8a. Add armeabi-v7a if 32-bit support needed.
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // IMPORTANT: This APK must be signed with the platform key
    // to be installed as a system app. Configure your signing key here
    // or place the signed APK directly in /system/priv-app/LogDaemon/.
    //
    // signingConfigs {
    //     create("platform") {
    //         storeFile = file("path/to/platform.keystore")
    //         storePassword = "..."
    //         keyAlias = "platform"
    //         keyPassword = "..."
    //     }
    // }

    buildTypes {
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.core:core-ktx:1.12.0")
}
