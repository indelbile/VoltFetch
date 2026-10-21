# VoltFetch's own code uses no reflection.
-dontwarn com.sun.net.httpserver.**

# Embedded yt-dlp / FFmpeg (youtubedl-android): loaded and unpacked partly through reflection,
# Jackson and Apache Commons Compress. Keep them whole so release (minified) builds behave like debug.
-keep class com.yausername.** { *; }
-keep class org.apache.commons.compress.archivers.zip.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.**
-dontwarn java.beans.**
