# Keep LiteRT LM API
-keep class com.google.ai.edge.litertlm.** { *; }
-keep interface com.google.ai.edge.litertlm.** { *; }

# Keep native methods and classes containing them
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep JNI callback methods (if any)
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# Keep coroutines safe
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep ModelPreset classes so reflection (like Gson) works if needed
-keep class com.example.auralocalai.data.ModelPreset { *; }
# Keep Kotlinx Serialization
-keepattributes *Annotation*,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <methods>;
}
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <init>(...);
}
-keepclassmembers class * implements kotlinx.serialization.KSerializer {
    *;
}
-keepclassmembers class * {
    *** Companion;
}
-keepnames class com.example.auralocalai.data.ChatSession { *; }
-keepnames class com.example.auralocalai.ui.ChatMessage { *; }
-keepnames class com.example.auralocalai.ui.InferenceTelemetry { *; }
-keepnames class com.example.auralocalai.ui.SearchSource { *; }

# Google ML Kit Text Recognition and Vision
-keep class com.google.mlkit.** { *; }
-keep interface com.google.mlkit.** { *; }
-keep class com.google.android.gms.tasks.** { *; }
-keep interface com.google.android.gms.tasks.** { *; }
-keep class com.google.android.gms.common.** { *; }
-keep class com.google.android.gms.dynamite.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**
