# Keep all application code intact
-keep class com.google.android.diskusage.** { *; }
-keepclassmembers class com.google.android.diskusage.** { *; }

# Keep Shizuku classes
-keep class moe.shizuku.** { *; }
-keep interface moe.shizuku.** { *; }
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }

# Keep Porter classes
-keep class eu.darken.porter.** { *; }
-keep interface eu.darken.porter.** { *; }

# Keep AndroidX annotations
-keepattributes *Annotation*
-dontwarn **
