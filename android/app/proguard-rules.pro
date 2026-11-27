# Keep line numbers and source file names for crash stack traces
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

# ------------------------------------------------------------------
# kotlinx.serialization
# ------------------------------------------------------------------
-keep,includedescriptorclasses class com.neekhar.pccontrol.**$$serializer { *; }
-keepclassmembers class com.neekhar.pccontrol.** {
    *** Companion;
}
-keepclasseswithmembers class com.neekhar.pccontrol.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class ** { *; }
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Navigation Compose type-safe routes
-keep class com.neekhar.pccontrol.navigation.** { *; }

# ------------------------------------------------------------------
# OkHttp & Okio
# ------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ------------------------------------------------------------------
# Firebase Messaging
# ------------------------------------------------------------------
-keep class com.google.firebase.messaging.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**

# ------------------------------------------------------------------
# WebRTC (org.webrtc / Stream WebRTC)
# ------------------------------------------------------------------
-keep class org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }
-keep class io.getstream.** { *; }
-keepclassmembers class io.getstream.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep @org.webrtc.CalledByNative class * { *; }
-keepclassmembers class * {
    @org.webrtc.CalledByNative <methods>;
    @org.webrtc.CalledByNative <fields>;
}
-dontwarn org.webrtc.**
-dontwarn io.getstream.**

# ------------------------------------------------------------------
# Media3 / ExoPlayer
# ------------------------------------------------------------------
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ------------------------------------------------------------------
# Coil 3
# ------------------------------------------------------------------
-keep class coil3.** { *; }
-dontwarn coil3.**
