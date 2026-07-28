package com.yourname.pdftoolkit.domain.operations

import android.content.Context
import android.net.Uri
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/**
 * Result of an organize operation.
 */
data class OrganizeResult(
    val originalPageCount: Int,
    val resultPageCount: Int,
    val pagesRemoved: Int = 0,
    val pagesReordered: Boolean = false
)

/**
 * Handles PDF page organization operations.
 * Supports removing pages, reordering pages, and reorganizing PDFs.
 */
class PdfOrganizer {
    
    /**
     * Remove specific pages from a PDF.
     * 
     * @param context Android context
     * @param inputUri URI of the PDF to modify
     * @param outputStream Output stream for the modified PDF
     * @param pagesToRemove Set of page numbers to remove (1-indexed)
     * @param onProgress Progress callback (0.0 to 1.0)
     * @return OrganizeResult with operation statistics
     */
    suspend fun removePages(
        context: Context,
        inputUri: Uri,
        outputStream: OutputStream,
        pagesToRemove: Set<Int>,
        onProgress: (Float) -> Unit = {}
    ): Result<OrganizeResult> = withContext(Dispatchers.IO) {
        var document: PDDocument? = null
        
        try {
            onProgress(0.1f)
            
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(
                    IllegalStateException("Cannot open input file")
                )
            
            document = PDDocument.load(inputStream, MemoryUsageSetting.setupTempFileOnly())
            val originalPageCount = document.numberOfPages
            
            if (originalPageCount == 0) {
                return@withContext Result.failure(
                    IllegalStateException("PDF has no pages")
                )
            }
            
            // Validate page numbers
            val invalidPages = pagesToRemove.filter { it < 1 || it > originalPageCount }
            if (invalidPages.isNotEmpty()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Invalid page numbers: $invalidPages (document has $originalPageCount pages)")
                )
            }
            
            // Check that we're not removing all pages
            if (pagesToRemove.size >= originalPageCount) {
                return@withContext Result.failure(
                    IllegalArgumentException("Cannot remove all pages from the document")
                )
            }
            
            onProgress(0.3f)
            
            // Remove pages in reverse order to maintain correct indices
            val sortedPagesToRemove = pagesToRemove.sortedDescending()
            
            sortedPagesToRemove.forEachIndexed { index, pageNum ->
                // Convert to 0-indexed
                document.removePage(pageNum - 1)
                
                val removeProgress = 0.3f + (0.5f * (index + 1).toFloat() / sortedPagesToRemove.size)
                onProgress(removeProgress)
            }
            
            onProgress(0.9f)
            
            // Save the document
            document.save(outputStream)
            outputStream.flush()
            
            onProgress(1.0f)
            
            Result.success(
                OrganizeResult(
                    originalPageCount = originalPageCount,
                    resultPageCount = originalPageCount - pagesToRemove.size,
                    pagesRemoved = pagesToRemove.size,
                    pagesReordered = false
                )
            )
            
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            document?.close()
        }
    }
    
    /**
     * Reorder pages in a PDF according to a new order.
     * 
     * @param context Android context
     * @param inputUri URI of the PDF to reorder
     * @param outputStream Output stream for the reordered PDF
     * @param newOrder List of page numbers (1-indexed) in the desired new order
     * @param onProgress Progress callback (0.0 to 1.0)
     * @return OrganizeResult with operation statistics
     */
    suspend fun reorderPages(
        context: Context,
        inputUri: Uri,
        outputStream: OutputStream,
        newOrder: List<Int>,
        onProgress: (Float) -> Unit = {}
    ): Result<OrganizeResult> = withContext(Dispatchers.IO) {
        var sourceDocument: PDDocument? = null
        var resultDocument: PDDocument? = null
        
        try {
            onProgress(0.1f)
            
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(
                    IllegalStateException("Cannot open input file")
                )
            
            sourceDocument = PDDocument.load(inputStream, MemoryUsageSetting.setupTempFileOnly())
            val originalPageCount = sourceDocument.numberOfPages
            
            if (originalPageCount == 0) {
                return@withContext Result.failure(
                    IllegalStateException("PDF has no pages")
                )
            }
            
            // Validate new order
            val invalidPages = newOrder.filter { it < 1 || it > originalPageCount }
            if (invalidPages.isNotEmpty()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Invalid page numbers: $invalidPages (document has $originalPageCount pages)")
                )
            }
            
            if (newOrder.isEmpty()) {
                return@withContext Result.failure(
                    IllegalArgumentException("New order cannot be empty")
                )
            }
            
            onProgress(0.2f)
            
            // Create new document with pages in new order
            resultDocument = PDDocument()
            
            newOrder.forEachIndexed { index, pageNum ->
                // Get the page from source (0-indexed)
                val page = sourceDocument.getPage(pageNum - 1)
                
                // Import the page to the new document
                resultDocument.importPage(page)
                
                val reorderProgress = 0.2f + (0.7f * (index + 1).toFloat() / newOrder.size)
                onProgress(reorderProgress)
            }
            
            onProgress(0.95f)
            
            // Save the reordered document
            resultDocument.save(outputStream)
            outputStream.flush()
            
            onProgress(1.0f)
            
            Result.success(
                OrganizeResult(
                    originalPageCount = originalPageCount,
                    resultPageCount = newOrder.size,
                    pagesRemoved = originalPageCount - newOrder.size,
                    pagesReordered = true
                )
            )
            
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            sourceDocument?.close()
            resultDocument?.close()
        }
    }
    
    /**
     * Reorder and optionally remove pages in one operation.
     * Only pages in the newOrder list will be included in the output.
     * 
     * @param context Android context
     * @param inputUri URI of the PDF to reorganize
     * @param outputStream Output stream for the reorganized PDF
     * @param newOrder List of page numbers (1-indexed) to include, in the desired order
     * @param onProgress Progress callback (0.0 to 1.0)
     * @return OrganizeResult with operation statistics
     */
    suspend fun reorganizePages(
        context: Context,
        inputUri: Uri,
        outputStream: OutputStream,
        newOrder: List<Int>,
        onProgress: (Float) -> Unit = {}
    ): Result<OrganizeResult> {
        // Reorder handles both reordering and removal
        return reorderPages(context, inputUri, outputStream, newOrder, onProgress)
    }
    
    /**
     * Reverse the order of all pages in a PDF.
     * 
     * @param context Android context
     * @param inputUri URI of the PDF to reverse
     * @param outputStream Output stream for the reversed PDF
     * @param onProgress Progress callback (0.0 to 1.0)
     * @return OrganizeResult with operation statistics
     */
    suspend fun reversePages(
        context: Context,
        inputUri: Uri,
        outputStream: OutputStream,
        onProgress: (Float) -> Unit = {}
    ): Result<OrganizeResult> = withContext(Dispatchers.IO) {
        var document: PDDocument? = null
        
        try {
            onProgress(0.1f)
            
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(
                    IllegalStateException("Cannot open input file")
                )
            
            document = PDDocument.load(inputStream, MemoryUsageSetting.setupTempFileOnly())
            val pageCount = document.numberOfPages
            
            if (pageCount == 0) {
                return@withContext Result.failure(
                    IllegalStateException("PDF has no pages")
                )
            }
            
            // Create reversed order: [n, n-1, ..., 2, 1]
            val reversedOrder = (pageCount downTo 1).toList()
            
            document.close()
            document = null
            
            // Reuse reorderPages for the actual work
            reorderPages(context, inputUri, outputStream, reversedOrder, onProgress)
            
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            document?.close()
        }
    }
    
    /**
     * Duplicate specific pages in a PDF.
     * 
     * @param context Android context
     * @param inputUri URI of the PDF
     * @param outputStream Output stream for the modified PDF
     * @param pagesToDuplicate Map of page numbers (1-indexed) to number of copies
     * @param onProgress Progress callback (0.0 to 1.0)
     * @return OrganizeResult with operation statistics
     */
    suspend fun duplicatePages(
        context: Context,
        inputUri: Uri,
        outputStream: OutputStream,
        pagesToDuplicate: Map<Int, Int>,
        onProgress: (Float) -> Unit = {}
    ): Result<OrganizeResult> = withContext(Dispatchers.IO) {
        var document: PDDocument? = null
        
        try {
            onProgress(0.1f)
            
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(
                    IllegalStateException("Cannot open input file")
                )
            
            document = PDDocument.load(inputStream, MemoryUsageSetting.setupTempFileOnly())
            val originalPageCount = document.numberOfPages
            
            if (originalPageCount == 0) {
                return@withContext Result.failure(
                    IllegalStateException("PDF has no pages")
                )
            }
            
            // Build the new order with duplicates
            val newOrder = mutableListOf<Int>()
            for (pageNum in 1..originalPageCount) {
                val copies = pagesToDuplicate[pageNum] ?: 1
                repeat(copies) {
                    newOrder.add(pageNum)
                }
            }
            
            document.close()
            document = null
            
            // Reuse reorderPages
            reorderPages(context, inputUri, outputStream, newOrder, onProgress)
            
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            document?.close()
        }
    }
    
    /**
     * Get the page count of a PDF.
     */
    suspend fun getPageCount(
        context: Context,
        uri: Uri
    ): Int = withContext(Dispatchers.IO) {
        var tempFile: File? = null
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null

        try {
            // Attempt to open direct ParcelFileDescriptor from ContentResolver (avoid copying)
            try {
                pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    renderer = PdfRenderer(pfd)
                    return@withContext renderer.pageCount
                }
            } catch (e: Exception) {
                android.util.Log.w("PdfOrganizer", "Direct file descriptor read failed, falling back to temp file", e)
            } finally {
                try { renderer?.close() } catch (_: Exception) {}
                try { pfd?.close() } catch (_: Exception) {}
                renderer = null
                pfd = null
            }

            // Fallback: Copy to temp file
            val temp = File.createTempFile("page_count_", ".pdf", context.cacheDir)
            tempFile = temp

            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext 0

            pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            renderer.pageCount
        } catch (e: Exception) {
            0
        } finally {
            try {
                renderer?.close()
            } catch (_: Exception) {
            }
            try {
                pfd?.close()
            } catch (_: Exception) {
            }
            try {
                tempFile?.delete()
            } catch (_: Exception) {
            }
        }
    }
    
    /**
     * Generate thumbnails for all pages in a PDF.
     * 
     * @param context Android context
     * @param uri URI of the PDF
     * @param width Desired thumbnail width
     * @param height Desired thumbnail height
     * @param callback Callback for each page thumbnail (pageNumber: 1-indexed, bitmap)
     */

    suspend fun getPageThumbnail(
        context: Context,
        uri: Uri,
        pageIndex: Int, // 0-based
        width: Int,
        height: Int
    ): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        var tempFile: File? = null
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var result: android.graphics.Bitmap? = null

        try {
            try {
                pfd = context.contentResolver.openFileDescriptor(uri, "r")
            } catch (e: Exception) {
                // Ignore
            }

            if (pfd == null) {
                val temp = File.createTempFile("temp_thumb_${pageIndex}_", ".pdf", context.cacheDir)
                tempFile = temp
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            }

            if (pfd != null) {
                renderer = PdfRenderer(pfd)
                if (pageIndex in 0 until renderer.pageCount) {
                    val page = renderer.openPage(pageIndex)

                    val pageWidth = page.width
                    val pageHeight = page.height
                    val scale = minOf(width.toFloat() / pageWidth, height.toFloat() / pageHeight)
                    val scaledWidth = maxOf(1, (pageWidth * scale).toInt())
                    val scaledHeight = maxOf(1, (pageHeight * scale).toInt())

                    val bitmap = android.graphics.Bitmap.createBitmap(
                        scaledWidth,
                        scaledHeight,
                        android.graphics.Bitmap.Config.ARGB_8888
                    )

                    // Fill with white background
                    bitmap.eraseColor(android.graphics.Color.WHITE)

                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()

                    result = bitmap
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            renderer?.close()
            pfd?.close()
            tempFile?.delete()
        }

        return@withContext result
    }

    suspend fun getPageThumbnails(
        context: Context,
        uri: Uri,
        width: Int,
        height: Int,
        callback: (Int, android.graphics.Bitmap) -> Unit
    ) = withContext(Dispatchers.IO) {
        var tempFile: File? = null
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null

        try {
            // Attempt to open direct ParcelFileDescriptor from ContentResolver (avoid copying)
            try {
                pfd = context.contentResolver.openFileDescriptor(uri, "r")
            } catch (e: Exception) {
                android.util.Log.w("PdfOrganizer", "Direct file descriptor read failed for thumbnails, falling back to temp file", e)
            }

            // Fallback: Copy to temp file if direct opening failed
            if (pfd == null) {
                val temp = File.createTempFile("temp_thumbnail_", ".pdf", context.cacheDir)
                tempFile = temp
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            }

            if (pfd != null) {
                renderer = PdfRenderer(pfd)
                val pageCount = renderer.pageCount
                
                for (i in 0 until pageCount) {
                    val page = renderer.openPage(i)
                    
                    // Calculate scaled dimensions maintaining aspect ratio
                    val pageWidth = page.width
                    val pageHeight = page.height
                    val scale = minOf(width.toFloat() / pageWidth, height.toFloat() / pageHeight)
                    val scaledWidth = (pageWidth * scale).toInt()
                    val scaledHeight = (pageHeight * scale).toInt()
                    
                    val bitmap = android.graphics.Bitmap.createBitmap(
                        scaledWidth,
                        scaledHeight,
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
                    
                    // Fill with white background
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    
                    page.render(
                        bitmap,
                        null,
                        null,
                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                    )
                    
                    page.close()
                    
                    // Send thumbnail via callback (1-indexed page number)
                    callback(i + 1, bitmap)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            try { tempFile?.delete() } catch (_: Exception) {}
        }
    }
}
