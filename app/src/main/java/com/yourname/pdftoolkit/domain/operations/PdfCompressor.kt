package com.yourname.pdftoolkit.domain.operations

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSObjectKey
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    MEDIUM(135f, 0.70f, "Medium - Balanced"),
    HIGH(110f, 0.55f, "High - Significant reduction"),
    MAXIMUM(85f, 0.40f, "Maximum - Smallest size")
}

/**
 * Compression mode determines the compression strategy.
 */
enum class CompressionMode {
    HYBRID,      // Current behavior: image optimization + optional rerender fallback
    TARGET_SIZE  // Progressively compress until the target is reached or no smaller result remains
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

/**
 * Result of a target-size compression operation.
 */
data class TargetSizeResult(
    val base: CompressionResult,
    val targetBytes: Long,
    val targetAchieved: Boolean
)

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
     * Quick check: does this PDF have any embedded raster images?
     * Used to decide compression strategy without loading the full document.
     */
    private fun pdfHasImages(file: File): Boolean {
        return try {
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                for (pageIndex in 0 until doc.numberOfPages) {
                    val resources = doc.getPage(pageIndex).resources ?: continue
                    val names = resources.xObjectNames?.toList() ?: continue
                    for (name in names) {
                        try {
                            if (resources.getXObject(name) is PDImageXObject) return true
                        } catch (e: Exception) { continue }
                    }
                }
                false
            }
        } catch (e: Exception) {
            false // assume no images if we can't read
        }
    }
    
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
            ensureActive()
            onProgress(0.05f)
            
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

            // Skip for very small files
            if (originalSize < 10 * 1024) {
                onProgress(1.0f)
                tempFile.inputStream().use { it.copyTo(outputStream) }
                outputStream.flush()
                return@withContext Result.success(CompressionResult(
                    originalSize = originalSize, compressedSize = originalSize,
                    compressionRatio = 1f, timeTakenMs = System.currentTimeMillis() - startTime,
                    pagesProcessed = countPages(tempFile),
                    strategyUsed = CompressionStrategy.IMAGE_OPTIMIZATION
                ))
            }

            onProgress(0.10f)

            // Try lossless structural and image optimization first
            var resultFile: File? = null
            var strategyUsed = CompressionStrategy.IMAGE_OPTIMIZATION

            val opt = tryImageOptimization(context, tempFile, profile, onProgress)
            
            // Smart threshold logic: if it saved less than 15%, we also consider tryFullRerender
            val isOptInefficient = opt != null && opt.length() > originalSize * 0.85f
            
            if (opt != null && opt.length() < originalSize) {
                resultFile = opt
                strategyUsed = CompressionStrategy.IMAGE_OPTIMIZATION
            } else {
                opt?.delete()
            }

            // Fall back to re-rendering if image optimization failed or was highly inefficient
            // A scan can be a single large page image or use nested resources, neither of
            // which the structural pass is guaranteed to improve.  Always evaluate the
            // raster fallback when that pass did not produce a worthwhile smaller file.
            val shouldTryRerender = resultFile == null || isOptInefficient ||
                level == CompressionLevel.HIGH || level == CompressionLevel.MAXIMUM ||
                (qualityPercent ?: 50) > 60
            if (shouldTryRerender) {
                onProgress(0.55f)
                val rerender = tryFullRerender(context, tempFile, profile) { p ->
                    onProgress(0.55f + p * 0.40f)
                }
                
                if (rerender != null && rerender.length() < originalSize) {
                    // If we have both, compare sizes and choose the smaller one!
                    if (resultFile != null) {
                        if (rerender.length() < resultFile.length()) {
                            resultFile.delete() // clean up optimized file
                            resultFile = rerender
                            strategyUsed = CompressionStrategy.FULL_RERENDER
                        } else {
                            rerender.delete() // clean up rerendered file
                        }
                    } else {
                        resultFile = rerender
                        strategyUsed = CompressionStrategy.FULL_RERENDER
                    }
                } else {
                    rerender?.delete()
                }
            }

            onProgress(0.95f)

            val finalFile = resultFile ?: tempFile
            finalFile.inputStream().use { it.copyTo(outputStream) }
            outputStream.flush()

            onProgress(1.0f)

            val compressedSize = finalFile.length()
            val pagesProcessed = countPages(finalFile)
            if (resultFile != null && resultFile != tempFile) resultFile.delete()

            return@withContext Result.success(CompressionResult(
                originalSize = originalSize,
                compressedSize = compressedSize,
                compressionRatio = if (originalSize > 0) compressedSize.toFloat() / originalSize else 1f,
                timeTakenMs = System.currentTimeMillis() - startTime,
                pagesProcessed = pagesProcessed,
                strategyUsed = strategyUsed
            ))
            
        } catch (e: CancellationException) {
            throw e
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
    /**
     * Prune document metadata, private application data, and unused XML schemas.
     */
    private fun pruneMetadata(document: PDDocument) {
        try {
            // Remove catalog-level metadata stream
            val catalog = document.documentCatalog
            catalog.metadata = null
            
            // Clean Document Information properties
            val info = document.documentInformation
            info.creator = null
            info.producer = "PDF Toolkit"
            info.author = null
            info.title = null
            info.subject = null
            info.keywords = null
            
            // Remove PieceInfo (private vendor-specific schemas, e.g. Adobe Illustrator layers)
            catalog.cosObject.removeItem(COSName.getPDFName("PieceInfo"))
            
            // Clean individual pages PieceInfo and Metadata
            for (i in 0 until document.numberOfPages) {
                val page = document.getPage(i)
                page.cosObject.removeItem(COSName.getPDFName("PieceInfo"))
                page.cosObject.removeItem(COSName.METADATA)
            }
        } catch (e: Exception) {
            // Safe fallback if metadata operations fail
        }
    }

    data class ImageContentKey(val length: Long, val md5: String)

    private fun computeImageHash(image: PDImageXObject): ImageContentKey? {
        return try {
            val length = image.cosObject.getLength().toLong()
            if (length <= 0) return null
            
            val digest = java.security.MessageDigest.getInstance("MD5")
            image.createInputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead = input.read(buffer)
                while (bytesRead != -1) {
                    digest.update(buffer, 0, bytesRead)
                    bytesRead = input.read(buffer)
                }
            }
            val hashBytes = digest.digest()
            val md5String = hashBytes.joinToString("") { "%02x".format(it) }
            ImageContentKey(length, md5String)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun tryImageOptimization(
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
            
            if (totalPages == 0) return null
            
            // Prune metadata first
            pruneMetadata(document)
            
            var imagesOptimized = 0
            val optimizedImages = mutableMapOf<COSObjectKey, PDImageXObject>()
            val hashCache = mutableMapOf<ImageContentKey, PDImageXObject>()
            
            // Process each page
            for (pageIndex in 0 until totalPages) {
                currentCoroutineContext().ensureActive()
                val page = document.getPage(pageIndex)
                val resources = page.resources ?: continue
                
                // Optimize images in this page
                imagesOptimized += optimizePageImages(document, resources, profile, optimizedImages, hashCache)
                
                val pageProgress = 0.10f + (0.45f * (pageIndex + 1).toFloat() / totalPages)
                onProgress(pageProgress)
            }
            
            document.save(outputFile)
            outputFile
            
        } catch (e: CancellationException) {
            outputFile.delete()
            throw e
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
    private suspend fun optimizePageImages(
        document: PDDocument,
        resources: PDResources,
        profile: CompressionProfile,
        optimizedCache: MutableMap<COSObjectKey, PDImageXObject>,
        hashCache: MutableMap<ImageContentKey, PDImageXObject>
    ): Int {
        var optimizedCount = 0
        
        try {
            val xObjectNames = resources.xObjectNames?.toList() ?: return 0
            
            for (name in xObjectNames) {
                currentCoroutineContext().ensureActive()
                try {
                    // Check if it's an indirect object (reference) to enable deduplication
                    val item = resources.cosObject.getItem(name)
                    var cacheKey: COSObjectKey? = null

                    if (item is COSObject) {
                        cacheKey = COSObjectKey(item.objectNumber, item.generationNumber)
                        val cached = optimizedCache[cacheKey]
                        if (cached != null) {
                            resources.put(name, cached)
                            // Count as optimized since we replaced it with an optimized version
                            optimizedCount++
                            continue
                        }
                    }

                    val xObject = resources.getXObject(name)
                    
                    if (xObject is PDImageXObject) {
                        // First check hash cache for identical image content
                        val hashKey = computeImageHash(xObject)
                        if (hashKey != null) {
                            val cachedByHash = hashCache[hashKey]
                            if (cachedByHash != null) {
                                resources.put(name, cachedByHash)
                                optimizedCount++
                                if (cacheKey != null) {
                                    optimizedCache[cacheKey] = cachedByHash
                                }
                                continue
                            }
                        }

                        val optimized = optimizeImage(document, xObject, profile)
                        if (optimized != null) {
                            resources.put(name, optimized)
                            optimizedCount++

                            if (cacheKey != null) {
                                optimizedCache[cacheKey] = optimized
                            }
                            if (hashKey != null) {
                                hashCache[hashKey] = optimized
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Skip this image if we can't process it
                    continue
                }
            }
        } catch (e: CancellationException) {
            throw e
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
            // Check filters/colorspace to skip monochrome (1-bit) images
            val isMonochrome = image.bitsPerComponent == 1
            val hasSMask = image.cosObject.containsKey(COSName.SMASK)
            val hasMask = image.cosObject.containsKey(COSName.MASK)
            
            // Skip to prevent massive bloat on 1-bit
            if (isMonochrome) {
                return null
            }
            
            // Get original stream length to compare later
            val originalLength = image.cosObject.getLength()
            
            val originalImage = image.image ?: return null
            
            // Skip small logos/icons (width or height < 128px) as compression saves nothing and causes blur
            if (originalImage.width < 128 || originalImage.height < 128) {
                return null
            }
            
            // Calculate target dimensions based on compression level
            val scaleFactor = profile.scaleFactor
            val targetWidth = (originalImage.width * scaleFactor).toInt().coerceAtLeast(32)
            val targetHeight = (originalImage.height * scaleFactor).toInt().coerceAtLeast(32)
            
            // Create scaled bitmap
            val scaledBitmap = if (scaleFactor < 1.0f) {
                Bitmap.createScaledBitmap(originalImage, targetWidth, targetHeight, true)
            } else {
                originalImage
            }
            
            try {
                val optimizedImage = if (hasSMask || hasMask || scaledBitmap.hasAlpha()) {
                    // Extract alpha mask and RGB channels separately
                    val width = scaledBitmap.width
                    val height = scaledBitmap.height
                    
                    // Create white-backed RGB bitmap
                    val rgbBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(rgbBitmap)
                    canvas.drawColor(android.graphics.Color.WHITE)
                    canvas.drawBitmap(scaledBitmap, 0f, 0f, null)
                    
                    try {
                        // Create white-backed RGB bitmap (flatten transparency)
                        // This is necessary because many mobile PDF viewers (Drive, Acrobat)
                        // fail to render DCTDecode (JPEG) images that have an SMask attached.
                        val colorImage = JPEGFactory.createFromImage(document, rgbBitmap, profile.jpegQuality)
                        
                        // Explicitly remove SMASK and MASK to ensure viewers don't expect them
                        colorImage.cosObject.removeItem(COSName.SMASK)
                        colorImage.cosObject.removeItem(COSName.MASK)
                        colorImage
                    } catch (e: Exception) {
                        null
                    } finally {
                        rgbBitmap.recycle()
                    }
                } else {
                    JPEGFactory.createFromImage(document, scaledBitmap, profile.jpegQuality)
                }
                
                if (optimizedImage != null) {
                    // Compare compressed stream length with original
                    val newLength = optimizedImage.cosObject.getLength()
                    if (newLength > 0 && newLength < originalLength) {
                        optimizedImage
                    } else {
                        null // Reject if the compressed stream is actually larger!
                    }
                } else {
                    null
                }
            } finally {
                if (scaledBitmap !== originalImage) {
                    scaledBitmap.recycle()
                }
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Try full re-render approach - converts each page to JPEG image.
     * Best for scanned documents but loses text searchability.
     */
    private suspend fun tryFullRerender(
        context: Context,
        inputFile: File,
        profile: CompressionProfile,
        onProgress: (Float) -> Unit
    ): File? {
        var outputDocument: PDDocument? = null
        var androidRenderer: android.graphics.pdf.PdfRenderer? = null
        var pfd: android.os.ParcelFileDescriptor? = null
        val outputFile = File(context.cacheDir, "rerender_${System.currentTimeMillis()}.pdf")
        
        return try {
            // A. Seekable File Descriptors
            // Ensure the source PDF URI is processed as a local, temporary cache-file copy (inputFile is guaranteed to be a temp file here)
            pfd = android.os.ParcelFileDescriptor.open(inputFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            androidRenderer = android.graphics.pdf.PdfRenderer(pfd)
            val totalPages = androidRenderer.pageCount
            
            if (totalPages == 0) return null
            
            outputDocument = PDDocument()
            
            for (pageIndex in 0 until totalPages) {
                currentCoroutineContext().ensureActive()
                var page: android.graphics.pdf.PdfRenderer.Page? = null
                var bitmap: Bitmap? = null
                
                try {
                    page = androidRenderer.openPage(pageIndex)

                    // C. Memory-Safe Dynamic Downsampling
                    // Apply an absolute resolution cap (e.g., max width/height of 2048px)
                    val maxDim = 2048f
                    val scale = profile.dpi / 72f
                    var targetWidth = (page.width * scale).toInt()
                    var targetHeight = (page.height * scale).toInt()

                    if (targetWidth > maxDim || targetHeight > maxDim) {
                        val downscale = maxDim / maxOf(targetWidth, targetHeight)
                        targetWidth = (targetWidth * downscale).toInt()
                        targetHeight = (targetHeight * downscale).toInt()
                    }

                    targetWidth = targetWidth.coerceAtLeast(1)
                    targetHeight = targetHeight.coerceAtLeast(1)

                    // Wrap the bitmap allocation and compression cycle in a try-catch (t: Throwable)
                    bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)

                    // B. Canvas Initialization & Color Erase
                    bitmap.eraseColor(android.graphics.Color.WHITE)

                    page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    val pageRect = PDRectangle(page.width.toFloat(), page.height.toFloat())
                    val newPage = PDPage(pageRect)
                    outputDocument.addPage(newPage)
                    
                    // Use high-efficiency compression formats
                    val pdImage = JPEGFactory.createFromImage(
                        outputDocument,
                        bitmap,
                        profile.jpegQuality
                    )
                    
                    PDPageContentStream(
                        outputDocument, 
                        newPage,
                        PDPageContentStream.AppendMode.OVERWRITE,
                        true,
                        true
                    ).use { contentStream ->
                        contentStream.drawImage(pdImage, pageRect.lowerLeftX, pageRect.lowerLeftY, pageRect.width, pageRect.height)
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    android.util.Log.e("PdfCompressor", "Error rendering page $pageIndex", t)
                    // We trapped the error, preventing the app from writing empty bytes silently to the next page stream.
                } finally {
                    page?.close()
                    // Recycle the bitmap explicitly at the end of every page processing iteration
                    bitmap?.recycle()
                }
                
                onProgress((pageIndex + 1).toFloat() / totalPages)
            }
            
            outputDocument.save(outputFile)
            outputFile
            
        } catch (e: CancellationException) {
            outputFile.delete()
            throw e
        } catch (t: Throwable) {
            outputFile.delete()
            null
        } finally {
            androidRenderer?.close()
            pfd?.close()
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

        val dpi = 150f - (65f * ratio) // 150 -> 85
        val jpegQuality = 0.9f - (0.52f * ratio) // 0.90 -> 0.38
        val scaleFactor = (1.0f - (0.45f * ratio)).coerceAtLeast(0.55f) // 1.00 -> 0.55, never below 0.55

        return CompressionProfile(
            dpi = dpi.coerceIn(85f, 150f),
            jpegQuality = jpegQuality.coerceIn(0.35f, 0.92f),
            scaleFactor = scaleFactor.coerceIn(0.55f, 1.0f)
        )
    }

    /** Compress PDF to a target file size using progressively stronger profiles. */
    suspend fun compressPdfToTargetSize(
        context: Context,
        inputUri: Uri,
        outputStream: OutputStream,
        targetBytes: Long,
        onProgress: (Float) -> Unit = {}
    ): Result<TargetSizeResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var tempFile: File? = null

        try {
            ensureActive()
            onProgress(0.05f)

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

            // Skip for very small files
            if (originalSize < 10 * 1024) {
                onProgress(1.0f)
                tempFile.inputStream().use { it.copyTo(outputStream) }
                outputStream.flush()
                val baseResult = CompressionResult(
                    originalSize = originalSize,
                    compressedSize = originalSize,
                    compressionRatio = 1f,
                    timeTakenMs = System.currentTimeMillis() - startTime,
                    pagesProcessed = countPages(tempFile),
                    strategyUsed = CompressionStrategy.IMAGE_OPTIMIZATION
                )
                return@withContext Result.success(TargetSizeResult(
                    base = baseResult,
                    targetBytes = targetBytes,
                    targetAchieved = originalSize <= targetBytes
                ))
            }

            onProgress(0.10f)

            // Try increasingly aggressive profiles. A single estimate cannot reliably hit a
            // byte target because PDFs differ radically in image content and encoding.
            val targetRatio = if (originalSize > 0) targetBytes.toFloat() / originalSize else 1f
            val profiles = profilesForTargetRatio(targetRatio)
            var resultFile: File? = null
            var strategyUsed = CompressionStrategy.IMAGE_OPTIMIZATION

            for (index in profiles.indices) {
                val profile = profiles[index]
                currentCoroutineContext().ensureActive()
                val progressStart = 0.10f + (0.80f * index / profiles.size)
                val progressSpan = 0.80f / profiles.size

                val opt = tryImageOptimization(context, tempFile, profile) { p ->
                    onProgress(progressStart + p * progressSpan * 0.45f)
                }
                if (opt != null && opt.length() < (resultFile?.length() ?: originalSize)) {
                    resultFile?.delete()
                    resultFile = opt
                    strategyUsed = CompressionStrategy.IMAGE_OPTIMIZATION
                } else {
                    opt?.delete()
                }

                // Re-rendering is essential for large scanned or single-page image PDFs;
                // their image data can be nested or already encoded efficiently.
                if ((resultFile?.length() ?: originalSize) > targetBytes) {
                    val rerender = tryFullRerender(context, tempFile, profile) { p ->
                        onProgress(progressStart + progressSpan * (0.45f + p * 0.55f))
                    }
                    if (rerender != null && rerender.length() < (resultFile?.length() ?: originalSize)) {
                        resultFile?.delete()
                        resultFile = rerender
                        strategyUsed = CompressionStrategy.FULL_RERENDER
                    } else {
                        rerender?.delete()
                    }
                }

                if ((resultFile?.length() ?: originalSize) <= targetBytes) break
            }

            onProgress(0.95f)

            val finalFile = resultFile ?: tempFile
            finalFile.inputStream().use { it.copyTo(outputStream) }
            outputStream.flush()

            onProgress(1.0f)

            val compressedSize = finalFile.length()
            val pagesProcessed = countPages(finalFile)
            if (resultFile != null && resultFile != tempFile) resultFile.delete()

            val baseResult = CompressionResult(
                originalSize = originalSize,
                compressedSize = compressedSize,
                compressionRatio = if (originalSize > 0) compressedSize.toFloat() / originalSize else 1f,
                timeTakenMs = System.currentTimeMillis() - startTime,
                pagesProcessed = pagesProcessed,
                strategyUsed = strategyUsed
            )

            return@withContext Result.success(TargetSizeResult(
                base = baseResult,
                targetBytes = targetBytes,
                targetAchieved = compressedSize <= targetBytes
            ))

        } catch (e: CancellationException) {
            throw e
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
     * Create a compression profile based on the target size ratio.
     * targetRatio = targetBytes / originalSize (0.0 to 1.0+)
     */
    private fun profileFromTargetRatio(targetRatio: Float): CompressionProfile {
        val clampedRatio = targetRatio.coerceIn(0.1f, 1.0f)
        // Invert: smaller target ratio = more aggressive compression
        val aggression = 1f - clampedRatio // 0.0 (mild) to ~0.9 (aggressive)

        // Map aggression to profile parameters
        // aggression 0.0 -> quality like LOW (dpi 150, q 0.85, scale 1.0)
        // aggression 0.9 -> quality like MAXIMUM (dpi 85, q 0.40, scale 0.55)
        val dpi = (150f - 65f * aggression).coerceIn(85f, 150f)
        val jpegQuality = (0.85f - 0.45f * aggression).coerceIn(0.40f, 0.85f)
        val scaleFactor = (1.0f - 0.45f * aggression).coerceIn(0.55f, 1.0f)

        return CompressionProfile(
            dpi = dpi,
            jpegQuality = jpegQuality,
            scaleFactor = scaleFactor
        )
    }

    /** Profiles used for a requested size, from quality-preserving to hard fallback. */
    private fun profilesForTargetRatio(targetRatio: Float): List<CompressionProfile> {
        val initial = profileFromTargetRatio(targetRatio)
        return listOf(
            initial,
            CompressionProfile(85f, 0.40f, 0.55f),
            CompressionProfile(65f, 0.28f, 0.42f),
            CompressionProfile(45f, 0.18f, 0.30f)
        ).distinct()
    }
}
