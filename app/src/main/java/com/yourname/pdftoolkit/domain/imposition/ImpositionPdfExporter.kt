package com.yourname.pdftoolkit.domain.imposition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Production-grade Imposition PDF Exporter.
 * Executes the complete pipeline: PDF Input -> Imposition Engine Layouts -> PDF Generator.
 * Uses native PdfRenderer / Canvas to draw transformed pages and PDFBox to assemble final vector/raster PDF files.
 */
object ImpositionPdfExporter {

    suspend fun exportImposedPdf(
        context: Context,
        inputUri: Uri,
        sheetLayouts: List<SheetLayout>,
        outputFile: File
    ): File = withContext(Dispatchers.IO) {
        val contentResolver = context.contentResolver
        val pfd = contentResolver.openFileDescriptor(inputUri, "r")
            ?: throw IllegalArgumentException("Failed to open source PDF descriptor")

        val pdfRenderer = PdfRenderer(pfd)
        val outDocument = PDDocument()

        try {
            for (sheet in sheetLayouts) {
                val pdPage = PDPage(PDRectangle(sheet.widthPt, sheet.heightPt))
                outDocument.addPage(pdPage)

                // Render sheet onto high-res Android bitmap canvas (300 DPI scaling = 300 / 72 = 4.166f)
                val dpiScale = 3f
                val bitmapWidth = (sheet.widthPt * dpiScale).toInt()
                val bitmapHeight = (sheet.heightPt * dpiScale).toInt()

                val sheetBitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(sheetBitmap)
                canvas.drawColor(android.graphics.Color.WHITE) // Clean background

                // Render each placed page onto sheet canvas
                for (placement in sheet.placements) {
                    if (placement.sourcePageIndex < 0 || placement.sourcePageIndex >= pdfRenderer.pageCount) {
                        // Render empty blank page box
                        continue
                    }

                    val sourcePage = pdfRenderer.openPage(placement.sourcePageIndex)
                    try {
                        val srcWidth = sourcePage.width
                        val srcHeight = sourcePage.height

                        // Render source page to temp bitmap
                        val srcScale = 2f
                        val pageBitmap = Bitmap.createBitmap(
                            (srcWidth * srcScale).toInt(),
                            (srcHeight * srcScale).toInt(),
                            Bitmap.Config.ARGB_8888
                        )
                        sourcePage.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                        canvas.save()

                        // Position and scale on sheet canvas
                        val destLeft = placement.xPt * dpiScale
                        val destTop = placement.yPt * dpiScale
                        val destWidth = placement.widthPt * dpiScale
                        val destHeight = placement.heightPt * dpiScale

                        // Apply center rotation if needed
                        if (placement.rotationDegrees != 0f) {
                            val centerX = destLeft + destWidth / 2f
                            val centerY = destTop + destHeight / 2f
                            canvas.rotate(placement.rotationDegrees, centerX, centerY)
                        }

                        val destRect = RectF(destLeft, destTop, destLeft + destWidth, destTop + destHeight)

                        // Handle crop box if specified
                        val srcRect = if (placement.cropBox != null) {
                            val crop = placement.cropBox
                            val l = (pageBitmap.width * crop.leftPct).toInt()
                            val t = (pageBitmap.height * crop.topPct).toInt()
                            val r = (pageBitmap.width * (1f - crop.rightPct)).toInt()
                            val b = (pageBitmap.height * (1f - crop.bottomPct)).toInt()
                            Rect(l, t, r, b)
                        } else {
                            Rect(0, 0, pageBitmap.width, pageBitmap.height)
                        }

                        canvas.drawBitmap(pageBitmap, srcRect, destRect, Paint(Paint.FILTER_BITMAP_FLAG))
                        canvas.restore()
                        pageBitmap.recycle()
                    } finally {
                        sourcePage.close()
                    }
                }

                // Draw Crop Marks, Registration Targets, and Safe Zones
                drawPrintMarks(canvas, sheet, dpiScale)

                // Embed high-res sheet bitmap into PDFBox page
                val pdImage = LosslessFactory.createFromImage(outDocument, sheetBitmap)
                val cs = PDPageContentStream(outDocument, pdPage)
                cs.drawImage(pdImage, 0f, 0f, sheet.widthPt, sheet.heightPt)
                cs.close()

                sheetBitmap.recycle()
            }

            FileOutputStream(outputFile).use { fos ->
                outDocument.save(fos)
            }
        } finally {
            outDocument.close()
            pdfRenderer.close()
            pfd.close()
        }

        outputFile
    }

    private fun drawPrintMarks(canvas: Canvas, sheet: SheetLayout, dpiScale: Float) {
        val paint = Paint().apply {
            color = android.graphics.Color.BLACK
            strokeWidth = 1.5f * dpiScale
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        val markLength = 12f * dpiScale
        val markOffset = 4f * dpiScale

        // Draw Crop Marks around placements if enabled
        if (sheet.showCropMarks) {
            for (p in sheet.placements) {
                val x = p.xPt * dpiScale
                val y = p.yPt * dpiScale
                val w = p.widthPt * dpiScale
                val h = p.heightPt * dpiScale

                // Top-Left Corner
                canvas.drawLine(x - markOffset - markLength, y, x - markOffset, y, paint)
                canvas.drawLine(x, y - markOffset - markLength, x, y - markOffset, paint)

                // Top-Right Corner
                canvas.drawLine(x + w + markOffset, y, x + w + markOffset + markLength, y, paint)
                canvas.drawLine(x + w, y - markOffset - markLength, x + w, y - markOffset, paint)

                // Bottom-Left Corner
                canvas.drawLine(x - markOffset - markLength, y + h, x - markOffset, y + h, paint)
                canvas.drawLine(x, y + h + markOffset, x, y + h + markOffset + markLength, paint)

                // Bottom-Right Corner
                canvas.drawLine(x + w + markOffset, y + h, x + w + markOffset + markLength, y + h, paint)
                canvas.drawLine(x + w, y + h + markOffset, x + w, y + h + markOffset + markLength, paint)
            }
        }

        // Draw Registration Crosshair Targets if enabled
        if (sheet.showRegistrationTargets) {
            val targetRadius = 6f * dpiScale
            val sheetW = sheet.widthPt * dpiScale
            val sheetH = sheet.heightPt * dpiScale

            val targets = listOf(
                Pair(sheetW / 2f, 10f * dpiScale),
                Pair(sheetW / 2f, sheetH - 10f * dpiScale),
                Pair(10f * dpiScale, sheetH / 2f),
                Pair(sheetW - 10f * dpiScale, sheetH / 2f)
            )

            for ((tx, ty) in targets) {
                canvas.drawCircle(tx, ty, targetRadius, paint)
                canvas.drawLine(tx - targetRadius * 1.5f, ty, tx + targetRadius * 1.5f, ty, paint)
                canvas.drawLine(tx, ty - targetRadius * 1.5f, tx, ty + targetRadius * 1.5f, paint)
            }
        }

        // Draw Safe Zone Dashed Rectangle if enabled
        if (sheet.showSafeZone) {
            val safePaint = Paint().apply {
                color = android.graphics.Color.BLUE
                strokeWidth = 1f * dpiScale
                style = Paint.Style.STROKE
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f * dpiScale, 5f * dpiScale), 0f)
            }

            for (p in sheet.placements) {
                val margin = 5f * dpiScale
                val rect = RectF(
                    p.xPt * dpiScale + margin,
                    p.yPt * dpiScale + margin,
                    (p.xPt + p.widthPt) * dpiScale - margin,
                    (p.yPt + p.heightPt) * dpiScale - margin
                )
                canvas.drawRect(rect, safePaint)
            }
        }
    }
}
