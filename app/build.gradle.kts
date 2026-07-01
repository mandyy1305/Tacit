plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.antiwispr"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.antiwispr"
        // minSdk 30 (Android 11): MANAGE_EXTERNAL_STORAGE / all-files access is an
        // Android 11 feature and is the cleanest way to read WhatsApp's scoped-storage
        // .opus files. Pinning the floor here avoids legacy-storage branching.
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // sherpa-onnx ships native .so for several ABIs in its AAR; package only arm64 (real
        // device) to keep the APK small. Add "x86_64" here if you need to run on an emulator.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // On-device ASR: prebuilt sherpa-onnx Android AAR (bundles ONNX Runtime + JNI + Kotlin API).
    // Vendored at app/libs/sherpa-onnx.aar (k2-fsa release v1.13.3).
    implementation(files("libs/sherpa-onnx.aar"))
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}