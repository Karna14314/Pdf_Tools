package com.yourname.pdftoolkit.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.pdftoolkit.R
import com.yourname.pdftoolkit.data.FileManager
import com.yourname.pdftoolkit.data.HistoryManager
import com.yourname.pdftoolkit.data.OperationType
import com.yourname.pdftoolkit.domain.operations.CropPlan
import com.yourname.pdftoolkit.domain.operations.NormalizedRect
import com.yourname.pdftoolkit.domain.operations.PdfCropper
import com.yourname.pdftoolkit.domain.operations.PdfOrganizer
import com.yourname.pdftoolkit.domain.operations.SplitOrientation
import com.yourname.pdftoolkit.util.OutputFolderManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CropMode { ALL_PAGES, PER_PAGE, SPLIT }

enum class PerPageVariant { EACH_PAGE, ODD_EVEN }

enum class SplitVariant { LINE, TWO_AREAS }

data class CropUiState(
    val sourceUri: Uri? = null,
    val sourceName: String = "",
    val pageCount: Int = 0,
    val currentPage: Int = 0, // 0-based
    val pageBitmap: Bitmap? = null,
    val isLoadingPage: Boolean = false,

    val mode: CropMode = CropMode.ALL_PAGES,
    val perPageVariant: PerPageVariant = PerPageVariant.EACH_PAGE,
    val splitVariant: SplitVariant = SplitVariant.LINE,

    val uniformRect: NormalizedRect? = null,
    val pageRects: Map<Int, NormalizedRect> = emptyMap(),
    val oddRect: NormalizedRect? = null,
    val evenRect: NormalizedRect? = null,

    val splitOrientation: SplitOrientation = SplitOrientation.VERTICAL,
    val splitPosition: Float = 0.5f,
    val splitAreas: List<NormalizedRect?> = listOf(null, null),
    val activeSplitArea: Int = 0,
    val reverseOrder: Boolean = false,

    val shrinkMediaBox: Boolean = false,

    val isProcessing: Boolean = false,
    val progress: Float = 0f,
    val showResult: Boolean = false,
    val resultSuccess: Boolean = false,
    val resultMessage: String = "",
    val resultUri: Uri? = null,
    val loadError: Boolean = false
) {
    /** True when the current page is an odd page (1, 3, 5…). */
    val isOddPage: Boolean get() = currentPage % 2 == 0

    /** Rectangle edited on the current page in the rectangle-based modes. */
    val editedRect: NormalizedRect?
        get() = when (mode) {
            CropMode.ALL_PAGES -> uniformRect
            CropMode.PER_PAGE -> when (perPageVariant) {
                PerPageVariant.EACH_PAGE -> pageRects[currentPage]
                PerPageVariant.ODD_EVEN -> if (isOddPage) oddRect else evenRect
            }
            CropMode.SPLIT -> null
        }

    /** Number of pages that will be cropped (before splitting). */
    val markedPages: Int
        get() = when (mode) {
            CropMode.ALL_PAGES -> if (uniformRect != null) pageCount else 0
            CropMode.PER_PAGE -> when (perPageVariant) {
                PerPageVariant.EACH_PAGE -> pageRects.keys.count { it < pageCount }
                PerPageVariant.ODD_EVEN ->
                    (if (oddRect != null) (pageCount + 1) / 2 else 0) + (if (evenRect != null) pageCount / 2 else 0)
            }
            CropMode.SPLIT -> pageCount
        }

    val canSave: Boolean
        get() = sourceUri != null && pageCount > 0 && !isProcessing && buildPlan() != null

    /** Converts the editing state into a [CropPlan], or null when there is nothing to do yet. */
    fun buildPlan(): CropPlan? = when (mode) {
        CropMode.ALL_PAGES -> uniformRect?.let { CropPlan.Uniform(it) }
        CropMode.PER_PAGE -> when (perPageVariant) {
            PerPageVariant.EACH_PAGE -> pageRects.takeIf { it.isNotEmpty() }?.let { CropPlan.PerPage(it) }
            PerPageVariant.ODD_EVEN ->
                if (oddRect == null && evenRect == null) null else CropPlan.OddEven(oddRect, evenRect)
        }
        CropMode.SPLIT -> {
            val (first, second) = when (splitVariant) {
                SplitVariant.LINE -> PdfCropper.splitAt(splitOrientation, splitPosition)
                SplitVariant.TWO_AREAS -> splitAreas[0] to splitAreas[1]
            }
            if (first != null && second != null) CropPlan.Split(first, second, reverseOrder) else null
        }
    }
}

/**
 * ViewModel for the Crop Pages screen. Keeps drawn areas across configuration changes.
 */
class CropViewModel : ViewModel() {

    private val _state = MutableStateFlow(CropUiState())
    val state: StateFlow<CropUiState> = _state.asStateFlow()

    private val organizer = PdfOrganizer()
    private val cropper = PdfCropper()
    private var renderJob: Job? = null
    private var renderWidthPx = 1080

    fun setSource(context: Context, uri: Uri, displayWidthPx: Int) {
        val appContext = context.applicationContext
        renderWidthPx = displayWidthPx.coerceIn(480, 1600)
        val name = FileManager.getFileInfo(appContext, uri)?.name ?: uri.lastPathSegment ?: "document.pdf"
        _state.value = CropUiState(sourceUri = uri, sourceName = name, isLoadingPage = true)
        viewModelScope.launch {
            val count = organizer.getPageCount(appContext, uri)
            if (count <= 0) {
                _state.update { it.copy(isLoadingPage = false, loadError = true) }
            } else {
                _state.update { it.copy(pageCount = count) }
                renderPage(appContext, 0)
            }
        }
    }

    fun clearSource() {
        renderJob?.cancel()
        _state.value = CropUiState()
    }

    fun dismissLoadError() {
        _state.value = CropUiState()
    }

    fun goToPage(context: Context, page: Int) {
        val s = _state.value
        if (s.pageCount == 0) return
        val target = page.coerceIn(0, s.pageCount - 1)
        if (target == s.currentPage && s.pageBitmap != null) return
        _state.update { it.copy(currentPage = target) }
        renderPage(context.applicationContext, target)
    }

    private fun renderPage(context: Context, page: Int) {
        val uri = _state.value.sourceUri ?: return
        renderJob?.cancel()
        _state.update { it.copy(isLoadingPage = true) }
        renderJob = viewModelScope.launch {
            val bitmap = organizer.getPageThumbnail(
                context, uri, page,
                width = renderWidthPx,
                height = (renderWidthPx * 1.6f).toInt()
            )
            _state.update {
                if (it.currentPage == page) it.copy(pageBitmap = bitmap, isLoadingPage = false) else it
            }
        }
    }

    fun setMode(mode: CropMode) = _state.update { it.copy(mode = mode) }

    fun setPerPageVariant(variant: PerPageVariant) = _state.update { it.copy(perPageVariant = variant) }

    fun setSplitVariant(variant: SplitVariant) = _state.update { s ->
        // Start the two free areas from the current split line, so the user has something to adjust.
        val areas = if (variant == SplitVariant.TWO_AREAS && s.splitAreas.all { it == null }) {
            PdfCropper.splitAt(s.splitOrientation, s.splitPosition).toList()
        } else {
            s.splitAreas
        }
        s.copy(splitVariant = variant, splitAreas = areas)
    }

    fun setSplitOrientation(orientation: SplitOrientation) =
        _state.update { it.copy(splitOrientation = orientation) }

    fun setSplitPosition(position: Float) = _state.update { it.copy(splitPosition = position) }

    fun setActiveSplitArea(index: Int) = _state.update { it.copy(activeSplitArea = index.coerceIn(0, 1)) }

    fun setSplitArea(index: Int, rect: NormalizedRect?) = _state.update { s ->
        s.copy(splitAreas = s.splitAreas.toMutableList().also { it[index] = rect })
    }

    fun toggleReverseOrder() = _state.update { it.copy(reverseOrder = !it.reverseOrder) }

    fun toggleShrinkMediaBox() = _state.update { it.copy(shrinkMediaBox = !it.shrinkMediaBox) }

    /** Updates the rectangle edited on the current page (rectangle-based modes). */
    fun setEditedRect(rect: NormalizedRect?) = _state.update { s ->
        when (s.mode) {
            CropMode.ALL_PAGES -> s.copy(uniformRect = rect)
            CropMode.PER_PAGE -> when (s.perPageVariant) {
                PerPageVariant.EACH_PAGE -> s.copy(
                    pageRects = if (rect == null) s.pageRects - s.currentPage else s.pageRects + (s.currentPage to rect)
                )
                PerPageVariant.ODD_EVEN -> if (s.isOddPage) s.copy(oddRect = rect) else s.copy(evenRect = rect)
            }
            CropMode.SPLIT -> s
        }
    }

    /** Clears the area(s) edited on the current page. */
    fun resetCurrent() {
        if (_state.value.mode != CropMode.SPLIT) {
            setEditedRect(null)
            return
        }
        _state.update { s ->
            s.copy(
                splitPosition = 0.5f,
                splitAreas = PdfCropper.splitAt(s.splitOrientation, 0.5f).toList(),
                activeSplitArea = 0
            )
        }
    }

    /** Per-page mode: copy the current page's area to every following page. */
    fun copyToFollowingPages() = _state.update { s ->
        val rect = s.pageRects[s.currentPage] ?: return@update s
        val updated = s.pageRects.toMutableMap()
        for (p in s.currentPage + 1 until s.pageCount) updated[p] = rect
        s.copy(pageRects = updated)
    }

    fun dismissResult() = _state.update { it.copy(showResult = false, resultUri = null) }

    fun save(context: Context) {
        val s = _state.value
        val uri = s.sourceUri ?: return
        val plan = s.buildPlan() ?: return
        if (s.isProcessing) return
        val appContext = context.applicationContext
        val isSplit = plan is CropPlan.Split

        viewModelScope.launch {
            _state.update { it.copy(isProcessing = true, progress = 0f) }

            val fileName = FileManager.generateOutputFileName(if (isSplit) "split" else "cropped")
            val output = try {
                OutputFolderManager.createOutputStream(appContext, fileName)
            } catch (e: Exception) {
                null
            }

            if (output == null) {
                finish(appContext, s.sourceName, false, appContext.getString(R.string.crop_error_output), null)
                return@launch
            }

            val result = try {
                output.outputStream.use { stream ->
                    cropper.crop(
                        context = appContext,
                        inputUri = uri,
                        outputStream = stream,
                        plan = plan,
                        shrinkMediaBox = s.shrinkMediaBox,
                        onProgress = { p -> _state.update { it.copy(progress = p) } }
                    )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }

            result.fold(
                onSuccess = { r ->
                    val summary = if (isSplit) {
                        appContext.getString(R.string.crop_split_success, r.inputPages, r.outputPages)
                    } else {
                        appContext.getString(R.string.crop_success, r.croppedPages)
                    }
                    val location = "${OutputFolderManager.getOutputFolderPath(appContext)}/${output.outputFile.fileName}"
                    val message = summary + "\n\n" + appContext.getString(R.string.crop_saved_to, location)
                    finish(appContext, s.sourceName, true, message, output.outputFile.contentUri, summary)
                },
                onFailure = { error ->
                    try { output.outputFile.file.delete() } catch (_: Exception) {}
                    val message = error.message ?: appContext.getString(R.string.crop_failed_title)
                    finish(appContext, s.sourceName, false, message, null)
                }
            )
        }
    }

    private suspend fun finish(
        context: Context,
        inputName: String,
        success: Boolean,
        message: String,
        outputUri: Uri?,
        details: String? = null
    ) {
        if (success && outputUri != null) {
            HistoryManager.recordSuccess(
                context = context,
                operationType = OperationType.CROP,
                inputFileName = inputName,
                outputFileUri = outputUri,
                outputFileName = "cropped_$inputName",
                details = details
            )
        } else if (!success) {
            HistoryManager.recordFailure(
                context = context,
                operationType = OperationType.CROP,
                inputFileName = inputName,
                errorMessage = message
            )
        }
        _state.update {
            it.copy(
                isProcessing = false,
                showResult = true,
                resultSuccess = success,
                resultMessage = message,
                resultUri = outputUri
            )
        }
    }
}
