package com.yourname.pdftoolkit.domain.operations

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Types of annotations supported.
 */
sealed class PdfAnnotation {
    abstract val pageIndex: Int
    
    /**
     * Highlight annotation - highlights text with semi-transparent color.
     */
    data class Highlight(
        override val pageIndex: Int,
        val rect: AnnotationRect,
        val color: Int = Color.YELLOW,
        val opacity: Float = 0.4f
    ) : PdfAnnotation()
    
    /**
     * Underline annotation.
     */
    data class Underline(
        override val pageIndex: Int,
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val color: Int = Color.RED,
        val strokeWidth: Float = 2f
    ) : PdfAnnotation()
    
    /**
     * Strikethrough annotation.
     */
    data class Strikethrough(
        override val pageIndex: Int,
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val color: Int = Color.RED,
        val strokeWidth: Float = 2f
    ) : PdfAnnotation()
    
    /**
     * Freehand drawing annotation.
     */
    data class FreehandDrawing(
        override val pageIndex: Int,
        val paths: List<DrawingPath>,
        val color: Int = Color.BLACK,
        val strokeWidth: Float = 3f
    ) : PdfAnnotation()
    
    /**
     * Text note / Sticky note annotation.
     */
    data class StickyNote(
        override val pageIndex: Int,
        val x: Float,
        val y: Float,
        val text: String,
        val color: Int = Color.YELLOW,
        val width: Float = 150f,
        val height: Float = 100f
    ) : PdfAnnotation()
    
    /**
     * Text box annotation.
     */
    data class TextBox(
        override val pageIndex: Int,
        val rect: AnnotationRect,
        val text: String,
        val fontSize: Float = 12f,
        val textColor: Int = Color.BLACK,
        val backgroundColor: Int? = null,
        val borderColor: Int? = Color.BLACK
    ) : PdfAnnotation()
    
    /**
     * Shape annotation (rectangle, circle, arrow).
     */
    data class Shape(
        override val pageIndex: Int,
        val shapeType: ShapeType,
        val rect: AnnotationRect,
        val strokeColor: Int = Color.BLACK,
        val fillColor: Int? = null,
        val strokeWidth: Float = 2f
    ) : PdfAnnotation()
    
    /**
     * Stamp annotation (approved, rejected, draft, etc.).
     */
    data class Stamp(
        override val pageIndex: Int,
        val stampType: StampType,
        val x: Float,
        val y: Float,
        val width: Float = 150f,
        val height: Float = 50f,
        val rotation: Float = 0f
    ) : PdfAnnotation()
}

/**
 * Rectangle for annotations.
 */
data class AnnotationRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

/**
 * Drawing path for freehand annotation.
 */
data class DrawingPath(
    val points: List<DrawingPoint>
)

/**
 * Point in a drawing path.
 */
data class DrawingPoint(
    val x: Float,
    val y: Float
)

/**
 * Shape types for shape annotations.
 */
enum class ShapeType {
    RECTANGLE,
    CIRCLE,
    ARROW,
    LINE
}

/**
 * Predefined stamp types.
 */
enum class StampType(val text: String, val color: Int) {
    APPROVED("APPROVED", Color.GREEN),
    REJECTED("REJECTED", Color.RED),
    DRAFT("DRAFT", Color.GRAY),
    CONFIDENTIAL("CONFIDENTIAL", Color.RED),
    FINAL("FINAL", Color.BLUE),
    FOR_REVIEW("FOR REVIEW", Color.YELLOW),
    VOID("VOID", Color.RED)
}

/**
 * Result of annotation operation.
 */
data class AnnotationResult(
    val success: Boolean,
    val annotationsAdded: Int,
    val errorMessage: String? = null
)

/**
 * PDF Annotator - Adds various annotations to PDF documents.
 * Supports highlights, underlines, drawings, sticky notes, text boxes, shapes, and stamps.
 * Uses Apache PDFBox-Android (Apache 2.0 License).
 */
class PdfAnnotator {
    
    /**
     * Add annotations to a PDF document.
     *
     * @param context Android context
     * @param inputUri Source PDF file URI
     * @param outputUri Destination PDF file URI
     * @param annotations List of annotations to add
     * @param progressCallback Progress callback (0-100)
     * @return AnnotationResult with operation status
     */
    suspend fun addAnnotations(
        context: Context,
        inputUri: Uri,
        outputUri: Uri,
        annotations: List<PdfAnnotation>,
        progressCallback: (Int) -> Unit = {}
    ): AnnotationResult = withContext(Dispatchers.IO) {
        var document: PDDocument? = null
        
        try {
            progressCallback(0)
            
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext AnnotationResult(
                    success = false,
                    annotationsAdded = 0,
                    errorMessage = "Cannot open source PDF"
                )
            
            document = PDDocument.load(inputStream)
            inputStream.close()
            
            progressCallback(10)
            
            val totalPages = document.numberOfPages
            var annotationsAdded = 0
            
            // Group annotations by page
            val annotationsByPage = annotations.groupBy { it.pageIndex }
            
            for ((pageIndex, pageAnnotations) in annotationsByPage) {
                if (pageIndex < 0 || pageIndex >= totalPages) continue
                
                val page = document.getPage(pageIndex)
                
                for (annotation in pageAnnotations) {
                    val added = addAnnotationToPage(document, page, annotation)
                    if (added) annotationsAdded++
                }
                
                val progress = 10 + ((pageIndex + 1) * 80 / totalPages)
                progressCallback(progress)
            }
            
            progressCallback(90)
            
            // Save the document
            context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                document.save(outputStream)
            }
            
            document.close()
            progressCallback(100)
            
            AnnotationResult(
                success = true,
                annotationsAdded = annotationsAdded
            )
            
        } catch (e: IOException) {
            document?.close()
            AnnotationResult(
                success = false,
                annotationsAdded = 0,
                errorMessage = "IO Error: ${e.message}"
            )
        } catch (e: Exception) {
            document?.close()
            AnnotationResult(
                success = false,
                annotationsAdded = 0,
                errorMessage = "Error: ${e.message}"
            )
        }
    }
    
    /**
     * Add a single annotation to a page.
     */
    private fun addAnnotationToPage(
        document: PDDocument,
        page: PDPage,
        annotation: PdfAnnotation
    ): Boolean {
        return try {
            when (annotation) {
                is PdfAnnotation.Highlight -> addHighlight(document, page, annotation)
                is PdfAnnotation.Underline -> addUnderline(document, page, annotation)
                is PdfAnnotation.Strikethrough -> addStrikethrough(document, page, annotation)
                is PdfAnnotation.FreehandDrawing -> addFreehandDrawing(document, page, annotation)
                is PdfAnnotation.StickyNote -> addStickyNote(document, page, annotation)
                is PdfAnnotation.TextBox -> addTextBox(document, page, annotation)
                is PdfAnnotation.Shape -> addShape(document, page, annotation)
                is PdfAnnotation.Stamp -> addStamp(document, page, annotation)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Add highlight annotation.
     */
    private fun addHighlight(document: PDDocument, page: PDPage, annotation: PdfAnnotation.Highlight) {
        val contentStream = PDPageContentStream(
            document, page,
            PDPageContentStream.AppendMode.APPEND, true, true
        )
        
        try {
            // Set transparency
            val graphicsState = PDExtendedGraphicsState()
            graphicsState.nonStrokingAlphaConstant = annotation.opacity
            contentStream.setGraphicsStateParameters(graphicsState)
            
            // Set color
            val r = Color.red(annotation.color)
            val g = Color.green(annotation.color)
            val b = Color.blue(annotation.color)
            contentStream.setNonStrokingColor(r, g, b)
            
            // Draw filled rectangle
            contentStream.addRect(
                annotation.rect.x,
                annotation.rect.y,
                annotation.rect.width,
                annotation.rect.height
            )
            contentStream.fill()
        } finally {
            contentStream.close()
        }
    }
    
    /**
     * Add underline annotation.
     */
    private fun addUnderline(document: PDDocument, page: PDPage, annotation: PdfAnnotation.Underline) {
        val contentStream = PDPageContentStream(
            document, page,
            PDPageContentStream.AppendMode.APPEND, true, true
        )
        
        try {
            val r = Color.red(annotation.color)
            val g = Color.green(annotation.color)
            val b = Color.blue(annotation.color)
            contentStream.setStrokingColor(r, g, b)
            contentStream.setLineWidth(annotation.strokeWidth)
            
            contentStream.moveTo(annotation.startX, annotation.startY)
            contentStream.lineTo(annotation.endX, annotation.endY)
            contentStream.stroke()
        } finally {
            contentStream.close()
        }
    }
    
    /**
     * Add strikethrough annotation.
     */
    private fun addStrikethrough(document: PDDocument, page: PDPage, annotation: PdfAnnotation.Strikethrough) {
        val contentStream = PDPageContentStream(
            document, page,
            PDPageContentStream.AppendMode.APPEND, true, true
        )
        
        try {
            val r = Color.red(annotation.color)
            val g = Color.green(annotation.color)
            val b = Color.blue(annotation.color)
            contentStream.setStrokingColor(r, g, b)
            contentStream.setLineWidth(annotation.strokeWidth)
            
            contentStream.moveTo(annotation.startX, annotation.startY)
            contentStream.lineTo(annotation.endX, annotation.endY)
            contentStream.stroke()
        } finally {
            contentStream.close()
        }
    }
    
    /**
     * Add freehand drawing annotation.
     */
    private fun addFreehandDrawing(document: PDDocument, page: PDPage, annotation: PdfAnnotation.FreehandDrawing) {
        val contentStream = PDPageContentStream(
            document, page,
            PDPageContentStream.AppendMode.APPEND, true, true
        )
        
        try {
            val r = Color.red(annotation.color)
            val g = Color.green(annotation.color)
            val b = Color.blue(annotation.color)
            contentStream.setStrokingColor(r, g, b)
            contentStream.setLineWidth(annotation.strokeWidth)
            contentStream.setLineCapStyle(1) // Round cap
            contentStream.setLineJoinStyle(1) // Round join
            
            for (path in annotation.paths) {
                if (path.points.isEmpty()) continue
                
                val firstPoint = path.points.first()
                contentStream.moveTo(firstPoint.x, firstPoint.y)
                
                for (i in 1 until path.points.size) {
                    val point = path.points[i]
                    contentStream.lineTo(point.x, point.y)
                }
                
                contentStream.stroke()
            }
        } finally {
            contentStream.close()
        }
    }
    
    /**
     * Add sticky note annotation.
     */
    private fun addStickyNote(document: PDDocument, page: PDPage, annotation: PdfAnnotation.StickyNote) {
        // Create sticky note bitmap
        val bitmap = createStickyNoteBitmap(annotation)
        val pdImage = LosslessFactory.createFromImage(document, bitmap)
        
        val contentStream = PDPageContentStream(
            document, page,
            PDPageContentStream.AppendMode.APPEND, true, true
        )
        
        try {
            contentStream.drawImage(pdImage, annotation.x, annotation.y, annotation.width, annotation.height)
        } finally {
            contentStream.close()
            bitmap.recycle()
        }
    }
    
    /**
     * Create bitmap for sticky note.
     */
    private fun createStickyNoteBitmap(annotation: PdfAnnotation.StickyNote): Bitmap {
        val width = annotation.width.toInt()
        val height = annotation.height.toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Background
        val bgPaint = Paint().apply {
            color = annotation.color
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        
        // Border
        val borderPaint = Paint().apply {
            color = Color.DKGRAY
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRect(1f, 1f, width - 1f, height - 1f, borderPaint)
        
        // Text
        val textPaint = Paint().apply {
            color = Color.BLACK
            textSize = 12f
            isAntiAlias = true
        }
        
        // Simple text wrapping
        val padding = 8f
        val lines = wrapText(annotation.text, textPaint, width - padding * 2)
        var y = padding + textPaint.textSize
        
        for (line in lines) {
            if (y > height - padding) break
            canvas.drawText(line, padding, y, textPaint)
            y += textPaint.textSize + 4
        }
        
        return bitmap
    }
    
    /**
     * Wrap text to fit within a width.
     */
    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()
        
        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            val testWidth = paint.measureText(testLine)
            
            if (testWidth > maxWidth) {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine.toString())
                    currentLine = StringBuilder(word)
                } else {
                    lines.add(word)
                }
            } else {
                currentLine = StringBuilder(testLine)
            }
        }
        
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.toString())
        }
        
        return lines
    }
    
    /**
     * Add text box annotation.
     */
    private fun addTextBox(document: PDDocument, page: PDPage, annotation: PdfAnnotation.TextBox) {
        val contentStream = PDPageContentStream(
            document, page,
            PDPageContentStream.AppendMode.APPEND, true, true
        )
        
        try {
            val rect = annotation.rect
            
            // Draw background if specified
            if (annotation.backgroundColor != null) {
                val r = Color.red(annotation.backgroundColor)
                val g = Color.green(annotation.backgroundColor)
                val b = Color.blue(annotation.backgroundColor)
                contentStream.setNonStrokingColor(r, g, b)
                contentStream.addRect(rect.x, rect.y, rect.width, rect.height)
                contentStream.fill()
            }
            
            // Draw border if specified
            if (annotation.borderColor != null) {
                val r = Color.red(annotation.borderColor)
                val g = Color.green(annotation.borderColor)
                val b = Color.blue(annotation.borderColor)
                contentStream.setStrokingColor(r, g, b)
                contentStream.setLineWidth(1f)
                contentStream.addRect(rect.x, rect.y, rect.width, rect.height)
                contentStream.stroke()
            }
            
            // Draw text
            val font = PDType1Font.HELVETICA
            val r = Color.red(annotation.textColor)
            val g = Color.green(annotation.textColor)
            val b = Color.blue(annotation.textColor)
            contentStream.setNonStrokingColor(r, g, b)
            contentStream.setFont(font, annotation.fontSize)
            
            contentStream.beginText()
            contentStream.newLineAtOffset(rect.x + 4, rect.y + rect.height - annotation.fontSize - 4)
            contentStream.showText(annotation.text)
            contentStream.endText()
            
        } finally {
            contentStream.close()
        }
    }
    
    /**
     * Add shape annotation.
     */
    private fun addShape(document: PDDocument, page: PDPage, annotation: PdfAnnotation.Shape) {
        val contentStream = PDPageContentStream(
            document, page,
            PDPageContentStream.AppendMode.APPEND, true, true
        )
        
        try {
            val rect = annotation.rect
            
            // Set stroke color
            val sr = Color.red(annotation.strokeColor)
            val sg = Color.green(annotation.strokeColor)
            val sb = Color.blue(annotation.strokeColor)
            contentStream.setStrokingColor(sr, sg, sb)
            contentStream.setLineWidth(annotation.strokeWidth)
            
            // Set fill color if specified
            if (annotation.fillColor != null) {
                val fr = Color.red(annotation.fillColor)
                val fg = Color.green(annotation.fillColor)
                val fb = Color.blue(annotation.fillColor)
                contentStream.setNonStrokingColor(fr, fg, fb)
            }
            
            when (annotation.shapeType) {
                ShapeType.RECTANGLE -> {
                    contentStream.addRect(rect.x, rect.y, rect.width, rect.height)
                    if (annotation.fillColor != null) {
                        contentStream.fillAndStroke()
                    } else {
                        contentStream.stroke()
                    }
                }
                
                ShapeType.CIRCLE -> {
                    // Approximate circle with bezier curves
                    val cx = rect.x + rect.width / 2
                    val cy = rect.y + rect.height / 2
                    val rx = rect.width / 2
                    val ry = rect.height / 2
                    val k = 0.5522848f
                    
                    contentStream.moveTo(cx - rx, cy)
                    contentStream.curveTo(cx - rx, cy + ry * k, cx - rx * k, cy + ry, cx, cy + ry)
                    contentStream.curveTo(cx + rx * k, cy + ry, cx + rx, cy + ry * k, cx + rx, cy)
                    contentStream.curveTo(cx + rx, cy - ry * k, cx + rx * k, cy - ry, cx, cy - ry)
                    contentStream.curveTo(cx - rx * k, cy - ry, cx - rx, cy - ry * k, cx - rx, cy)
                    
                    if (annotation.fillColor != null) {
                        contentStream.fillAndStroke()
                    } else {
                        contentStream.stroke()
                    }
                }
                
                ShapeType.LINE -> {
                    contentStream.moveTo(rect.x, rect.y)
                    contentStream.lineTo(rect.x + rect.width, rect.y + rect.height)
                    contentStream.stroke()
                }
                
                ShapeType.ARROW -> {
                    val startX = rect.x
                    val startY = rect.y
                    val endX = rect.x + rect.width
                    val endY = rect.y + rect.height
                    
                    // Draw line
                    contentStream.moveTo(startX, startY)
                    contentStream.lineTo(endX, endY)
                    contentStream.stroke()
                    
                    // Draw arrowhead
                    val arrowSize = 10f
                    val angle = kotlin.math.atan2((endY - startY).toDouble(), (endX - startX).toDouble())
                    val angle1 = angle + Math.toRadians(150.0)
                    val angle2 = angle - Math.toRadians(150.0)
                    
                    contentStream.moveTo(endX, endY)
                    contentStream.lineTo(
                        endX + arrowSize * kotlin.math.cos(angle1).toFloat(),
                        endY + arrowSize * kotlin.math.sin(angle1).toFloat()
                    )
                    contentStream.stroke()
                    
                    contentStream.moveTo(endX, endY)
                    contentStream.lineTo(
                        endX + arrowSize * kotlin.math.cos(angle2).toFloat(),
                        endY + arrowSize * kotlin.math.sin(angle2).toFloat()
                    )
                    contentStream.stroke()
                }
            }
            
        } finally {
            contentStream.close()
        }
    }
    
    /**
     * Add stamp annotation.
     */
    private fun addStamp(document: PDDocument, page: PDPage, annotation: PdfAnnotation.Stamp) {
        // Create stamp bitmap
        val bitmap = createStampBitmap(annotation)
        val pdImage = LosslessFactory.createFromImage(document, bitmap)
        
        val contentStream = PDPageContentStream(
            document, page,
            PDPageContentStream.AppendMode.APPEND, true, true
        )
        
        try {
            // Apply rotation if needed
            if (annotation.rotation != 0f) {
                contentStream.saveGraphicsState()
                val radians = Math.toRadians(annotation.rotation.toDouble())
                val matrix = com.tom_roush.pdfbox.util.Matrix(
                    kotlin.math.cos(radians).toFloat(), kotlin.math.sin(radians).toFloat(),
                    -kotlin.math.sin(radians).toFloat(), kotlin.math.cos(radians).toFloat(),
                    annotation.x, annotation.y
                )
                contentStream.transform(matrix)
                contentStream.drawImage(pdImage, 0f, 0f, annotation.width, annotation.height)
                contentStream.restoreGraphicsState()
            } else {
                contentStream.drawImage(pdImage, annotation.x, annotation.y, annotation.width, annotation.height)
            }
        } finally {
            contentStream.close()
            bitmap.recycle()
        }
    }
    
    /**
     * Create bitmap for stamp.
     */
    private fun createStampBitmap(annotation: PdfAnnotation.Stamp): Bitmap {
        val width = annotation.width.toInt()
        val height = annotation.height.toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        canvas.drawColor(Color.TRANSPARENT)
        
        // Border
        val borderPaint = Paint().apply {
            color = annotation.stampType.color
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        val rect = RectF(4f, 4f, width - 4f, height - 4f)
        canvas.drawRoundRect(rect, 8f, 8f, borderPaint)
        
        // Text
        val textPaint = Paint().apply {
            color = annotation.stampType.color
            textSize = height * 0.5f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            isAntiAlias = true
        }
        
        val textY = height / 2f + textPaint.textSize / 3f
        canvas.drawText(annotation.stampType.text, width / 2f, textY, textPaint)
        
        return bitmap
    }
}
