# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /usr/local/lib/android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.

# PDFBox-Android
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# AndroidX DocumentFile
-keep class androidx.documentfile.** { *; }

# General
-dontwarn java.awt.**
-dontwarn javax.imageio.**

# Jetpack Compose (Generally validated by R8 but good to be safe)
-keepattributes *Annotation*
