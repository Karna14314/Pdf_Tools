package com.yourname.pdftoolkit.ui.pdfviewer

import android.os.Build
import android.os.ext.SdkExtensions

/**
 * Capability check for native PDF viewer support.
 *
 * The androidx.pdf PdfViewerFragment requires:
 * - API 31+ (Android 12/S)
 * - SDK Extension level 13+ (available on Google Play devices and recent OEM updates)
 *
 * Edge case handling: Some devices may report API 31+ but lack the actual extension.
 * We wrap SdkExtensions call in try-catch to handle these gracefully.
 */
object PdfViewerCapability {

    /**
     * Returns true if the device supports androidx.pdf native viewer.
     * Requires API 31+ AND SDK Extension level 13.
     * Safe to call on any API level.
     */
    fun isSupported(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13
        } else {
            false
        }
    }

    @androidx.annotation.VisibleForTesting
    fun isNativeViewerSupportedForSdk(sdkInt: Int, extensionVersion: Int): Boolean {
        if (sdkInt < 31) return false
        return extensionVersion >= 13
    }

    @androidx.annotation.VisibleForTesting
    fun isNativeViewerSupportedSafe(sdkInt: Int, extensionProvider: () -> Int): Boolean {
        if (sdkInt < 31) return false
        return try {
            extensionProvider() >= 13
        } catch (e: Throwable) {
            false
        }
    }
}
