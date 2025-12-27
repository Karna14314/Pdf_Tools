package com.yourname.pdftoolkit.domain.operations

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * Position options for page numbers.
 */
enum class PageNumberPosition {
    BOTTOM_LEFT,
    BOTTOM_CENTER,
    BOTTOM_RIGHT,
    TOP_LEFT,
    TOP_CENTER,
    TOP_RIGHT
}

/**
 * Format options for page number text.
 */
enum class PageNumberFormat {
    /** Simple: 1, 2, 3... */
    SIMPLE,
    /** With total: 1 of 5, 2 of 5... */
    WITH_TOTAL,
    /** Prefixed: Page 1, Page 2... */
    PREFIXED,
    /** Prefixed with total: Page 1 of 5... */
    PREFIXED_WITH_TOTAL
}

/**
 * Options for page numbering.
 */
data class PageNumberOptions(
    val position: PageNumberPosition = PageNumberPosition.BOTTOM_RIGHT,
    val format: PageNumberFormat = PageNumberFormat.WITH_TOTAL,
    val fontSize: Float = 10f,
    val marginX: Float = 50f,
    val marginY: Float = 30f,
    val startPage: Int = 1,
    val startNumber: Int = 1
)

/**
 * Handles adding page numbers to PDF documents.
 * Uses PDPageContentStream to draw text at specified coordinates.
 */
class PdfPageNumberer {
    
    /**
     * Add page numbers to all pages in a PDF.
     * 
     * @param context Android context
     * @param inputUri URI of the PDF to number
     * @param outputStream Output stream for the numbered PDF
     * @param options Page numbering options
     * @param onProgress Progress callback (0.0 to 1.0)
     * @return Number of pages numbered
     */
    suspend fun addPageNumbers(
        context: Context,
        inputUri: Uri,
        outputStream: OutputStream,
        options: PageNumberOptions = PageNumberOptions(),
        onProgress: (Float) -> Unit = {}
    ): Result<Int> = withContext(Dispatchers.IO) {
        var document: PDDocument? = null
        
        try {
            onProgress(0.05f)
            
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(
                    IllegalStateException("Cannot open input file")
                )
            
            document = PDDocument.load(inputStream)
            val totalPages = document.numberOfPages
            
            if (totalPages == 0) {
                return@withContext Result.failure(
                    IllegalStateException("PDF has no pages")
                )
            }
            
            onProgress(0.1f)
            
            // Use a standard font that's always available
            val font = PDType1Font.HELVETICA
            
            var numberedCount = 0
            
            for (pageIndex in 0 until totalPages) {
                // Skip pages before startPage (1-indexed)
                if (pageIndex + 1 < options.startPage) {
                    continue
                }
                
                val page = document.getPage(pageIndex)
                val pageRect = page.mediaBox
                val pageWidth = pageRect.width
                val pageHeight = pageRect.height
                
                // Calculate current page number
                val currentPageNum = options.startNumber + (pageIndex + 1 - options.startPage)
                val totalDisplayPages = totalPages - options.startPage + 1
                
                // Format the page number text
                val pageNumberText = formatPageNumber(currentPageNum, totalDisplayPages, options.format)
                
                // Calculate text width for positioning
                val textWidth = font.getStringWidth(pageNumberText) / 1000 * options.fontSize
                
                // Calculate position based on alignment
                val (x, y) = calculatePosition(
                    pageWidth = pageWidth,
                    pageHeight = pageHeight,
                    textWidth = textWidth,
                    options = options
                )
                
                // Add page number using content stream
                PDPageContentStream(
                    document,
                    page,
                    PDPageContentStream.AppendMode.APPEND,
                    true,
                    true
                ).use { contentStream ->
                    contentStream.beginText()
                    contentStream.setFont(font, options.fontSize)
                    contentStream.newLineAtOffset(x, y)
                    contentStream.showText(pageNumberText)
                    contentStream.endText()
                }
                
                numberedCount++
                
                val pageProgress = 0.1f + (0.85f * (pageIndex + 1).toFloat() / totalPages)
                onProgress(pageProgress)
            }
            
            onProgress(0.98f)
            
            // Save the document
            document.save(outputStream)
            
            onProgress(1.0f)
            
            Result.success(numberedCount)
            
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            document?.close()
        }
    }
    
    /**
     * Format the page number text according to the specified format.
     */
    private fun formatPageNumber(
        currentPage: Int,
        totalPages: Int,
        format: PageNumberFormat
    ): String {
        return when (format) {
            PageNumberFormat.SIMPLE -> "$currentPage"
            PageNumberFormat.WITH_TOTAL -> "$currentPage of $totalPages"
            PageNumberFormat.PREFIXED -> "Page $currentPage"
            PageNumberFormat.PREFIXED_WITH_TOTAL -> "Page $currentPage of $totalPages"
        }
    }
    
    /**
     * Calculate the X and Y coordinates for page number placement.
     */
    private fun calculatePosition(
        pageWidth: Float,
        pageHeight: Float,
        textWidth: Float,
        options: PageNumberOptions
    ): Pair<Float, Float> {
        val marginX = options.marginX
        val marginY = options.marginY
        
        val x = when (options.position) {
            PageNumberPosition.BOTTOM_LEFT, PageNumberPosition.TOP_LEFT -> marginX
            PageNumberPosition.BOTTOM_CENTER, PageNumberPosition.TOP_CENTER -> (pageWidth - textWidth) / 2
            PageNumberPosition.BOTTOM_RIGHT, PageNumberPosition.TOP_RIGHT -> pageWidth - marginX - textWidth
        }
        
        val y = when (options.position) {
            PageNumberPosition.BOTTOM_LEFT, PageNumberPosition.BOTTOM_CENTER, PageNumberPosition.BOTTOM_RIGHT -> marginY
            PageNumberPosition.TOP_LEFT, PageNumberPosition.TOP_CENTER, PageNumberPosition.TOP_RIGHT -> pageHeight - marginY
        }
        
        return Pair(x, y)
    }
    
    /**
     * Preview what page numbers will look like.
     */
    fun previewPageNumbers(
        totalPages: Int,
        options: PageNumberOptions
    ): List<String> {
        val previews = mutableListOf<String>()
        val validTotalPages = totalPages - options.startPage + 1
        
        for (pageIndex in 0 until minOf(5, totalPages)) {
            if (pageIndex + 1 < options.startPage) {
                previews.add("(no number)")
                continue
            }
            
            val currentPageNum = options.startNumber + (pageIndex + 1 - options.startPage)
            previews.add(formatPageNumber(currentPageNum, validTotalPages, options.format))
        }
        
        if (totalPages > 5) {
            previews.add("...")
        }
        
        return previews
    }
}
