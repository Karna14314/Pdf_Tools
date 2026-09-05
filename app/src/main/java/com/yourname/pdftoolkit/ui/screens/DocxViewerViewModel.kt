package com.yourname.pdftoolkit.ui.screens

import android.content.Context
import android.net.Uri
import com.yourname.pdftoolkit.domain.operations.OfficeConverter
import com.yourname.pdftoolkit.domain.operations.DocumentSearchEngine
import com.yourname.pdftoolkit.domain.operations.DocSearchResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.poi.xwpf.usermodel.ParagraphAlignment
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.IBodyElement
import android.util.Base64
import java.io.File
import java.io.FileInputStream

enum class TabStopAlignment { LEFT, CENTER, RIGHT, DECIMAL, CLEAR }

data class DocxTabStop(
    val positionTwips: Long,
    val alignment: TabStopAlignment = TabStopAlignment.LEFT
) {
    val positionPt: Float get() = positionTwips / 20f
}

data class DocxPageGeometry(
    val widthTwips: Long = 11906L,   // Default A4 width in twips (595.3 pt)
    val heightTwips: Long = 16838L,  // Default A4 height in twips (841.9 pt)
    val marginTopTwips: Long = 1440L,    // Default 1 inch in twips (72 pt)
    val marginBottomTwips: Long = 1440L,
    val marginLeftTwips: Long = 1440L,
    val marginRightTwips: Long = 1440L,
    val headerMarginTwips: Long = 720L,
    val footerMarginTwips: Long = 720L,
    val isLandscape: Boolean = false
) {
    val widthPt: Float get() = widthTwips / 20f
    val heightPt: Float get() = heightTwips / 20f
    val marginTopPt: Float get() = marginTopTwips / 20f
    val marginBottomPt: Float get() = marginBottomTwips / 20f
    val marginLeftPt: Float get() = marginLeftTwips / 20f
    val marginRightPt: Float get() = marginRightTwips / 20f
    val headerMarginPt: Float get() = headerMarginTwips / 20f
    val footerMarginPt: Float get() = footerMarginTwips / 20f

    val printableWidthPt: Float get() = (widthPt - marginLeftPt - marginRightPt).coerceAtLeast(100f)
    val printableHeightPt: Float get() = (heightPt - marginTopPt - marginBottomPt).coerceAtLeast(100f)
}

data class DocxRun(
    val text: String,
    val isBold: Boolean,
    val isItalic: Boolean,
    val isUnderline: Boolean,
    val isStrike: Boolean,
    val color: String? = null,
    val fontFamily: String? = null,
    val fontSizePt: Float? = null,
    val hyperlinkUrl: String? = null,
    val imageUrl: String? = null,
    val widthEmu: Long? = null,
    val heightEmu: Long? = null,
    val isPageBreak: Boolean = false,
    val isTab: Boolean = false
)

data class DocxParagraph(
    val runs: List<DocxRun>,
    val alignment: String, // "LEFT", "CENTER", "RIGHT", "JUSTIFY"
    val headingLevel: Int, // 0 = body, 1-6 = heading level
    val isHeading: Boolean,
    val comment: String? = null,
    val spacingAfterPt: Float = 0f,
    val spacingBeforePt: Float = 0f,
    val lineHeightMultiplier: Float = 1.15f,
    val exactLineHeightPt: Float? = null,
    val indentStartPt: Float = 0f,
    val firstLineIndentPt: Float = 0f,
    val tabStops: List<DocxTabStop> = emptyList(),
    val isKeepNext: Boolean = false,
    val isKeepLines: Boolean = false,
    val isPageBreakBefore: Boolean = false
) {
    // Backward compatibility helpers for UI dp calculations
    val indentStartDp: Int get() = (indentStartPt * (160f / 72f)).toInt()
    val firstLineIndentDp: Int get() = (firstLineIndentPt * (160f / 72f)).toInt()
}

data class DocxTableCell(val paragraphs: List<DocxParagraph>)
data class DocxTableRow(val cells: List<DocxTableCell>)

sealed class DocxBodyElement {
    data class Para(val paragraph: DocxParagraph) : DocxBodyElement()
    data class Table(val rows: List<DocxTableRow>) : DocxBodyElement()
}

data class DocxDocument(
    val elements: List<DocxBodyElement>,
    val pageGeometry: DocxPageGeometry = DocxPageGeometry()
)

sealed class DocxLoadState {
    object Loading : DocxLoadState()
    data class Success(
        val document: DocxDocument,
        val fileName: String,
        val docxBase64: String? = null  // Base64-encoded file bytes for WebView rendering
    ) : DocxLoadState()
    data class Error(val message: String) : DocxLoadState()
}

class DocxViewerViewModel : ViewModel() {

    private val officeConverter = OfficeConverter()

    private val _loadState = MutableStateFlow<DocxLoadState>(DocxLoadState.Loading)
    val loadState: StateFlow<DocxLoadState> = _loadState.asStateFlow()

    private val _saveStatus = kotlinx.coroutines.flow.MutableSharedFlow<String>()
    val saveStatus = _saveStatus.asSharedFlow()

    private var activeDocument: XWPFDocument? = null
    private var activeLegacyDocument: org.apache.poi.hwpf.HWPFDocument? = null
    private var activeFilePath: String? = null
    private var appContext: Context? = null

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<DocSearchResult>>(emptyList())
    val searchResults: StateFlow<List<DocSearchResult>> = _searchResults.asStateFlow()

    private val _currentMatchIndex = MutableStateFlow(-1)
    val currentMatchIndex: StateFlow<Int> = _currentMatchIndex.asStateFlow()

    /**
     * Safely reads DOCX/DOC paragraphs inside coroutines using Apache POI,
     * translating typography run formats, and updating Room DB logs.
     */
    fun loadWordFile(context: Context, filePath: String) {
        appContext = context.applicationContext
        viewModelScope.launch {
            _loadState.value = DocxLoadState.Loading
            withContext(Dispatchers.IO) {
                // Free previous document reference if any
                try {
                    activeDocument?.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                try {
                    activeLegacyDocument?.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                activeDocument = null
                activeLegacyDocument = null
                activeFilePath = null

                var fileInputStream: FileInputStream? = null
                var doc: XWPFDocument? = null
                var legacyDoc: org.apache.poi.hwpf.HWPFDocument? = null
                var base64ForWebView: String? = null
                try {
                    val file = File(filePath)
                    if (!file.exists() || !file.isFile) {
                        _loadState.value = DocxLoadState.Error("Target Word document does not exist or is corrupted.")
                        return@withContext
                    }

                    // Read raw bytes for WebView rendering (DOCX files only),
                    // byte-identical to the source like Omnisuite
                    val rawBytes = file.readBytes()
                    base64ForWebView = if (!filePath.endsWith(".doc", ignoreCase = true)) {
                        Base64.encodeToString(rawBytes, Base64.NO_WRAP)
                    } else null

                    if (filePath.endsWith(".doc", ignoreCase = true)) {
                        fileInputStream = FileInputStream(file)
                        val hwpf = org.apache.poi.hwpf.HWPFDocument(fileInputStream)
                        legacyDoc = hwpf
                        val parsedDoc = parseLegacyDocument(hwpf)

                        activeLegacyDocument = legacyDoc
                        activeFilePath = filePath

                        _loadState.value = DocxLoadState.Success(
                            document = parsedDoc,
                            fileName = file.name,
                            docxBase64 = null  // WebView rendering not supported for legacy .doc
                        )
                    } else {
                        // Fast path: WebView renders directly from base64. Never open
                        // XWPFDocument here — its OPC parse stalls on image-heavy
                        // files and doubles peak memory (rawBytes + POI package).
                        // Lightweight zip check only, for the empty-document gate.
                        val hasContent = try {
                            java.util.zip.ZipFile(file).use { zip ->
                                val entry = zip.getEntry("word/document.xml")
                                entry != null && entry.size != 0L
                            }
                        } catch (e: Exception) {
                            true
                        }

                        activeDocument = null
                        activeFilePath = filePath

                        _loadState.value = DocxLoadState.Success(
                            document = DocxDocument(
                                if (hasContent) listOf(
                                    DocxBodyElement.Para(
                                        DocxParagraph(
                                            runs = listOf(DocxRun(text = "", isBold = false, isItalic = false, isUnderline = false, isStrike = false)),
                                            alignment = "LEFT",
                                            headingLevel = 0,
                                            isHeading = false
                                        )
                                    )
                                ) else emptyList()
                            ),
                            fileName = file.name,
                            docxBase64 = base64ForWebView
                        )
                    }

                } catch (t: Throwable) {
                    t.printStackTrace()
                    try {
                        doc?.close()
                    } catch (ex: Throwable) {
                        ex.printStackTrace()
                    }
                    try {
                        legacyDoc?.close()
                    } catch (ex: Throwable) {
                        ex.printStackTrace()
                    }
                    if (base64ForWebView != null) {
                        _loadState.value = DocxLoadState.Success(
                            document = DocxDocument(emptyList()),
                            fileName = File(filePath).name,
                            docxBase64 = base64ForWebView
                        )
                    } else {
                        _loadState.value = DocxLoadState.Error("Apache POI word parser failure: ${t.localizedMessage}")
                    }
                } finally {
                    try {
                        fileInputStream?.close()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    fun setLoadError(message: String) {
        _loadState.value = DocxLoadState.Error(message)
    }

    private fun extractNumber(value: Any?): Long? {
        return when (value) {
            null -> null
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> value.toString().toLongOrNull()
        }
    }

    private fun parseLegacyDocument(doc: org.apache.poi.hwpf.HWPFDocument): DocxDocument {
        val elements = mutableListOf<DocxBodyElement>()
        val range = doc.range
        val numParagraphs = range.numParagraphs()
        for (i in 0 until numParagraphs) {
            val paragraph = range.getParagraph(i)
            val runs = mutableListOf<DocxRun>()
            val numCharacterRuns = paragraph.numCharacterRuns()
            for (j in 0 until numCharacterRuns) {
                val run = paragraph.getCharacterRun(j)
                val text = run.text() ?: ""
                runs.add(
                    DocxRun(
                        text = text,
                        isBold = run.isBold,
                        isItalic = run.isItalic,
                        isUnderline = run.getUnderlineCode() != 0,
                        isStrike = run.isStrikeThrough,
                        fontFamily = run.fontName,
                        fontSizePt = if (run.fontSize > 0) run.fontSize / 2f else null
                    )
                )
            }
            val alignment = when (paragraph.justification) {
                1 -> "CENTER"
                2 -> "RIGHT"
                3 -> "JUSTIFY"
                else -> "LEFT"
            }
            elements.add(
                DocxBodyElement.Para(
                    DocxParagraph(
                        runs = runs,
                        alignment = alignment,
                        headingLevel = 0,
                        isHeading = false,
                        comment = null
                    )
                )
            )
        }
        return DocxDocument(elements = elements, pageGeometry = DocxPageGeometry())
    }

    private fun loadParagraphComments(filePath: String): Map<Int, String> {
        val commentsFile = File("$filePath.comments")
        if (!commentsFile.exists()) return emptyMap()
        val map = mutableMapOf<Int, String>()
        try {
            commentsFile.readLines().forEach { line ->
                val idx = line.indexOf(':')
                if (idx != -1) {
                    val pIdx = line.substring(0, idx).toIntOrNull()
                    val commentText = line.substring(idx + 1)
                    if (pIdx != null) {
                        map[pIdx] = commentText
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return map
    }

    private fun saveParagraphComments(filePath: String, comments: Map<Int, String>) {
        val commentsFile = File("$filePath.comments")
        try {
            val lines = comments.filter { it.value.isNotBlank() }
                .map { "${it.key}:${it.value}" }
            commentsFile.writeText(lines.joinToString("\n"))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isOnOff(onOff: org.openxmlformats.schemas.wordprocessingml.x2006.main.CTOnOff?): Boolean {
        if (onOff == null) return false
        if (!onOff.isSetVal()) return true
        val v = onOff.`val`?.toString()?.lowercase() ?: return true
        return v != "0" && v != "false" && v != "off" && v != "none"
    }

    private fun parseDocument(doc: XWPFDocument): DocxDocument {
        val elements = mutableListOf<DocxBodyElement>()
        val commentsMap = activeFilePath?.let { loadParagraphComments(it) } ?: emptyMap()
        var globalParaIndex = 0

        for (bodyElement in doc.bodyElements) {
            when (bodyElement) {
                is org.apache.poi.xwpf.usermodel.XWPFParagraph -> {
                    val parsed = parseParagraph(bodyElement, commentsMap[globalParaIndex])
                    elements.add(DocxBodyElement.Para(parsed))
                    globalParaIndex++
                }
                is org.apache.poi.xwpf.usermodel.XWPFTable -> {
                    val rows = mutableListOf<DocxTableRow>()
                    for (row in bodyElement.rows) {
                        val cells = mutableListOf<DocxTableCell>()
                        for (cell in row.tableCells) {
                            val cellParas = cell.paragraphs.map { parseParagraph(it, null) }
                            cells.add(DocxTableCell(cellParas))
                        }
                        rows.add(DocxTableRow(cells))
                    }
                    elements.add(DocxBodyElement.Table(rows))
                }
            }
        }

        // Parse sectPr (Page Size & Margins) with fallback to paragraph-level sectPr
        var geometry = DocxPageGeometry()
        try {
            val sectPr = doc.document?.body?.sectPr
                ?: doc.paragraphs.lastOrNull()?.ctp?.pPr?.sectPr
                ?: doc.paragraphs.asReversed().mapNotNull { it.ctp?.pPr?.sectPr }.firstOrNull()

            var widthTwips = 11906L  // default A4 width (or 12240 for Letter)
            var heightTwips = 16838L // default A4 height (or 15840 for Letter)
            var isLandscape = false
            var topTwips = 720L      // default 0.5 inch (36 pt)
            var bottomTwips = 720L
            var leftTwips = 720L
            var rightTwips = 720L
            var headerTwips = 360L
            var footerTwips = 360L

            if (sectPr != null) {
                val pgSz = sectPr.pgSz
                if (pgSz != null) {
                    val rawW = extractNumber(pgSz.w)
                    val rawH = extractNumber(pgSz.h)
                    if (rawW != null && rawW > 0) widthTwips = rawW
                    if (rawH != null && rawH > 0) heightTwips = rawH
                    isLandscape = pgSz.orient?.toString()?.equals("landscape", ignoreCase = true) == true ||
                            (widthTwips > heightTwips)
                }

                val pgMar = sectPr.pgMar
                if (pgMar != null) {
                    extractNumber(pgMar.top)?.let { if (it >= 0) topTwips = it }
                    extractNumber(pgMar.bottom)?.let { if (it >= 0) bottomTwips = it }
                    extractNumber(pgMar.left)?.let { if (it >= 0) leftTwips = it }
                    extractNumber(pgMar.right)?.let { if (it >= 0) rightTwips = it }
                    extractNumber(pgMar.header)?.let { if (it >= 0) headerTwips = it }
                    extractNumber(pgMar.footer)?.let { if (it >= 0) footerTwips = it }
                }
            }

            geometry = DocxPageGeometry(
                widthTwips = widthTwips,
                heightTwips = heightTwips,
                marginTopTwips = topTwips,
                marginBottomTwips = bottomTwips,
                marginLeftTwips = leftTwips,
                marginRightTwips = rightTwips,
                headerMarginTwips = headerTwips,
                footerMarginTwips = footerTwips,
                isLandscape = isLandscape
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return DocxDocument(elements = elements, pageGeometry = geometry)
    }

    private fun parseParagraph(
        paragraph: org.apache.poi.xwpf.usermodel.XWPFParagraph,
        comment: String?
    ): DocxParagraph {
        val runs = mutableListOf<DocxRun>()
        for (run in paragraph.runs) {
            var isPageBreak = false
            var hasTab = false

            try {
                val ctr = run.ctr
                if (ctr != null) {
                    val brList = ctr.brList
                    if (brList != null) {
                        for (br in brList) {
                            if (br.type?.toString()?.equals("page", ignoreCase = true) == true) {
                                isPageBreak = true
                            }
                        }
                    }
                    val tabList = ctr.tabList
                    if (tabList != null && tabList.isNotEmpty()) {
                        hasTab = true
                    }
                }
            } catch (e: Exception) {
                // Ignore XML inspection errors
            }

            var text = run.text() ?: run.getText(0) ?: ""
            if (hasTab && !text.contains("\t")) {
                text = "\t$text"
            }

            val isBold = run.isBold
            val isItalic = run.isItalic
            val isUnderline = run.underline != org.apache.poi.xwpf.usermodel.UnderlinePatterns.NONE
            val isStrike = run.isStrikeThrough
            val color = run.color
            val fontFamily = run.fontFamily
            val fontSize = run.fontSize
            val hyperlinkUrl = if (run is org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun) {
                run.getHyperlink(paragraph.document)?.url
            } else null

            var imageUrl: String? = null
            var emuWidth: Long? = null
            var emuHeight: Long? = null
            try {
                val pictures = run.embeddedPictures
                if (pictures.isNotEmpty()) {
                    val pic = pictures[0]
                    val picData = pic.pictureData?.data
                    if (picData != null && picData.isNotEmpty()) {
                        val ext = pic.pictureData?.suggestFileExtension() ?: "png"
                        try {
                            val ctPic = pic.javaClass.getMethod("getCTPic").invoke(pic)
                            val spPr = ctPic.javaClass.getMethod("getSpPr").invoke(ctPic)
                            val xfrm = spPr.javaClass.getMethod("getXfrm").invoke(spPr)
                            val extVal = xfrm.javaClass.getMethod("getExt").invoke(xfrm)
                            emuWidth = (extVal.javaClass.getMethod("getCx").invoke(extVal) as Number).toLong()
                            emuHeight = (extVal.javaClass.getMethod("getCy").invoke(extVal) as Number).toLong()
                        } catch (e: Throwable) {
                            // ignore CTPic reflection error
                        }
                        val cacheDir = appContext?.cacheDir ?: File(activeFilePath ?: ".").parentFile ?: File(".")
                        val imgHash = picData.contentHashCode()
                        val tempPicFile = File(cacheDir, "docx_img_${imgHash}.$ext")
                        if (!tempPicFile.exists()) {
                            tempPicFile.writeBytes(picData)
                        }
                        imageUrl = tempPicFile.absolutePath
                    }
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }

            runs.add(DocxRun(
                text = text, isBold = isBold, isItalic = isItalic,
                isUnderline = isUnderline, isStrike = isStrike,
                color = color, fontFamily = fontFamily,
                fontSizePt = if (fontSize > 0) fontSize.toFloat() else null,
                hyperlinkUrl = hyperlinkUrl, imageUrl = imageUrl,
                widthEmu = emuWidth, heightEmu = emuHeight,
                isPageBreak = isPageBreak, isTab = hasTab
            ))
        }

        val alignment = when (paragraph.alignment) {
            org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER -> "CENTER"
            org.apache.poi.xwpf.usermodel.ParagraphAlignment.RIGHT -> "RIGHT"
            org.apache.poi.xwpf.usermodel.ParagraphAlignment.BOTH -> "JUSTIFY"
            else -> "LEFT"
        }

        val styleId = paragraph.styleID?.lowercase() ?: ""
        val headingLevel = when {
            styleId.contains("heading1") || styleId == "title" -> 1
            styleId.contains("heading2") -> 2
            styleId.contains("heading3") -> 3
            styleId.contains("heading4") -> 4
            styleId.contains("heading5") || styleId.contains("heading6") -> 5
            else -> 0
        }

        // Parse w:spacing (line, lineRule, before, after)
        var lineHeightMultiplier = 1.15f
        var exactLineHeightPt: Float? = null
        var spacingBeforePt = 0f
        var spacingAfterPt = 0f
        var isKeepNext = headingLevel in 1..3
        var isKeepLines = false
        var isPageBreakBefore = false
        val tabStops = mutableListOf<DocxTabStop>()

        try {
            val ctp = paragraph.ctp
            val pPr = ctp?.pPr
            if (pPr != null) {
                if (isOnOff(pPr.keepNext)) isKeepNext = true
                if (isOnOff(pPr.keepLines)) isKeepLines = true
                if (isOnOff(pPr.pageBreakBefore)) isPageBreakBefore = true

                val spacing = pPr.spacing
                if (spacing != null) {
                    val rawLine = extractNumber(spacing.line)
                    val lineRuleStr = spacing.lineRule?.toString()?.lowercase() ?: "auto"
                    if (rawLine != null && rawLine > 0) {
                        if (lineRuleStr == "exact" || lineRuleStr == "atleast") {
                            exactLineHeightPt = rawLine / 20f
                        } else {
                            lineHeightMultiplier = (rawLine / 240f).coerceIn(0.85f, 3.0f)
                        }
                    }
                    val rawBefore = extractNumber(spacing.before)
                    if (rawBefore != null && rawBefore > 0) {
                        spacingBeforePt = rawBefore / 20f
                    }
                    val rawAfter = extractNumber(spacing.after)
                    if (rawAfter != null && rawAfter > 0) {
                        spacingAfterPt = rawAfter / 20f
                    }
                }

                val ctTabs = pPr.tabs
                if (ctTabs != null) {
                    for (tab in ctTabs.tabList) {
                        val pos = extractNumber(tab.pos) ?: continue
                        val align = when (tab.`val`?.toString()?.lowercase()) {
                            "right" -> TabStopAlignment.RIGHT
                            "center" -> TabStopAlignment.CENTER
                            "decimal" -> TabStopAlignment.DECIMAL
                            "clear" -> TabStopAlignment.CLEAR
                            else -> TabStopAlignment.LEFT
                        }
                        tabStops.add(DocxTabStop(pos, align))
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore XML inspection errors
        }

        // Fallbacks from POI high-level getters if XML was absent
        if (spacingBeforePt == 0f && paragraph.spacingBefore > 0) {
            spacingBeforePt = paragraph.spacingBefore / 20f
        }
        if (spacingAfterPt == 0f && paragraph.spacingAfter > 0) {
            spacingAfterPt = paragraph.spacingAfter / 20f
        }

        val rawIndentLeft = paragraph.indentationLeft.coerceAtLeast(0)
        val rawFirstLine = paragraph.indentationFirstLine.coerceAtLeast(0)
        val indentStartPt = rawIndentLeft / 20f
        val firstLineIndentPt = rawFirstLine / 20f

        return DocxParagraph(
            runs = runs,
            alignment = alignment,
            headingLevel = headingLevel,
            isHeading = headingLevel > 0,
            comment = comment,
            spacingAfterPt = spacingAfterPt,
            spacingBeforePt = spacingBeforePt,
            lineHeightMultiplier = lineHeightMultiplier,
            exactLineHeightPt = exactLineHeightPt,
            indentStartPt = indentStartPt,
            firstLineIndentPt = firstLineIndentPt,
            tabStops = tabStops,
            isKeepNext = isKeepNext,
            isKeepLines = isKeepLines,
            isPageBreakBefore = isPageBreakBefore
        )
    }
    /**
     * Converts the current active Word document directly to PDF and writes it to a SAF URI.
     */
    fun exportToPdf(
        context: Context,
        outputUri: Uri,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        viewModelScope.launch {
            if (activeLegacyDocument != null) {
                onFailure("Exporting to PDF is not supported for legacy Word (.doc) documents. Please save as .docx to export.")
                return@launch
            }
            val docxPath = activeFilePath
            if (docxPath == null) {
                onFailure("No active document loaded.")
                return@launch
            }
            withContext(Dispatchers.IO) {
                val tempPdfFile = File(context.cacheDir, "temp_export_${System.currentTimeMillis()}.pdf")
                try {
                    officeConverter.convertDocxToPdf(File(docxPath), tempPdfFile, context)
                    context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                        tempPdfFile.inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        onSuccess()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        onFailure(e.localizedMessage ?: "Conversion failed")
                    }
                } finally {
                    if (tempPdfFile.exists()) {
                        tempPdfFile.delete()
                    }
                }
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _currentMatchIndex.value = -1
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val path = activeFilePath ?: return@withContext
                val results = if (path.endsWith(".doc", ignoreCase = true)) {
                    DocumentSearchEngine.searchDoc(path, query)
                } else {
                    DocumentSearchEngine.searchDocx(path, query)
                }
                _searchResults.value = results
                if (results.isNotEmpty()) {
                    _currentMatchIndex.value = 0
                } else {
                    _currentMatchIndex.value = -1
                }
            }
        }
    }

    fun nextMatch() {
        val results = _searchResults.value
        if (results.isEmpty()) return
        val nextIndex = (_currentMatchIndex.value + 1) % results.size
        _currentMatchIndex.value = nextIndex
    }

    fun prevMatch() {
        val results = _searchResults.value
        if (results.isEmpty()) return
        val prevIndex = (_currentMatchIndex.value - 1 + results.size) % results.size
        _currentMatchIndex.value = prevIndex
    }

    override fun onCleared() {
        super.onCleared()
        try {
            activeDocument?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            activeLegacyDocument?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
