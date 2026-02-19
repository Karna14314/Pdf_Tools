package com.yourname.pdftoolkit.domain.operations

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * ML Kit OCR Engine for Play Store build.
 * Uses Google ML Kit Text Recognition.
 */
class OcrEngine(private val context: Context) {
    
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    
    suspend fun initialize(): Boolean {
        // ML Kit doesn't require explicit initialization
        return true
    }
    
    suspend fun recognizeText(bitmap: Bitmap): String {
        return suspendCancellableCoroutine { continuation ->
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            
            recognizer.process(inputImage)
                .addOnSuccessListener { text ->
                    continuation.resume(text.text)
                }
                .addOnFailureListener {
                    continuation.resume("")
                }
        }
    }
    
    fun close() {
        recognizer.close()
    }
}
