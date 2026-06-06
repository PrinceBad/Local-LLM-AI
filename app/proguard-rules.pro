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
