# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /usr/local/lib/android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.

# PDFBox-Android
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**

# Apache POI for Office documents
-keep class org.apache.poi.** { *; }
-dontwarn org.apache.poi.**
-keep class org.apache.xmlbeans.** { *; }
-dontwarn org.apache.xmlbeans.**
-keep class org.openxmlformats.schemas.** { *; }
-dontwarn org.openxmlformats.schemas.**
-keep class com.microsoft.schemas.** { *; }
-dontwarn com.microsoft.schemas.**

# Apache Commons (used by POI)
-keep class org.apache.commons.** { *; }
-dontwarn org.apache.commons.**

# XML parsing
-keep class javax.xml.** { *; }
-dontwarn javax.xml.**

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
-dontwarn sun.misc.Unsafe
-dontwarn org.slf4j.**
-dontwarn org.apache.logging.log4j.**

# ETSI XML Signature (referenced by POI but not used on Android)
-dontwarn org.etsi.uri.x01903.v13.**
-dontwarn org.etsi.uri.**
-dontwarn org.w3.x2000.x09.xmldsig.**

# Jetpack Compose (Generally validated by R8 but good to be safe)
-keepattributes *Annotation*

# Keep OOXML schemas
-keepclassmembers class * extends org.apache.xmlbeans.XmlObject {
    public static ** Factory;
}

# Glide image loading library
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
    <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-keep class com.bumptech.glide.load.data.ParcelFileDescriptorRewinder$InternalRewinder {
    *** rewind();
}

# uCrop image cropping library
-dontwarn com.yalantis.ucrop**
-keep class com.yalantis.ucrop** { *; }
-keep interface com.yalantis.ucrop** { *; }

# ML Kit (PlayStore flavor only)
-keep class com.google.mlkit.vision.text.** { *; }
-keep class com.google.mlkit.vision.common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_vision_text.**

# Room Database
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-keepclassmembers class * {
    @androidx.room.Query <methods>;
    @androidx.room.Insert <methods>;
    @androidx.room.Update <methods>;
    @androidx.room.Delete <methods>;
}
-dontwarn androidx.room.paging.**

# DataStore Preferences
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}
-keep class androidx.datastore.core.** { *; }
-dontwarn androidx.datastore.**

# PDF Viewer (Jetpack)
-keep class androidx.pdf.** { *; }
-keep class androidx.pdf.viewer.** { *; }
-keep class androidx.pdf.document.** { *; }
-keep class androidx.pdf.ink.** { *; }
-dontwarn androidx.pdf.**

# Ink/Annotation libraries
-keep class androidx.ink.** { *; }
-keep class androidx.ink.brush.** { *; }
-keep class androidx.ink.geometry.** { *; }
-keep class androidx.ink.rendering.** { *; }
-keep class androidx.ink.strokes.** { *; }
-dontwarn androidx.ink.**

# Play In-App Review API
-keep class com.google.android.play.core.** { *; }
-keep class com.google.android.play.core.review.** { *; }
-dontwarn com.google.android.play.core.**

# AppCompat for uCrop
-keep class androidx.appcompat.** { *; }
-dontwarn androidx.appcompat.**
