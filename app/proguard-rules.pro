# TACIT R8 / ProGuard keep rules.
#
# STATUS: staged, not yet active. Release minification is intentionally left OFF until a
# device-tested release run confirms the ASR (sherpa-onnx JNI) and LLM (MediaPipe GenAI)
# runtimes survive shrinking. R8 can strip classes reached only through JNI or reflection,
# which build cleanly but crash at runtime, so this must be validated on hardware first.
# When enabling minification, wire this file via proguardFiles in the release buildType.

# --- On-device ASR: sherpa-onnx (native methods + JNI-referenced Kotlin/Java classes) ---
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class com.k2fsa.sherpa.onnx.** {
    native <methods>;
}

# --- On-device LLM: MediaPipe Tasks GenAI (reflection + native) ---
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# --- Firebase (Auth + Messaging) ---
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# --- OkHttp / Okio ---
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# --- Keep all native method signatures across the app (capture / DSP JNI bridges) ---
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- Kotlin metadata + coroutines (safe defaults) ---
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-dontwarn kotlinx.coroutines.**
