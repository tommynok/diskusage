# Keep all application code intact
-keep class com.google.android.diskusage.** { *; }
-keepclassmembers class com.google.android.diskusage.** { *; }

# Keep AndroidX annotations
-keepattributes *Annotation*
-dontwarn **
