package com.yourname.pdftoolkit.domain.operations

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Defines compression levels for PDF compression.
 * Different levels use different strategies based on content type.
 */
enum class CompressionLevel(val dpi: Float, val jpegQuality: Float, val description: String) {
    LOW(150f, 0.85f, "Low - Minor size reduction"),
    MEDIUM(120f, 0.70f, "Medium - Balanced"),
    HIGH(96f, 0.55f, "High - Significant reduction"),
    MAXIMUM(72f, 0.40f, "Maximum - Smallest size")
}

/**
 * Compression strategy based on PDF content analysis.
 */
enum class CompressionStrategy {
    IMAGE_OPTIMIZATION,  // Optimize embedded images only (best for text-heavy PDFs)
    FULL_RERENDER       // Re-render as images (best for scanned/image-heavy PDFs)
}

/**
 * Result of a compression operation.
 */
data class CompressionResult(
    val originalSize: Long,
    val compressedSize: Long,
    val compressionRatio: Float,
    val timeTakenMs: Long,
    val pagesProcessed: Int,
    val strategyUsed: CompressionStrategy = CompressionStrategy.IMAGE_OPTIMIZATION
) {
    val savedBytes: Long get() = originalSize - compressedSize
    val savedPercentage: Float get() = if (originalSize > 0) (savedBytes.toFloat() / originalSize) * 100 else 0f
    val wasReduced: Boolean get() = compressedSize < originalSize
}

data class CompressionProfile(
    val dpi: Float,
    val jpegQuality: Float,
    val scaleFactor: Float
)

/**
 * Handles PDF compression operations with smart strategy selection.
 * 
 * Strategy Selection:
 * 1. IMAGE_OPTIMIZATION: Compresses embedded images without re-rendering pages.
 *    Best for: Text-heavy PDFs, PDFs with high-quality images, documents.
 * 
 * 2. FULL_RERENDER: Re-renders each page as a compressed JPEG.
 *    Best for: Scanned documents, image-heavy PDFs, PDFs with complex graphics.
 * 
 * The compressor tries IMAGE_OPTIMIZATION first, and if it doesn't achieve
 * good compression, falls back to FULL_RERENDER. It always compares the
 * result to the original and keeps the smaller version.
 */
class PdfCompressor {
    
    /**
     * Compress a PDF file using the optimal strategy.
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
        qualityPercent: Int? = null,
        onProgress: (Float) -> Unit = {}
    ): Result<CompressionResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var tempFile: File? = null
        val profile = profileFromSlider(qualityPercent) ?: profileFromLevel(level)
        
        try {
            onProgress(0.05f)
            
            var detectedPageCount: Int? = null

            // Create a temp file to avoid loading everything into memory
            val cacheDir = File(context.cacheDir, "compress_cache")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            tempFile = File(cacheDir, "temp_compress_${System.currentTimeMillis()}.pdf")

            // Copy URI content to temp file
            context.contentResolver.openInputStream(inputUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Result.failure(
                IllegalStateException("Cannot open input file")
            )
            
            val originalSize = tempFile.length()
            
            // Skip compression for very small files (< 50KB)
            if (originalSize < 50 * 1024) {
                onProgress(1.0f)
                tempFile.inputStream().use { input ->
                    input.copyTo(outputStream)
                }
                outputStream.flush()
                return@withContext Result.success(
                    CompressionResult(
                        originalSize = originalSize,
                        compressedSize = originalSize,
                        compressionRatio = 1f,
                        timeTakenMs = System.currentTimeMillis() - startTime,
                        pagesProcessed = countPages(tempFile),
                        strategyUsed = CompressionStrategy.IMAGE_OPTIMIZATION
                    )
                )
            }
            
            onProgress(0.10f)
            
            // Try image optimization first (preserves text quality)
            // Note: tryImageOptimization writes to a temp file internally if successful
            val optimizedFile = tryImageOptimization(context, tempFile, profile, onProgress)
            
            onProgress(0.55f)
            
            // Try full re-render approach for potentially better compression
            val rerenderedFile = tryFullRerender(context, tempFile, profile) { progress ->
                onProgress(0.55f + progress * 0.35f)
            }, { count ->
                if (detectedPageCount == null) detectedPageCount = count
            })
            
            onProgress(0.90f)
            
            // Collect all valid compression results
            // List of Pair<File, Strategy>
            val candidates = mutableListOf<Pair<File, CompressionStrategy>>()
            
            if (optimizedFile != null && optimizedFile.length() < originalSize) {
                candidates.add(optimizedFile to CompressionStrategy.IMAGE_OPTIMIZATION)
            }
            if (rerenderedFile != null && rerenderedFile.length() < originalSize) {
                candidates.add(rerenderedFile to CompressionStrategy.FULL_RERENDER)
            }
            
            // Select the smallest result that's still smaller than original
            val bestMatch = if (candidates.isNotEmpty()) {
                candidates.minByOrNull { it.first.length() }!!
            } else {
                null
            }
            
            val finalFile = bestMatch?.first ?: tempFile
            val strategyUsed = bestMatch?.second ?: CompressionStrategy.IMAGE_OPTIMIZATION

            onProgress(0.95f)
            
            // Write the best result to output
            finalFile.inputStream().use { input ->
                input.copyTo(outputStream)
            }
            outputStream.flush()
            
            onProgress(1.0f)
            
            val timeTaken = System.currentTimeMillis() - startTime
            val pagesProcessed = detectedPageCount ?: countPages(finalFile)
            val compressedSize = finalFile.length()

            // Clean up temporary result files
            if (optimizedFile != null && optimizedFile != finalFile) optimizedFile.delete()
            if (rerenderedFile != null && rerenderedFile != finalFile) rerenderedFile.delete()
            // We delete finalFile later if it was a temp file created by optimization
            if (finalFile != tempFile) finalFile.delete()
            
            Result.success(
                CompressionResult(
                    originalSize = originalSize,
                    compressedSize = compressedSize,
                    compressionRatio = if (originalSize > 0) compressedSize.toFloat() / originalSize else 1f,
                    timeTakenMs = timeTaken,
                    pagesProcessed = pagesProcessed,
                    strategyUsed = strategyUsed
                )
            )
            
        } catch (e: OutOfMemoryError) {
            Result.failure(
                IllegalStateException(
                    "Compression failed due to low memory. Try closing other apps or lowering compression level.",
                    e
                )
            )
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException(
                    e.message ?: "Compression failed. The PDF may be encrypted or unsupported.",
                    e
                )
            )
        } finally {
            tempFile?.delete()
        }
    }
    
    /**
     * Try to compress by optimizing embedded images only.
     * This preserves text quality and searchability.
     */
    private fun tryImageOptimization(
        context: Context,
        inputFile: File,
        profile: CompressionProfile,
        onProgress: (Float) -> Unit
    ): File? {
        var document: PDDocument? = null
        val outputFile = File(context.cacheDir, "opt_${System.currentTimeMillis()}.pdf")
        
        return try {
            // Use MemoryUsageSetting to enable temp file buffering instead of full memory load
            document = PDDocument.load(inputFile, MemoryUsageSetting.setupTempFileOnly())
            val totalPages = document.numberOfPages
            onPageCountAvailable(totalPages)
            
            if (totalPages == 0) return null
            
            var imagesOptimized = 0
            
            // Process each page
            for (pageIndex in 0 until totalPages) {
                val page = document.getPage(pageIndex)
                val resources = page.resources ?: continue
                
                // Optimize images in this page
                imagesOptimized += optimizePageImages(document, resources, profile)
                
                val pageProgress = 0.10f + (0.45f * (pageIndex + 1).toFloat() / totalPages)
                onProgress(pageProgress)
            }
            
            document.save(outputFile)
            outputFile
            
        } catch (e: Exception) {
            outputFile.delete()
            null
        } finally {
            document?.close()
        }
    }
    
    /**
     * Optimize images in a page's resources.
     * Returns the number of images optimized.
     */
    private fun optimizePageImages(
        document: PDDocument,
        resources: PDResources,
        profile: CompressionProfile
    ): Int {
        var optimizedCount = 0
        
        try {
            val xObjectNames = resources.xObjectNames?.toList() ?: return 0
            
            for (name in xObjectNames) {
                try {
                    val xObject = resources.getXObject(name)
                    
                    if (xObject is PDImageXObject) {
                        val optimized = optimizeImage(document, xObject, profile)
                        if (optimized != null) {
                            resources.put(name, optimized)
                            optimizedCount++
                        }
                    }
                } catch (e: Exception) {
                    // Skip this image if we can't process it
                    continue
                }
            }
        } catch (e: Exception) {
            // Resources iteration failed
        }
        
        return optimizedCount
    }
    
    /**
     * Optimize a single image by recompressing it as JPEG.
     */
    private fun optimizeImage(
        document: PDDocument,
        image: PDImageXObject,
        profile: CompressionProfile
    ): PDImageXObject? {
        return try {
            // Get the image as bitmap
            val originalImage = image.image ?: return null
            
            // Calculate target dimensions based on compression level
            val scaleFactor = profile.scaleFactor
            
            val targetWidth = (originalImage.width * scaleFactor).toInt().coerceAtLeast(100)
            val targetHeight = (originalImage.height * scaleFactor).toInt().coerceAtLeast(100)
            
            // Create scaled bitmap
            val scaledBitmap = if (scaleFactor < 1.0f) {
                Bitmap.createScaledBitmap(originalImage, targetWidth, targetHeight, true)
            } else {
                originalImage
            }
            
            try {
                // Create JPEG with specified quality
                JPEGFactory.createFromImage(document, scaledBitmap, profile.jpegQuality)
            } finally {
                if (scaledBitmap != originalImage) {
                    scaledBitmap.recycle()
                }
                originalImage.recycle()
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Try full re-render approach - converts each page to JPEG image.
     * Best for scanned documents but loses text searchability.
     */
    private fun tryFullRerender(
        context: Context,
        inputFile: File,
        profile: CompressionProfile,
        onProgress: (Float) -> Unit
    ): File? {
        var inputDocument: PDDocument? = null
        var outputDocument: PDDocument? = null
        val outputFile = File(context.cacheDir, "rerender_${System.currentTimeMillis()}.pdf")
        
        return try {
            inputDocument = PDDocument.load(inputFile, MemoryUsageSetting.setupTempFileOnly())
            val totalPages = inputDocument.numberOfPages
            onPageCountAvailable(totalPages)
            
            if (totalPages == 0) return null
            
            outputDocument = PDDocument()
            val renderer = PDFRenderer(inputDocument)
            
            for (pageIndex in 0 until totalPages) {
                val originalPage = inputDocument.getPage(pageIndex)
                val pageRect = originalPage.mediaBox ?: PDRectangle.A4
                
                // Render page to bitmap at compression DPI
                val bitmap = renderer.renderImageWithDPI(pageIndex, profile.dpi)
                
                try {
                    // Create new page matching original dimensions
                    val newPage = PDPage(pageRect)
                    outputDocument.addPage(newPage)
                    
                    // Create compressed JPEG image
                    val pdImage = JPEGFactory.createFromImage(
                        outputDocument, 
                        bitmap, 
                        profile.jpegQuality
                    )
                    
                    // Draw the compressed image to fill the page
                    PDPageContentStream(
                        outputDocument, 
                        newPage,
                        PDPageContentStream.AppendMode.OVERWRITE,
                        true,
                        true
                    ).use { contentStream ->
                        contentStream.drawImage(pdImage, 0f, 0f, pageRect.width, pageRect.height)
                    }
                } finally {
                    bitmap.recycle()
                }
                
                onProgress((pageIndex + 1).toFloat() / totalPages)
            }
            
            outputDocument.save(outputFile)
            outputFile
            
        } catch (e: Exception) {
            outputFile.delete()
            null
        } finally {
            inputDocument?.close()
            outputDocument?.close()
        }
    }
    
    /**
     * Count pages in a PDF file.
     */
    private fun countPages(file: File): Int {
        return try {
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                doc.numberOfPages
            }
        } catch (e: Exception) {
            0
        }
    }
    
    /**
     * Estimate the compressed size based on compression level.
     * This is a rough estimate - actual results depend on PDF content.
     */
    fun estimateCompressedSize(originalSize: Long, level: CompressionLevel): Long {
        val reductionFactor = when (level) {
            CompressionLevel.LOW -> 0.85f
            CompressionLevel.MEDIUM -> 0.60f
            CompressionLevel.HIGH -> 0.40f
            CompressionLevel.MAXIMUM -> 0.30f
        }
        return (originalSize * reductionFactor).toLong()
    }

    fun estimateCompressedSize(originalSize: Long, qualityPercent: Int): Long {
        val clamped = qualityPercent.coerceIn(0, 100)
        // 0 = best quality (minimal reduction), 100 = max compression.
        val reductionFactor = 0.85f - (0.55f * (clamped / 100f))
        return (originalSize * reductionFactor).toLong()
    }

    private fun profileFromLevel(level: CompressionLevel): CompressionProfile {
        val scale = when (level) {
            CompressionLevel.LOW -> 1.0f
            CompressionLevel.MEDIUM -> 0.85f
            CompressionLevel.HIGH -> 0.70f
            CompressionLevel.MAXIMUM -> 0.55f
        }
        return CompressionProfile(
            dpi = level.dpi,
            jpegQuality = level.jpegQuality,
            scaleFactor = scale
        )
    }

    private fun profileFromSlider(qualityPercent: Int?): CompressionProfile? {
        if (qualityPercent == null) return null
        val clamped = qualityPercent.coerceIn(0, 100)
        val ratio = clamped / 100f

        val dpi = 150f - (78f * ratio) // 150 -> 72
        val jpegQuality = 0.9f - (0.52f * ratio) // 0.90 -> 0.38
        val scaleFactor = 1.0f - (0.45f * ratio) // 1.00 -> 0.55

        return CompressionProfile(
            dpi = dpi.coerceIn(72f, 150f),
            jpegQuality = jpegQuality.coerceIn(0.35f, 0.92f),
            scaleFactor = scaleFactor.coerceIn(0.55f, 1.0f)
        )
    }
}
