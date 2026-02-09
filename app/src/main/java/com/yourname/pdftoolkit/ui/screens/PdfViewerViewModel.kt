package com.yourname.pdftoolkit.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.BufferedOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

data class SearchMatch(
    val pageIndex: Int,
    val rects: List<RectF>
)

data class SearchState(
    val query: String = "",
    val matches: List<SearchMatch> = emptyList(),
    val currentMatchIndex: Int = 0,
    val isLoading: Boolean = false
)

sealed class SaveState {
    object Idle : SaveState()
    object Saving : SaveState()
    data class Success(val uri: Uri) : SaveState()
    data class Error(val message: String) : SaveState()
}

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

class PdfViewerViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<PdfViewerUiState>(PdfViewerUiState.Idle)
    val uiState: StateFlow<PdfViewerUiState> = _uiState.asStateFlow()

    private val _toolState = MutableStateFlow<PdfTool>(PdfTool.None)
    val toolState: StateFlow<PdfTool> = _toolState.asStateFlow()

    private val _searchState = MutableStateFlow(SearchState())
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    private val _selectedAnnotationTool = MutableStateFlow(AnnotationTool.NONE)
    val selectedAnnotationTool: StateFlow<AnnotationTool> = _selectedAnnotationTool.asStateFlow()

    private val _selectedColor = MutableStateFlow(Color.Yellow.copy(alpha = 0.5f))
    val selectedColor: StateFlow<Color> = _selectedColor.asStateFlow()

    private val _annotations = MutableStateFlow<List<AnnotationStroke>>(emptyList())
    val annotations: StateFlow<List<AnnotationStroke>> = _annotations.asStateFlow()

    // Document management
    private var document: PDDocument? = null
    private var pdfRenderer: PDFRenderer? = null
    private val documentMutex = Mutex()

    // Search Cache
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

        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) {
             // Recycle bitmap if evicted to free native memory?
             // Android Bitmaps on newer versions (Honeycomb+) are managed by Dalvik/ART,
             // but recycling can still help with large images.
             // However, reusing them via an object pool would be better than recycling if we re-render.
             // For simplicity and safety against reusing recycled bitmaps, we won't manually recycle here
             // immediately unless we are sure it's not used.
             // Relying on GC is safer for ViewModels.
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
                    // inputStream is closed by PDDocument.load usually, but check source.
                    // PDDocument.load(InputStream) reads the stream.

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
        // Reset specific annotation tool if we leave Edit mode
        if (tool != PdfTool.Edit) {
            _selectedAnnotationTool.value = AnnotationTool.NONE
        } else {
            // Default to Highlighter when entering Edit mode
            if (_selectedAnnotationTool.value == AnnotationTool.NONE) {
                _selectedAnnotationTool.value = AnnotationTool.HIGHLIGHTER
            }
        }
    }

    fun setAnnotationTool(tool: AnnotationTool) {
        _selectedAnnotationTool.value = tool
        if (tool != AnnotationTool.NONE) {
            _toolState.value = PdfTool.Edit
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

    fun search(query: String) {
        if (query.length < 2) {
            _searchState.value = SearchState(query = query)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _searchState.value = _searchState.value.copy(query = query, isLoading = true)

            val matches = mutableListOf<SearchMatch>()

            documentMutex.withLock {
                val doc = document ?: return@withLock
                val totalPages = doc.numberOfPages

                for (pageIndex in 0 until totalPages) {
                    try {
                        val lowerQuery = query.lowercase()

                        // Check cache first to avoid re-parsing if query not found
                        val cachedText = extractedTextCache[pageIndex]
                        if (cachedText != null && !cachedText.contains(lowerQuery)) {
                            continue
                        }

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

                        // This populates textPositions and returns text
                        val pageText = stripper.getText(doc).lowercase()
                        extractedTextCache[pageIndex] = pageText

                        val sb = StringBuilder()
                        val positionMap = mutableListOf<Int>() // Map char index in sb to index in textPositions

                        textPositions.forEachIndexed { index, tp ->
                            sb.append(tp.unicode)
                            repeat(tp.unicode.length) {
                                positionMap.add(index)
                            }
                        }

                        val rawText = sb.toString().lowercase()
                        var pos = 0

                        while (true) {
                            val found = rawText.indexOf(lowerQuery, pos)
                            if (found == -1) break

                            val matchRects = mutableListOf<RectF>()

                            for (i in found until (found + lowerQuery.length)) {
                                if (i < positionMap.size) {
                                    val tpIndex = positionMap[i]
                                    val tp = textPositions[tpIndex]

                                    // Scale 1.5f (Matches render scale)
                                    val scale = 1.5f
                                    val x = tp.xDirAdj * scale
                                    val y = tp.yDirAdj * scale
                                    val w = tp.widthDirAdj * scale
                                    val h = tp.heightDir * scale

                                    matchRects.add(RectF(x, y, x + w, y + h))
                                }
                            }

                            if (matchRects.isNotEmpty()) {
                                matches.add(SearchMatch(pageIndex, matchRects))
                            }
                            pos = found + 1
                        }
                    } catch (e: Exception) {
                        Log.e("PdfViewerVM", "Error searching page $pageIndex", e)
                    }
                }
            }

            _searchState.value = SearchState(
                query = query,
                matches = matches,
                isLoading = false
            )
        }
    }

    fun nextMatch() {
        val currentState = _searchState.value
        if (currentState.matches.isNotEmpty()) {
            val nextIndex = (currentState.currentMatchIndex + 1) % currentState.matches.size
            _searchState.value = currentState.copy(currentMatchIndex = nextIndex)
        }
    }

    fun prevMatch() {
        val currentState = _searchState.value
        if (currentState.matches.isNotEmpty()) {
            val prevIndex = if (currentState.currentMatchIndex > 0) currentState.currentMatchIndex - 1 else currentState.matches.size - 1
            _searchState.value = currentState.copy(currentMatchIndex = prevIndex)
        }
    }

    fun clearSearch() {
        _searchState.value = SearchState()
        // Optionally keep tool state or reset it.
        // If we clear search, we likely exit search mode.
        // But maybe user just wants to clear text.
        // Screen logic handles "Close search" via setTool(PdfTool.None).
    }

    fun saveAnnotations(context: Context, outputUri: Uri) {
        val currentAnnotations = _annotations.value

        viewModelScope.launch(Dispatchers.IO) {
            _saveState.value = SaveState.Saving

            documentMutex.withLock {
                val sourceDoc = document
                if (sourceDoc == null) {
                    _saveState.value = SaveState.Error("Document is not loaded")
                    return@withLock
                }

                val destDoc = PDDocument()
                var outputStream: BufferedOutputStream? = null

                try {
                    outputStream = BufferedOutputStream(context.contentResolver.openOutputStream(outputUri))

                    val totalPages = sourceDoc.numberOfPages

                    for (pageIndex in 0 until totalPages) {
                        val pageAnnotations = currentAnnotations.filter { it.pageIndex == pageIndex }

                        if (pageAnnotations.isEmpty()) {
                            // OPTIMIZATION: Fast copy for pages without annotations
                            destDoc.importPage(sourceDoc.getPage(pageIndex))
                        } else {
                            // Render and flatten
                            val cachedBitmap = bitmapCache.get(pageIndex)
                            val workingBitmap = if (cachedBitmap != null) {
                                cachedBitmap.copy(Bitmap.Config.ARGB_8888, true)
                            } else {
                                pdfRenderer?.renderImage(pageIndex, 1.5f)
                            }

                            if (workingBitmap != null) {
                                val canvas = Canvas(workingBitmap)
                                val paint = Paint().apply {
                                    style = Paint.Style.STROKE
                                    strokeCap = Paint.Cap.ROUND
                                    strokeJoin = Paint.Join.ROUND
                                    isAntiAlias = true
                                }

                                pageAnnotations.forEach { annotation ->
                                    paint.color = android.graphics.Color.argb(
                                        (annotation.color.alpha * 255).toInt(),
                                        (annotation.color.red * 255).toInt(),
                                        (annotation.color.green * 255).toInt(),
                                        (annotation.color.blue * 255).toInt()
                                    )
                                    paint.strokeWidth = annotation.strokeWidth

                                    if (annotation.points.isNotEmpty()) {
                                        val path = android.graphics.Path()
                                        path.moveTo(annotation.points.first().x, annotation.points.first().y)
                                        for (i in 1 until annotation.points.size) {
                                            path.lineTo(annotation.points[i].x, annotation.points[i].y)
                                        }
                                        canvas.drawPath(path, paint)
                                    }
                                }

                                // Scale back to PDF points (72 DPI)
                                val scaleFactor = 1.5f
                                val pageWidth = workingBitmap.width / scaleFactor
                                val pageHeight = workingBitmap.height / scaleFactor

                                val page = PDPage(PDRectangle(pageWidth, pageHeight))
                                destDoc.addPage(page)

                                val pdImage = LosslessFactory.createFromImage(destDoc, workingBitmap)
                                PDPageContentStream(destDoc, page).use { cs ->
                                    cs.drawImage(pdImage, 0f, 0f, pageWidth, pageHeight)
                                }

                                workingBitmap.recycle()
                            }
                        }
                    }

                    destDoc.save(outputStream)
                    _saveState.value = SaveState.Success(outputUri)

                } catch (e: Exception) {
                    Log.e("PdfViewerVM", "Error saving PDF", e)
                    _saveState.value = SaveState.Error(e.message ?: "Unknown error")
                } finally {
                    destDoc.close()
                    outputStream?.close()
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
