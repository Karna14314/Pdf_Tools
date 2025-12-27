package com.yourname.pdftoolkit.domain.operations

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * Result of text extraction.
 */
data class TextExtractionResult(
    val characterCount: Int,
    val wordCount: Int,
    val pageCount: Int,
    val hasText: Boolean
)

/**
 * Options for text extraction.
 */
data class TextExtractionOptions(
    val startPage: Int = 1,
    val endPage: Int = Int.MAX_VALUE,
    val addPageBreaks: Boolean = true,
    val preserveLineBreaks: Boolean = true,
    val sortByPosition: Boolean = true
)

/**
 * Handles text extraction from PDF documents.
 * Uses PDFTextStripper to extract text content.
 */
class TextExtractor {
    
    /**
     * Extract all text from a PDF and save to a text file.
     * 
     * @param context Android context
     * @param inputUri URI of the PDF
     * @param outputStream Output stream for the text file
     * @param options Extraction options
     * @param onProgress Progress callback (0.0 to 1.0)
     * @return TextExtractionResult with statistics
     */
    suspend fun extractToTextFile(
        context: Context,
        inputUri: Uri,
        outputStream: OutputStream,
        options: TextExtractionOptions = TextExtractionOptions(),
        onProgress: (Float) -> Unit = {}
    ): Result<TextExtractionResult> = withContext(Dispatchers.IO) {
        var document: PDDocument? = null
        
        try {
            onProgress(0.1f)
            
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
            
            onProgress(0.2f)
            
            // Configure the text stripper
            val textStripper = PDFTextStripper().apply {
                startPage = options.startPage
                endPage = minOf(options.endPage, totalPages)
                sortByPosition = options.sortByPosition
                
                if (options.addPageBreaks) {
                    pageEnd = "\n\n--- Page Break ---\n\n"
                }
            }
            
            onProgress(0.3f)
            
            // Extract text
            val extractedText = textStripper.getText(document)
            
            onProgress(0.7f)
            
            // Write to output stream as UTF-8
            OutputStreamWriter(outputStream, StandardCharsets.UTF_8).use { writer ->
                writer.write(extractedText)
            }
            
            onProgress(0.9f)
            
            // Calculate statistics
            val characterCount = extractedText.length
            val wordCount = extractedText.split("\\s+".toRegex())
                .filter { it.isNotBlank() }
                .size
            val hasText = characterCount > 0
            
            onProgress(1.0f)
            
            Result.success(
                TextExtractionResult(
                    characterCount = characterCount,
                    wordCount = wordCount,
                    pageCount = minOf(options.endPage, totalPages) - options.startPage + 1,
                    hasText = hasText
                )
            )
            
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            document?.close()
        }
    }
    
    /**
     * Extract text from a PDF and return it as a string.
     * 
     * @param context Android context
     * @param uri URI of the PDF
     * @param options Extraction options
     * @return Extracted text string
     */
    suspend fun extractToString(
        context: Context,
        uri: Uri,
        options: TextExtractionOptions = TextExtractionOptions()
    ): String = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                PDDocument.load(inputStream).use { document ->
                    val textStripper = PDFTextStripper().apply {
                        startPage = options.startPage
                        endPage = minOf(options.endPage, document.numberOfPages)
                        sortByPosition = options.sortByPosition
                    }
                    
                    textStripper.getText(document)
                }
            } ?: ""
        } catch (e: Exception) {
            ""
        }
    }
    
    /**
     * Extract text from a specific page.
     * 
     * @param context Android context
     * @param uri URI of the PDF
     * @param pageNumber Page number (1-indexed)
     * @return Extracted text from the page
     */
    suspend fun extractFromPage(
        context: Context,
        uri: Uri,
        pageNumber: Int
    ): String = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                PDDocument.load(inputStream).use { document ->
                    if (pageNumber < 1 || pageNumber > document.numberOfPages) {
                        return@withContext ""
                    }
                    
                    val textStripper = PDFTextStripper().apply {
                        startPage = pageNumber
                        endPage = pageNumber
                        sortByPosition = true
                    }
                    
                    textStripper.getText(document)
                }
            } ?: ""
        } catch (e: Exception) {
            ""
        }
    }
    
    /**
     * Check if a PDF contains extractable text.
     * Returns false for scanned/image-only PDFs that would need OCR.
     * 
     * @param context Android context
     * @param uri URI of the PDF
     * @return true if the PDF has extractable text
     */
    suspend fun hasExtractableText(
        context: Context,
        uri: Uri
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                PDDocument.load(inputStream).use { document ->
                    val textStripper = PDFTextStripper().apply {
                        startPage = 1
                        endPage = minOf(3, document.numberOfPages) // Check first few pages
                    }
                    
                    val text = textStripper.getText(document)
                    text.trim().isNotEmpty()
                }
            } ?: false
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Get word count from a PDF.
     * 
     * @param context Android context
     * @param uri URI of the PDF
     * @return Word count
     */
    suspend fun getWordCount(
        context: Context,
        uri: Uri
    ): Int = withContext(Dispatchers.IO) {
        val text = extractToString(context, uri)
        text.split("\\s+".toRegex())
            .filter { it.isNotBlank() }
            .size
    }
    
    /**
     * Search for text within a PDF.
     * 
     * @param context Android context
     * @param uri URI of the PDF
     * @param searchText Text to search for
     * @param caseSensitive Whether search is case-sensitive
     * @return List of page numbers (1-indexed) containing the text
     */
    suspend fun searchText(
        context: Context,
        uri: Uri,
        searchText: String,
        caseSensitive: Boolean = false
    ): List<Int> = withContext(Dispatchers.IO) {
        val pagesWithText = mutableListOf<Int>()
        
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                PDDocument.load(inputStream).use { document ->
                    for (pageNum in 1..document.numberOfPages) {
                        val textStripper = PDFTextStripper().apply {
                            startPage = pageNum
                            endPage = pageNum
                        }
                        
                        val pageText = textStripper.getText(document)
                        
                        val found = if (caseSensitive) {
                            pageText.contains(searchText)
                        } else {
                            pageText.lowercase().contains(searchText.lowercase())
                        }
                        
                        if (found) {
                            pagesWithText.add(pageNum)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Return empty list on error
        }
        
        pagesWithText
    }
}
