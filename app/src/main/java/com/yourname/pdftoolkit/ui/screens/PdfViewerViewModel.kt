package com.yourname.pdftoolkit.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.pdftoolkit.data.SafUriManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream

// Moved from PdfViewerScreen.kt
enum class AnnotationTool(val displayName: String) {
    NONE("Select"),
    HIGHLIGHTER("Highlighter"),
    MARKER("Marker"),
    UNDERLINE("Underline")
}

data class AnnotationStroke(
    val pageIndex: Int,
    val tool: AnnotationTool,
    val color: Color,
    val points: List<Offset>,
    val strokeWidth: Float
)

// Sealed class for mutually exclusive tool states
sealed class PdfTool {
    object None : PdfTool()
    object Search : PdfTool()
    object Edit : PdfTool() // General Edit mode (shows annotation toolbar)
}

sealed class PdfViewerUiState {
    object Idle : PdfViewerUiState()
    object Loading : PdfViewerUiState()
    data class Error(val message: String) : PdfViewerUiState()
    data class Loaded(val totalPages: Int) : PdfViewerUiState()
}

sealed class SearchState {
    object Idle : SearchState()
    object Searching : SearchState()
    data class Results(
        val matches: List<Pair<Int, Int>>, // (pageIndex, textPositionIndex)
        val currentMatchIndex: Int
    ) : SearchState()
    object Empty : SearchState()
}

sealed class SaveState {
    object Idle : SaveState()
    object Saving : SaveState()
    object Success : SaveState()
    data class Error(val message: String) : SaveState()
}

class PdfViewerViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<PdfViewerUiState>(PdfViewerUiState.Idle)
    val uiState: StateFlow<PdfViewerUiState> = _uiState.asStateFlow()

    private val _toolState = MutableStateFlow<PdfTool>(PdfTool.None)
    val toolState: StateFlow<PdfTool> = _toolState.asStateFlow()

    private val _selectedAnnotationTool = MutableStateFlow(AnnotationTool.NONE)
    val selectedAnnotationTool: StateFlow<AnnotationTool> = _selectedAnnotationTool.asStateFlow()

    private val _selectedColor = MutableStateFlow(Color.Yellow.copy(alpha = 0.5f))
    val selectedColor: StateFlow<Color> = _selectedColor.asStateFlow()

    private val _annotations = MutableStateFlow<List<AnnotationStroke>>(emptyList())
    val annotations: StateFlow<List<AnnotationStroke>> = _annotations.asStateFlow()

    // Search State
    private val _searchState = MutableStateFlow<SearchState>(SearchState.Idle)
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    // Save State
    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    // Document management
    private var document: PDDocument? = null
    private var pdfRenderer: PDFRenderer? = null
    private val documentMutex = Mutex()

    // Cache for extracted text to avoid re-parsing
    // Map<PageIndex, TextContent>
    private val extractedTextCache = mutableMapOf<Int, String>()

    // Bitmap Cache
    // Calculate cache size: Use 1/8th of the available memory for this memory cache.
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8

    private val bitmapCache = object : LruCache<Int, Bitmap>(cacheSize) {
        override fun sizeOf(key: Int, bitmap: Bitmap): Int {
            // The cache size will be measured in kilobytes rather than number of items.
            return bitmap.byteCount / 1024
        }
    }

    fun loadPdf(context: Context, uri: Uri, password: String = "") {
        viewModelScope.launch {
            _uiState.value = PdfViewerUiState.Loading
            try {
                if (!PDFBoxResourceLoader.isReady()) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                }

                closeDocument() // Close existing if any

                withContext(Dispatchers.IO) {
                    val inputStream = context.contentResolver.openInputStream(uri)
                        ?: throw Exception("Cannot open URI")

                    val doc = if (password.isNotEmpty()) {
                        PDDocument.load(inputStream, password)
                    } else {
                        PDDocument.load(inputStream)
                    }

                    documentMutex.withLock {
                        document = doc
                        pdfRenderer = PDFRenderer(doc)
                    }

                    _uiState.value = PdfViewerUiState.Loaded(doc.numberOfPages)
                }
            } catch (e: Exception) {
                Log.e("PdfViewerVM", "Error loading PDF", e)
                _uiState.value = PdfViewerUiState.Error(e.message ?: "Failed to load PDF")
            }
        }
    }

    suspend fun loadPage(pageIndex: Int): Bitmap? {
        // Check cache first
        bitmapCache.get(pageIndex)?.let { return it }

        return withContext(Dispatchers.IO) {
            documentMutex.withLock {
                try {
                    val renderer = pdfRenderer ?: return@withLock null
                    // Render at 1.5x scale (approx 108 dpi) for good quality on mobile
                    val scale = 1.5f
                    val bitmap = renderer.renderImage(pageIndex, scale)

                    if (bitmap != null) {
                        bitmapCache.put(pageIndex, bitmap)
                    }
                    bitmap
                } catch (e: Exception) {
                    Log.e("PdfViewerVM", "Error rendering page $pageIndex", e)
                    null
                }
            }
        }
    }

    fun setTool(tool: PdfTool) {
        _toolState.value = tool
        // Reset states based on tool selection to prevent conflicts
        when (tool) {
            PdfTool.None -> {
                _selectedAnnotationTool.value = AnnotationTool.NONE
                _searchState.value = SearchState.Idle
            }
            PdfTool.Edit -> {
                // Clear search when editing
                _searchState.value = SearchState.Idle
                // Default to Highlighter when entering Edit mode if none selected
                if (_selectedAnnotationTool.value == AnnotationTool.NONE) {
                    _selectedAnnotationTool.value = AnnotationTool.HIGHLIGHTER
                }
            }
            PdfTool.Search -> {
                // Clear annotation tool when searching
                _selectedAnnotationTool.value = AnnotationTool.NONE
            }
        }
    }

    fun setAnnotationTool(tool: AnnotationTool) {
        _selectedAnnotationTool.value = tool
        if (tool != AnnotationTool.NONE) {
            setTool(PdfTool.Edit)
        }
    }

    fun setColor(color: Color) {
        _selectedColor.value = color
    }

    fun addAnnotation(stroke: AnnotationStroke) {
        val currentList = _annotations.value.toMutableList()
        currentList.add(stroke)
        _annotations.value = currentList
    }

    fun undoAnnotation() {
        val currentList = _annotations.value.toMutableList()
        if (currentList.isNotEmpty()) {
            currentList.removeAt(currentList.lastIndex)
            _annotations.value = currentList
        }
    }

    fun clearAnnotations() {
        _annotations.value = emptyList()
    }

    // --- Search Logic ---

    fun search(query: String) {
        if (query.length < 2) {
            _searchState.value = SearchState.Idle
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _searchState.value = SearchState.Searching

            val results = mutableListOf<Pair<Int, Int>>()
            val lowerQuery = query.lowercase()

            documentMutex.withLock {
                val doc = document ?: return@withLock
                val totalPages = doc.numberOfPages

                // Only search first 20 pages for performance (or all if small)
                // Assuming we want to search all for correctness, but maybe chunk it?
                // Let's stick to the previous 20 page limit or increase it slightly
                // Ideally, we should do this incrementally or on a background thread properly.
                // For now, let's limit to 50 pages to be safe or just all if user expects it.
                // The previous code had `maxPages = totalPages.coerceAtMost(20)`.
                val maxPages = totalPages // Bolt optimization: let's try searching all but carefully.

                try {
                    val stripper = PDFTextStripper()

                    for (i in 0 until maxPages) {
                        // Check cache first
                        var text = extractedTextCache[i]
                        if (text == null) {
                            stripper.startPage = i + 1
                            stripper.endPage = i + 1
                            text = stripper.getText(doc)
                            extractedTextCache[i] = text
                        }

                        val lowerText = text!!.lowercase()
                        var pos = 0
                        while (true) {
                            val found = lowerText.indexOf(lowerQuery, pos)
                            if (found == -1) break
                            results.add(i to found)
                            pos = found + 1
                        }
                    }
                } catch (e: Exception) {
                    Log.e("PdfViewerVM", "Error extracting text during search", e)
                }
            }

            if (results.isNotEmpty()) {
                _searchState.value = SearchState.Results(results, 0)
            } else {
                _searchState.value = SearchState.Empty
            }
        }
    }

    fun nextSearchResult() {
        val currentState = _searchState.value
        if (currentState is SearchState.Results) {
            val nextIndex = if (currentState.currentMatchIndex < currentState.matches.size - 1)
                currentState.currentMatchIndex + 1 else 0 // Loop or stop? Let's stop at end usually, but loop is nice.
            _searchState.value = currentState.copy(currentMatchIndex = nextIndex)
        }
    }

    fun previousSearchResult() {
        val currentState = _searchState.value
        if (currentState is SearchState.Results) {
            val prevIndex = if (currentState.currentMatchIndex > 0)
                currentState.currentMatchIndex - 1 else currentState.matches.size - 1
            _searchState.value = currentState.copy(currentMatchIndex = prevIndex)
        }
    }

    fun clearSearch() {
        _searchState.value = SearchState.Idle
    }

    suspend fun getSearchHighlights(pageIndex: Int, query: String): List<List<RectF>> = withContext(Dispatchers.IO) {
        if (query.length < 2) return@withContext emptyList<List<RectF>>()

        val allMatches = mutableListOf<List<RectF>>()

        documentMutex.withLock {
            val doc = document ?: return@withLock

            try {
                val textPositions = mutableListOf<TextPosition>()

                val stripper = object : PDFTextStripper() {
                    override fun processTextPosition(text: TextPosition) {
                        super.processTextPosition(text)
                        textPositions.add(text)
                    }
                }

                stripper.sortByPosition = true
                stripper.startPage = pageIndex + 1
                stripper.endPage = pageIndex + 1

                // This populates textPositions
                stripper.getText(doc) // We don't need the string return, just the callback side-effects

                val lowerQuery = query.lowercase()

                val sb = StringBuilder()
                textPositions.forEach { sb.append(it.unicode) }
                val rawText = sb.toString().lowercase()

                var pos = 0
                while (true) {
                    val found = rawText.indexOf(lowerQuery, pos)
                    if (found == -1) break

                    val matchRects = mutableListOf<RectF>()

                    // Construct rect for this match
                    for (i in found until (found + lowerQuery.length)) {
                        if (i < textPositions.size) {
                            val tp = textPositions[i]
                            // Render scale in loadPage is 1.5f.
                            // PDF standard is 72 DPI.
                            // 1.5f * 72 = 108 DPI.
                            // So if we render at 1.5 scale of PDF points, the scale factor relative to points is just 1.5f.
                            val renderScale = 1.5f

                            val x = tp.xDirAdj * renderScale
                            val y = tp.yDirAdj * renderScale
                            val w = tp.widthDirAdj * renderScale
                            val h = tp.heightDir * renderScale

                            matchRects.add(RectF(x, y, x + w, y + h))
                        }
                    }

                    if (matchRects.isNotEmpty()) {
                        allMatches.add(matchRects)
                    }

                    pos = found + 1
                }
            } catch (e: Exception) {
                Log.e("PdfViewerVM", "Error getting highlights", e)
            }
        }

        allMatches
    }

    // --- Save Logic ---

    fun saveAnnotations(context: Context, outputUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _saveState.value = SaveState.Saving
            val currentAnnotations = _annotations.value

            // If no annotations, we should probably just copy the file, but we'll assume the caller checked.

            documentMutex.withLock {
                val srcDoc = document
                if (srcDoc == null) {
                    _saveState.value = SaveState.Error("Document is not loaded")
                    return@launch
                }

                val newDoc = PDDocument()
                var outputStream: BufferedOutputStream? = null

                try {
                    val totalPages = srcDoc.numberOfPages

                    for (i in 0 until totalPages) {
                        val pageAnnotations = currentAnnotations.filter { it.pageIndex == i }

                        if (pageAnnotations.isEmpty()) {
                            // Bolt Optimization: Import page directly (Vector Copy)
                            // This preserves quality and reduces file size/processing time
                            newDoc.importPage(srcDoc.getPage(i))
                        } else {
                            // Flatten annotations for this page
                            // 1. Render page to bitmap
                            val renderer = pdfRenderer ?: PDFRenderer(srcDoc)
                            // Use same scale as viewer for consistency, or higher for print?
                            // 1.5f is ~108 DPI. Might be low for print, but good for screen.
                            // Let's use 2.0f for slightly better saved quality? Or stick to 1.5f.
                            val scale = 1.5f
                            val bitmap = renderer.renderImage(i, scale)

                            // 2. Draw annotations
                            val annotatedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                            val canvas = android.graphics.Canvas(annotatedBitmap)

                             for (annotation in pageAnnotations) {
                                if (annotation.points.size >= 2) {
                                    val paint = android.graphics.Paint().apply {
                                        color = android.graphics.Color.argb(
                                            (annotation.color.alpha * 255).toInt(),
                                            (annotation.color.red * 255).toInt(),
                                            (annotation.color.green * 255).toInt(),
                                            (annotation.color.blue * 255).toInt()
                                        )
                                        strokeWidth = annotation.strokeWidth * (scale / 1.5f) // Adjust for scale if changed
                                        style = android.graphics.Paint.Style.STROKE
                                        strokeCap = android.graphics.Paint.Cap.ROUND
                                        strokeJoin = android.graphics.Paint.Join.ROUND
                                        isAntiAlias = true
                                    }

                                    val path = android.graphics.Path()
                                    // Points are in the coordinate space of the displayed bitmap (which is scaled by 1.5f)
                                    // So we can draw directly.
                                    path.moveTo(annotation.points[0].x, annotation.points[0].y)
                                    for (pIndex in 1 until annotation.points.size) {
                                        path.lineTo(annotation.points[pIndex].x, annotation.points[pIndex].y)
                                    }
                                    canvas.drawPath(path, paint)
                                }
                            }

                            // 3. Create PDF page
                            val width = annotatedBitmap.width.toFloat()
                            val height = annotatedBitmap.height.toFloat()

                            // Convert back to PDF points (72 DPI)
                            // renderer.renderImage(i, scale): result width = page.mediaBox.width * scale.
                            // So to get back to mediaBox size, we divide by scale.
                            val pageWidth = width / scale
                            val pageHeight = height / scale

                            val page = PDPage(PDRectangle(pageWidth, pageHeight))
                            newDoc.addPage(page)

                            // 4. Add image
                            val pdImage = LosslessFactory.createFromImage(newDoc, annotatedBitmap)
                            PDPageContentStream(newDoc, page).use { contentStream ->
                                contentStream.drawImage(pdImage, 0f, 0f, pageWidth, pageHeight)
                            }

                            annotatedBitmap.recycle()
                            // Don't recycle 'bitmap' as it might be in cache?
                            // Actually 'bitmap' from renderImage is fresh if not from cache, but loadPage uses cache.
                            // We shouldn't recycle what we got from loadPage if it's cached!
                            // Here we called renderer.renderImage directly, bypassing loadPage/cache?
                            // No, I called renderer.renderImage(i, scale) directly above.
                            // So 'bitmap' is fresh and NOT in cache. Safe to recycle?
                            // Wait, if I use `viewModel.loadPage(i)` I get cached one.
                            // Here I used `renderer.renderImage`. This is good, avoids cache pollution/locking issues?
                            // Actually, let's use the local 'bitmap' var.
                            bitmap.recycle()
                        }
                    }

                    outputStream = BufferedOutputStream(context.contentResolver.openOutputStream(outputUri))
                    newDoc.save(outputStream)

                    _saveState.value = SaveState.Success
                    SafUriManager.addRecentFile(context, outputUri)

                } catch (e: Exception) {
                    Log.e("PdfViewerVM", "Error saving annotations", e)
                    _saveState.value = SaveState.Error(e.message ?: "Unknown error")
                } finally {
                    try {
                        newDoc.close()
                        outputStream?.close()
                    } catch (e: Exception) {
                        Log.e("PdfViewerVM", "Error closing saved doc", e)
                    }
                }
            }
        }
    }

    private suspend fun closeDocument() {
        documentMutex.withLock {
            try {
                document?.close()
            } catch (e: Exception) {
                Log.e("PdfViewerVM", "Error closing document", e)
            } finally {
                document = null
                pdfRenderer = null
                bitmapCache.evictAll()
                extractedTextCache.clear()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch(Dispatchers.IO) {
            closeDocument()
        }
    }
}
