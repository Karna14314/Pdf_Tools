package com.yourname.pdftoolkit.domain.operations

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.cos
import kotlin.math.sin

/**
 * Watermark position options on the page.
 */
enum class WatermarkPosition {
    CENTER,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    TILED
}

/**
 * Watermark type - text or image.
 */
sealed class WatermarkType {
    data class Text(
        val content: String,
        val fontSize: Float = 48f,
        val rotation: Float = 45f
    ) : WatermarkType()
    
    data class Image(
        val imageUri: Uri,
        val width: Float = 200f,
        val height: Float = 200f,
        val rotation: Float = 0f
    ) : WatermarkType()
}

/**
 * Configuration for watermark appearance.
 */
data class WatermarkConfig(
    val type: WatermarkType,
    val position: WatermarkPosition = WatermarkPosition.CENTER,
    val opacity: Float = 0.3f, // 0.0 to 1.0
    val applyToAllPages: Boolean = true,
    val specificPages: List<Int> = emptyList() // 0-indexed
)

/**
 * Result of watermark operation.
 */
data class WatermarkResult(
    val success: Boolean,
    val pagesProcessed: Int,
    val errorMessage: String? = null
)

/**
 * PDF Watermarker - Adds text or image watermarks to PDF documents.
 * Uses Apache PDFBox-Android (Apache 2.0 License).
 */
class PdfWatermarker {
    
    /**
     * Add watermark to a PDF document.
     *
     * @param context Android context for file operations
     * @param inputUri Source PDF file URI
     * @param outputUri Destination PDF file URI
     * @param config Watermark configuration
     * @param progressCallback Progress callback (0-100)
     * @return WatermarkResult with operation status
     */
    suspend fun addWatermark(
        context: Context,
        inputUri: Uri,
        outputUri: Uri,
        config: WatermarkConfig,
        progressCallback: (Int) -> Unit = {}
    ): WatermarkResult = withContext(Dispatchers.IO) {
        var document: PDDocument? = null
        
        try {
            progressCallback(0)
            
            // Load the PDF document
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext WatermarkResult(
                    success = false,
                    pagesProcessed = 0,
                    errorMessage = "Cannot open source PDF"
                )
            
            document = PDDocument.load(inputStream)
            inputStream.close()
            
            val totalPages = document.numberOfPages
            if (totalPages == 0) {
                document.close()
                return@withContext WatermarkResult(
                    success = false,
                    pagesProcessed = 0,
                    errorMessage = "PDF has no pages"
                )
            }
            
            progressCallback(10)
            
            // Determine which pages to watermark
            val pagesToProcess = if (config.applyToAllPages) {
                (0 until totalPages).toList()
            } else {
                config.specificPages.filter { it in 0 until totalPages }
            }
            
            // Load image bitmap if using image watermark
            val watermarkBitmap: Bitmap? = when (val type = config.type) {
                is WatermarkType.Image -> {
                    context.contentResolver.openInputStream(type.imageUri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                }
                else -> null
            }
            
            // Apply watermark to each page
            pagesToProcess.forEachIndexed { index, pageIndex ->
                val page = document.getPage(pageIndex)
                
                when (config.type) {
                    is WatermarkType.Text -> {
                        addTextWatermark(document, page, config.type, config.position, config.opacity)
                    }
                    is WatermarkType.Image -> {
                        watermarkBitmap?.let {
                            addImageWatermark(document, page, it, config.type, config.position, config.opacity)
                        }
                    }
                }
                
                val progress = 10 + ((index + 1) * 80 / pagesToProcess.size)
                progressCallback(progress)
            }
            
            // Recycle bitmap
            watermarkBitmap?.recycle()
            
            progressCallback(90)
            
            // Save the document
            context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                document.save(outputStream)
            }
            
            document.close()
            progressCallback(100)
            
            WatermarkResult(
                success = true,
                pagesProcessed = pagesToProcess.size
            )
            
        } catch (e: IOException) {
            document?.close()
            WatermarkResult(
                success = false,
                pagesProcessed = 0,
                errorMessage = "IO Error: ${e.message}"
            )
        } catch (e: Exception) {
            document?.close()
            WatermarkResult(
                success = false,
                pagesProcessed = 0,
                errorMessage = "Error: ${e.message}"
            )
        }
    }
    
    /**
     * Add text watermark to a page.
     */
    private fun addTextWatermark(
        document: PDDocument,
        page: PDPage,
        textConfig: WatermarkType.Text,
        position: WatermarkPosition,
        opacity: Float
    ) {
        val pageRect = page.mediaBox
        val pageWidth = pageRect.width
        val pageHeight = pageRect.height
        
        val contentStream = PDPageContentStream(
            document,
            page,
            PDPageContentStream.AppendMode.APPEND,
            true,
            true
        )
        
        try {
            // Set transparency
            val graphicsState = PDExtendedGraphicsState()
            graphicsState.nonStrokingAlphaConstant = opacity
            graphicsState.strokingAlphaConstant = opacity
            contentStream.setGraphicsStateParameters(graphicsState)
            
            // Set font
            val font = PDType1Font.HELVETICA_BOLD
            contentStream.setFont(font, textConfig.fontSize)
            contentStream.setNonStrokingColor(128, 128, 128) // Gray color
            
            // Calculate text dimensions
            val textWidth = font.getStringWidth(textConfig.content) / 1000 * textConfig.fontSize
            val textHeight = textConfig.fontSize
            
            if (position == WatermarkPosition.TILED) {
                // Tiled watermark
                addTiledTextWatermark(contentStream, textConfig, font, pageWidth, pageHeight)
            } else {
                // Single position watermark
                val (x, y) = calculatePosition(position, pageWidth, pageHeight, textWidth, textHeight)
                
                contentStream.beginText()
                
                // Apply rotation
                val radians = Math.toRadians(textConfig.rotation.toDouble())
                val rotationMatrix = Matrix(
                    cos(radians).toFloat(), sin(radians).toFloat(),
                    -sin(radians).toFloat(), cos(radians).toFloat(),
                    x, y
                )
                contentStream.setTextMatrix(rotationMatrix)
                
                contentStream.showText(textConfig.content)
                contentStream.endText()
            }
        } finally {
            contentStream.close()
        }
    }
    
    /**
     * Add tiled text watermark across the page.
     */
    private fun addTiledTextWatermark(
        contentStream: PDPageContentStream,
        textConfig: WatermarkType.Text,
        font: PDType1Font,
        pageWidth: Float,
        pageHeight: Float
    ) {
        val textWidth = font.getStringWidth(textConfig.content) / 1000 * textConfig.fontSize
        val spacing = textWidth * 1.5f
        val verticalSpacing = textConfig.fontSize * 3
        
        var y = 0f
        while (y < pageHeight + verticalSpacing) {
            var x = 0f
            while (x < pageWidth + spacing) {
                contentStream.beginText()
                
                val radians = Math.toRadians(textConfig.rotation.toDouble())
                val rotationMatrix = Matrix(
                    cos(radians).toFloat(), sin(radians).toFloat(),
                    -sin(radians).toFloat(), cos(radians).toFloat(),
                    x, y
                )
                contentStream.setTextMatrix(rotationMatrix)
                contentStream.showText(textConfig.content)
                contentStream.endText()
                
                x += spacing
            }
            y += verticalSpacing
        }
    }
    
    /**
     * Add image watermark to a page.
     */
    private fun addImageWatermark(
        document: PDDocument,
        page: PDPage,
        bitmap: Bitmap,
        imageConfig: WatermarkType.Image,
        position: WatermarkPosition,
        opacity: Float
    ) {
        val pageRect = page.mediaBox
        val pageWidth = pageRect.width
        val pageHeight = pageRect.height
        
        val contentStream = PDPageContentStream(
            document,
            page,
            PDPageContentStream.AppendMode.APPEND,
            true,
            true
        )
        
        try {
            // Set transparency
            val graphicsState = PDExtendedGraphicsState()
            graphicsState.nonStrokingAlphaConstant = opacity
            graphicsState.strokingAlphaConstant = opacity
            contentStream.setGraphicsStateParameters(graphicsState)
            
            // Create PDF image from bitmap
            val pdImage = LosslessFactory.createFromImage(document, bitmap)
            
            val imgWidth = imageConfig.width
            val imgHeight = imageConfig.height
            
            if (position == WatermarkPosition.TILED) {
                // Tiled image watermark
                val spacingX = imgWidth * 1.5f
                val spacingY = imgHeight * 1.5f
                
                var y = 0f
                while (y < pageHeight) {
                    var x = 0f
                    while (x < pageWidth) {
                        contentStream.drawImage(pdImage, x, y, imgWidth, imgHeight)
                        x += spacingX
                    }
                    y += spacingY
                }
            } else {
                val (x, y) = calculatePosition(position, pageWidth, pageHeight, imgWidth, imgHeight)
                contentStream.drawImage(pdImage, x, y, imgWidth, imgHeight)
            }
        } finally {
            contentStream.close()
        }
    }
    
    /**
     * Calculate position coordinates based on position enum.
     */
    private fun calculatePosition(
        position: WatermarkPosition,
        pageWidth: Float,
        pageHeight: Float,
        elementWidth: Float,
        elementHeight: Float
    ): Pair<Float, Float> {
        val margin = 50f
        
        return when (position) {
            WatermarkPosition.CENTER -> {
                Pair((pageWidth - elementWidth) / 2, (pageHeight - elementHeight) / 2)
            }
            WatermarkPosition.TOP_LEFT -> {
                Pair(margin, pageHeight - elementHeight - margin)
            }
            WatermarkPosition.TOP_RIGHT -> {
                Pair(pageWidth - elementWidth - margin, pageHeight - elementHeight - margin)
            }
            WatermarkPosition.BOTTOM_LEFT -> {
                Pair(margin, margin)
            }
            WatermarkPosition.BOTTOM_RIGHT -> {
                Pair(pageWidth - elementWidth - margin, margin)
            }
            WatermarkPosition.TILED -> {
                Pair(0f, 0f) // Not used for tiled
            }
        }
    }
}
