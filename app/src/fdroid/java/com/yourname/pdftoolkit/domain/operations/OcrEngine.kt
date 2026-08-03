package com.yourname.pdftoolkit.domain.operations

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Tesseract OCR Engine for F-Droid build.
 * Uses open-source Tesseract OCR library with tessdata_fast English model.
 */
class OcrEngine(private val context: Context) {
    
    private var tessBaseAPI: TessBaseAPI? = null
    private var isInitialized = false
    
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isInitialized) return@withContext true
            
            val tessDataPath = File(context.filesDir, "tessdata")
            if (!tessDataPath.exists()) {
                tessDataPath.mkdirs()
            }
            
            val trainedDataFile = File(tessDataPath, "eng.traineddata")
            val assetFd = try {
                context.assets.openFd("tessdata/eng.traineddata")
            } catch (e: Exception) {
                null
            }
            val assetLength = assetFd?.length ?: -1L
            assetFd?.close()
            
            // If file does not exist or size does not match asset, copy/overwrite
            if (!trainedDataFile.exists() || (assetLength > 0 && trainedDataFile.length() != assetLength)) {
                Log.d(TAG, "Copying tessdata/eng.traineddata from assets to ${trainedDataFile.absolutePath} (asset size: $assetLength)")
                context.assets.open("tessdata/eng.traineddata").use { input ->
                    FileOutputStream(trainedDataFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            
            tessBaseAPI = TessBaseAPI()
            tessBaseAPI?.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
            val success = tessBaseAPI?.init(context.filesDir.absolutePath, "eng") == true
            isInitialized = success
            Log.d(TAG, "TessBaseAPI initialization result: $success for path ${context.filesDir.absolutePath}")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Tesseract OCR engine", e)
            false
        }
    }
    
    suspend fun recognizeText(bitmap: Bitmap): List<OcrWord> = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) {
                val initResult = initialize()
                if (!initResult) {
                    Log.e(TAG, "Failed to initialize TessBaseAPI before recognizeText")
                    return@withContext emptyList()
                }
            }

            // Ensure bitmap is in ARGB_8888 software format for Tesseract C++ engine
            val safeBitmap = if (bitmap.config != Bitmap.Config.ARGB_8888 || (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE)) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            }

            try {
                tessBaseAPI?.setImage(safeBitmap) ?: return@withContext emptyList()

                // Crucial: calling utF8Text triggers Tesseract recognition analysis before fetching resultIterator
                val fullText = tessBaseAPI?.utF8Text ?: ""
                Log.d(TAG, "Page text length: ${fullText.length}")
                if (fullText.isBlank()) {
                    return@withContext emptyList()
                }

                val iterator = tessBaseAPI?.resultIterator ?: run {
                    Log.w(TAG, "TessBaseAPI resultIterator returned null after utF8Text on image (${safeBitmap.width}x${safeBitmap.height})")
                    return@withContext emptyList()
                }

                val words = mutableListOf<OcrWord>()
                try {
                    do {
                        val text = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD) ?: continue
                        if (text.isBlank()) continue
                        val rect = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                        words.add(
                            OcrWord(
                                text = text,
                                boundingBox = rect?.let { OcrBoundingBox(it.left, it.top, it.right, it.bottom) },
                                confidence = iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                            )
                        )
                    } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                } finally {
                    iterator.delete()
                }
                Log.d(TAG, "Recognized ${words.size} words from bitmap (${safeBitmap.width}x${safeBitmap.height})")
                words
            } finally {
                if (safeBitmap != bitmap && !safeBitmap.isRecycled) {
                    safeBitmap.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error recognizing text with Tesseract", e)
            emptyList()
        }
    }
    
    fun close() {
        tessBaseAPI?.recycle()
        tessBaseAPI = null
        isInitialized = false
    }

    companion object {
        private const val TAG = "OcrEngine"
    }
}
