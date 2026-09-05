package com.yourname.pdftoolkit.scan

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.net.Uri

/**
 * F-Droid stub: ML Kit document scanner is Play Services-only and
 * unavailable in the FOSS flavor. Camera + gallery flow is used instead.
 */
object SmartDocScanner {
    val isAvailable: Boolean = false

    fun startScan(
        activity: Activity,
        onLaunch: (IntentSender) -> Unit,
        onError: (Exception) -> Unit
    ) {
        onError(UnsupportedOperationException("Smart scan requires Google Play Services"))
    }

    fun parsePdfUri(data: Intent?): Uri? = null
}
