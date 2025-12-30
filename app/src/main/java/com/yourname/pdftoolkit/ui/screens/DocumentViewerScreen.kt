package com.yourname.pdftoolkit.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.util.Xml
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

private const val TAG = "DocumentViewer"

/**
 * Android-compatible OOXML document parser.
 * 
 * IMPORTANT: Apache POI's SAX/StAX-based parsing is NOT compatible with Android
 * due to missing "declaration-handler" property support in Android's XML parser.
 * 
 * This implementation uses Android's native XmlPullParser with direct ZIP extraction,
 * which is fully compatible and doesn't require any external XML parser libraries.
 */
object AndroidDocumentParser {
    
    /**
     * Parse a DOCX file using Android's XmlPullParser.
     * DOCX files are ZIP archives containing XML files.
     * Main content is in word/document.xml
     */
    fun parseDocx(inputStream: InputStream): List<WordParagraph> {
        val paragraphs = mutableListOf<WordParagraph>()
        
        try {
            ZipInputStream(inputStream).use { zipStream ->
                var entry: ZipEntry? = zipStream.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        // Don't close the zipStream, just parse the entry
                        parseDocxXml(zipStream, paragraphs)
                        break
                    }
                    zipStream.closeEntry()
                    entry = zipStream.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing DOCX: ${e.message}", e)
            throw e
        }
        
        return paragraphs
    }
    
    private fun parseDocxXml(inputStream: InputStream, paragraphs: MutableList<WordParagraph>) {
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(inputStream, "UTF-8")
            
            var currentText = StringBuilder()
            var isBold = false
            var isItalic = false
            var isHeading = false
            var inParagraph = false
            var inRun = false
            
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "p" -> { // Paragraph
                                inParagraph = true
                                currentText = StringBuilder()
                                isBold = false
                                isItalic = false
                                isHeading = false
                            }
                            "r" -> inRun = true // Run (text run)
                            "b" -> if (inRun) isBold = true // Bold
                            "i" -> if (inRun) isItalic = true // Italic
                            "pStyle" -> { // Paragraph style
                                val styleVal = parser.getAttributeValue(null, "val")
                                if (styleVal?.contains("Heading", ignoreCase = true) == true) {
                                    isHeading = true
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inParagraph && inRun) {
                            currentText.append(parser.text)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "p" -> { // End paragraph
                                val text = currentText.toString().trim()
                                if (text.isNotEmpty()) {
                                    paragraphs.add(WordParagraph(
                                        text = text,
                                        isHeading = isHeading,
                                        isBold = isBold,
                                        isItalic = isItalic
                                    ))
                                }
                                inParagraph = false
                            }
                            "r" -> inRun = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: XmlPullParserException) {
            Log.e(TAG, "XML parsing error in DOCX: ${e.message}", e)
            throw IOException("Failed to parse document content: ${e.message}")
        }
    }
    
    /**
     * Parse an XLSX file using Android's XmlPullParser.
     * XLSX files are ZIP archives containing XML files.
     * Sheet data is in xl/worksheets/sheet1.xml, etc.
     * Shared strings are in xl/sharedStrings.xml
     */
    fun parseXlsx(inputStream: InputStream): List<ExcelSheet> {
        val sheets = mutableListOf<ExcelSheet>()
        val sharedStrings = mutableListOf<String>()
        val sheetDataMap = mutableMapOf<String, List<List<String>>>()
        val sheetNames = mutableListOf<String>()
        
        try {
            // First pass: read all entries into memory (since we need multiple files)
            val entries = mutableMapOf<String, ByteArray>()
            ZipInputStream(inputStream).use { zipStream ->
                var entry: ZipEntry? = zipStream.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && (
                        entry.name == "xl/sharedStrings.xml" ||
                        entry.name.startsWith("xl/worksheets/sheet") ||
                        entry.name == "xl/workbook.xml"
                    )) {
                        entries[entry.name] = zipStream.readBytes()
                    }
                    zipStream.closeEntry()
                    entry = zipStream.nextEntry
                }
            }
            
            // Parse shared strings first
            entries["xl/sharedStrings.xml"]?.let { data ->
                parseSharedStrings(data.inputStream(), sharedStrings)
            }
            
            // Parse workbook to get sheet names
            entries["xl/workbook.xml"]?.let { data ->
                parseWorkbook(data.inputStream(), sheetNames)
            }
            
            // Parse each sheet
            entries.filter { it.key.startsWith("xl/worksheets/sheet") }
                .toSortedMap()
                .forEach { (name, data) ->
                    val sheetIndex = name.removePrefix("xl/worksheets/sheet").removeSuffix(".xml").toIntOrNull() ?: 1
                    val rows = parseSheet(data.inputStream(), sharedStrings)
                    val sheetName = sheetNames.getOrElse(sheetIndex - 1) { "Sheet $sheetIndex" }
                    sheets.add(ExcelSheet(name = sheetName, rows = rows))
                }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing XLSX: ${e.message}", e)
            throw e
        }
        
        return sheets
    }
    
    private fun parseSharedStrings(inputStream: InputStream, strings: MutableList<String>) {
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(inputStream, "UTF-8")
            
            var currentString = StringBuilder()
            var inString = false
            
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "si") {
                            inString = true
                            currentString = StringBuilder()
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inString) {
                            currentString.append(parser.text)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "si") {
                            strings.add(currentString.toString())
                            inString = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing shared strings: ${e.message}")
        }
    }
    
    private fun parseWorkbook(inputStream: InputStream, sheetNames: MutableList<String>) {
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(inputStream, "UTF-8")
            
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "sheet") {
                    val name = parser.getAttributeValue(null, "name")
                    if (name != null) {
                        sheetNames.add(name)
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing workbook: ${e.message}")
        }
    }
    
    private fun parseSheet(inputStream: InputStream, sharedStrings: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(inputStream, "UTF-8")
            
            var currentRow = mutableListOf<String>()
            var currentValue = StringBuilder()
            var isSharedString = false
            var inValue = false
            var inRow = false
            var maxCols = 0
            
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "row" -> {
                                inRow = true
                                currentRow = mutableListOf()
                            }
                            "c" -> { // Cell
                                val type = parser.getAttributeValue(null, "t")
                                isSharedString = type == "s"
                                currentValue = StringBuilder()
                            }
                            "v" -> inValue = true // Cell value
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inValue) {
                            currentValue.append(parser.text)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "c" -> { // End cell
                                val value = if (isSharedString) {
                                    val index = currentValue.toString().trim().toIntOrNull()
                                    if (index != null && index < sharedStrings.size) {
                                        sharedStrings[index]
                                    } else {
                                        currentValue.toString()
                                    }
                                } else {
                                    currentValue.toString()
                                }
                                currentRow.add(value)
                            }
                            "v" -> inValue = false
                            "row" -> { // End row
                                if (currentRow.isNotEmpty() && rows.size < 100) { // Limit rows
                                    // Limit columns
                                    val limitedRow = currentRow.take(20)
                                    rows.add(limitedRow)
                                    maxCols = maxOf(maxCols, limitedRow.size)
                                }
                                inRow = false
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
            
            // Normalize row lengths
            return rows.map { row ->
                if (row.size < maxCols) {
                    row + List(maxCols - row.size) { "" }
                } else {
                    row
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing sheet: ${e.message}", e)
        }
        
        return rows
    }
    
    /**
     * Parse a PPTX file using Android's XmlPullParser.
     * PPTX files are ZIP archives containing XML files.
     * Slides are in ppt/slides/slide1.xml, etc.
     */
    fun parsePptx(inputStream: InputStream): List<SlideContent> {
        val slides = mutableListOf<SlideContent>()
        
        try {
            // Read all slide entries
            val slideEntries = mutableMapOf<Int, ByteArray>()
            ZipInputStream(inputStream).use { zipStream ->
                var entry: ZipEntry? = zipStream.nextEntry
                while (entry != null) {
                    if (entry.name.startsWith("ppt/slides/slide") && entry.name.endsWith(".xml")) {
                        val slideNum = entry.name
                            .removePrefix("ppt/slides/slide")
                            .removeSuffix(".xml")
                            .toIntOrNull()
                        if (slideNum != null) {
                            slideEntries[slideNum] = zipStream.readBytes()
                        }
                    }
                    zipStream.closeEntry()
                    entry = zipStream.nextEntry
                }
            }
            
            // Parse each slide in order
            slideEntries.toSortedMap().forEach { (slideNum, data) ->
                val slideContent = parseSlide(data.inputStream(), slideNum)
                slides.add(slideContent)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing PPTX: ${e.message}", e)
            throw e
        }
        
        return slides
    }
    
    private fun parseSlide(inputStream: InputStream, slideNumber: Int): SlideContent {
        val texts = mutableListOf<String>()
        
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(inputStream, "UTF-8")
            
            var currentText = StringBuilder()
            var inTextBody = false
            var inParagraph = false
            
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "txBody" -> inTextBody = true
                            "p" -> if (inTextBody) {
                                inParagraph = true
                                currentText = StringBuilder()
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inParagraph) {
                            currentText.append(parser.text)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "p" -> {
                                if (inParagraph) {
                                    val text = currentText.toString().trim()
                                    if (text.isNotEmpty()) {
                                        texts.add(text)
                                    }
                                    inParagraph = false
                                }
                            }
                            "txBody" -> inTextBody = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing slide $slideNumber: ${e.message}", e)
        }
        
        // First text is usually the title
        val title = texts.firstOrNull() ?: ""
        val content = if (texts.size > 1) texts.drop(1) else emptyList()
        
        return SlideContent(
            slideNumber = slideNumber,
            title = title,
            content = content
        )
    }
}

/**
 * Types of Office documents supported.
 */
enum class DocumentType(val extensions: List<String>, val mimeTypes: List<String>) {
    WORD(
        listOf("docx", "doc"),
        listOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/msword")
    ),
    EXCEL(
        listOf("xlsx", "xls"),
        listOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel")
    ),
    POWERPOINT(
        listOf("pptx", "ppt"),
        listOf("application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/vnd.ms-powerpoint")
    ),
    UNKNOWN(emptyList(), emptyList())
}

/**
 * Represents parsed content from an Office document.
 */
sealed class DocumentContent {
    data class WordContent(
        val paragraphs: List<WordParagraph>,
        val pageCount: Int = 1
    ) : DocumentContent()
    
    data class ExcelContent(
        val sheets: List<ExcelSheet>
    ) : DocumentContent()
    
    data class PowerPointContent(
        val slides: List<SlideContent>
    ) : DocumentContent()
    
    data class Error(val message: String) : DocumentContent()
}

data class WordParagraph(
    val text: String,
    val isHeading: Boolean = false,
    val isBold: Boolean = false,
    val isItalic: Boolean = false
)

data class ExcelSheet(
    val name: String,
    val rows: List<List<String>>
)

data class SlideContent(
    val slideNumber: Int,
    val title: String,
    val content: List<String>
)

/**
 * Document Viewer Screen for Office documents (DOCX, XLSX, PPTX).
 * Supports search, share, and open with external app functionality.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentViewerScreen(
    documentUri: Uri?,
    documentName: String = "Document",
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var isLoading by remember { mutableStateOf(true) }
    var documentContent by remember { mutableStateOf<DocumentContent?>(null) }
    var documentType by remember { mutableStateOf(DocumentType.UNKNOWN) }
    var selectedSheetIndex by remember { mutableIntStateOf(0) }
    
    // Search state
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResultCount by remember { mutableIntStateOf(0) }
    
    // Load document when screen opens
    LaunchedEffect(documentUri) {
        if (documentUri == null) {
            documentContent = DocumentContent.Error("No document provided")
            isLoading = false
            return@LaunchedEffect
        }
        
        isLoading = true
        scope.launch {
            try {
                val type = detectDocumentType(context, documentUri, documentName)
                documentType = type
                documentContent = loadDocument(context, documentUri, type)
            } catch (e: Exception) {
                documentContent = DocumentContent.Error("Failed to load document: ${e.localizedMessage}")
            }
            isLoading = false
        }
    }
    
    // Update search result count when search query or content changes
    LaunchedEffect(searchQuery, documentContent) {
        if (searchQuery.isBlank()) {
            searchResultCount = 0
            return@LaunchedEffect
        }
        
        searchResultCount = when (documentContent) {
            is DocumentContent.WordContent -> {
                val content = documentContent as DocumentContent.WordContent
                content.paragraphs.sumOf { para ->
                    para.text.lowercase().split(searchQuery.lowercase()).size - 1
                }
            }
            is DocumentContent.ExcelContent -> {
                val content = documentContent as DocumentContent.ExcelContent
                content.sheets.sumOf { sheet ->
                    sheet.rows.sumOf { row ->
                        row.sumOf { cell ->
                            cell.lowercase().split(searchQuery.lowercase()).size - 1
                        }
                    }
                }
            }
            is DocumentContent.PowerPointContent -> {
                val content = documentContent as DocumentContent.PowerPointContent
                content.slides.sumOf { slide ->
                    val titleMatches = slide.title.lowercase().split(searchQuery.lowercase()).size - 1
                    val contentMatches = slide.content.sumOf { text ->
                        text.lowercase().split(searchQuery.lowercase()).size - 1
                    }
                    titleMatches + contentMatches
                }
            }
            else -> 0
        }
    }
    
    Scaffold(
        topBar = {
            if (isSearchActive) {
                // Search mode top bar
                SearchTopBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onClose = {
                        isSearchActive = false
                        searchQuery = ""
                    },
                    resultCount = searchResultCount
                )
            } else {
                // Normal top bar
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = documentName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                            Text(
                                text = when (documentType) {
                                    DocumentType.WORD -> "Word Document"
                                    DocumentType.EXCEL -> "Excel Spreadsheet"
                                    DocumentType.POWERPOINT -> "PowerPoint Presentation"
                                    DocumentType.UNKNOWN -> "Document"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        // Search button
                        if (documentContent != null && documentContent !is DocumentContent.Error) {
                            IconButton(onClick = { isSearchActive = true }) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                        }
                        
                        // Share button
                        if (documentUri != null) {
                            IconButton(onClick = { shareDocument(context, documentUri, documentType) }) {
                                Icon(Icons.Default.Share, contentDescription = "Share")
                            }
                            IconButton(onClick = { openWithExternalApp(context, documentUri, documentType) }) {
                                Icon(Icons.Default.OpenInNew, contentDescription = "Open with...")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                isLoading -> {
                    // Loading state
                    DocumentLoadingState()
                }
                
                documentContent is DocumentContent.Error -> {
                    // Error state
                    val error = documentContent as DocumentContent.Error
                    DocumentErrorState(
                        message = error.message,
                        onGoBack = onNavigateBack
                    )
                }
                
                documentContent is DocumentContent.WordContent -> {
                    WordDocumentView(
                        content = documentContent as DocumentContent.WordContent,
                        searchQuery = searchQuery
                    )
                }
                
                documentContent is DocumentContent.ExcelContent -> {
                    val excelContent = documentContent as DocumentContent.ExcelContent
                    ExcelDocumentView(
                        content = excelContent,
                        selectedSheetIndex = selectedSheetIndex,
                        onSheetSelected = { selectedSheetIndex = it },
                        searchQuery = searchQuery
                    )
                }
                
                documentContent is DocumentContent.PowerPointContent -> {
                    PowerPointDocumentView(
                        content = documentContent as DocumentContent.PowerPointContent,
                        searchQuery = searchQuery
                    )
                }
            }
        }
    }
}

/**
 * Search top bar with text field and result count.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    resultCount: Int
) {
    TopAppBar(
        title = {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search in document...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                ),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        Text(
                            text = if (resultCount > 0) "$resultCount found" else "No matches",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (resultCount > 0) MaterialTheme.colorScheme.primary 
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Close search")
            }
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

/**
 * Loading state composable.
 */
@Composable
private fun DocumentLoadingState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            strokeWidth = 4.dp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Loading document...",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Error state composable.
 */
@Composable
private fun DocumentErrorState(
    message: String,
    onGoBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Unable to open document",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onGoBack) {
            Text("Go Back")
        }
    }
}

@Composable
private fun WordDocumentView(
    content: DocumentContent.WordContent,
    searchQuery: String
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        items(content.paragraphs) { paragraph ->
            if (paragraph.text.isNotBlank()) {
                Text(
                    text = buildHighlightedText(
                        text = paragraph.text,
                        searchQuery = searchQuery,
                        baseStyle = SpanStyle(
                            fontWeight = if (paragraph.isBold || paragraph.isHeading) FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (paragraph.isItalic) FontStyle.Italic else FontStyle.Normal,
                            fontSize = if (paragraph.isHeading) 20.sp else 16.sp
                        )
                    ),
                    modifier = Modifier.padding(vertical = if (paragraph.isHeading) 12.dp else 4.dp),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
        
        if (content.paragraphs.isEmpty()) {
            item {
                Text(
                    text = "This document appears to be empty.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp)
                )
            }
        }
    }
}

@Composable
private fun ExcelDocumentView(
    content: DocumentContent.ExcelContent,
    selectedSheetIndex: Int,
    onSheetSelected: (Int) -> Unit,
    searchQuery: String
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Sheet tabs
        if (content.sheets.size > 1) {
            ScrollableTabRow(
                selectedTabIndex = selectedSheetIndex,
                modifier = Modifier.fillMaxWidth(),
                edgePadding = 16.dp
            ) {
                content.sheets.forEachIndexed { index, sheet ->
                    Tab(
                        selected = selectedSheetIndex == index,
                        onClick = { onSheetSelected(index) },
                        text = { Text(sheet.name) }
                    )
                }
            }
        }
        
        // Sheet content
        if (content.sheets.isNotEmpty() && selectedSheetIndex < content.sheets.size) {
            val sheet = content.sheets[selectedSheetIndex]
            
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(rememberScrollState()),
                contentPadding = PaddingValues(16.dp)
            ) {
                items(sheet.rows) { row ->
                    val rowIndex = sheet.rows.indexOf(row)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        row.forEachIndexed { index, cell ->
                            val containsMatch = searchQuery.isNotBlank() && 
                                cell.contains(searchQuery, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .width(120.dp)
                                    .background(
                                        when {
                                            containsMatch -> Color(0xFFFFEB3B).copy(alpha = 0.3f)
                                            rowIndex == 0 -> MaterialTheme.colorScheme.primaryContainer
                                            else -> MaterialTheme.colorScheme.surface
                                        }
                                    )
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = buildHighlightedText(
                                        text = cell,
                                        searchQuery = searchQuery,
                                        baseStyle = SpanStyle(
                                            fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal
                                        )
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3
                                )
                            }
                            if (index < row.size - 1) {
                                Divider(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(40.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                        }
                    }
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun PowerPointDocumentView(
    content: DocumentContent.PowerPointContent,
    searchQuery: String
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(content.slides) { slide ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    // Slide number badge
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = "Slide ${slide.slideNumber}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Title with search highlight
                    if (slide.title.isNotBlank()) {
                        Text(
                            text = buildHighlightedText(
                                text = slide.title,
                                searchQuery = searchQuery,
                                baseStyle = SpanStyle(fontWeight = FontWeight.Bold)
                            ),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    
                    // Content with search highlight
                    slide.content.forEach { text ->
                        if (text.isNotBlank()) {
                            Text(
                                text = buildHighlightedText(
                                    text = "• $text",
                                    searchQuery = searchQuery,
                                    baseStyle = SpanStyle()
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                    
                    if (slide.title.isBlank() && slide.content.isEmpty()) {
                        Text(
                            text = "(Empty slide)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontStyle = FontStyle.Italic
                        )
                    }
                }
            }
        }
        
        if (content.slides.isEmpty()) {
            item {
                Text(
                    text = "This presentation appears to be empty.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp)
                )
            }
        }
    }
}

/**
 * Build annotated string with highlighted search matches.
 */
private fun buildHighlightedText(
    text: String,
    searchQuery: String,
    baseStyle: SpanStyle
) = buildAnnotatedString {
    if (searchQuery.isBlank()) {
        withStyle(baseStyle) {
            append(text)
        }
        return@buildAnnotatedString
    }
    
    val highlightStyle = baseStyle.copy(
        background = Color(0xFFFFEB3B).copy(alpha = 0.6f),
        fontWeight = FontWeight.Bold
    )
    
    var startIndex = 0
    val lowercaseText = text.lowercase()
    val lowercaseQuery = searchQuery.lowercase()
    
    while (true) {
        val matchIndex = lowercaseText.indexOf(lowercaseQuery, startIndex)
        if (matchIndex == -1) {
            // No more matches, append rest of text
            withStyle(baseStyle) {
                append(text.substring(startIndex))
            }
            break
        }
        
        // Append text before match
        if (matchIndex > startIndex) {
            withStyle(baseStyle) {
                append(text.substring(startIndex, matchIndex))
            }
        }
        
        // Append highlighted match
        withStyle(highlightStyle) {
            append(text.substring(matchIndex, matchIndex + searchQuery.length))
        }
        
        startIndex = matchIndex + searchQuery.length
    }
}

// Helper functions

private fun detectDocumentType(context: Context, uri: Uri, fileName: String): DocumentType {
    // Try by MIME type first
    val mimeType = context.contentResolver.getType(uri)
    
    DocumentType.entries.forEach { type ->
        if (type.mimeTypes.any { it.equals(mimeType, ignoreCase = true) }) {
            return type
        }
    }
    
    // Fall back to file extension
    val extension = fileName.substringAfterLast('.', "").lowercase()
    DocumentType.entries.forEach { type ->
        if (type.extensions.contains(extension)) {
            return type
        }
    }
    
    return DocumentType.UNKNOWN
}

private suspend fun loadDocument(
    context: Context,
    uri: Uri,
    type: DocumentType
): DocumentContent = withContext(Dispatchers.IO) {
    try {
        // Try to open the input stream
        var inputStream: java.io.InputStream? = null
        
        // Check if this is OUR app's FileProvider URI (not other providers!)
        val isOurFileProvider = uri.scheme == "content" && 
            (uri.authority == "${context.packageName}.provider" ||
             uri.authority?.startsWith("com.yourname.pdftoolkit") == true && 
             uri.authority?.endsWith(".provider") == true)
        
        if (isOurFileProvider) {
            try {
                inputStream = context.contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                // If FileProvider fails, try to extract the file path from cache
                try {
                    val pathSegments = uri.pathSegments
                    if (pathSegments.isNotEmpty()) {
                        val cacheDir = java.io.File(context.cacheDir, "shared_files")
                        val fileName = pathSegments.lastOrNull()
                        if (fileName != null) {
                            val file = java.io.File(cacheDir, fileName)
                            if (file.exists()) {
                                inputStream = file.inputStream()
                            }
                        }
                    }
                } catch (e2: Exception) {
                    // Fall through
                }
            }
        }
        
        // Try direct content resolver access (SAF URIs)
        if (inputStream == null) {
            inputStream = try {
                context.contentResolver.openInputStream(uri)
            } catch (e: SecurityException) {
                throw IOException("Permission denied: Cannot open document. Please try opening from Files tab. ${e.message}")
            } catch (e: Exception) {
                throw IOException("Cannot open document: ${e.message}")
            }
        }
        
        if (inputStream == null) {
            throw IOException("Cannot open document - no access to URI")
        }
        
        // No SAX initialization needed - using Android's native XmlPullParser
        Log.d(TAG, "Loading document with Android-native parser...")
        
        inputStream.use { stream ->
            when (type) {
                DocumentType.WORD -> loadWordDocument(stream)
                DocumentType.EXCEL -> loadExcelDocument(stream)
                DocumentType.POWERPOINT -> loadPowerPointDocument(stream)
                DocumentType.UNKNOWN -> DocumentContent.Error("Unsupported document format")
            }
        }
    } catch (e: SecurityException) {
        DocumentContent.Error("Permission denied: Cannot access document. Please try opening the file from within the app.")
    } catch (e: IOException) {
        DocumentContent.Error("Error reading document: ${e.localizedMessage}")
    } catch (e: Exception) {
        Log.e(TAG, "Exception loading document: ${e.message}", e)
        DocumentContent.Error("Error loading document: ${e.localizedMessage}")
    }
}

/**
 * Load Word document using Android-native OOXML parser.
 * This avoids Apache POI's SAX parsing which is incompatible with Android.
 */
private fun loadWordDocument(inputStream: java.io.InputStream): DocumentContent {
    return try {
        Log.d(TAG, "Starting Word document parsing with Android parser...")
        val paragraphs = AndroidDocumentParser.parseDocx(inputStream)
        Log.d(TAG, "Word document parsed successfully: ${paragraphs.size} paragraphs")
        
        if (paragraphs.isEmpty()) {
            DocumentContent.WordContent(
                paragraphs = listOf(WordParagraph(
                    text = "(Document appears to be empty or contains only images/tables)",
                    isHeading = false,
                    isBold = false,
                    isItalic = true
                ))
            )
        } else {
            DocumentContent.WordContent(paragraphs = paragraphs)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to parse Word document: ${e.message}", e)
        DocumentContent.Error("Failed to parse Word document: ${e.localizedMessage}")
    }
}

/**
 * Load Excel document using Android-native OOXML parser.
 * This avoids Apache POI's SAX parsing which is incompatible with Android.
 */
private fun loadExcelDocument(inputStream: java.io.InputStream): DocumentContent {
    return try {
        Log.d(TAG, "Starting Excel document parsing with Android parser...")
        val sheets = AndroidDocumentParser.parseXlsx(inputStream)
        Log.d(TAG, "Excel document parsed successfully: ${sheets.size} sheets")
        
        if (sheets.isEmpty()) {
            DocumentContent.ExcelContent(
                sheets = listOf(ExcelSheet(
                    name = "Sheet1",
                    rows = listOf(listOf("(Spreadsheet appears to be empty)"))
                ))
            )
        } else {
            DocumentContent.ExcelContent(sheets = sheets)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to parse Excel document: ${e.message}", e)
        DocumentContent.Error("Failed to parse Excel document: ${e.localizedMessage}")
    }
}

/**
 * Load PowerPoint document using Android-native OOXML parser.
 * This avoids Apache POI's SAX parsing which is incompatible with Android.
 */
private fun loadPowerPointDocument(inputStream: java.io.InputStream): DocumentContent {
    return try {
        Log.d(TAG, "Starting PowerPoint document parsing with Android parser...")
        val slides = AndroidDocumentParser.parsePptx(inputStream)
        Log.d(TAG, "PowerPoint document parsed successfully: ${slides.size} slides")
        
        if (slides.isEmpty()) {
            DocumentContent.PowerPointContent(
                slides = listOf(SlideContent(
                    slideNumber = 1,
                    title = "(Presentation appears to be empty)",
                    content = emptyList()
                ))
            )
        } else {
            DocumentContent.PowerPointContent(slides = slides)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to parse PowerPoint document: ${e.message}", e)
        DocumentContent.Error("Failed to parse PowerPoint document: ${e.localizedMessage}")
    }
}

private fun shareDocument(context: Context, uri: Uri, type: DocumentType) {
    try {
        val mimeType = type.mimeTypes.firstOrNull() ?: "*/*"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            this.type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Document"))
    } catch (e: Exception) {
        Toast.makeText(context, "Unable to share document", Toast.LENGTH_SHORT).show()
    }
}

private fun openWithExternalApp(context: Context, uri: Uri, type: DocumentType) {
    try {
        val mimeType = type.mimeTypes.firstOrNull() ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open with"))
    } catch (e: Exception) {
        Toast.makeText(context, "No app found to open this document", Toast.LENGTH_SHORT).show()
    }
}
