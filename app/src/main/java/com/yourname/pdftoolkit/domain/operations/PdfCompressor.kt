package com.yourname.pdftoolkit.domain.operations

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * Defines compression levels for PDF compression.
 */
enum class CompressionLevel(val quality: Float, val dpi: Float) {
    LOW(0.85f, 150f),      // Slight compression, high quality
    MEDIUM(0.70f, 120f),   // Balanced
    HIGH(0.50f, 100f),     // Maximum compression, lower quality
    MAXIMUM(0.30f, 72f)    // Aggressive compression
}

/**
 * Result of a compression operation.
 */
data class CompressionResult(
    val originalSize: Long,
    val compressedSize: Long,
    val compressionRatio: Float,
    val timeTakenMs: Long
) {
    val savedBytes: Long get() = originalSize - compressedSize
    val savedPercentage: Float get() = if (originalSize > 0) (savedBytes.toFloat() / originalSize) * 100 else 0f
}

/**
 * Handles PDF compression operations.
 * Reduces PDF file size by re-rendering pages as compressed JPEG images.
 */
class PdfCompressor {
    
    /**
     * Compress a PDF file by re-rendering pages as compressed images.
     * This approach works well for scanned documents and image-heavy PDFs.
     * 
     * @param context Android context
     * @param inputUri URI of the PDF to compress
     * @param outputStream Output stream for the compressed PDF
     * @param level Compression level (affects quality and DPI)
     * @param onProgress Progress callback (0.0 to 1.0)
     * @return CompressionResult with size statistics
     */
    suspend fun compressPdf(
        context: Context,
        inputUri: Uri,
        outputStream: OutputStream,
        level: CompressionLevel = CompressionLevel.MEDIUM,
        onProgress: (Float) -> Unit = {}
    ): Result<CompressionResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var inputDocument: PDDocument? = null
        var outputDocument: PDDocument? = null
        
        try {
            // Get original file size
            val originalSize = getFileSize(context, inputUri)
            
            onProgress(0.05f)
            
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(
                    IllegalStateException("Cannot open input file")
                )
            
            inputDocument = PDDocument.load(inputStream)
            val totalPages = inputDocument.numberOfPages
            
            if (totalPages == 0) {
                return@withContext Result.failure(
                    IllegalStateException("PDF has no pages")
                )
            }
            
            onProgress(0.1f)
            
            // Create new document with compressed pages
            outputDocument = PDDocument()
            val renderer = PDFRenderer(inputDocument)
            
            for (pageIndex in 0 until totalPages) {
                // Render page to bitmap at reduced DPI
                val bitmap = renderer.renderImageWithDPI(pageIndex, level.dpi)
                
                try {
                    // Create new page with same dimensions as original
                    val originalPage = inputDocument.getPage(pageIndex)
                    val pageRect = originalPage.mediaBox ?: PDRectangle.A4
                    val newPage = PDPage(pageRect)
                    outputDocument.addPage(newPage)
                    
                    // Create compressed JPEG image from bitmap
                    val pdImage = JPEGFactory.createFromImage(outputDocument, bitmap, level.quality)
                    
                    // Draw the image to fill the entire page
                    PDPageContentStream(outputDocument, newPage).use { contentStream ->
                        contentStream.drawImage(pdImage, 0f, 0f, pageRect.width, pageRect.height)
                    }
                } finally {
                    bitmap.recycle()
                }
                
                // Update progress (10% to 90% for page processing)
                val pageProgress = 0.1f + (0.8f * (pageIndex + 1).toFloat() / totalPages)
                onProgress(pageProgress)
            }
            
            onProgress(0.95f)
            
            // Save the compressed document
            outputDocument.save(outputStream)
            
            onProgress(1.0f)
            
            val timeTaken = System.currentTimeMillis() - startTime
            
            Result.success(
                CompressionResult(
                    originalSize = originalSize,
                    compressedSize = originalSize, // Will be measured by caller
                    compressionRatio = 1.0f,
                    timeTakenMs = timeTaken
                )
            )
            
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            inputDocument?.close()
            outputDocument?.close()
        }
    }
    
    /**
     * Get the file size in bytes.
     */
    private fun getFileSize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.available().toLong()
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
    
    /**
     * Estimate the compressed size based on compression level.
     * This is a rough estimate and actual results may vary.
     */
    fun estimateCompressedSize(originalSize: Long, level: CompressionLevel): Long {
        val reductionFactor = when (level) {
            CompressionLevel.LOW -> 0.75f
            CompressionLevel.MEDIUM -> 0.50f
            CompressionLevel.HIGH -> 0.35f
            CompressionLevel.MAXIMUM -> 0.20f
        }
        return (originalSize * reductionFactor).toLong()
    }
}
