# Irminsul ProGuard Rules

# Keep native method declarations
-keepclassmembers class com.github.konkers.irminsul.** {
    native <methods>;
}

# Keep data classes used by JSON serialization
-keep class com.github.konkers.irminsul.**$CaptureStatsData { *; }

# Hilt
-dontwarn dagger.hilt.**

# Protobuf
-dontwarn com.google.protobuf.**
