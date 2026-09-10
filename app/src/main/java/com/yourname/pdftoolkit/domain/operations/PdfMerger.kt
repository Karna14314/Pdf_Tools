package com.yourname.pdftoolkit.domain.operations

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.UUID
import com.yourname.pdftoolkit.util.MemoryGuard

/**
 * Type of item to be merged.
 */
enum class MergeItemType {
    PDF,
    IMAGE
}

/**
 * Data class representing a file item (PDF or Image) selected for merging.
 */
data class MergeItem(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val name: String,
    val size: Long,
    val formattedSize: String,
    val type: MergeItemType,
    val pageCount: Int = 1
)

/**
 * Data class representing an individual page in the target merged sequence.
 */
data class MergePageItem(
    val id: String = UUID.randomUUID().toString(),
    val sourceItem: MergeItem,
    val sourcePageIndex: Int, // 0-indexed page in source PDF, or 0 for image
    val overallPageNumber: Int, // 1-indexed overall page number in merged doc
    val sourceLabel: String,
    val isImage: Boolean = (sourceItem.type == MergeItemType.IMAGE)
)

/**
 * Handles PDF and Image merge operations.
 * Combines multiple PDF files and Image files into a single document.
 */
class PdfMerger {
    
    /**
     * Merge multiple PDF files into one (legacy overload).
     * 
     * @param context Android context for content resolver access
     * @param inputUris List of URIs pointing to PDFs to merge (in order)
     * @param outputStream Output stream to write the merged PDF
     * @param onProgress Callback for progress updates (0.0 to 1.0)
     * @return Result indicating success or failure
     */
    suspend fun mergePdfs(
        context: Context,
        inputUris: List<Uri>,
        outputStream: OutputStream,
        onProgress: (Float) -> Unit = {}
    ): Result<Unit> {
        val items = inputUris.map { uri ->
            MergeItem(
                uri = uri,
                name = uri.lastPathSegment ?: "document.pdf",
                size = 0L,
                formattedSize = "",
                type = MergeItemType.PDF,
                pageCount = 1
            )
        }
        return mergeItems(context, items, outputStream, onProgress)
    }

    /**
     * Merge multiple PDF and/or image items into a single PDF document.
     *
     * @param context Android context
     * @param items List of MergeItem (PDFs and Images) in desired merge sequence
     * @param outputStream Output stream to write merged PDF
     * @param onProgress Callback for progress updates (0.0 to 1.0)
     * @return Result indicating success or failure
     */
    suspend fun mergeItems(
        context: Context,
        items: List<MergeItem>,
        outputStream: OutputStream,
        onProgress: (Float) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        MemoryGuard.checkMemory("mergeItems")

        if (items.isEmpty()) {
            return@withContext Result.failure(
                IllegalArgumentException("At least 1 file is required for merging")
            )
        }

        val merger = PDFMergerUtility()
        var destinationDocument: PDDocument? = null

        try {
            ensureActive()
            destinationDocument = PDDocument()
            val destination = destinationDocument

            items.forEachIndexed { index, item ->
                ensureActive()

                when (item.type) {
                    MergeItemType.PDF -> {
                        context.contentResolver.openInputStream(item.uri)?.use { inputStream ->
                            PDDocument.load(inputStream, MemoryUsageSetting.setupTempFileOnly()).use { sourceDocument ->
                                merger.appendDocument(destination, sourceDocument)
                            }
                        } ?: return@withContext Result.failure(
                            IllegalStateException("Cannot open PDF file: ${item.uri}")
                        )
                    }
                    MergeItemType.IMAGE -> {
                        val bitmap = loadBitmap(context, item.uri)
                            ?: return@withContext Result.failure(
                                IllegalStateException("Cannot load image: ${item.uri}")
                            )
                        try {
                            addImagePageToDocument(destination, bitmap)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }

                onProgress((index + 1).toFloat() / items.size)
            }

            ensureActive()
            destination.save(outputStream)
            onProgress(1.0f)
            Result.success(Unit)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                destinationDocument?.close()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    /**
     * Helper to load bitmap cleanly from Uri with memory safety.
     */
    private fun loadBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(inputStream, null, options)

                val maxDimension = 3072
                var sampleSize = 1
                while (options.outWidth / sampleSize > maxDimension ||
                    options.outHeight / sampleSize > maxDimension
                ) {
                    sampleSize *= 2
                }

                context.contentResolver.openInputStream(uri)?.use { stream2 ->
                    val decodeOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    BitmapFactory.decodeStream(stream2, null, decodeOptions)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Convert bitmap into a PDF page and add to PDDocument maintaining aspect ratio.
     */
    private fun addImagePageToDocument(document: PDDocument, bitmap: Bitmap) {
        val pageRect = PDRectangle.A4
        val page = PDPage(pageRect)
        document.addPage(page)

        val pixelCount = bitmap.width * bitmap.height
        val pdImage = if (pixelCount > 1024 * 1024) {
            JPEGFactory.createFromImage(document, bitmap, 0.9f)
        } else {
            LosslessFactory.createFromImage(document, bitmap)
        }

        val pageWidth = pageRect.width
        val pageHeight = pageRect.height
        val imageWidth = bitmap.width.toFloat()
        val imageHeight = bitmap.height.toFloat()

        val scale = minOf(pageWidth / imageWidth, pageHeight / imageHeight)
        val scaledWidth = imageWidth * scale
        val scaledHeight = imageHeight * scale

        val x = (pageWidth - scaledWidth) / 2
        val y = (pageHeight - scaledHeight) / 2

        PDPageContentStream(document, page).use { contentStream ->
            contentStream.drawImage(pdImage, x, y, scaledWidth, scaledHeight)
        }
    }

    /**
     * Build list of page items for Page View preview.
     */
    fun buildPageItems(items: List<MergeItem>): List<MergePageItem> {
        val pageItems = mutableListOf<MergePageItem>()
        var overallIndex = 1

        items.forEach { item ->
            when (item.type) {
                MergeItemType.IMAGE -> {
                    pageItems.add(
                        MergePageItem(
                            sourceItem = item,
                            sourcePageIndex = 0,
                            overallPageNumber = overallIndex++,
                            sourceLabel = item.name,
                            isImage = true
                        )
                    )
                }
                MergeItemType.PDF -> {
                    val count = maxOf(1, item.pageCount)
                    for (p in 0 until count) {
                        pageItems.add(
                            MergePageItem(
                                sourceItem = item,
                                sourcePageIndex = p,
                                overallPageNumber = overallIndex++,
                                sourceLabel = if (count > 1) "${item.name} (p. ${p + 1})" else item.name,
                                isImage = false
                            )
                        )
                    }
                }
            }
        }
        return pageItems
    }
    
    /**
     * Get the total page count of multiple PDFs.
     */
    suspend fun getTotalPageCount(
        context: Context,
        uris: List<Uri>
    ): Int = withContext(Dispatchers.IO) {
        MemoryGuard.checkMemory("getTotalPageCount")
        var totalPages = 0
        
        uris.forEach { uri ->
            ensureActive()
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    PDDocument.load(inputStream, MemoryUsageSetting.setupTempFileOnly()).use { document ->
                        totalPages += document.numberOfPages
                    }
                }
            } catch (e: Exception) {
                // Skip files that can't be read
            }
        }
        
        totalPages
    }

    /**
     * Query page count for a single file URI (PDF or Image).
     */
    suspend fun getPageCount(context: Context, uri: Uri, isImage: Boolean): Int = withContext(Dispatchers.IO) {
        if (isImage) return@withContext 1
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                PDDocument.load(inputStream, MemoryUsageSetting.setupTempFileOnly()).use { document ->
                    document.numberOfPages
                }
            } ?: 1
        } catch (e: Exception) {
            1
        }
    }
}
