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
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Defines compression levels for PDF compression.
 * Uses progressively lower DPI and JPEG quality.
 */
enum class CompressionLevel(val dpi: Float, val jpegQuality: Float, val description: String) {
    LOW(150f, 0.80f, "Low - Minor size reduction"),
    MEDIUM(100f, 0.65f, "Medium - Balanced"),
    HIGH(72f, 0.50f, "High - Significant reduction"),
    MAXIMUM(50f, 0.35f, "Maximum - Smallest size")
}

/**
 * Result of a compression operation.
 */
data class CompressionResult(
    val originalSize: Long,
    val compressedSize: Long,
    val compressionRatio: Float,
    val timeTakenMs: Long,
    val pagesProcessed: Int
) {
    val savedBytes: Long get() = originalSize - compressedSize
    val savedPercentage: Float get() = if (originalSize > 0) (savedBytes.toFloat() / originalSize) * 100 else 0f
    val wasReduced: Boolean get() = compressedSize < originalSize
}

/**
 * Handles PDF compression operations.
 * 
 * Strategy: Re-renders each page as a JPEG image at reduced DPI.
 * This is the most reliable compression method for Android as it:
 * - Works with all PDF types (scanned, digital, mixed)
 * - Produces consistent, predictable compression results
 * - Uses native Android Bitmap for image handling
 * 
 * Trade-off: Text becomes non-searchable after compression.
 * For text-heavy PDFs where searchability is critical, recommend
 * using the original file or a lower compression level.
 */
class PdfCompressor {
    
    /**
     * Compress a PDF file by re-rendering pages as JPEG images.
     * 
     * @param context Android context
     * @param inputUri URI of the PDF to compress
     * @param outputStream Output stream for the compressed PDF
     * @param level Compression level (affects quality and size)
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
            onProgress(0.05f)
            
            // Get original file size
            val originalSize = getActualFileSize(context, inputUri)
            
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(
                    IllegalStateException("Cannot open input file")
                )
            
            inputDocument = PDDocument.load(inputStream)
            inputStream.close()
            
            val totalPages = inputDocument.numberOfPages
            
            if (totalPages == 0) {
                return@withContext Result.failure(
                    IllegalStateException("PDF has no pages")
                )
            }
            
            onProgress(0.1f)
            
            // Create new document
            outputDocument = PDDocument()
            val renderer = PDFRenderer(inputDocument)
            
            for (pageIndex in 0 until totalPages) {
                // Get original page dimensions for output
                val originalPage = inputDocument.getPage(pageIndex)
                val pageRect = originalPage.mediaBox ?: PDRectangle.A4
                
                // Render page to bitmap at compression DPI
                val bitmap = renderer.renderImageWithDPI(pageIndex, level.dpi)
                
                try {
                    // Create new page matching original dimensions
                    val newPage = PDPage(pageRect)
                    outputDocument.addPage(newPage)
                    
                    // Create compressed JPEG image
                    val pdImage = JPEGFactory.createFromImage(
                        outputDocument, 
                        bitmap, 
                        level.jpegQuality
                    )
                    
                    // Draw the compressed image to fill the page
                    PDPageContentStream(
                        outputDocument, 
                        newPage,
                        PDPageContentStream.AppendMode.OVERWRITE,
                        true, // compress content stream
                        true  // reset context
                    ).use { contentStream ->
                        contentStream.drawImage(pdImage, 0f, 0f, pageRect.width, pageRect.height)
                    }
                } finally {
                    bitmap.recycle()
                }
                
                val pageProgress = 0.1f + (0.8f * (pageIndex + 1).toFloat() / totalPages)
                onProgress(pageProgress)
            }
            
            onProgress(0.92f)
            
            // Write to a buffer first to measure size
            val buffer = ByteArrayOutputStream()
            outputDocument.save(buffer)
            val compressedBytes = buffer.toByteArray()
            val compressedSize = compressedBytes.size.toLong()
            
            onProgress(0.96f)
            
            // Write to actual output
            outputStream.write(compressedBytes)
            outputStream.flush()
            
            onProgress(1.0f)
            
            val timeTaken = System.currentTimeMillis() - startTime
            
            Result.success(
                CompressionResult(
                    originalSize = originalSize,
                    compressedSize = compressedSize,
                    compressionRatio = if (originalSize > 0) compressedSize.toFloat() / originalSize else 1f,
                    timeTakenMs = timeTaken,
                    pagesProcessed = totalPages
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
     * Get the actual file size in bytes using proper cursor query.
     */
    private fun getActualFileSize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIndex >= 0) {
                        cursor.getLong(sizeIndex)
                    } else {
                        // Fallback: read entire stream to count bytes
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            stream.readBytes().size.toLong()
                        } ?: 0L
                    }
                } else {
                    0L
                }
            } ?: 0L
        } catch (e: Exception) {
            // Last resort fallback
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.readBytes().size.toLong()
                } ?: 0L
            } catch (e2: Exception) {
                0L
            }
        }
    }
    
    /**
     * Estimate the compressed size based on compression level.
     * This is a rough estimate - actual results depend on PDF content.
     */
    fun estimateCompressedSize(originalSize: Long, level: CompressionLevel): Long {
        // More conservative estimates
        val reductionFactor = when (level) {
            CompressionLevel.LOW -> 0.80f
            CompressionLevel.MEDIUM -> 0.55f
            CompressionLevel.HIGH -> 0.35f
            CompressionLevel.MAXIMUM -> 0.25f
        }
        return (originalSize * reductionFactor).toLong()
    }
}
