# ─────────────────────────────────────────────────────────────────────────────
# StreamTube Android TV — ProGuard / R8 rules
# ─────────────────────────────────────────────────────────────────────────────

# ── JNI Native Bridge ────────────────────────────────────────────────────────
# The Rust cdylib calls back into these classes via JNI name-mangling.
# If R8 renames or removes them the .so will crash with UnsatisfiedLinkError.
-keep class com.streamtube.tv.core.NativeBridge { *; }
-keep class com.streamtube.tv.core.** { *; }

# ── Kotlin Serialization (DTO models) ────────────────────────────────────────
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers @kotlinx.serialization.Serializable class * {
    static final kotlinx.serialization.descriptors.SerialDescriptor serialVersionUID;
    static final kotlinx.serialization.KSerializer INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn kotlinx.serialization.**

# ── Media3 / ExoPlayer ───────────────────────────────────────────────────────
# Renderers and extractors are loaded by class name at runtime.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-keep interface androidx.media3.common.** { *; }
-keepclassmembers class * extends androidx.media3.exoplayer.ExoPlayer { *; }

# ── Coil image loading ───────────────────────────────────────────────────────
-dontwarn coil.**
-keep class coil.** { *; }

# ── Kotlin coroutines / Flow ─────────────────────────────────────────────────
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ── OkHttp / Okio (pulled in by Coil) ───────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# ── ZXing QR code generator ──────────────────────────────────────────────────
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ── FileProvider (APK updater) ───────────────────────────────────────────────
-keep class androidx.core.content.FileProvider { *; }

# ── Suppress harmless missing-class warnings ─────────────────────────────────
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn java.lang.instrument.**
