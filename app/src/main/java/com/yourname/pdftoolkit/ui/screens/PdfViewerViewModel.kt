package com.yourname.pdftoolkit.ui.screens

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
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
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.math.roundToInt

data class PageTextData(val text: String, val positions: List<TextPosition>)

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
    data class Saving(val progress: Float) : SaveState()
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

    companion object {
        const val RENDER_SCALE = 1.0f
        const val MAX_FILE_SIZE_MB = 100 // Warn/optimize for files larger than this
        const val MAX_CACHE_SIZE_MB = 20 // Max bitmap cache in MB
    }

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

    private val _selectedColor = MutableStateFlow(Color.Yellow)
    val selectedColor: StateFlow<Color> = _selectedColor.asStateFlow()

    private val _annotations = MutableStateFlow<List<AnnotationStroke>>(emptyList())
    val annotations: StateFlow<List<AnnotationStroke>> = _annotations.asStateFlow()
    
    // Redo stack for undone annotations
    private val redoStack = mutableListOf<AnnotationStroke>()

    // Document management
    private var document: PDDocument? = null
    private var pdfRenderer: PDFRenderer? = null
    private val documentMutex = Mutex()
    private var tempFile: File? = null

    // Search Cache
    private val extractedTextCache = mutableMapOf<Int, PageTextData>()

    // Search Job Control
    private var searchJob: Job? = null
    @Volatile
    private var renderTargetWidthPx: Int = 0

    fun setRenderTargetWidth(widthPx: Int) {
        if (widthPx <= 0 || widthPx == renderTargetWidthPx) return
        renderTargetWidthPx = widthPx
        bitmapCache.evictAll()
    }

    fun loadPdf(context: Context, uri: Uri, password: String = "") {
        viewModelScope.launch {
            _uiState.value = PdfViewerUiState.Loading
            try {
                if (!PDFBoxResourceLoader.isReady()) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                }

                withContext(Dispatchers.IO) {
                    closeDocument() // Must run on IO — acquires documentMutex and does file IO
                    // Use a temp file to load the PDF to avoid OOM with large files
                    // PDDocument.load(File, MemoryUsageSetting) allows using disk instead of RAM
                    val fileToLoad: File
                    var createdTempFile: File? = null

                    try {
                        if (uri.scheme == "file" && uri.path != null) {
                            fileToLoad = File(uri.path!!)
                        } else {
                            // For content URIs, copy to a temp file
                            // Create a unique temp file in cache dir
                            val temp = File.createTempFile("pdf_view_", ".pdf", context.cacheDir)

                            context.contentResolver.openInputStream(uri)?.use { input ->
                                FileOutputStream(temp).use { output ->
                                    input.copyTo(output)
                                }
                            } ?: throw Exception("Cannot open URI")

                            fileToLoad = temp
                            createdTempFile = temp // Track locally
                        }

                        // Check file size before loading
                        val fileSize = fileToLoad.length()
                        val fileSizeMB = fileSize / (1024 * 1024)

                        Log.d("PdfViewerVM", "Loading PDF: ${fileSizeMB}MB, available memory check...")

                        // For very large files, warn but still try to load with reduced settings
                        if (fileSizeMB > MAX_FILE_SIZE_MB) {
                            Log.w("PdfViewerVM", "Large PDF detected (${fileSizeMB}MB). Using memory-optimized settings.")
                        }

                        // Check if we have enough memory to safely load this file
                        if (!canSafelyLoadFile(fileSize)) {
                            Log.w("PdfViewerVM", "Low memory warning for ${fileSizeMB}MB PDF. May experience issues.")
                        }

                        // Use temp-file-only mode for large files to reduce RAM usage
                        val memorySettings = if (fileSizeMB > MAX_FILE_SIZE_MB) {
                            MemoryUsageSetting.setupTempFileOnly()
                        } else {
                            MemoryUsageSetting.setupMixed(100 * 1024 * 1024) // 100MB threshold before using temp files
                        }

                        val doc = if (password.isNotEmpty()) {
                            PDDocument.load(fileToLoad, password, memorySettings)
                        } else {
                            PDDocument.load(fileToLoad, memorySettings)
                        }

                        documentMutex.withLock {
                            document = doc
                            pdfRenderer = PDFRenderer(doc)
                            tempFile = createdTempFile // Transfer ownership to instance
                        }

                        // CRITICAL: Pre-render first page BEFORE setting state to Loaded
                        // This ensures the bitmap is ready when the UI first displays,
                        // preventing blank page issues on initial load
                        val firstPageBitmap = loadPage(0)
                        if (firstPageBitmap == null && doc.numberOfPages > 0) {
                            Log.w("PdfViewerVM", "Failed to pre-render first page, but continuing anyway")
                        }

                        _uiState.value = PdfViewerUiState.Loaded(doc.numberOfPages)
                    } catch (e: Throwable) {
                        // Clean up any temp file created if loading failed
                        createdTempFile?.delete()
                        throw e // Rethrow to outer catch
                    }
                }
            } catch (e: OutOfMemoryError) {
                Log.e("PdfViewerVM", "OOM loading PDF - file too large or corrupted", e)
                _uiState.value = PdfViewerUiState.Error("PDF too large or corrupted. Try a smaller file.")
            } catch (e: Exception) {
                Log.e("PdfViewerVM", "Error loading PDF", e)
                _uiState.value = PdfViewerUiState.Error(e.message ?: "Failed to load PDF")
            }
        }
    }

    fun setTool(tool: PdfTool) {
        // Bolt: Logic Conflict Fix - Ensure state cleanup on transition

        // 1. If leaving Search mode
        if (_toolState.value is PdfTool.Search && tool !is PdfTool.Search) {
            stopSearch() // Stop any active search
        }

        // 2. If entering Search mode
        if (tool is PdfTool.Search) {
            // Ensure edit tools are deactivated to prevent ghost interactions
            _selectedAnnotationTool.value = AnnotationTool.NONE
        }

        // 3. If entering Edit mode
        if (tool is PdfTool.Edit) {
            clearSearch() // Clear search results entirely
        }

        // 4. Update tool state
        _toolState.value = tool

        // 5. Reset specific annotation tool if we leave Edit mode
        if (tool !is PdfTool.Edit) {
            _selectedAnnotationTool.value = AnnotationTool.NONE
        }
    }

    fun setAnnotationTool(tool: AnnotationTool) {
        _selectedAnnotationTool.value = tool
        if (tool != AnnotationTool.NONE && _toolState.value !is PdfTool.Edit) {
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
        // Clear redo stack when new annotation is added
        redoStack.clear()
    }

    fun undoAnnotation() {
        val currentList = _annotations.value.toMutableList()
        if (currentList.isNotEmpty()) {
            val removed = currentList.removeAt(currentList.lastIndex)
            _annotations.value = currentList
            // Add to redo stack
            redoStack.add(removed)
        }
    }
    
    fun redoAnnotation() {
        if (redoStack.isNotEmpty()) {
            val stroke = redoStack.removeAt(redoStack.lastIndex)
            val currentList = _annotations.value.toMutableList()
            currentList.add(stroke)
            _annotations.value = currentList
        }
    }
    
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun clearAnnotations() {
        _annotations.value = emptyList()
    }

    /**
     * Validates a bitmap for safe drawing operations.
     * @return true if bitmap is safe to use (non-null, not recycled, has dimensions)
     */
    /**
     * Renders a PDF page using PDFBox with fallback to Android native PdfRenderer.
     * Detects corrupt bitmaps (all same color) and falls back to native renderer.
     * Includes font warming for better initial render performance.
     */
    private fun PDFRenderer.renderImageWithFallback(pageIndex: Int, scale: Float, pdfFile: File?): Bitmap? {
        val pdfBoxBitmap = try {
            renderImage(pageIndex, scale)
        } catch (e: OutOfMemoryError) {
            Log.w("PdfViewerVM", "OOM rendering page $pageIndex with PDFBox, using native fallback", e)
            return renderWithNativePdfRenderer(pageIndex, scale, pdfFile)
        } catch (e: Exception) {
            Log.w("PdfViewerVM", "PDFBox render failed for page $pageIndex, falling back to native", e)
            return renderWithNativePdfRenderer(pageIndex, scale, pdfFile)
        }

        // Check if bitmap is null (font rendering failure)
        if (pdfBoxBitmap == null) {
            Log.w("PdfViewerVM", "PDFBox returned null bitmap for page $pageIndex, using native fallback")
            return renderWithNativePdfRenderer(pageIndex, scale, pdfFile)
        }

        // Check if bitmap is corrupt (all pixels same color or empty)
        if (isBitmapCorrupt(pdfBoxBitmap)) {
            Log.w("PdfViewerVM", "PDFBox produced corrupt bitmap for page $pageIndex, using native fallback")
            pdfBoxBitmap.recycle()
            return renderWithNativePdfRenderer(pageIndex, scale, pdfFile)
        }

        return pdfBoxBitmap
    }
    
    /**
     * Checks if a bitmap is corrupt (all pixels same color or mostly empty).
     */
    private fun isBitmapCorrupt(bitmap: Bitmap): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return true
        
        // Sample pixels to check for uniformity
        val width = bitmap.width
        val height = bitmap.height
        val sampleSize = 10 // Check every 10th pixel
        
        var firstPixel: Int? = null
        var uniformCount = 0
        var totalSamples = 0
        
        for (y in 0 until height step sampleSize) {
            for (x in 0 until width step sampleSize) {
                val pixel = bitmap.getPixel(x, y)
                if (firstPixel == null) {
                    firstPixel = pixel
                } else if (pixel == firstPixel) {
                    uniformCount++
                }
                totalSamples++
            }
        }
        
        // If 95%+ of sampled pixels are the same color, consider it corrupt
        return totalSamples > 0 && uniformCount.toFloat() / totalSamples > 0.95f
    }
    
    /**
     * Renders a PDF page using Android's native PdfRenderer.
     */
    private fun renderWithNativePdfRenderer(pageIndex: Int, scale: Float, pdfFile: File?): Bitmap? {
        if (pdfFile == null || !pdfFile.exists()) {
            Log.e("PdfViewerVM", "Cannot use native renderer: PDF file not available")
            return null
        }
        
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        
        return try {
            pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            
            if (pageIndex >= renderer.pageCount) {
                Log.e("PdfViewerVM", "Page index $pageIndex out of bounds for native renderer")
                return null
            }
            
            page = renderer.openPage(pageIndex)

            val width = (page.width * scale).roundToInt().coerceAtLeast(1)
            val height = (page.height * scale).roundToInt().coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = if (!bitmap.isRecycled) Canvas(bitmap) else return null
            canvas.drawColor(android.graphics.Color.WHITE) // White background
            
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            
            Log.d("PdfViewerVM", "Native PdfRenderer successfully rendered page $pageIndex")
            bitmap
        } catch (e: Exception) {
            Log.e("PdfViewerVM", "Native PdfRenderer failed for page $pageIndex", e)
            null
        } finally {
            page?.close()
            renderer?.close()
            pfd?.close()
        }
    }

    // Track bitmaps awaiting delayed recycle to prevent returning them during fast scroll
    private val pendingRecycleBitmaps = java.util.HashSet<Bitmap>()

    /**
     * Validates a bitmap for safe drawing operations.
     * @return true if bitmap is safe to use (non-null, not recycled, has dimensions, not pending recycle)
     */
    private fun isBitmapValid(bitmap: Bitmap?): Boolean {
        if (bitmap == null) return false
        if (bitmap.isRecycled) return false
        if (bitmap.width <= 0 || bitmap.height <= 0) return false
        // CRITICAL: Check if bitmap is pending recycle (Fix D - fast scroll issue)
        if (pendingRecycleBitmaps.contains(bitmap)) return false
        return true
    }

    /**
     * Safely draws to a canvas with try-catch protection.
     * @return true if draw operation succeeded
     */
    private fun safeCanvasDraw(
        logTag: String,
        drawOperation: () -> Unit
    ): Boolean {
        return try {
            drawOperation()
            true
        } catch (e: Exception) {
            Log.e("PdfViewerVM", "Canvas draw failed in $logTag: ${e.message}", e)
            false
        }
    }

    // Bitmap cache with dynamic sizing based on file size and available memory
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val maxCacheSize = (MAX_CACHE_SIZE_MB * 1024).coerceAtMost(maxMemory / 8)
    private val bitmapCache = object : LruCache<Int, Bitmap>(maxCacheSize) {
        override fun sizeOf(key: Int, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }

        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap?, newValue: Bitmap?) {
            super.entryRemoved(evicted, key, oldValue, newValue)
            // Fix D: Track bitmap as pending recycle before delayed recycle
            // This prevents returning recycled bitmaps during fast scroll
            oldValue?.let { bitmap ->
                pendingRecycleBitmaps.add(bitmap)
                viewModelScope.launch(Dispatchers.IO) {
                    delay(500)
                    // Remove from pending set before recycling
                    pendingRecycleBitmaps.remove(bitmap)
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    /**
     * Check if there's enough memory to safely load a PDF file.
     * For large files, we need to be more conservative with memory.
     */
    private fun canSafelyLoadFile(fileSize: Long): Boolean {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val availableMemory = maxMemory - usedMemory

        // Require at least 4x file size in available memory for PDF parsing
        // PDFs can expand to 10x their size in memory when parsed
        // Be more conservative to prevent OOM on edge cases
        val requiredMemory = fileSize * 4

        return availableMemory > requiredMemory
    }

    private fun getRenderTargetWidthPx(): Int {
        return renderTargetWidthPx.takeIf { it > 0 }
            ?: Resources.getSystem().displayMetrics.widthPixels.coerceAtLeast(1)
    }

    private fun getPageRenderScale(page: PDPage): Float {
        val pageBox = page.cropBox ?: page.mediaBox
        val pageWidthPt = (pageBox.width.takeIf { it > 0f }
            ?: page.mediaBox.width.takeIf { it > 0f }
            ?: 595f)
        val targetWidthPx = getRenderTargetWidthPx().toFloat()
        return (targetWidthPx / pageWidthPt).coerceIn(0.1f, 4.0f)
    }

    /**
     * Calculate appropriate render scale based on file size and available memory.
     * Large files use lower scale to prevent OOM.
     */
    private fun getOptimalRenderScale(fileSize: Long): Float {
        val fileSizeMB = fileSize / (1024 * 1024)
        return when {
            fileSizeMB > 100 -> 0.75f // Large files: 75% scale
            fileSizeMB > 50 -> 0.85f // Medium-large: 85% scale
            else -> RENDER_SCALE // Normal: 100% scale
        }
    }

    private var prefetchJob: Job? = null
    
    suspend fun loadPage(pageIndex: Int): Bitmap? {
        // Check cache first - validate bitmap is still usable
        bitmapCache.get(pageIndex)?.let { cached ->
            if (isBitmapValid(cached)) {
                return cached
            } else {
                // Remove invalid bitmap from cache
                bitmapCache.remove(pageIndex)
            }
        }

        // Trigger prefetch for adjacent pages
        prefetchPages(pageIndex)

        return withContext(Dispatchers.IO) {
            ensureActive()

            documentMutex.withLock {
                try {
                    // Double check cache inside lock
                    bitmapCache.get(pageIndex)?.let { cached ->
                        if (isBitmapValid(cached)) {
                            return@withLock cached
                        } else {
                            bitmapCache.remove(pageIndex)
                        }
                    }

                    val renderer = pdfRenderer ?: return@withLock null
                    val doc = document ?: return@withLock null
                    val scale = getPageRenderScale(doc.getPage(pageIndex))

                    ensureActive()

                    // Try PDFBox first, fall back to Android native PdfRenderer on failure
                    val bitmap = try {
                        renderer.renderImageWithFallback(pageIndex, scale, tempFile)
                    } catch (e: OutOfMemoryError) {
                        Log.e("PdfViewerVM", "OOM rendering page $pageIndex at scale $scale, retrying with lower scale", e)
                        // Retry with lower scale to prevent crash
                        try {
                            renderer.renderImageWithFallback(pageIndex, scale * 0.5f, tempFile)
                        } catch (e2: OutOfMemoryError) {
                            Log.e("PdfViewerVM", "OOM even at reduced scale for page $pageIndex", e2)
                            null
                        }
                    } catch (e: Exception) {
                        Log.e("PdfViewerVM", "Error rendering page $pageIndex with PDFBox, trying native fallback", e)
                        renderWithNativePdfRenderer(pageIndex, scale, tempFile)
                    }

                    if (bitmap != null && isBitmapValid(bitmap)) {
                        bitmapCache.put(pageIndex, bitmap)
                    } else if (bitmap != null && bitmap.isRecycled) {
                        Log.w("PdfViewerVM", "Rendered bitmap already recycled for page $pageIndex")
                        return@withLock null
                    }
                    bitmap
                } catch (e: Exception) {
                    Log.e("PdfViewerVM", "Error rendering page $pageIndex", e)
                    null
                }
            }
        }
    }

    private fun prefetchPages(currentPage: Int) {
        val totalPages = (_uiState.value as? PdfViewerUiState.Loaded)?.totalPages ?: return

        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            val range = listOf(
                currentPage + 1, currentPage + 2, currentPage + 3,  // Ahead pages
                currentPage - 1, currentPage - 2                       // Behind pages
            )

            for (page in range) {
                if (page in 0 until totalPages) {
                    // Skip if already cached and valid
                    val cached = bitmapCache.get(page)
                    if (cached != null && isBitmapValid(cached)) {
                        continue
                    }
                    if (cached != null && !isBitmapValid(cached)) {
                        bitmapCache.remove(page)
                    }

                    yield()

                    try {
                        documentMutex.withLock {
                            val cached2 = bitmapCache.get(page)
                            if (cached2 != null && isBitmapValid(cached2)) {
                                return@withLock
                            }
                            if (cached2 != null) {
                                bitmapCache.remove(page)
                            }

                            try {
                                val doc = document ?: return@withLock
                                val scale = getPageRenderScale(doc.getPage(page))
                                pdfRenderer?.renderImageWithFallback(page, scale, tempFile)?.let { bitmap ->
                                    if (isBitmapValid(bitmap)) {
                                        bitmapCache.put(page, bitmap)
                                    } else {
                                        Log.w("PdfViewerVM", "Prefetched invalid bitmap for page $page")
                                    }
                                }
                            } catch (e: OutOfMemoryError) {
                                Log.w("PdfViewerVM", "OOM prefetching page $page, skipping")
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore prefetch errors
                        Log.w("PdfViewerVM", "Error prefetching page $page: ${e.message}")
                    }
                }
            }
        }
    }

    fun stopSearch() {
        searchJob?.cancel()
        searchJob = null
        val currentState = _searchState.value
        if (currentState.isLoading) {
            _searchState.value = currentState.copy(isLoading = false)
        }
    }

    fun search(query: String) {
        // Cancel previous search
        stopSearch()

        if (query.length < 2) {
            _searchState.value = SearchState(query = query)
            return
        }

        searchJob = viewModelScope.launch(Dispatchers.IO) {
            _searchState.value = _searchState.value.copy(query = query, isLoading = true)

            val matches = mutableListOf<SearchMatch>()

            documentMutex.withLock {
                val doc = document ?: return@withLock
                val totalPages = doc.numberOfPages

                for (pageIndex in 0 until totalPages) {
                    ensureActive() // Allow cancellation
                    yield()

                    try {
                        val lowerQuery = query.lowercase()

                        // Check cache first
                        var pageData = extractedTextCache[pageIndex]

                        if (pageData == null) {
                            // Extract if not cached
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
                            pageData = PageTextData(pageText, textPositions)
                            extractedTextCache[pageIndex] = pageData
                        }

                        if (!pageData.text.contains(lowerQuery)) {
                            continue
                        }

                        val sb = StringBuilder()
                        val positionMap = mutableListOf<Int>() // Map char index in sb to index in textPositions

                        pageData.positions.forEachIndexed { index, tp ->
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
                                    val tp = pageData.positions[tpIndex]

                                    // Scale 1.5f (Matches render scale)
                                    val scale = getPageRenderScale(doc.getPage(pageIndex))
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
        searchJob?.cancel()
        searchJob = null
        _searchState.value = SearchState()
        // Optionally keep tool state or reset it.
        // If we clear search, we likely exit search mode.
        // But maybe user just wants to clear text.
        // Screen logic handles "Close search" via setTool(PdfTool.None).
    }

    fun saveAnnotations(context: Context, outputUri: Uri) {
        val currentAnnotations = _annotations.value

        viewModelScope.launch(Dispatchers.IO) {
            _saveState.value = SaveState.Saving(0f)

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
                        ensureActive() // Allow cancellation
                        yield() // Bolt: Allow UI updates

                        val pageAnnotations = currentAnnotations.filter { it.pageIndex == pageIndex }
                        val sourcePage = sourceDoc.getPage(pageIndex)
                        val rotation = sourcePage.rotation

                        if (pageAnnotations.isEmpty()) {
                            // OPTIMIZATION: Fast copy for pages without annotations
                            val importedPage = destDoc.importPage(sourcePage)
                            // PDDocument.importPage returns the imported page, which belongs to destDoc but isn't added yet
                            // We must call addPage.
                            // destDoc.addPage(importedPage) // Removed to prevent duplicate pages
                        } else if (rotation == 0) {
                            // VECTOR INJECTION: Preserve text and vectors for upright pages
                            val importedPage = destDoc.importPage(sourcePage)
                            // importedPage is owned by destDoc, so we don't need to manually copy mediaBox from source.
                            // destDoc.addPage(importedPage) // Removed to prevent duplicate pages

                            // Append content stream to draw on top
                            PDPageContentStream(destDoc, importedPage, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                                val pageHeight = importedPage.mediaBox.height
                                var currentAlpha = -1f // Initialize with impossible alpha

                                pageAnnotations.forEach { annotation ->
                                    // Set color and alpha
                                    cs.setStrokingColor(annotation.color.red, annotation.color.green, annotation.color.blue)

                                    if (currentAlpha != annotation.color.alpha) {
                                        currentAlpha = annotation.color.alpha
                                        val graphicsState = PDExtendedGraphicsState()
                                        graphicsState.strokingAlphaConstant = currentAlpha
                                        cs.setGraphicsStateParameters(graphicsState)
                                    }

                                    // Set line width (normalized to PDF points)
                                    val pageWidth = importedPage.mediaBox.width
                                    val pdfStrokeWidth = annotation.strokeWidth * pageWidth

                                    cs.setLineWidth(pdfStrokeWidth)
                                    cs.setLineCapStyle(1) // Round Cap
                                    cs.setLineJoinStyle(1) // Round Join

                                    if (annotation.points.isNotEmpty()) {
                                        val first = annotation.points.first()
                                        // Coordinate Transform: Normalized -> PDF Bottom-Left
                                        // X_pdf = X_norm * PageWidth
                                        // Y_pdf = PageHeight - (Y_norm * PageHeight)

                                        val startX = first.x * pageWidth
                                        val startY = pageHeight - (first.y * pageHeight)

                                        cs.moveTo(startX, startY)

                                        for (i in 1 until annotation.points.size) {
                                            val p = annotation.points[i]
                                            val px = p.x * pageWidth
                                            val py = pageHeight - (p.y * pageHeight)
                                            cs.lineTo(px, py)
                                        }
                                        cs.stroke()
                                    }
                                }
                            }
                        } else {
                            // RASTER FALLBACK: For rotated pages, use safer bitmap rasterization to guarantee alignment
                            // Render and flatten

                            // Render fresh and ensure mutable copy
                            val rendered = pdfRenderer?.renderImage(pageIndex, RENDER_SCALE)
                            val workingBitmap = rendered?.copy(Bitmap.Config.ARGB_8888, true).also {
                                // Recycle the immutable rendered one if it was created just for this
                                rendered?.recycle()
                            }

                            if (workingBitmap != null) {
                                // Guard: Ensure bitmap is valid before creating canvas
                                if (workingBitmap.isRecycled) {
                                    return@withLock
                                }
                                
                                try {
                                    val canvas = Canvas(workingBitmap)
                                    val paint = Paint().apply {
                                        style = Paint.Style.STROKE
                                        strokeCap = Paint.Cap.ROUND
                                        strokeJoin = Paint.Join.ROUND
                                        isAntiAlias = true
                                    }

                                    pageAnnotations.forEach { annotation ->
                                        val red = (annotation.color.red * 255).toInt()
                                        val green = (annotation.color.green * 255).toInt()
                                        val blue = (annotation.color.blue * 255).toInt()

                                        if (annotation.tool == AnnotationTool.HIGHLIGHTER) {
                                            // Keep text readable under highlights in exported PDF.
                                            paint.color = android.graphics.Color.argb(90, red, green, blue)
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                                paint.blendMode = BlendMode.MULTIPLY
                                            } else {
                                                @Suppress("DEPRECATION")
                                                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                                            }
                                        } else {
                                            // Marker/underline should remain solid in exported output.
                                            paint.color = android.graphics.Color.argb(255, red, green, blue)
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                                paint.blendMode = null
                                            } else {
                                                @Suppress("DEPRECATION")
                                                paint.xfermode = null
                                            }
                                        }
                                        // Denormalize stroke width for bitmap
                                        paint.strokeWidth = annotation.strokeWidth * workingBitmap.width

                                        if (annotation.points.isNotEmpty()) {
                                            val path = android.graphics.Path()
                                            val first = annotation.points.first()
                                            path.moveTo(first.x * workingBitmap.width, first.y * workingBitmap.height)

                                            for (i in 1 until annotation.points.size) {
                                                val p = annotation.points[i]
                                                path.lineTo(p.x * workingBitmap.width, p.y * workingBitmap.height)
                                            }
                                            // Safe draw with try-catch protection
                                            safeCanvasDraw("saveAnnotations-page$pageIndex") {
                                                canvas.drawPath(path, paint)
                                            }
                                        }
                                    }

                                    // Scale back to PDF points (72 DPI)
                                    // Render scale is 1.5f (approx 108 DPI)
                                    // PDF point is 1/72 inch.
                                    // 108 / 72 = 1.5.
                                    val scaleFactor = RENDER_SCALE
                                    val pageWidth = workingBitmap.width / scaleFactor
                                    val pageHeight = workingBitmap.height / scaleFactor

                                    val page = PDPage(PDRectangle(pageWidth, pageHeight))
                                    destDoc.addPage(page)

                                    val pdImage = LosslessFactory.createFromImage(destDoc, workingBitmap)
                                    PDPageContentStream(destDoc, page).use { cs ->
                                        cs.drawImage(pdImage, 0f, 0f, pageWidth, pageHeight)
                                    }
                                } finally {
                                    // Ensure bitmap is recycled immediately to free native memory
                                    workingBitmap.recycle()
                                }
                            }
                        }

                        // Update Progress
                        val progress = (pageIndex + 1).toFloat() / totalPages
                        _saveState.value = SaveState.Saving(progress)
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
                extractedTextCache.clear()
                bitmapCache.evictAll()

                // Clean up temp file
                try {
                    if (tempFile?.exists() == true) {
                        tempFile?.delete()
                    }
                } catch (e: Exception) {
                    Log.e("PdfViewerVM", "Error deleting temp file", e)
                }
                tempFile = null
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
