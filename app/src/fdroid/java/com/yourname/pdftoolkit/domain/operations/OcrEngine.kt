package com.yourname.pdftoolkit.domain.operations

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Tesseract OCR Engine for F-Droid build.
 * Uses open-source Tesseract OCR library.
 */
class OcrEngine(private val context: Context) {
    
    private var tessBaseAPI: TessBaseAPI? = null
    private var isInitialized = false
    
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isInitialized) return@withContext true
            
            // Copy tessdata to app directory
            val tessDataPath = File(context.filesDir, "tessdata")
            if (!tessDataPath.exists()) {
                tessDataPath.mkdirs()
            }
            
            // Copy eng.traineddata from assets if not exists
            val trainedDataFile = File(tessDataPath, "eng.traineddata")
            if (!trainedDataFile.exists()) {
                context.assets.open("tessdata/eng.traineddata").use { input ->
                    FileOutputStream(trainedDataFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            
            tessBaseAPI = TessBaseAPI()
            val success = tessBaseAPI?.init(context.filesDir.absolutePath, "eng") == true
            isInitialized = success
            success
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    suspend fun recognizeText(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) {
                initialize()
            }
            
            tessBaseAPI?.setImage(bitmap)
            tessBaseAPI?.utF8Text ?: ""
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }
    
    fun close() {
        tessBaseAPI?.end()
        tessBaseAPI = null
        isInitialized = false
    }
}
