# Keep the JNI bridge for the on-device LLM (NativeLib).
-keepclasseswithmembers class com.frit.app.core.llm.NativeLib {
    native <methods>;
}
-keep class com.frit.app.core.llm.NativeLib { *; }

# Keep model download metadata / progress callback data classes used via reflection.
-keep class com.frit.app.core.llm.ModelManager$DownloadProgress { *; }

# Compose / runtime already ship default rules via proguard-android-optimize,
# but keep all our own app classes stable to avoid stripping critical enums/parsers.
-keep class com.frit.app.** { *; }
-keepclassmembers class com.frit.app.** { *; }

# Keep JSON parsing classes used reflectively by org.json.
-keep class org.json.** { *; }

# OkHttp certificate pinning + TLS should never be stripped of annotations.
-keepattributes *Annotation*
