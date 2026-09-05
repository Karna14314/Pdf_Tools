package com.yourname.pdftoolkit.ui.screens.imposition

import android.app.Application
import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.pdftoolkit.domain.imposition.*
import com.yourname.pdftoolkit.util.OutputFolderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ImpositionUiState(
    val fileUri: Uri? = null,
    val fileName: String = "",
    val pageCount: Int = 0,
    val sourceWidthPt: Float = 595.28f,
    val sourceHeightPt: Float = 841.89f,
    val config: ImpositionConfig = ImpositionConfig(),
    val calculatedSheets: List<SheetLayout> = emptyList(),
    val isExporting: Boolean = false,
    val exportProgressMessage: String = "",
    val exportedFile: File? = null,
    val exportedUri: Uri? = null,
    val errorMessage: String? = null
)

class PrintImpositionViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication<Application>().applicationContext

    private val _uiState = MutableStateFlow(ImpositionUiState())
    val uiState: StateFlow<ImpositionUiState> = _uiState.asStateFlow()

    fun setSelectedFile(uri: Uri, name: String) {
        viewModelScope.launch {
            try {
                var count = 0
                var width = 595.28f
                var height = 841.89f

                withContext(Dispatchers.IO) {
                    val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    pfd?.use { descriptor ->
                        val renderer = PdfRenderer(descriptor)
                        count = renderer.pageCount
                        if (count > 0) {
                            val firstPage = renderer.openPage(0)
                            width = firstPage.width.toFloat()
                            height = firstPage.height.toFloat()
                            firstPage.close()
                        }
                        renderer.close()
                    }
                }

                _uiState.update { state ->
                    val updatedState = state.copy(
                        fileUri = uri,
                        fileName = name,
                        pageCount = count,
                        sourceWidthPt = width,
                        sourceHeightPt = height,
                        errorMessage = null
                    )
                    val sheets = ImpositionEngine.calculateLayout(
                        pageCount = count,
                        sourceWidthPt = width,
                        sourceHeightPt = height,
                        config = updatedState.config
                    )
                    updatedState.copy(calculatedSheets = sheets)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to load PDF metadata: ${e.message}") }
            }
        }
    }

    fun updateConfig(update: (ImpositionConfig) -> ImpositionConfig) {
        _uiState.update { state ->
            val newConfig = update(state.config)
            val sheets = ImpositionEngine.calculateLayout(
                pageCount = state.pageCount,
                sourceWidthPt = state.sourceWidthPt,
                sourceHeightPt = state.sourceHeightPt,
                config = newConfig
            )
            state.copy(config = newConfig, calculatedSheets = sheets)
        }
    }

    fun exportImposedPdf() {
        val state = _uiState.value
        val uri = state.fileUri ?: return
        if (state.calculatedSheets.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, exportProgressMessage = "Generating imposed PDF sheets...", errorMessage = null) }
            try {
                val outName = "Imposed_${state.fileName.ifBlank { "document.pdf" }}"
                val finalName = if (outName.endsWith(".pdf")) outName else "$outName.pdf"
                val outputFileResult = OutputFolderManager.createOutputFile(context, finalName)
                    ?: throw IllegalStateException("Could not create output file in PDF Toolkit folder")

                val tempOut = File(context.cacheDir, "imposed_temp_${System.currentTimeMillis()}.pdf")
                val exported = ImpositionPdfExporter.exportImposedPdf(
                    context = context,
                    inputUri = uri,
                    sheetLayouts = state.calculatedSheets,
                    outputFile = tempOut
                )

                // Copy to public folder
                tempOut.inputStream().use { input ->
                    outputFileResult.file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                tempOut.delete()

                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportedFile = outputFileResult.file,
                        exportedUri = outputFileResult.contentUri,
                        exportProgressMessage = "Export complete!"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        errorMessage = "Export failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun clearExportResult() {
        _uiState.update { it.copy(exportedFile = null, errorMessage = null) }
    }
}
