package com.yourname.pdftoolkit.domain.operations

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFSlide
import org.apache.poi.xslf.usermodel.XSLFShape
import org.apache.poi.xslf.usermodel.XSLFTextShape
import org.apache.poi.xslf.usermodel.XSLFSimpleShape
import org.apache.poi.xslf.usermodel.XSLFPictureData
import org.apache.poi.xslf.usermodel.XSLFPictureShape
import org.apache.poi.xslf.usermodel.XSLFGroupShape
import org.apache.poi.sl.usermodel.Placeholder
import org.apache.poi.sl.usermodel.Shape
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Offline-first office document format (Word, Excel) to PDF conversion engine.
 * Synthesizes formatted text blocks and landscape cell tables into standard paginated PDFs.
 * Runs fully on Dispatchers.IO background contexts with aggressive resource management.
 */
class OfficeConverter {

    /**
     * Converts a DOCX Word file paragraph-by-paragraph to an A4 PDF with dynamic line-wrapping and pagination.
     */
    suspend fun convertDocxToPdf(docxFile: File, pdfFile: File, context: android.content.Context) = withContext(Dispatchers.IO) {
        // Enforce PDFBox resource loading setup
        PDFBoxResourceLoader.init(context)

        var docxStream: FileInputStream? = null
        var docx: XWPFDocument? = null
        var pdf: PDDocument? = null
        var contentStream: PDPageContentStream? = null

        try {
            docxStream = FileInputStream(docxFile)
            docx = XWPFDocument(docxStream)
            pdf = PDDocument()

            val fontNormal = PDType1Font.HELVETICA
            val fontBold = PDType1Font.HELVETICA_BOLD
            val fontSizeNormal = 11f
            val fontSizeHeading = 15f
            val leadingNormal = fontSizeNormal * 1.25f
            val leadingHeading = fontSizeHeading * 1.25f

            // A4 Bounds: 595 x 842 points
            val pageBounds = PDRectangle.A4
            val margin = 50f
            val printableWidth = pageBounds.width - (2 * margin)

            var currentPage = PDPage(pageBounds)
            pdf.addPage(currentPage)
            contentStream = PDPageContentStream(pdf, currentPage)

            var yPosition = pageBounds.height - margin

            for (bodyElement in docx.bodyElements) {
                if (bodyElement is org.apache.poi.xwpf.usermodel.XWPFTable) {
                    for (row in bodyElement.rows) {
                        val cells = row.tableCells
                        val cellCount = cells.size
                        if (cellCount == 0) continue
                        val colWidth = printableWidth / cellCount.toFloat()

                        // Store lines of text for each cell
                        class CellLine(val text: String, val font: PDType1Font, val fontSize: Float, val leading: Float, val colorHex: String?)
                        val cellLinesList = mutableListOf<List<CellLine>>()
                        var maxCellHeight = 0f

                        for (cell in cells) {
                            val lines = mutableListOf<CellLine>()
                            for (para in cell.paragraphs) {
                                val isHeading = para.styleID?.lowercase()?.contains("heading") == true ||
                                        para.runs.firstOrNull()?.fontSize ?: 0 > 14

                                for (run in para.runs) {
                                    val font = when {
                                        run.isBold && run.isItalic -> PDType1Font.HELVETICA_BOLD_OBLIQUE
                                        run.isBold || isHeading -> PDType1Font.HELVETICA_BOLD
                                        run.isItalic -> PDType1Font.HELVETICA_OBLIQUE
                                        else -> PDType1Font.HELVETICA
                                    }
                                    val fontSizeHalfPoints = run.fontSize
                                    val fontSize = if (fontSizeHalfPoints > 0) (fontSizeHalfPoints.toFloat()) else (if (isHeading) fontSizeHeading else fontSizeNormal)
                                    val leading = fontSize * 1.4f
                                    val runText = run.getText(0) ?: ""
                                    if (runText.isNotEmpty()) {
                                        val wrapped = wrapText(runText, font, fontSize, colWidth - 10f)
                                        for (line in wrapped) {
                                            lines.add(CellLine(line, font, fontSize, leading, run.color))
                                        }
                                    }
                                }
                            }
                            val cellHeight = lines.sumOf { it.leading.toDouble() }.toFloat()
                            if (cellHeight > maxCellHeight) {
                                maxCellHeight = cellHeight
                            }
                            cellLinesList.add(lines)
                        }

                        // Check page bound break for the entire row height
                        if (yPosition - maxCellHeight < margin) {
                            contentStream?.close()
                            currentPage = PDPage(pageBounds)
                            pdf.addPage(currentPage)
                            contentStream = PDPageContentStream(pdf, currentPage)
                            yPosition = pageBounds.height - margin
                        }

                        val startY = yPosition
                        var rowBottomY = startY - maxCellHeight
                        if (maxCellHeight == 0f) {
                            rowBottomY = startY - 15f
                        }

                        for (cellIdx in 0 until cellCount) {
                            val cellX = margin + (cellIdx.toFloat() * colWidth)
                            val lines = cellLinesList[cellIdx]
                            var currentCellY = startY

                            for (line in lines) {
                                currentCellY -= line.leading
                                val sanitizedLine = sanitizeText(line.text)
                                contentStream?.beginText()
                                val colorHex = line.colorHex
                                if (colorHex != null && colorHex.length == 6) {
                                    try {
                                        val r = colorHex.substring(0, 2).toInt(16)
                                        val g = colorHex.substring(2, 4).toInt(16)
                                        val b = colorHex.substring(4, 6).toInt(16)
                                        contentStream?.setNonStrokingColor(r, g, b)
                                    } catch (e: Exception) {
                                        contentStream?.setNonStrokingColor(0, 0, 0)
                                    }
                                } else {
                                    contentStream?.setNonStrokingColor(0, 0, 0)
                                }
                                contentStream?.setFont(line.font, line.fontSize)
                                contentStream?.newLineAtOffset(cellX + 5f, currentCellY)
                                contentStream?.showText(sanitizedLine)
                                contentStream?.endText()
                            }

                            // Draw borders for this cell
                            contentStream?.setStrokingColor(200, 200, 200)
                            contentStream?.setLineWidth(0.5f)
                            contentStream?.moveTo(cellX, startY)
                            contentStream?.lineTo(cellX + colWidth, startY)
                            contentStream?.lineTo(cellX + colWidth, rowBottomY)
                            contentStream?.lineTo(cellX, rowBottomY)
                            contentStream?.lineTo(cellX, startY)
                            contentStream?.stroke()
                        }

                        yPosition = rowBottomY
                    }
                }

                if (bodyElement is org.apache.poi.xwpf.usermodel.XWPFParagraph) {
                    val paragraph = bodyElement
                    val isHeading = paragraph.styleID?.lowercase()?.contains("heading") == true ||
                            paragraph.runs.firstOrNull()?.fontSize ?: 0 > 14
                    
                    var xCursor = margin

                    // Check if paragraph needs a page break before starting if yPosition is too low
                    if (yPosition - 15f < margin) {
                        contentStream?.close()
                        currentPage = PDPage(pageBounds)
                        pdf.addPage(currentPage)
                        contentStream = PDPageContentStream(pdf, currentPage)
                        yPosition = pageBounds.height - margin
                    }

                    // Check if paragraph has text or images
                    val hasTextOrImage = paragraph.runs.any { (it.getText(0) ?: "").isNotEmpty() || it.embeddedPictures.isNotEmpty() }
                    if (!hasTextOrImage) {
                        continue
                    }

                    for (run in paragraph.runs) {
                        // Check for images
                        val pictures = run.embeddedPictures
                        if (pictures.isNotEmpty()) {
                            for (pic in pictures) {
                                try {
                                    val picData = pic.pictureData.data
                                    val decoded = BitmapFactory.decodeByteArray(picData, 0, picData.size)
                                    if (decoded != null) {
                                        val originalWidth = decoded.width.toFloat()
                                        val originalHeight = decoded.height.toFloat()
                                        var targetWidth = Math.min(printableWidth, originalWidth)
                                        var targetHeight = (targetWidth / originalWidth) * originalHeight
                                        // Clamp images taller than one page
                                        val maxImageHeight = pageBounds.height - (2 * margin)
                                        if (targetHeight > maxImageHeight) {
                                            targetWidth = (targetWidth * maxImageHeight / targetHeight)
                                            targetHeight = maxImageHeight
                                        }
                                        // Downscale huge photos to print size before embedding
                                        // (keeps PDF small and conversion fast)
                                        val bitmap = if (originalWidth > targetWidth * 1.5f) {
                                            val scaled = Bitmap.createScaledBitmap(
                                                decoded,
                                                targetWidth.toInt().coerceAtLeast(1),
                                                targetHeight.toInt().coerceAtLeast(1),
                                                true
                                            )
                                            decoded.recycle()
                                            scaled
                                        } else {
                                            decoded
                                        }

                                        if (xCursor > margin) {
                                            yPosition -= (if (isHeading) fontSizeHeading else fontSizeNormal) * 1.4f
                                            xCursor = margin
                                        }

                                        if (yPosition - targetHeight < margin) {
                                            contentStream?.close()
                                            currentPage = PDPage(pageBounds)
                                            pdf.addPage(currentPage)
                                            contentStream = PDPageContentStream(pdf, currentPage)
                                            yPosition = pageBounds.height - margin
                                        }

                                        val pdImage = LosslessFactory.createFromImage(pdf, bitmap)
                                        contentStream?.drawImage(pdImage, margin, yPosition - targetHeight, targetWidth, targetHeight)
                                        yPosition -= targetHeight
                                        bitmap.recycle()
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }

                        // Check for text
                        val runText = run.getText(0) ?: ""
                        if (runText.isNotEmpty()) {
                            val fontSizeHalfPoints = run.fontSize
                            val actualFontSize = if (fontSizeHalfPoints > 0) (fontSizeHalfPoints.toFloat()) else (if (isHeading) fontSizeHeading else fontSizeNormal)
                            val leading = actualFontSize * 1.4f
                            val font = when {
                                run.isBold && run.isItalic -> PDType1Font.HELVETICA_BOLD_OBLIQUE
                                run.isBold -> PDType1Font.HELVETICA_BOLD
                                run.isItalic -> PDType1Font.HELVETICA_OBLIQUE
                                else -> PDType1Font.HELVETICA
                            }

                            // Split runText into words
                            val words = runText.split(Regex("(?<=\\s)|(?=\\s)"))
                            for (word in words) {
                                if (word.isEmpty()) continue
                                val sanitizedWord = sanitizeText(word)
                                val wordWidth = try {
                                    font.getStringWidth(sanitizedWord) / 1000f * actualFontSize
                                } catch (e: Exception) {
                                    0f
                                }

                                if (xCursor + wordWidth > pageBounds.width - margin) {
                                    yPosition -= leading
                                    xCursor = margin

                                    if (yPosition < margin) {
                                        contentStream?.close()
                                        currentPage = PDPage(pageBounds)
                                        pdf.addPage(currentPage)
                                        contentStream = PDPageContentStream(pdf, currentPage)
                                        yPosition = pageBounds.height - margin
                                    }
                                }

                                if (sanitizedWord.trim().isNotEmpty()) {
                                    contentStream?.beginText()
                                    val colorHex = run.color
                                    if (colorHex != null && colorHex.length == 6) {
                                        try {
                                            val r = colorHex.substring(0, 2).toInt(16)
                                            val g = colorHex.substring(2, 4).toInt(16)
                                            val b = colorHex.substring(4, 6).toInt(16)
                                            contentStream?.setNonStrokingColor(r, g, b)
                                        } catch (e: Exception) {
                                            contentStream?.setNonStrokingColor(0, 0, 0)
                                        }
                                    } else {
                                        contentStream?.setNonStrokingColor(0, 0, 0)
                                    }
                                    contentStream?.setFont(font, actualFontSize)
                                    contentStream?.newLineAtOffset(xCursor, yPosition)
                                    contentStream?.showText(sanitizedWord)
                                    contentStream?.endText()
                                }
                                xCursor += wordWidth
                            }
                        }
                    }

                    val lastLeading = (if (isHeading) fontSizeHeading else fontSizeNormal) * 1.4f
                    yPosition -= (lastLeading + 6f)
                    // Next paragraph always starts at the left margin (prevents
                    // mid-line continuation that wasted vertical space / pages)
                    xCursor = margin
                }
            }

            contentStream?.close()
            contentStream = null

            FileOutputStream(pdfFile).use { out ->
                pdf.save(out)
            }

        } finally {
            try {
                contentStream?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                pdf?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                docx?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                docxStream?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Converts a XLSX spreadsheet sheet-by-sheet to landscape A4 PDFs drawing clean cell gridlines.
     */
    suspend fun convertXlsxToPdf(xlsxFile: File, pdfFile: File, context: android.content.Context) = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)

        var xlsxStream: FileInputStream? = null
        var workbook: XSSFWorkbook? = null
        var pdf: PDDocument? = null
        var contentStream: PDPageContentStream? = null

        try {
            xlsxStream = FileInputStream(xlsxFile)
            workbook = XSSFWorkbook(xlsxStream)
            pdf = PDDocument()

            // Landscape A4 bounds: width 842f, height 595f
            val pageBounds = PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width)
            val margin = 40f
            val printableWidth = pageBounds.width - (2 * margin)

            val fontNormal = PDType1Font.HELVETICA
            val fontBold = PDType1Font.HELVETICA_BOLD
            val fontSizeCell = 9f
            val rowHeight = 22f

            for (sheetIndex in 0 until workbook.numberOfSheets) {
                val sheet = workbook.getSheetAt(sheetIndex)
                
                // Identify active column bounds
                var maxCol = 0
                for (rowIndex in 0..sheet.lastRowNum) {
                    val row = sheet.getRow(rowIndex) ?: continue
                    if (row.lastCellNum > maxCol) {
                        maxCol = row.lastCellNum.toInt()
                    }
                }

                if (maxCol == 0) continue // Empty sheet

                // Dynamically fit columns in printable width
                val colWidth = Math.min(150f, printableWidth / maxCol)

                var currentPage = PDPage(pageBounds)
                pdf.addPage(currentPage)
                contentStream = PDPageContentStream(pdf, currentPage)

                // Page headers and title
                contentStream?.beginText()
                contentStream?.setFont(fontBold, 12f)
                contentStream?.newLineAtOffset(margin, pageBounds.height - margin + 12f)
                contentStream?.showText("Spreadsheet Export: ${sheet.sheetName}")
                contentStream?.endText()

                var yPosition = pageBounds.height - margin - 20f

                for (rowIndex in 0..sheet.lastRowNum) {
                    val row = sheet.getRow(rowIndex) ?: continue

                    // Check bounds for page breaks
                    if (yPosition - rowHeight < margin) {
                        contentStream?.close()
                        currentPage = PDPage(pageBounds)
                        pdf.addPage(currentPage)
                        contentStream = PDPageContentStream(pdf, currentPage)
                        yPosition = pageBounds.height - margin - 20f
                    }

                    // Draw cell grid lines and values
                    for (colIndex in 0 until maxCol) {
                        val cell = row.getCell(colIndex)
                        val cellValue = when {
                            cell == null -> ""
                            cell.cellType == CellType.NUMERIC -> {
                                val num = cell.numericCellValue
                                if (num == num.toLong().toDouble()) num.toLong().toString() else num.toString()
                            }
                            cell.cellType == CellType.BOOLEAN -> cell.booleanCellValue.toString()
                            cell.cellType == CellType.FORMULA -> {
                                try {
                                    cell.stringCellValue
                                } catch (e: Exception) {
                                    try {
                                        cell.numericCellValue.toString()
                                    } catch (ex: Exception) {
                                        ""
                                    }
                                }
                            }
                            else -> cell.stringCellValue ?: ""
                        }

                        val cellX = margin + (colIndex * colWidth)

                        // 1. Draw Cell border lines
                        contentStream?.setStrokingColor(200, 200, 200)
                        contentStream?.setLineWidth(0.5f)
                        contentStream?.moveTo(cellX, yPosition)
                        contentStream?.lineTo(cellX + colWidth, yPosition)
                        contentStream?.lineTo(cellX + colWidth, yPosition - rowHeight)
                        contentStream?.lineTo(cellX, yPosition - rowHeight)
                        contentStream?.lineTo(cellX, yPosition)
                        contentStream?.stroke()

                        // 2. Draw cell string values (with custom truncation if needed)
                        if (cellValue.isNotBlank()) {
                            val sanitizedValue = sanitizeText(cellValue)
                            val displayValue = truncateToWidth(sanitizedValue, fontNormal, fontSizeCell, colWidth - 8f)

                            contentStream?.beginText()
                            contentStream?.setFont(fontNormal, fontSizeCell)
                            contentStream?.newLineAtOffset(cellX + 4f, yPosition - rowHeight + 6f)
                            contentStream?.showText(displayValue)
                            contentStream?.endText()
                        }
                    }

                    yPosition -= rowHeight
                }

                contentStream?.close()
                contentStream = null
            }

            FileOutputStream(pdfFile).use { out ->
                pdf.save(out)
            }

        } finally {
            try {
                contentStream?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                pdf?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                workbook?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                xlsxStream?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    data class PicCrop(val l: Float = 0f, val t: Float = 0f, val r: Float = 0f, val b: Float = 0f)

    private fun extractBlipCrop(xml: Any): PicCrop? {
        val xmlStr = try { xml.toString() } catch (_: Throwable) { "" }
        if (xmlStr.isBlank()) return null
        val match = Regex("""<[^>]*srcRect[^>]*>""", RegexOption.IGNORE_CASE).find(xmlStr)
        if (match != null) {
            val tag = match.value
            fun getAttr(attr: String): Float {
                val m = Regex("""$attr=["'](\d+)["']""", RegexOption.IGNORE_CASE).find(tag)
                val v = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
                return (v.toFloat() / 100000f).coerceIn(0f, 0.99f)
            }
            val l = getAttr("l")
            val t = getAttr("t")
            val r = getAttr("r")
            val b = getAttr("b")
            if (l > 0f || t > 0f || r > 0f || b > 0f) {
                return PicCrop(l, t, r, b)
            }
        }
        return null
    }

    private fun extractBlipEmbedId(xml: Any): String? {
        val xmlStr = try { xml.toString() } catch (_: Throwable) { "" }
        if (xmlStr.isNotBlank()) {
            val svgMatch = Regex("""(?:asvg:svgBlip|svgBlip)[^>]*(?:r:embed|embed|r:link|link)=["'](rId\d+)["']""", RegexOption.IGNORE_CASE).find(xmlStr)
            if (svgMatch != null) return svgMatch.groupValues[1]
        }
        try {
            var blipFill: Any? = null
            try { blipFill = xml.javaClass.getMethod("getBlipFill").invoke(xml) } catch (_: Throwable) {}
            if (blipFill == null) {
                try {
                    val spPr = xml.javaClass.getMethod("getSpPr").invoke(xml)
                    if (spPr != null) blipFill = spPr.javaClass.getMethod("getBlipFill").invoke(spPr)
                } catch (_: Throwable) {}
            }
            if (blipFill != null) {
                val blip = try { blipFill.javaClass.getMethod("getBlip").invoke(blipFill) } catch (_: Throwable) { null }
                if (blip != null) {
                    val blipXmlStr = try { blip.toString() } catch (_: Throwable) { "" }
                    if (blipXmlStr.isNotBlank()) {
                        val svgMatch = Regex("""(?:asvg:svgBlip|svgBlip)[^>]*(?:r:embed|embed|r:link|link)=["'](rId\d+)["']""", RegexOption.IGNORE_CASE).find(blipXmlStr)
                        if (svgMatch != null) return svgMatch.groupValues[1]
                    }
                    val embed = try { blip.javaClass.getMethod("getEmbed").invoke(blip) as? String } catch (_: Throwable) { null }
                    if (!embed.isNullOrBlank()) return embed
                }
            }
        } catch (_: Throwable) { }
        if (xmlStr.isNotBlank()) {
            val match = Regex("""(?:r:embed|embed|r:link|link)=["'](rId\d+)["']""", RegexOption.IGNORE_CASE).find(xmlStr)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    private fun resolvePictureBytesFromBlipId(slide: Any, blipId: String): Pair<ByteArray, String?>? {
        if (slide !is XSLFSlide) return null
        try {
            val relDoc = slide.getRelationById(blipId)
            if (relDoc is XSLFPictureData) {
                return Pair(relDoc.data, relDoc.contentType)
            }
        } catch (_: Throwable) { }
        try {
            val sheetPart = slide.packagePart
            val rel = sheetPart?.getRelationship(blipId)
            if (rel != null) {
                val part = sheetPart.getRelatedPart(rel) ?: sheetPart.getPackage()?.getPart(rel)
                val bytes = part?.inputStream?.use { stream -> stream.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) {
                    return Pair(bytes, part.contentType)
                }
            }
        } catch (_: Throwable) { }
        try {
            val slideShow = slide.slideShow
            for (pd in slideShow.pictureData) {
                if (pd.packagePart?.partName?.name?.contains(blipId, ignoreCase = true) == true) {
                    return Pair(pd.data, pd.contentType)
                }
            }
        } catch (_: Throwable) { }
        return null
    }

    private fun extractPictureDataFromShape(shape: Any, slide: Any): Triple<ByteArray, String?, PicCrop?>? {
        try {
            val xml = try { shape.javaClass.getMethod("getXmlObject").invoke(shape) } catch (_: Throwable) {
                try {
                    val m = shape.javaClass.getDeclaredMethod("fetchXmlObject")
                    m.isAccessible = true
                    m.invoke(shape)
                } catch (_: Throwable) { null }
            }
            if (xml != null) {
                val crop = extractBlipCrop(xml)
                val blipId = extractBlipEmbedId(xml)
                if (!blipId.isNullOrBlank()) {
                    val resolved = resolvePictureBytesFromBlipId(slide, blipId)
                    if (resolved != null) return Triple(resolved.first, resolved.second, crop)
                }
            }
        } catch (_: Throwable) { }

        if (shape is XSLFPictureShape) {
            try {
                val pd = shape.pictureData
                val data = pd?.data
                if (data != null && data.isNotEmpty()) {
                    val xml = try { shape.javaClass.getMethod("getXmlObject").invoke(shape) } catch (_: Throwable) { null }
                    val crop = if (xml != null) extractBlipCrop(xml) else null
                    return Triple(data, pd.contentType, crop)
                }
            } catch (_: Throwable) { }
        }

        try {
            val method = shape.javaClass.getMethod("getPictureData")
            val pd = method.invoke(shape)
            if (pd != null) {
                val data = pd.javaClass.getMethod("getData").invoke(pd) as? ByteArray
                if (data != null && data.isNotEmpty()) {
                    val ct = try { pd.javaClass.getMethod("getContentType").invoke(pd) as? String } catch (_: Throwable) { null }
                    val xml = try { shape.javaClass.getMethod("getXmlObject").invoke(shape) } catch (_: Throwable) { null }
                    val crop = if (xml != null) extractBlipCrop(xml) else null
                    return Triple(data, ct, crop)
                }
            }
        } catch (_: Throwable) { }

        return null
    }

    private fun extractThemeColorScheme(slide: Any?): Map<String, String> {
        val colorMap = mutableMapOf<String, String>()
        if (slide !is XSLFSlide) return colorMap
        try {
            val theme = slide.theme
            if (theme != null) {
                val ctTheme = try { theme.javaClass.getMethod("getXmlObject").invoke(theme) } catch (_: Throwable) { null }
                val xmlStr = ctTheme?.toString() ?: ""
                val clrSchemeMatch = Regex("""<[^>]*clrScheme[^>]*name=["']([^"']*)["'][^>]*>(.*?)</[^>]*clrScheme>""", RegexOption.DOT_MATCHES_ALL).find(xmlStr)
                val schemeContent = clrSchemeMatch?.groupValues?.get(2) ?: xmlStr

                val schemeColorTags = listOf("dk1", "lt1", "dk2", "lt2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")
                for (tag in schemeColorTags) {
                    val tagMatch = Regex("""<a:$tag>\s*<a:srgbClr\s+val=["']([0-9A-Fa-f]{6})["']""", RegexOption.IGNORE_CASE).find(schemeContent)
                    if (tagMatch != null) {
                        colorMap[tag] = "#${tagMatch.groupValues[1]}"
                    } else {
                        val sysMatch = Regex("""<a:$tag>\s*<a:sysClr\s+[^>]*lastClr=["']([0-9A-Fa-f]{6})["']""", RegexOption.IGNORE_CASE).find(schemeContent)
                        if (sysMatch != null) {
                            colorMap[tag] = "#${sysMatch.groupValues[1]}"
                        }
                    }
                }
            }
        } catch (_: Throwable) { }
        return colorMap
    }

    private fun extractRunColor(run: Any?, defaultColor: Int): Int {
        if (run == null) return defaultColor
        try {
            val xmlRun = try {
                run.javaClass.getMethod("getXmlObject").invoke(run)
            } catch (t: Throwable) {
                try {
                    val m = run.javaClass.getDeclaredMethod("fetchXmlObject")
                    m.isAccessible = true
                    m.invoke(run)
                } catch (t2: Throwable) { null }
            }
            if (xmlRun != null) {
                val rPr = try { xmlRun.javaClass.getMethod("getRPr").invoke(xmlRun) } catch (t: Throwable) { null }
                if (rPr != null) {
                    val solidFill = try { rPr.javaClass.getMethod("getSolidFill").invoke(rPr) } catch (t: Throwable) { null }
                    if (solidFill != null) {
                        val srgb = try { solidFill.javaClass.getMethod("getSrgbClr").invoke(solidFill) } catch (t: Throwable) { null }
                        if (srgb != null) {
                            val hexBytes = try { srgb.javaClass.getMethod("getVal").invoke(srgb) as? ByteArray } catch (t: Throwable) { null }
                            if (hexBytes != null && hexBytes.size >= 3) {
                                return android.graphics.Color.rgb(
                                    hexBytes[0].toInt() and 0xFF,
                                    hexBytes[1].toInt() and 0xFF,
                                    hexBytes[2].toInt() and 0xFF
                                )
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) { }
        return defaultColor
    }

    private fun cropAndDrawBitmap(canvas: android.graphics.Canvas, dataBytes: ByteArray, crop: PicCrop?, destRect: android.graphics.Rect) {
        try {
            val bitmap = BitmapFactory.decodeByteArray(dataBytes, 0, dataBytes.size) ?: return
            val origW = bitmap.width
            val origH = bitmap.height
            val finalBmp = if (crop != null && (crop.l > 0f || crop.t > 0f || crop.r > 0f || crop.b > 0f)) {
                val startX = (crop.l * origW).toInt().coerceIn(0, origW - 1)
                val startY = (crop.t * origH).toInt().coerceIn(0, origH - 1)
                val cropW = ((1f - crop.l - crop.r) * origW).toInt().coerceIn(1, origW - startX)
                val cropH = ((1f - crop.t - crop.b) * origH).toInt().coerceIn(1, origH - startY)
                val cropped = Bitmap.createBitmap(bitmap, startX, startY, cropW, cropH)
                if (cropped != bitmap) bitmap.recycle()
                cropped
            } else {
                bitmap
            }
            canvas.drawBitmap(finalBmp, null, destRect, null)
            finalBmp.recycle()
        } catch (_: Throwable) { }
    }

    /**
     * Renders a PowerPoint PPTX file to high-resolution bitmaps with full layout, image, and typography fidelity.
     * Returns a list of Bitmaps, one per slide.
     */
    suspend fun renderPptxToBitmaps(pptxFile: File, targetWidth: Int = 1920): List<Bitmap> = withContext(Dispatchers.IO) {
        var pptxStream: FileInputStream? = null
        var ppt: XMLSlideShow? = null
        val bitmaps = mutableListOf<Bitmap>()

        try {
            pptxStream = FileInputStream(pptxFile)
            ppt = XMLSlideShow(pptxStream)

            val slideDimEmu = getSlideDimensionsEmu(ppt)
            val slideWidthEmu = slideDimEmu.first
            val slideHeightEmu = slideDimEmu.second

            // Calculate target height maintaining exact aspect ratio
            val targetHeight = if (slideWidthEmu > 0) (targetWidth * slideHeightEmu / slideWidthEmu).toInt() else (targetWidth * 9 / 16)
            val slideWidthPt = if (slideWidthEmu > 0) (slideWidthEmu / 12700f) else 720f
            val fontScale = targetWidth.toFloat() / slideWidthPt

            val themeColors = if (ppt.slides.isNotEmpty()) extractThemeColorScheme(ppt.slides[0]) else emptyMap()

            fun resolveColor(schemeOrHex: String?, fallback: Int): Int {
                if (schemeOrHex == null) return fallback
                if (schemeOrHex.startsWith("#")) {
                    return try { android.graphics.Color.parseColor(schemeOrHex) } catch (_: Throwable) { fallback }
                }
                val mapped = themeColors[schemeOrHex]
                if (mapped != null) {
                    return try { android.graphics.Color.parseColor(mapped) } catch (_: Throwable) { fallback }
                }
                return when (schemeOrHex.lowercase()) {
                    "tx1", "dk1" -> android.graphics.Color.rgb(15, 23, 42)
                    "tx2", "dk2" -> android.graphics.Color.rgb(30, 41, 59)
                    "bg1", "lt1" -> android.graphics.Color.WHITE
                    "bg2", "lt2" -> android.graphics.Color.rgb(248, 250, 252)
                    "accent1" -> android.graphics.Color.rgb(37, 99, 235)
                    else -> fallback
                }
            }

            for ((slideIndex, slide) in ppt.slides.withIndex()) {
                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.WHITE)

                // 1. Slide Background Color
                val bgColorHex = getSlideBgColor(slide)
                if (bgColorHex != null) {
                    try {
                        canvas.drawColor(android.graphics.Color.parseColor(bgColorHex))
                    } catch (_: Throwable) { }
                }

                // Background picture if present
                try {
                    val ctSlide = try { slide.javaClass.getMethod("getXmlObject").invoke(slide) } catch (_: Throwable) { null }
                    if (ctSlide != null) {
                        val cSld = try { ctSlide.javaClass.getMethod("getCSld").invoke(ctSlide) } catch (_: Throwable) { null }
                        val bg = try { cSld?.javaClass?.getMethod("getBg")?.invoke(cSld) } catch (_: Throwable) { null }
                        if (bg != null) {
                            val bgBlipId = extractBlipEmbedId(bg)
                            if (!bgBlipId.isNullOrBlank()) {
                                val resolved = resolvePictureBytesFromBlipId(slide, bgBlipId)
                                if (resolved != null && resolved.first.isNotEmpty()) {
                                    cropAndDrawBitmap(canvas, resolved.first, null, android.graphics.Rect(0, 0, targetWidth, targetHeight))
                                }
                            }
                        }
                    }
                } catch (_: Throwable) { }

                // 2. Flatten all shapes (including nested group children and Slide Master / Layout background shapes)
                val allSlideShapes = mutableListOf<org.apache.poi.xslf.usermodel.XSLFShape>()
                fun collectBitmapShapes(shapes: List<org.apache.poi.xslf.usermodel.XSLFShape>, isMasterOrLayout: Boolean = false) {
                    for (s in shapes) {
                        if (isMasterOrLayout && s is XSLFSimpleShape && s.isPlaceholder) {
                            // Skip master/layout text placeholders so placeholder prompt text doesn't render
                            continue
                        }
                        if (s is XSLFGroupShape) {
                            try { collectBitmapShapes(s.shapes, isMasterOrLayout) } catch (_: Throwable) { }
                        } else {
                            allSlideShapes.add(s)
                        }
                    }
                }
                val showMaster = try {
                    (slide.javaClass.getMethod("getDisplayMasterShapes").invoke(slide) as? Boolean) ?: true
                } catch (_: Throwable) { true }
                if (showMaster) {
                    try {
                        val masterShapes = slide.slideLayout?.slideMaster?.shapes ?: emptyList()
                        collectBitmapShapes(masterShapes, isMasterOrLayout = true)
                    } catch (_: Throwable) { }
                    try {
                        val layoutShapes = slide.slideLayout?.shapes ?: emptyList()
                        collectBitmapShapes(layoutShapes, isMasterOrLayout = true)
                    } catch (_: Throwable) { }
                }
                try { collectBitmapShapes(slide.shapes, isMasterOrLayout = false) } catch (_: Throwable) { }

                // 3. Draw all shapes in z-order
                for (shape in allSlideShapes) {
                    val normBounds = getShapeNormalizedBounds(shape, slide, slideWidthEmu, slideHeightEmu)

                    // Priority: PictureShape / blipFill
                    val picTriple = extractPictureDataFromShape(shape, slide)
                    if (picTriple != null && picTriple.first.isNotEmpty()) {
                        val fb = normBounds ?: floatArrayOf(0.05f, 0.3f, 0.6f, 0.4f)
                        val destRect = android.graphics.Rect(
                            (fb[0] * targetWidth).toInt(), (fb[1] * targetHeight).toInt(),
                            ((fb[0] + fb[2]) * targetWidth).toInt(), ((fb[1] + fb[3]) * targetHeight).toInt()
                        )
                        cropAndDrawBitmap(canvas, picTriple.first, picTriple.third, destRect)
                        continue
                    }

                    if (normBounds == null) continue
                    val px = normBounds[0] * targetWidth.toFloat()
                    val py = normBounds[1] * targetHeight.toFloat()
                    val pw = normBounds[2] * targetWidth.toFloat()
                    val ph = normBounds[3] * targetHeight.toFloat()

                    val geomType = getShapeGeometryType(shape)

                    // Draw geometry fill & stroke
                    if (shape is XSLFSimpleShape) {
                        val fillColor = getShapeFillColor(shape, themeColors)
                        if (fillColor != null) {
                            val fillPaint = Paint().apply { color = fillColor; style = Paint.Style.FILL; isAntiAlias = true }
                            drawShapeGeometry(canvas, geomType, px, py, pw, ph, fillPaint)
                        }
                        val lineColor = getShapeLineColor(shape, themeColors)
                        if (lineColor != null) {
                            val strokePaint = Paint().apply { color = lineColor; style = Paint.Style.STROKE; strokeWidth = 2f * (targetWidth / 960f); isAntiAlias = true }
                            drawShapeGeometry(canvas, geomType, px, py, pw, ph, strokePaint)
                        } else if (geomType == ShapeGeom.ELLIPSE) {
                            val strokePaint = Paint().apply { color = android.graphics.Color.rgb(30, 41, 59); style = Paint.Style.STROKE; strokeWidth = 1.5f * (targetWidth / 960f); isAntiAlias = true }
                            drawShapeGeometry(canvas, geomType, px, py, pw, ph, strokePaint)
                        }
                    }

                    // Draw text content
                    if (shape is XSLFTextShape) {
                        val paragraphs = try { shape.textParagraphs } catch (t: Throwable) { emptyList() }
                        val isTitle = try {
                            shape.isPlaceholder && (shape.textType == Placeholder.TITLE || shape.textType == Placeholder.CENTERED_TITLE)
                        } catch (_: Throwable) { shape.shapeName.contains("title", ignoreCase = true) }

                        val defaultTextColor = resolveColor(if (isTitle) "accent1" else "tx1", if (isTitle) android.graphics.Color.rgb(30, 58, 138) else android.graphics.Color.rgb(15, 23, 42))

                        if (paragraphs.isNotEmpty()) {
                            var curY = py
                            for (p in paragraphs) {
                                val pRuns = try { p.textRuns } catch (_: Throwable) { emptyList() }
                                val pText = try { pRuns.joinToString("") { it.rawText ?: "" } } catch (_: Throwable) { "" }
                                if (pText.isBlank()) continue

                                val isEllipseGeom = (geomType == ShapeGeom.ELLIPSE)
                                val maxFontPt = pRuns.mapNotNull {
                                    val fs = try { it.fontSize } catch (_: Throwable) { null }
                                    if (fs != null && fs > 0) fs.toFloat() else null
                                }.maxOrNull() ?: (if (isTitle) 26f else (if (isEllipseGeom) 12f else 14f))

                                val scaledFontSize = maxFontPt * fontScale
                                val runColor = extractRunColor(pRuns.firstOrNull(), defaultTextColor)

                                val textPaint = Paint().apply {
                                    color = runColor
                                    textSize = scaledFontSize
                                    isAntiAlias = true
                                    isFakeBoldText = pRuns.any { try { it.isBold } catch (_: Throwable) { false } } || isTitle
                                }

                                curY += textPaint.textSize + (4f * fontScale)

                                val bulletLevel = try { p.indentLevel } catch (_: Throwable) { 0 }
                                val hasBullet = bulletLevel > 0 || (paragraphs.size > 1 && !isTitle && !isEllipseGeom)
                                val bulletPrefix = if (hasBullet) "• " else ""
                                val indentOffset = (bulletLevel * 16f * fontScale)
                                val fullLine = bulletPrefix + pText.trim()
                                val maxLineWidth = (pw - 12f * fontScale - indentOffset).coerceAtLeast(50f)
                                val lines = wrapTextForCanvas(fullLine, textPaint, maxLineWidth)

                                for (line in lines) {
                                    if (curY <= py + ph + (20f * fontScale)) {
                                        val drawX = if (isEllipseGeom || (isTitle && pw >= targetWidth * 0.4f)) {
                                            (px + (pw - textPaint.measureText(line)) / 2f)
                                        } else {
                                            (px + 6f * fontScale + indentOffset)
                                        }
                                        canvas.drawText(line, drawX, curY, textPaint)
                                        curY += textPaint.textSize * 1.35f
                                    }
                                }
                                curY += 2f * fontScale
                            }
                        }
                    }
                }
                bitmaps.add(bitmap)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { ppt?.close() } catch (e: Exception) {}
            try { pptxStream?.close() } catch (e: Exception) {}
        }
        return@withContext bitmaps
    }

    /**
     * Converts a PPTX presentation file slide-by-slide to A4 PDF with high visual fidelity.
     */
    suspend fun convertPptxToPdf(pptxFile: File, pdfFile: File, renderMode: String, context: android.content.Context) = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)

        var pdf: PDDocument? = null
        try {
            pdf = PDDocument()
            val bitmaps = renderPptxToBitmaps(pptxFile, targetWidth = 1920)

            for (bitmap in bitmaps) {
                // Calculate page bounds matching the slide aspect ratio
                val aspect = if (bitmap.height > 0) bitmap.width.toFloat() / bitmap.height.toFloat() else (16f / 9f)
                val pageWidth = 842f // A4 landscape width points
                val pageHeight = (pageWidth / aspect)
                val pageBounds = PDRectangle(pageWidth, pageHeight)

                val page = PDPage(pageBounds)
                pdf.addPage(page)

                PDPageContentStream(pdf, page).use { contentStream ->
                    val pdImage = LosslessFactory.createFromImage(pdf, bitmap)
                    contentStream.drawImage(pdImage, 0f, 0f, pageWidth, pageHeight)
                }
                bitmap.recycle()
            }

            FileOutputStream(pdfFile).use { out ->
                pdf.save(out)
            }
        } finally {
            try { pdf?.close() } catch (e: Exception) { e.printStackTrace() }
        }
    }

    /**
     * Gets slide dimensions in EMUs without using java.awt.
     */
    private fun getSlideDimensionsEmu(ppt: XMLSlideShow): Pair<Long, Long> {
        return try {
            val ctPresentation = try {
                ppt.javaClass.getMethod("getCTPresentation").invoke(ppt)
            } catch (t: Throwable) { null }
            if (ctPresentation != null) {
                val sldSz = ctPresentation.javaClass.getMethod("getSldSz").invoke(ctPresentation)
                if (sldSz != null) {
                    val cx = (sldSz.javaClass.getMethod("getCx").invoke(sldSz) as? Number)?.toLong() ?: 9144000L
                    val cy = (sldSz.javaClass.getMethod("getCy").invoke(sldSz) as? Number)?.toLong() ?: 5143500L
                    return Pair(cx, cy)
                }
            }
            Pair(9144000L, 5143500L)
        } catch (t: Throwable) {
            Pair(9144000L, 5143500L)
        }
    }

    /**
     * Extracts shape bounds in EMU units directly from XML, avoiding java.awt dependencies.
     */
    private fun getShapeBoundsEmu(shape: Any): FloatArray? {
        return try {
            val xmlObj = try {
                shape.javaClass.getMethod("getXmlObject").invoke(shape)
            } catch (t: Throwable) {
                try {
                    val method = shape.javaClass.getDeclaredMethod("fetchXmlObject")
                    method.isAccessible = true
                    method.invoke(shape)
                } catch (t2: Throwable) { null }
            } ?: return null

            val spPr = try {
                xmlObj.javaClass.getMethod("getSpPr").invoke(xmlObj)
            } catch (t: Throwable) { null } ?: return null

            val xfrm = try {
                spPr.javaClass.getMethod("getXfrm").invoke(spPr)
            } catch (t: Throwable) { null } ?: return null

            val off = try { xfrm.javaClass.getMethod("getOff").invoke(xfrm) } catch (t: Throwable) { null }
            val ext = try { xfrm.javaClass.getMethod("getExt").invoke(xfrm) } catch (t: Throwable) { null }

            val x = (try { off?.javaClass?.getMethod("getX")?.invoke(off) as? Number } catch (t: Throwable) { null })?.toFloat()
            val y = (try { off?.javaClass?.getMethod("getY")?.invoke(off) as? Number } catch (t: Throwable) { null })?.toFloat()
            val cx = (try { ext?.javaClass?.getMethod("getCx")?.invoke(ext) as? Number } catch (t: Throwable) { null })?.toFloat()
            val cy = (try { ext?.javaClass?.getMethod("getCy")?.invoke(ext) as? Number } catch (t: Throwable) { null })?.toFloat()

            if (x != null && y != null && cx != null && cy != null) {
                floatArrayOf(x, y, cx, cy)
            } else null
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Extracts normalized shape bounds (left, top, width, height: 0.0f..1.0f)
     * using getAnchor() reflection with fallback to XMLBeans without java.awt compile dependencies.
     */
    private fun extractLongValueOC(obj: Any?): Long? {
        if (obj == null) return null
        if (obj is Number) return obj.toLong()
        try {
            val longValMethod = obj.javaClass.getMethod("getLongValue")
            val v = longValMethod.invoke(obj)
            if (v is Number) return v.toLong()
        } catch (_: Throwable) { }
        try {
            val str = obj.toString().trim()
            val num = str.toLongOrNull()
            if (num != null) return num
            val doubleNum = str.toDoubleOrNull()
            if (doubleNum != null) return doubleNum.toLong()
        } catch (_: Throwable) { }
        return null
    }

    private fun getShapeNormalizedBounds(
        shape: Any,
        slide: Any?,
        slideWidthEmu: Long,
        slideHeightEmu: Long
    ): FloatArray? {
        // Strategy 1: XMLBeans direct EMU extraction
        val directXml = getXmlShapeBoundsNormalized(shape, slideWidthEmu, slideHeightEmu)
        if (directXml != null) return directXml

        // Strategy 2: If placeholder, resolve from Slide Layout / Master
        if (slide is XSLFSlide && shape is XSLFShape) {
            try {
                val phDetails = try { shape.placeholderDetails } catch (_: Throwable) { null }
                val phType = phDetails?.placeholder
                if (phType != null) {
                    val layoutShapes = try { slide.slideLayout?.shapes } catch (_: Throwable) { emptyList() } ?: emptyList()
                    for (lShape in layoutShapes) {
                        if (lShape.placeholderDetails?.placeholder == phType) {
                            val lBounds = getXmlShapeBoundsNormalized(lShape, slideWidthEmu, slideHeightEmu)
                            if (lBounds != null) return lBounds
                        }
                    }
                    val masterShapes = try { slide.slideLayout?.slideMaster?.shapes } catch (_: Throwable) { emptyList() } ?: emptyList()
                    for (mShape in masterShapes) {
                        if (mShape.placeholderDetails?.placeholder == phType) {
                            val mBounds = getXmlShapeBoundsNormalized(mShape, slideWidthEmu, slideHeightEmu)
                            if (mBounds != null) return mBounds
                        }
                    }
                }
            } catch (_: Throwable) { }
        }

        // Strategy 3: Try getAnchor() via reflection
        try {
            val anchor = shape.javaClass.getMethod("getAnchor").invoke(shape)
            if (anchor != null) {
                val x = (anchor.javaClass.getMethod("getX").invoke(anchor) as? Number)?.toDouble()
                val y = (anchor.javaClass.getMethod("getY").invoke(anchor) as? Number)?.toDouble()
                val w = (anchor.javaClass.getMethod("getWidth").invoke(anchor) as? Number)?.toDouble()
                val h = (anchor.javaClass.getMethod("getHeight").invoke(anchor) as? Number)?.toDouble()

                val slideWPt = if (slideWidthEmu > 0) slideWidthEmu / 12700.0 else 720.0
                val slideHPt = if (slideHeightEmu > 0) slideHeightEmu / 12700.0 else 540.0

                if (x != null && y != null && w != null && h != null && w > 0 && h > 0) {
                    return floatArrayOf(
                        (x / slideWPt).toFloat().coerceIn(0f, 1f),
                        (y / slideHPt).toFloat().coerceIn(0f, 1f),
                        (w / slideWPt).toFloat().coerceIn(0.01f, 1f),
                        (h / slideHPt).toFloat().coerceIn(0.01f, 1f)
                    )
                }
            }
        } catch (_: Throwable) { }

        return null
    }

    private fun getXmlShapeBoundsNormalized(
        shape: Any,
        slideWidthEmu: Long,
        slideHeightEmu: Long
    ): FloatArray? {
        if (slideWidthEmu <= 0 || slideHeightEmu <= 0) return null
        try {
            val xmlObj = try {
                shape.javaClass.getMethod("getXmlObject").invoke(shape)
            } catch (t: Throwable) {
                try {
                    val method = shape.javaClass.getDeclaredMethod("fetchXmlObject")
                    method.isAccessible = true
                    method.invoke(shape)
                } catch (t2: Throwable) { null }
            } ?: return null

            var xfrm: Any? = null
            xfrm = tryGetXfrmOC(xmlObj, "getSpPr")
            if (xfrm == null) xfrm = tryGetXfrmOC(xmlObj, "getGrpSpPr")
            if (xfrm == null) {
                for (methodName in listOf("getCxnSpPr", "getNvSpPr", "getNvPicPr", "getNvCxnSpPr")) {
                    xfrm = tryGetXfrmOC(xmlObj, methodName)
                    if (xfrm != null) break
                }
            }
            if (xfrm == null) {
                xfrm = try { xmlObj.javaClass.getMethod("getXfrm").invoke(xmlObj) } catch (_: Throwable) { null }
            }

            if (xfrm == null) return null

            val off = try { xfrm.javaClass.getMethod("getOff").invoke(xfrm) } catch (_: Throwable) { null }
            val ext = try { xfrm.javaClass.getMethod("getExt").invoke(xfrm) } catch (_: Throwable) { null }

            val rawX = try { off?.javaClass?.getMethod("getX")?.invoke(off) } catch (_: Throwable) { null }
            val rawY = try { off?.javaClass?.getMethod("getY")?.invoke(off) } catch (_: Throwable) { null }
            val rawCx = try { ext?.javaClass?.getMethod("getCx")?.invoke(ext) } catch (_: Throwable) { null }
            val rawCy = try { ext?.javaClass?.getMethod("getCy")?.invoke(ext) } catch (_: Throwable) { null }

            val rawXVal = extractLongValueOC(rawX)
            val rawYVal = extractLongValueOC(rawY)
            val rawCxVal = extractLongValueOC(rawCx)
            val rawCyVal = extractLongValueOC(rawCy)

            if (rawXVal != null && rawYVal != null && rawCxVal != null && rawCyVal != null && rawCxVal > 0 && rawCyVal > 0) {
                var curX: Long = rawXVal
                var curY: Long = rawYVal
                var curCx: Long = rawCxVal
                var curCy: Long = rawCyVal

                // Apply parent group transforms recursively if shape is nested in a group shape
                var parentShape = (shape as? XSLFShape)?.parent
                while (parentShape is XSLFGroupShape) {
                    val groupXml = try { parentShape.javaClass.getMethod("getXmlObject").invoke(parentShape) } catch (_: Throwable) { null }
                    if (groupXml != null) {
                        val grpXfrm = tryGetXfrmOC(groupXml, "getGrpSpPr")
                            ?: try { groupXml.javaClass.getMethod("getXfrm").invoke(groupXml) } catch (_: Throwable) { null }
                        if (grpXfrm != null) {
                            val gOff = try { grpXfrm.javaClass.getMethod("getOff").invoke(grpXfrm) } catch (_: Throwable) { null }
                            val gExt = try { grpXfrm.javaClass.getMethod("getExt").invoke(grpXfrm) } catch (_: Throwable) { null }
                            val gChOff = try { grpXfrm.javaClass.getMethod("getChOff").invoke(grpXfrm) } catch (_: Throwable) { null }
                            val gChExt = try { grpXfrm.javaClass.getMethod("getChExt").invoke(grpXfrm) } catch (_: Throwable) { null }

                            val gX = extractLongValueOC(try { gOff?.javaClass?.getMethod("getX")?.invoke(gOff) } catch (_: Throwable) { null }) ?: 0L
                            val gY = extractLongValueOC(try { gOff?.javaClass?.getMethod("getY")?.invoke(gOff) } catch (_: Throwable) { null }) ?: 0L
                            val gCx = extractLongValueOC(try { gExt?.javaClass?.getMethod("getCx")?.invoke(gExt) } catch (_: Throwable) { null }) ?: slideWidthEmu
                            val gCy = extractLongValueOC(try { gExt?.javaClass?.getMethod("getCy")?.invoke(gExt) } catch (_: Throwable) { null }) ?: slideHeightEmu

                            val chX = extractLongValueOC(try { gChOff?.javaClass?.getMethod("getX")?.invoke(gChOff) } catch (_: Throwable) { null }) ?: gX
                            val chY = extractLongValueOC(try { gChOff?.javaClass?.getMethod("getY")?.invoke(gChOff) } catch (_: Throwable) { null }) ?: gY
                            val chCx = extractLongValueOC(try { gChExt?.javaClass?.getMethod("getCx")?.invoke(gChExt) } catch (_: Throwable) { null }) ?: gCx
                            val chCy = extractLongValueOC(try { gChExt?.javaClass?.getMethod("getCy")?.invoke(gChExt) } catch (_: Throwable) { null }) ?: gCy

                            val scaleX = if (chCx > 0) gCx.toDouble() / chCx.toDouble() else 1.0
                            val scaleY = if (chCy > 0) gCy.toDouble() / chCy.toDouble() else 1.0

                            curX = (gX + (curX - chX) * scaleX).toLong()
                            curY = (gY + (curY - chY) * scaleY).toLong()
                            curCx = (curCx * scaleX).toLong()
                            curCy = (curCy * scaleY).toLong()
                        }
                    }
                    parentShape = parentShape.parent
                }

                return floatArrayOf(
                    (curX.toFloat() / slideWidthEmu.toFloat()).coerceIn(0f, 1f),
                    (curY.toFloat() / slideHeightEmu.toFloat()).coerceIn(0f, 1f),
                    (curCx.toFloat() / slideWidthEmu.toFloat()).coerceIn(0.001f, 1f),
                    (curCy.toFloat() / slideHeightEmu.toFloat()).coerceIn(0.001f, 1f)
                )
            }
        } catch (_: Throwable) { }
        return null
    }

    /** Helper: tries parentObj.getMethodName().getXfrm() via reflection */
    private fun tryGetXfrmOC(parentObj: Any, prMethodName: String): Any? {
        return try {
            val pr = parentObj.javaClass.getMethod(prMethodName).invoke(parentObj) ?: return null
            pr.javaClass.getMethod("getXfrm").invoke(pr)
        } catch (_: Throwable) { null }
    }

    private enum class ShapeGeom { RECTANGLE, ROUNDED_RECTANGLE, ELLIPSE, HEXAGON, TRIANGLE, DIAMOND, FREEFORM }

    private fun getShapeGeometryType(shape: Any): ShapeGeom {
        try {
            if (shape is org.apache.poi.sl.usermodel.SimpleShape<*, *>) {
                val st = try { shape.shapeType } catch (_: Throwable) { null }
                if (st != null) {
                    val stName = st.name.lowercase()
                    when {
                        stName.contains("hexagon") -> return ShapeGeom.HEXAGON
                        stName.contains("ellipse") || stName.contains("oval") || stName.contains("circle") -> return ShapeGeom.ELLIPSE
                        stName.contains("round") && stName.contains("rect") -> return ShapeGeom.ROUNDED_RECTANGLE
                        stName.contains("triangle") -> return ShapeGeom.TRIANGLE
                        stName.contains("diamond") -> return ShapeGeom.DIAMOND
                    }
                }
            }
            val xmlObj = try { shape.javaClass.getMethod("getXmlObject").invoke(shape) } catch (_: Throwable) { null }
            if (xmlObj != null) {
                val spPr = try { xmlObj.javaClass.getMethod("getSpPr").invoke(xmlObj) } catch (_: Throwable) { null }
                if (spPr != null) {
                    val prstGeom = try { spPr.javaClass.getMethod("getPrstGeom").invoke(spPr) } catch (_: Throwable) { null }
                    if (prstGeom != null) {
                        val prst = try { prstGeom.javaClass.getMethod("getPrst").invoke(prstGeom)?.toString()?.lowercase() } catch (_: Throwable) { null }
                        if (prst != null) {
                            when {
                                prst.contains("hexagon") -> return ShapeGeom.HEXAGON
                                prst.contains("ellipse") || prst.contains("oval") || prst.contains("circle") -> return ShapeGeom.ELLIPSE
                                prst.contains("roundrect") -> return ShapeGeom.ROUNDED_RECTANGLE
                                prst.contains("triangle") -> return ShapeGeom.TRIANGLE
                                prst.contains("diamond") -> return ShapeGeom.DIAMOND
                            }
                        }
                    }
                    val custGeom = try { spPr.javaClass.getMethod("getCustGeom").invoke(spPr) } catch (_: Throwable) { null }
                    if (custGeom != null) return ShapeGeom.FREEFORM
                }
            }
        } catch (_: Throwable) {}
        return ShapeGeom.RECTANGLE
    }

    private fun drawShapeGeometry(
        canvas: android.graphics.Canvas,
        geom: ShapeGeom,
        px: Float, py: Float, pw: Float, ph: Float,
        paint: Paint
    ) {
        when (geom) {
            ShapeGeom.ELLIPSE -> {
                canvas.drawOval(px, py, px + pw, py + ph, paint)
            }
            ShapeGeom.ROUNDED_RECTANGLE -> {
                val rx = pw * 0.15f
                val ry = ph * 0.15f
                canvas.drawRoundRect(px, py, px + pw, py + ph, rx, ry, paint)
            }
            ShapeGeom.HEXAGON -> {
                val path = android.graphics.Path().apply {
                    val insetW = pw * 0.25f
                    moveTo(px + insetW, py)
                    lineTo(px + pw - insetW, py)
                    lineTo(px + pw, py + ph * 0.5f)
                    lineTo(px + pw - insetW, py + ph)
                    lineTo(px + insetW, py + ph)
                    lineTo(px, py + ph * 0.5f)
                    close()
                }
                canvas.drawPath(path, paint)
            }
            ShapeGeom.TRIANGLE -> {
                val path = android.graphics.Path().apply {
                    moveTo(px + pw * 0.5f, py)
                    lineTo(px + pw, py + ph)
                    lineTo(px, py + ph)
                    close()
                }
                canvas.drawPath(path, paint)
            }
            ShapeGeom.DIAMOND -> {
                val path = android.graphics.Path().apply {
                    moveTo(px + pw * 0.5f, py)
                    lineTo(px + pw, py + ph * 0.5f)
                    lineTo(px + pw * 0.5f, py + ph)
                    lineTo(px, py + ph * 0.5f)
                    close()
                }
                canvas.drawPath(path, paint)
            }
            ShapeGeom.FREEFORM, ShapeGeom.RECTANGLE -> {
                canvas.drawRect(px, py, px + pw, py + ph, paint)
            }
        }
    }

    private fun extractAlphaFromColorObj(colorObj: Any?): Long? {
        if (colorObj == null) return null
        try {
            val alphaList = try { colorObj.javaClass.getMethod("getAlphaList").invoke(colorObj) as? List<*> } catch (_: Throwable) { null }
            if (!alphaList.isNullOrEmpty()) {
                val firstAlpha = alphaList[0]
                val valObj = try { firstAlpha?.javaClass?.getMethod("getVal")?.invoke(firstAlpha) } catch (_: Throwable) { null }
                val l = extractLongValueOC(valObj)
                if (l != null) return l
            }
        } catch (_: Throwable) {}
        try {
            val alphaArray = try { colorObj.javaClass.getMethod("getAlphaArray").invoke(colorObj) as? Array<*> } catch (_: Throwable) { null }
            if (!alphaArray.isNullOrEmpty()) {
                val firstAlpha = alphaArray[0]
                val valObj = try { firstAlpha?.javaClass?.getMethod("getVal")?.invoke(firstAlpha) } catch (_: Throwable) { null }
                val l = extractLongValueOC(valObj)
                if (l != null) return l
            }
        } catch (_: Throwable) {}
        try {
            val xmlStr = colorObj.toString()
            val match = Regex("""(?:<a:alpha|alpha)[^>]*val=["'](\d+)["']""", RegexOption.IGNORE_CASE).find(xmlStr)
            if (match != null) {
                return match.groupValues[1].toLongOrNull()
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun resolveSchemeColorName(schemeName: String, themeColors: Map<String, String>): String? {
        val s = schemeName.lowercase()
        for ((key, hex) in themeColors) {
            if (s.contains(key)) return hex
        }
        return when {
            s.contains("accent1") -> "#1E40AF"
            s.contains("accent2") -> "#EA580C"
            s.contains("accent3") -> "#0D9488"
            s.contains("accent4") -> "#7C3AED"
            s.contains("accent5") -> "#16A34A"
            s.contains("accent6") -> "#E11D48"
            s.contains("tx1") || s.contains("dk1") -> "#0F172A"
            s.contains("tx2") || s.contains("dk2") -> "#334155"
            s.contains("bg1") || s.contains("lt1") -> "#FFFFFF"
            s.contains("bg2") || s.contains("lt2") -> "#F8FAFC"
            s.contains("hlink") -> "#2563EB"
            else -> null
        }
    }

    private fun extractColorAndAlphaFromFill(fillObj: Any?, themeColors: Map<String, String>): Int? {
        if (fillObj == null) return null
        try {
            var hexColor: String? = null
            var alphaVal: Long? = null

            var solidFill: Any? = fillObj
            try {
                val sf = fillObj.javaClass.getMethod("getSolidFill").invoke(fillObj)
                if (sf != null) solidFill = sf
            } catch (_: Throwable) {}

            val srgb = try { solidFill?.javaClass?.getMethod("getSrgbClr")?.invoke(solidFill) } catch (_: Throwable) { null }
            if (srgb != null) {
                val bytes = try { srgb.javaClass.getMethod("getVal").invoke(srgb) as? ByteArray } catch (_: Throwable) { null }
                if (bytes != null && bytes.size >= 3) {
                    hexColor = String.format("#%02X%02X%02X", bytes[0].toInt() and 0xFF, bytes[1].toInt() and 0xFF, bytes[2].toInt() and 0xFF)
                }
                alphaVal = extractAlphaFromColorObj(srgb)
            }

            if (hexColor == null) {
                val schemeClr = try { solidFill?.javaClass?.getMethod("getSchemeClr")?.invoke(solidFill) } catch (_: Throwable) { null }
                if (schemeClr != null) {
                    val valObj = try { schemeClr.javaClass.getMethod("getVal").invoke(schemeClr) } catch (_: Throwable) { null }
                    hexColor = resolveSchemeColorName(valObj?.toString() ?: "", themeColors)
                    alphaVal = extractAlphaFromColorObj(schemeClr)
                }
            }

            if (hexColor == null) {
                val prstClr = try { solidFill?.javaClass?.getMethod("getPrstClr")?.invoke(solidFill) } catch (_: Throwable) { null }
                if (prstClr != null) {
                    val valObj = try { prstClr.javaClass.getMethod("getVal").invoke(prstClr) } catch (_: Throwable) { null }
                    hexColor = when (valObj?.toString()?.lowercase()) {
                        "black" -> "#000000"; "white" -> "#FFFFFF"; "red" -> "#FF0000"; "green" -> "#008000"; "blue" -> "#0000FF"; else -> null
                    }
                    alphaVal = extractAlphaFromColorObj(prstClr)
                }
            }

            if (hexColor == null) {
                val gradFill = try { fillObj.javaClass.getMethod("getGradFill").invoke(fillObj) } catch (_: Throwable) { null }
                if (gradFill != null) {
                    val gsLst = try { gradFill.javaClass.getMethod("getGsLst").invoke(gradFill) } catch (_: Throwable) { null }
                    if (gsLst != null) {
                        val gsArray = try {
                            val m = gsLst.javaClass.getMethod("getGsArray")
                            m.invoke(gsLst) as? Array<*>
                        } catch (_: Throwable) { null }
                        if (!gsArray.isNullOrEmpty() && gsArray[0] != null) {
                            return extractColorAndAlphaFromFill(gsArray[0], themeColors)
                        }
                    }
                }
            }

            if (hexColor == null) return null

            val rgbInt = android.graphics.Color.parseColor(hexColor)
            val alphaInt = if (alphaVal != null && alphaVal >= 0) {
                val alphaFloat = when {
                    alphaVal > 100L -> (alphaVal.toFloat() / 100000f).coerceIn(0f, 1f)
                    else -> (alphaVal.toFloat() / 100f).coerceIn(0f, 1f)
                }
                (alphaFloat * 255f).toInt().coerceIn(0, 255)
            } else {
                val a = (rgbInt shr 24) and 0xFF
                if (a == 0) 255 else a
            }

            return android.graphics.Color.argb(alphaInt, (rgbInt shr 16) and 0xFF, (rgbInt shr 8) and 0xFF, rgbInt and 0xFF)
        } catch (_: Throwable) {
            return null
        }
    }

    /**
     * Extracts shape fill color as Android Color int, or null if none.
     */
    private fun getShapeFillColor(shape: XSLFSimpleShape, themeColors: Map<String, String>): Int? {
        return try {
            val xmlObj = try {
                shape.javaClass.getMethod("getXmlObject").invoke(shape)
            } catch (_: Throwable) { null } ?: return null

            val spPr = try {
                xmlObj.javaClass.getMethod("getSpPr").invoke(xmlObj)
            } catch (_: Throwable) { null } ?: return null

            extractColorAndAlphaFromFill(spPr, themeColors)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Extracts shape line/border color as Android Color int, or null if none.
     */
    private fun getShapeLineColor(shape: XSLFSimpleShape, themeColors: Map<String, String>): Int? {
        return try {
            val xmlObj = try {
                shape.javaClass.getMethod("getXmlObject").invoke(shape)
            } catch (_: Throwable) { null } ?: return null

            val spPr = try {
                xmlObj.javaClass.getMethod("getSpPr").invoke(xmlObj)
            } catch (_: Throwable) { null } ?: return null

            val ln = try {
                spPr.javaClass.getMethod("getLn").invoke(spPr)
            } catch (_: Throwable) { null } ?: return null

            extractColorAndAlphaFromFill(ln, themeColors)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Extracts slide background color as hex string, or null if none.
     */
    private fun getSlideBgColor(slide: org.apache.poi.sl.usermodel.Slide<*, *>): String? {
        if (slide !is org.apache.poi.xslf.usermodel.XSLFSlide) return null
        return try {
            val ctSlide = try {
                slide.javaClass.getMethod("getXmlObject").invoke(slide)
            } catch (t: Throwable) { null } ?: return null

            val cSld = try {
                ctSlide.javaClass.getMethod("getCSld").invoke(ctSlide)
            } catch (t: Throwable) { null } ?: return null

            val bg = try {
                cSld.javaClass.getMethod("getBg").invoke(cSld)
            } catch (t: Throwable) { null } ?: return null

            val bgPr = try {
                bg.javaClass.getMethod("getBgPr").invoke(bg)
            } catch (t: Throwable) { null } ?: return null

            val solidFill = try {
                bgPr.javaClass.getMethod("getSolidFill").invoke(bgPr)
            } catch (t: Throwable) { null } ?: return null

            val srgbClr = try {
                solidFill.javaClass.getMethod("getSrgbClr").invoke(solidFill)
            } catch (t: Throwable) { null } ?: return null

            val hexBytes = try {
                srgbClr.javaClass.getMethod("getVal").invoke(srgbClr) as? ByteArray
            } catch (t: Throwable) { null } ?: return null

            val hex = hexBytes.joinToString("") { String.format("%02X", it) }
            if (hex.isNotBlank()) "#$hex" else null
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Greedy word wrapping routine checking exact horizontal width budget constraint.
     */
    private fun wrapText(text: String, font: PDType1Font, fontSize: Float, maxWidth: Float): List<String> {
        val words = text.split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()

        for (word in words) {
            if (word.isEmpty()) continue
            val testLine = if (currentLine.isEmpty()) word else "${currentLine} $word"
            val cleanTestLine = sanitizeText(testLine)
            try {
                val width = (font.getStringWidth(cleanTestLine) / 1000f * fontSize)
                if (width <= maxWidth) {
                    currentLine.append(if (currentLine.isEmpty()) word else " $word")
                } else {
                    if (currentLine.isNotEmpty()) {
                        lines.add(currentLine.toString())
                    }
                    currentLine = StringBuilder(word)
                }
            } catch (e: Exception) {
                currentLine.append(if (currentLine.isEmpty()) word else " $word")
            }
        }
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.toString())
        }
        return lines
    }

    /**
     * Truncates text greedily with an ellipsis fallback to guarantee matrix visual alignment.
     */
    private fun truncateToWidth(text: String, font: PDType1Font, fontSize: Float, maxWidth: Float): String {
        try {
            var width = (font.getStringWidth(text) / 1000f * fontSize)
            if (width <= maxWidth) return text

            var truncated = text
            while (truncated.isNotEmpty() && width > maxWidth) {
                truncated = truncated.dropLast(1)
                width = (font.getStringWidth("$truncated...") / 1000f * fontSize)
            }
            return if (truncated.isEmpty()) "" else "$truncated..."
        } catch (e: Exception) {
            return text
        }
    }

    private fun wrapTextForCanvas(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()

        for (word in words) {
            if (word.isEmpty()) continue
            val testLine = if (currentLine.isEmpty()) word else "${currentLine} $word"
            val width = paint.measureText(testLine)
            if (width <= maxWidth) {
                currentLine.append(if (currentLine.isEmpty()) word else " $word")
            } else {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine.toString())
                }
                currentLine = StringBuilder(word)
            }
        }
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.toString())
        }
        return lines
    }

    /**
     * Filters high-unicode glyph ranges above WinAnsiEncoding bounds to avoid PDFBox rendering exception loops.
     */
    private fun sanitizeText(text: String): String {
        val sb = StringBuilder()
        for (char in text) {
            val code = char.code
            if (code in 32..126 || code in 160..255) {
                sb.append(char)
            } else if (char == '\n' || char == '\r' || char == '\t') {
                sb.append(' ')
            } else {
                when (char) {
                    '‘', '’' -> sb.append('\'')
                    '“', '”' -> sb.append('"')
                    '–', '—' -> sb.append('-')
                    else -> sb.append(' ')
                }
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ")
    }
}
