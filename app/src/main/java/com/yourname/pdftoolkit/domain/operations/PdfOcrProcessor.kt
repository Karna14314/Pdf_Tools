package com.yourname.pdftoolkit.domain.operations

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * OCR language support.
 * Note: ML Kit's default recognizer supports Latin-based languages.
 * For other languages, different recognizers would be needed.
 */
enum class OcrLanguage(val displayName: String) {
    LATIN("Latin-based (English, Spanish, French, German, etc.)"),
    // Future: Add support for other scripts with ML Kit
}

/**
 * OCR result for a single page.
 */
data class OcrPageResult(
    val pageNumber: Int, // 1-indexed
    val text: String,
    val markdownText: String = "",
    val blocks: List<OcrTextBlock>,
    val confidence: Float
)

/**
 * Text block detected by OCR.
 */
data class OcrTextBlock(
    val text: String,
    val boundingBox: OcrBoundingBox?,
    val lines: List<OcrTextLine>
)

/**
 * Text line detected by OCR.
 */
data class OcrTextLine(
    val text: String,
    val boundingBox: OcrBoundingBox?,
    val words: List<OcrWord>
)

/**
 * Word detected by OCR.
 */
data class OcrWord(
    val text: String,
    val boundingBox: OcrBoundingBox?,
    val confidence: Float
)

/**
 * Bounding box for OCR elements.
 */
data class OcrBoundingBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

/**
 * Full OCR result for a PDF.
 */
data class OcrResult(
    val success: Boolean,
    val pages: List<OcrPageResult>,
    val fullText: String,
    val markdownText: String = "",
    val errorMessage: String? = null
)

/**
 * Result of making a PDF searchable.
 */
data class SearchablePdfResult(
    val success: Boolean,
    val pagesProcessed: Int,
    val errorMessage: String? = null
)

/**
 * OCR Processor - Performs Optical Character Recognition on PDF pages.
 * Uses flavor-specific OCR engine (ML Kit for Play Store, Tesseract for F-Droid).
 * Can extract text and make scanned PDFs searchable.
 */
class PdfOcrProcessor(private val context: Context) {
    
    private val ocrEngine = OcrEngine(context)
    private companion object {
        private const val MAX_OCR_PIXELS = 4_000_000 // ~4MP per page
        private const val OCR_CHUNK_SIZE = 3
        private const val MAX_WORDS_PER_PAGE = 1000
    }
    
    /**
     * Extract text from a PDF using OCR.
     * Useful for scanned PDFs that don't have embedded text.
     *
     * @param pdfUri PDF file URI
     * @param pageRange Pages to process (null for all pages)
     * @param progressCallback Progress callback (0-100)
     * @return OcrResult with extracted text
     */
    suspend fun extractTextWithOcr(
        pdfUri: Uri,
        pageRange: IntRange? = null,
        progressCallback: (Int) -> Unit = {}
    ): OcrResult = withContext(Dispatchers.IO) {
        var document: PDDocument? = null
        var tempFile: File? = null
        
        try {
            ensureActive()
            progressCallback(0)
            
            // Create a temp file to avoid loading everything into memory
            val cacheDir = File(context.cacheDir, "ocr_cache")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            tempFile = File(cacheDir, "temp_ocr_${System.currentTimeMillis()}.pdf")
            
            context.contentResolver.openInputStream(pdfUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext OcrResult(
                success = false,
                pages = emptyList(),
                fullText = "",
                errorMessage = "Cannot open PDF file"
            )

            // Use MemoryUsageSetting to enable temp file buffering instead of full memory load
            document = PDDocument.load(tempFile, MemoryUsageSetting.setupTempFileOnly())
            
            val totalPages = document.numberOfPages
            val pagesToProcess = pageRange ?: (0 until totalPages)
            val validPages = pagesToProcess.filter { it in 0 until totalPages }
            
            progressCallback(10)
            
            val renderer = PDFRenderer(document)
            val pageResults = mutableListOf<OcrPageResult>()
            val fullTextBuilder = StringBuilder()
            val fullMarkdownBuilder = StringBuilder()
            
            for ((index, pageIndex) in validPages.withIndex()) {
                ensureActive()

                // Render page to image
                val page = document.getPage(pageIndex)
                val dpi = getSafeOcrDpi(page.mediaBox.width, page.mediaBox.height)
                val pageImage = renderer.renderImageWithDPI(pageIndex, dpi)
                
                try {
                    ensureActive()
                    val words = performOcrOnBitmap(pageImage)

                    if (words.isNotEmpty()) {
                        val pageText = words.joinToString(" ") { it.text }
                        val pageMarkdown = buildMarkdownFromWords(words)
                        val blocks = listOf(
                            OcrTextBlock(
                                text = pageText,
                                boundingBox = null,
                                lines = listOf(
                                    OcrTextLine(
                                        text = pageText,
                                        boundingBox = null,
                                        words = words
                                    )
                                )
                            )
                        )
                        val pageResult = OcrPageResult(
                            pageNumber = pageIndex + 1,
                            text = pageText,
                            markdownText = pageMarkdown,
                            blocks = blocks,
                            confidence = 0.85f
                        )
                        pageResults.add(pageResult)

                        if (fullTextBuilder.isNotEmpty()) {
                            fullTextBuilder.append("\n\n--- Page ${pageIndex + 1} ---\n\n")
                            fullMarkdownBuilder.append("\n\n---\n\n### Page ${pageIndex + 1}\n\n")
                        } else {
                            fullMarkdownBuilder.append("### Page ${pageIndex + 1}\n\n")
                        }
                        fullTextBuilder.append(pageText)
                        fullMarkdownBuilder.append(pageMarkdown)
                    }
                } finally {
                    pageImage.recycle()
                }
                
                val progress = 10 + ((index + 1) * 85 / validPages.size)
                progressCallback(progress)
                if ((index + 1) % OCR_CHUNK_SIZE == 0) {
                    yield()
                }
            }
            
            document.close()
            progressCallback(100)
            
            OcrResult(
                success = true,
                pages = pageResults,
                fullText = fullTextBuilder.toString(),
                markdownText = fullMarkdownBuilder.toString()
            )
            
        } catch (e: CancellationException) {
            document?.close()
            throw e
        } catch (e: IOException) {
            document?.close()
            OcrResult(
                success = false,
                pages = emptyList(),
                fullText = "",
                errorMessage = "IO Error: ${e.message}"
            )
        } catch (e: Exception) {
            document?.close()
            OcrResult(
                success = false,
                pages = emptyList(),
                fullText = "",
                errorMessage = "Error: ${e.message}"
            )
        } finally {
            tempFile?.delete()
        }
    }
    
    /**
     * Make a scanned PDF searchable by adding a hidden text layer.
     * The visual appearance remains the same, but text becomes searchable/selectable.
     *
     * @param inputUri Source PDF file URI
     * @param outputUri Destination PDF file URI
     * @param progressCallback Progress callback (0-100)
     * @return SearchablePdfResult with operation status
     */
    suspend fun makeSearchable(
        inputUri: Uri,
        outputUri: Uri,
        progressCallback: (Int) -> Unit = {}
    ): SearchablePdfResult = withContext(Dispatchers.IO) {
        var document: PDDocument? = null
        var tempFile: File? = null
        
        try {
            ensureActive()
            progressCallback(0)
            
            // Create a temp file
            val cacheDir = File(context.cacheDir, "ocr_cache")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            tempFile = File(cacheDir, "temp_searchable_${System.currentTimeMillis()}.pdf")
            
            context.contentResolver.openInputStream(inputUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext SearchablePdfResult(
                success = false,
                pagesProcessed = 0,
                errorMessage = "Cannot open source PDF"
            )

            // Load with memory safety
            document = PDDocument.load(tempFile, MemoryUsageSetting.setupTempFileOnly())
            
            val totalPages = document.numberOfPages
            progressCallback(10)
            
            val renderer = PDFRenderer(document)
            
            for (pageIndex in 0 until totalPages) {
                ensureActive()
                val page = document.getPage(pageIndex)
                
                // Render page to image for OCR
                val dpi = getSafeOcrDpi(page.mediaBox.width, page.mediaBox.height)
                val pageImage = renderer.renderImageWithDPI(pageIndex, dpi)
                
                try {
                    ensureActive()
                    val words = performOcrOnBitmap(pageImage)

                    if (words.isNotEmpty()) {
                        addTextLayerToPage(document, page, words, pageImage.width, pageImage.height, dpi)
                    }
                } finally {
                    pageImage.recycle()
                }
                
                val progress = 10 + ((pageIndex + 1) * 80 / totalPages)
                progressCallback(progress)
                if ((pageIndex + 1) % OCR_CHUNK_SIZE == 0) {
                    yield()
                }
            }
            
            progressCallback(90)
            ensureActive()
            
            // Save the document
            context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                document.save(outputStream)
                outputStream.flush()
            }
            
            document.close()
            progressCallback(100)
            
            SearchablePdfResult(
                success = true,
                pagesProcessed = totalPages
            )
            
        } catch (e: CancellationException) {
            document?.close()
            throw e
        } catch (e: IOException) {
            document?.close()
            SearchablePdfResult(
                success = false,
                pagesProcessed = 0,
                errorMessage = "IO Error: ${e.message}"
            )
        } catch (e: Exception) {
            document?.close()
            SearchablePdfResult(
                success = false,
                pagesProcessed = 0,
                errorMessage = "Error: ${e.message}"
            )
        } finally {
            tempFile?.delete()
        }
    }
    
    /**
     * Extract text from an image using OCR.
     */
    suspend fun extractTextFromImage(
        imageUri: Uri
    ): String = withContext(Dispatchers.IO) {
        try {
            ensureActive()
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(imageUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            } ?: return@withContext ""

            val sampleSize = calculateInSampleSize(options.outWidth, options.outHeight, MAX_OCR_PIXELS)
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            val bitmap = context.contentResolver.openInputStream(imageUri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return@withContext ""
            
            ensureActive()
            val words = performOcrOnBitmap(bitmap)
            bitmap.recycle()
            
            words.joinToString(" ") { it.text }
        } catch (e: Exception) {
            ""
        }
    }
    
    /**
     * Perform OCR on a bitmap using the flavor-specific OCR engine.
     */
    private suspend fun performOcrOnBitmap(bitmap: Bitmap): List<OcrWord> {
        ocrEngine.initialize()
        return ocrEngine.recognizeText(bitmap)
    }

    private fun getSafeOcrDpi(pageWidthPoints: Float, pageHeightPoints: Float): Float {
        return OcrDpiUtil.getSafeOcrDpi(pageWidthPoints, pageHeightPoints)
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxPixels: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while ((width / sample) * (height / sample) > maxPixels) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }
    
    /**
     * Add invisible text layer to a page for searchability.
     * Places each word individually at its detected position scaled to PDF point space.
     */
    private fun addTextLayerToPage(
        document: PDDocument,
        page: PDPage,
        words: List<OcrWord>,
        imageWidth: Int,
        imageHeight: Int,
        dpi: Float
    ) {
        val cropBox = page.cropBox
        val font = PDType1Font.HELVETICA

        val contentStream = PDPageContentStream(
            document,
            page,
            PDPageContentStream.AppendMode.APPEND,
            true,
            true
        )

        try {
            val graphicsState = PDExtendedGraphicsState()
            graphicsState.nonStrokingAlphaConstant = 0f
            contentStream.setGraphicsStateParameters(graphicsState)

            val scale = 72f / dpi
            for (word in words.take(MAX_WORDS_PER_PAGE)) {
                val box = word.boundingBox ?: continue
                val cleanText = word.text.filter { it.code < 256 }
                if (cleanText.isBlank()) continue

                val pdfX = cropBox.lowerLeftX + box.left * scale
                val pdfY = cropBox.upperRightY - box.bottom * scale
                val boxHeightPts = (box.bottom - box.top) * scale
                val fontSize = (boxHeightPts * 0.8f).coerceIn(4f, 24f)

                contentStream.beginText()
                contentStream.setFont(font, fontSize)
                contentStream.newLineAtOffset(pdfX, pdfY)
                contentStream.showText(cleanText)
                contentStream.endText()
            }
        } finally {
            contentStream.close()
        }
    }

    /**
     * Build Markdown formatted text from recognized OCR words.
     * Uses bounding box layout analysis to structure headers, paragraphs, and list items.
     */
    private fun buildMarkdownFromWords(words: List<OcrWord>): String {
        if (words.isEmpty()) return ""

        val validWords = words.filter { it.boundingBox != null }
        if (validWords.isEmpty()) {
            return words.joinToString(" ") { it.text }
        }

        // Group words into lines by bounding box vertical position
        val lines = mutableListOf<MutableList<OcrWord>>()
        val sortedWords = validWords.sortedWith(
            compareBy<OcrWord> { it.boundingBox!!.top }.thenBy { it.boundingBox!!.left }
        )

        for (word in sortedWords) {
            val box = word.boundingBox!!
            val matchingLine = lines.find { line ->
                val lineTop = line.minOf { it.boundingBox!!.top }
                val lineBottom = line.maxOf { it.boundingBox!!.bottom }
                val wordCenterY = (box.top + box.bottom) / 2
                wordCenterY >= (lineTop - 6) && wordCenterY <= (lineBottom + 6)
            }

            if (matchingLine != null) {
                matchingLine.add(word)
            } else {
                lines.add(mutableListOf(word))
            }
        }

        lines.forEach { line -> line.sortBy { it.boundingBox!!.left } }

        val lineHeights = lines.map { line ->
            line.maxOf { it.boundingBox!!.bottom } - line.minOf { it.boundingBox!!.top }
        }.sorted()
        val medianHeight = if (lineHeights.isNotEmpty()) lineHeights[lineHeights.size / 2].toFloat() else 20f

        val markdownBuilder = StringBuilder()
        var prevBottom = -1

        for (line in lines) {
            val lineText = line.joinToString(" ") { it.text }.trim()
            if (lineText.isBlank()) continue

            val lineTop = line.minOf { it.boundingBox!!.top }
            val lineBottom = line.maxOf { it.boundingBox!!.bottom }
            val lineHeight = (lineBottom - lineTop).toFloat()

            if (prevBottom != -1 && (lineTop - prevBottom) > (medianHeight * 1.5f)) {
                markdownBuilder.append("\n\n")
            } else if (prevBottom != -1) {
                markdownBuilder.append("\n")
            }

            when {
                lineHeight > medianHeight * 1.6f -> {
                    markdownBuilder.append("# ").append(lineText)
                }
                lineHeight > medianHeight * 1.3f -> {
                    markdownBuilder.append("## ").append(lineText)
                }
                lineText.startsWith("•") || lineText.startsWith("- ") || lineText.startsWith("* ") -> {
                    markdownBuilder.append("- ").append(lineText.removePrefix("•").removePrefix("-").removePrefix("*").trim())
                }
                else -> {
                    markdownBuilder.append(lineText)
                }
            }

            prevBottom = lineBottom
        }

        return markdownBuilder.toString()
    }
    
    /**
     * Close the OCR engine when done.
     */
    fun close() {
        ocrEngine.close()
    }
}
