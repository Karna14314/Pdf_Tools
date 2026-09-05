package com.yourname.pdftoolkit.scan

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * Play Store implementation: ML Kit document scanner with automatic
 * edge detection, editable crop borders and direct PDF output.
 */
object SmartDocScanner {
    val isAvailable: Boolean = true

    fun startScan(
        activity: Activity,
        onLaunch: (IntentSender) -> Unit,
        onError: (Exception) -> Unit
    ) {
        try {
            val options = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setPageLimit(100)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
            val client = GmsDocumentScanning.getClient(options)
            client.getStartScanIntent(activity)
                .addOnSuccessListener { intentSender: IntentSender -> onLaunch(intentSender) }
                .addOnFailureListener { e -> onError(e as? Exception ?: Exception(e)) }
        } catch (e: Exception) {
            onError(e)
        }
    }

    fun parsePdfUri(data: Intent?): Uri? {
        return try {
            GmsDocumentScanningResult.fromActivityResultIntent(data)?.pdf?.uri
        } catch (e: Exception) {
            null
        }
    }
}
