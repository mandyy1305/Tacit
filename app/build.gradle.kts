plugins {
    alias(libs.plugins.android.application)
    // Compose compiler; pairs with AGP 9's built-in Kotlin (no org.jetbrains.kotlin.android needed).
    alias(libs.plugins.kotlin.compose)
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
    buildFeatures {
        compose = true
        // BuildConfig.DEBUG gates release-only behaviour (e.g. diagnostic tree dumping off in
        // release, per the accessibility disclosure).
        buildConfig = true
    }
}

dependencies {
    // On-device ASR: prebuilt sherpa-onnx Android AAR (bundles ONNX Runtime + JNI + Kotlin API).
    // Vendored at app/libs/sherpa-onnx.aar (k2-fsa release v1.13.3).
    implementation(files("libs/sherpa-onnx.aar"))
    // On-device LLM for transcript summaries (MediaPipe LLM Inference; Qwen2.5-1.5B .task
    // downloaded at runtime like the Whisper model).
    implementation(libs.mediapipe.tasks.genai)

    // TACIT Cloud: Firebase Auth is the identity layer only (initialized manually from
    // assets/google-services.json — no google-services Gradle plugin, which fights AGP 9);
    // Credential Manager drives Google sign-in; OkHttp talks to the tacit-cloud Go server.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    // Cloud Messaging: server pushes "transcript ready" when a long-note batch job finishes.
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.okhttp)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // Explicit: OverlayComposeWindow implements SavedStateRegistryOwner for the
    // WindowManager-hosted ComposeView (no Activity in the overlay path).
    implementation(libs.androidx.savedstate.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
