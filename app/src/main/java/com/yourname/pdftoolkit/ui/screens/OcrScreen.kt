package com.yourname.pdftoolkit.ui.screens
import com.yourname.pdftoolkit.util.safeLaunch

import androidx.compose.ui.res.stringResource
import com.yourname.pdftoolkit.R

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yourname.pdftoolkit.domain.operations.PdfOcrProcessor
import com.yourname.pdftoolkit.util.FileOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for OCR Screen.
 */
class OcrViewModel : ViewModel() {
    private val _state = MutableStateFlow(OcrUiState())
    val state: StateFlow<OcrUiState> = _state.asStateFlow()
    
    private var ocrProcessor: PdfOcrProcessor? = null
    
    fun setSourcePdf(uri: Uri, name: String) {
        _state.value = _state.value.copy(sourceUri = uri, sourceName = name)
    }
    
    fun setMode(mode: OcrMode) {
        _state.value = _state.value.copy(mode = mode)
    }

    fun setViewFormat(format: OcrViewFormat) {
        _state.value = _state.value.copy(viewFormat = format)
    }
    
    fun extractText(context: android.content.Context) {
        if (_state.value.isProcessing) return
        val sourceUri = _state.value.sourceUri ?: return
        
        viewModelScope.launch {
            _state.value = _state.value.copy(isProcessing = true, progress = 0, error = null)
            
            ocrProcessor = PdfOcrProcessor(context)
            
            val result = ocrProcessor?.extractTextWithOcr(
                pdfUri = sourceUri,
                progressCallback = { progress ->
                    _state.value = _state.value.copy(progress = progress)
                }
            )
            
            _state.value = _state.value.copy(
                isProcessing = false,
                isComplete = result?.success == true,
                error = result?.errorMessage,
                extractedText = result?.fullText ?: "",
                markdownText = result?.markdownText ?: result?.fullText ?: "",
                pagesProcessed = result?.pages?.size ?: 0
            )
        }
    }
    
    fun makeSearchable(
        context: android.content.Context,
        outputUri: Uri
    ) {
        if (_state.value.isProcessing) return
        val sourceUri = _state.value.sourceUri ?: return
        
        viewModelScope.launch {
            _state.value = _state.value.copy(isProcessing = true, progress = 0, error = null)
            
            ocrProcessor = PdfOcrProcessor(context)
            
            val result = ocrProcessor?.makeSearchable(
                inputUri = sourceUri,
                outputUri = outputUri,
                progressCallback = { progress ->
                    _state.value = _state.value.copy(progress = progress)
                }
            )
            
            if (result?.success == true) {
                com.yourname.pdftoolkit.data.SafUriManager.addRecentFile(context, outputUri)
                
                // Record in history
                com.yourname.pdftoolkit.data.HistoryManager.recordSuccess(
                    context = context,
                    operationType = com.yourname.pdftoolkit.data.OperationType.OCR,
                    inputFileName = sourceUri.lastPathSegment ?: "PDF",
                    outputFileUri = outputUri,
                    outputFileName = "searchable.pdf",
                    details = "Made PDF searchable with ${result.pagesProcessed} pages processed"
                )
            } else if (result?.success == false) {
                // Record failure in history
                com.yourname.pdftoolkit.data.HistoryManager.recordFailure(
                    context = context,
                    operationType = com.yourname.pdftoolkit.data.OperationType.OCR,
                    inputFileName = sourceUri.lastPathSegment ?: "PDF",
                    errorMessage = result.errorMessage
                )
            }
            
            _state.value = _state.value.copy(
                isProcessing = false,
                isComplete = result?.success == true,
                error = result?.errorMessage,
                pagesProcessed = result?.pagesProcessed ?: 0,
                resultUri = if (result?.success == true) outputUri else null
            )
        }
    }
    
    fun reset() {
        ocrProcessor?.close()
        ocrProcessor = null
        _state.value = OcrUiState()
    }
    
    override fun onCleared() {
        super.onCleared()
        ocrProcessor?.close()
    }
}

enum class OcrMode {
    EXTRACT_TEXT,
    MAKE_SEARCHABLE
}

enum class OcrViewFormat {
    MARKDOWN,
    RAW_TEXT
}

data class OcrUiState(
    val sourceUri: Uri? = null,
    val sourceName: String = "",
    val mode: OcrMode = OcrMode.EXTRACT_TEXT,
    val viewFormat: OcrViewFormat = OcrViewFormat.MARKDOWN,
    val isProcessing: Boolean = false,
    val progress: Int = 0,
    val isComplete: Boolean = false,
    val error: String? = null,
    val extractedText: String = "",
    val markdownText: String = "",
    val pagesProcessed: Int = 0,
    val resultUri: Uri? = null
)

/**
 * OCR Screen - Extract text from scanned PDFs using ML Kit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrScreen(
    onNavigateBack: () -> Unit,
    viewModel: OcrViewModel = viewModel()
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showFullScreenReader by remember { mutableStateOf(false) }
    
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val name = context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                cursor.getString(nameIndex)
            } ?: "Selected PDF"
            viewModel.setSourcePdf(it, name)
        }
    }
    
    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let { viewModel.makeSearchable(context, it) }
    }
    
    val saveTextLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let { outputUri ->
            context.contentResolver.openOutputStream(outputUri)?.use { stream ->
                stream.write(state.extractedText.toByteArray())
            }
        }
    }

    val saveMarkdownLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        uri?.let { outputUri ->
            context.contentResolver.openOutputStream(outputUri)?.use { stream ->
                stream.write(state.markdownText.toByteArray())
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ocr_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Info Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Offline OCR recognizes text in scanned PDFs and images. Works completely offline on your device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            
            // Source PDF Selection
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Source PDF",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    if (state.sourceUri != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.PictureAsPdf,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = state.sourceName,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            IconButton(onClick = { pdfPickerLauncher.safeLaunch(arrayOf("application/pdf"), context) }) {
                                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_change))
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = { pdfPickerLauncher.safeLaunch(arrayOf("application/pdf"), context) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.FileOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.action_select_pdf))
                        }
                    }
                }
            }
            
            // Mode Selection
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "OCR Mode",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    // Extract Text Mode
                    Card(
                        onClick = { viewModel.setMode(OcrMode.EXTRACT_TEXT) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (state.mode == OcrMode.EXTRACT_TEXT)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = state.mode == OcrMode.EXTRACT_TEXT,
                                onClick = { viewModel.setMode(OcrMode.EXTRACT_TEXT) }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    "Extract Text",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "Extract all text content to a text file",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    
                    // Make Searchable Mode
                    Card(
                        onClick = { viewModel.setMode(OcrMode.MAKE_SEARCHABLE) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (state.mode == OcrMode.MAKE_SEARCHABLE)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = state.mode == OcrMode.MAKE_SEARCHABLE,
                                onClick = { viewModel.setMode(OcrMode.MAKE_SEARCHABLE) }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    "Make Searchable",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "Add invisible text layer for search/copy",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            
            // Processing State
            AnimatedVisibility(visible = state.isProcessing) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.ocr_progress, state.progress))
                        Text(
                            "This may take a while for large documents",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        LinearProgressIndicator(
                            progress = state.progress / 100f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            
            // Extracted Text Result
            AnimatedVisibility(
                visible = state.isComplete && 
                         !state.isProcessing && 
                         state.mode == OcrMode.EXTRACT_TEXT &&
                         state.extractedText.isNotEmpty()
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Disclaimer Banner
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Layout Disclaimer: Markdown formatting and structural positioning are approximated from document spatial analysis. Layout may vary from original PDF.",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Extracted Text",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { showFullScreenReader = true }) {
                                    Icon(Icons.Default.Fullscreen, contentDescription = stringResource(R.string.ocr_reader_title))
                                }
                                OutlinedButton(
                                    onClick = {
                                        val textToCopy = if (state.viewFormat == OcrViewFormat.MARKDOWN) state.markdownText else state.extractedText
                                        clipboardManager.setText(AnnotatedString(textToCopy))
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.action_copy), style = MaterialTheme.typography.labelMedium)
                                }
                                FilledTonalButton(
                                    onClick = {
                                        if (state.viewFormat == OcrViewFormat.MARKDOWN) {
                                            saveMarkdownLauncher.safeLaunch("extracted_markdown_${System.currentTimeMillis()}.md", context)
                                        } else {
                                            saveTextLauncher.safeLaunch("extracted_text_${System.currentTimeMillis()}.txt", context)
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (state.viewFormat == OcrViewFormat.MARKDOWN) "Save .md" else "Save .txt", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }

                        // Format Selection Chips
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = state.viewFormat == OcrViewFormat.MARKDOWN,
                                onClick = { viewModel.setViewFormat(OcrViewFormat.MARKDOWN) },
                                label = { Text(stringResource(R.string.ocr_markdown_view)) },
                                leadingIcon = if (state.viewFormat == OcrViewFormat.MARKDOWN) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null
                            )
                            FilterChip(
                                selected = state.viewFormat == OcrViewFormat.RAW_TEXT,
                                onClick = { viewModel.setViewFormat(OcrViewFormat.RAW_TEXT) },
                                label = { Text(stringResource(R.string.ocr_raw_text)) },
                                leadingIcon = if (state.viewFormat == OcrViewFormat.RAW_TEXT) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null
                            )
                        }
                        
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            val displayText = if (state.viewFormat == OcrViewFormat.MARKDOWN) state.markdownText else state.extractedText
                            
                            Column(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                if (state.viewFormat == OcrViewFormat.MARKDOWN) {
                                    displayText.split("\n").forEach { line ->
                                        val trimmed = line.trim()
                                        when {
                                            trimmed.startsWith("# ") -> {
                                                Text(
                                                    text = trimmed.removePrefix("# ").trim(),
                                                    style = MaterialTheme.typography.titleLarge,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(vertical = 4.dp)
                                                )
                                            }
                                            trimmed.startsWith("## ") -> {
                                                Text(
                                                    text = trimmed.removePrefix("## ").trim(),
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.secondary,
                                                    modifier = Modifier.padding(vertical = 2.dp)
                                                )
                                            }
                                            trimmed.startsWith("### Page ") -> {
                                                Text(
                                                    text = trimmed.removePrefix("### ").trim(),
                                                    style = MaterialTheme.typography.labelLarge,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.tertiary,
                                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                                )
                                            }
                                            trimmed == "---" -> {
                                                Divider(modifier = Modifier.padding(vertical = 8.dp))
                                            }
                                            trimmed.startsWith("- ") -> {
                                                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                                                    Text("• ", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                                    Text(
                                                        text = trimmed.removePrefix("- ").trim(),
                                                        style = MaterialTheme.typography.bodyMedium
                                                    )
                                                }
                                            }
                                            else -> {
                                                if (trimmed.isNotEmpty()) {
                                                    Text(
                                                        text = line,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        modifier = Modifier.padding(vertical = 2.dp)
                                                    )
                                                } else {
                                                    Spacer(modifier = Modifier.height(6.dp))
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Text(
                                        text = displayText,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                        
                        Text(
                            text = "${state.extractedText.length} characters extracted from ${state.pagesProcessed} pages",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Success State (Make Searchable)
            AnimatedVisibility(
                visible = state.isComplete && 
                         !state.isProcessing && 
                         state.mode == OcrMode.MAKE_SEARCHABLE
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "PDF Made Searchable!",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "${state.pagesProcessed} pages processed",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        state.resultUri?.let { uri ->
                            FilledTonalButton(
                                onClick = { scope.launch(Dispatchers.IO) { FileOpener.openPdf(context, uri) } }
                            ) {
                                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.action_open))
                            }
                        }
                    }
                }
            }
            
            // Error State
            state.error?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Action Button
            Button(
                onClick = {
                    when (state.mode) {
                        OcrMode.EXTRACT_TEXT -> viewModel.extractText(context)
                        OcrMode.MAKE_SEARCHABLE -> {
                            val fileName = "searchable_${System.currentTimeMillis()}.pdf"
                            saveDocumentLauncher.safeLaunch(fileName, context)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.sourceUri != null && !state.isProcessing
            ) {
                Icon(
                    if (state.mode == OcrMode.EXTRACT_TEXT) Icons.Default.TextFields else Icons.Default.Search,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (state.mode == OcrMode.EXTRACT_TEXT) "Extract Text" else "Make Searchable"
                )
            }
            
            // Reset Button
            if (state.isComplete) {
                OutlinedButton(
                    onClick = { viewModel.reset() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.ocr_process_another))
                }
            }
        }
    }

    // Full Screen Document Reader Dialog
    if (showFullScreenReader) {
        Dialog(
            onDismissRequest = { showFullScreenReader = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.ocr_reader_title)) },
                            navigationIcon = {
                                IconButton(onClick = { showFullScreenReader = false }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                                }
                            },
                            actions = {
                                IconButton(onClick = {
                                    val textToCopy = if (state.viewFormat == OcrViewFormat.MARKDOWN) state.markdownText else state.extractedText
                                    clipboardManager.setText(AnnotatedString(textToCopy))
                                }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.action_copy))
                                }
                                IconButton(onClick = {
                                    if (state.viewFormat == OcrViewFormat.MARKDOWN) {
                                        saveMarkdownLauncher.safeLaunch("extracted_markdown_${System.currentTimeMillis()}.md", context)
                                    } else {
                                        saveTextLauncher.safeLaunch("extracted_text_${System.currentTimeMillis()}.txt", context)
                                    }
                                }) {
                                    Icon(Icons.Default.Save, contentDescription = stringResource(R.string.pdf_save))
                                }
                            }
                        )
                    }
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Format Chips
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = state.viewFormat == OcrViewFormat.MARKDOWN,
                                onClick = { viewModel.setViewFormat(OcrViewFormat.MARKDOWN) },
                                label = { Text(stringResource(R.string.ocr_markdown_view)) },
                                leadingIcon = if (state.viewFormat == OcrViewFormat.MARKDOWN) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null
                            )
                            FilterChip(
                                selected = state.viewFormat == OcrViewFormat.RAW_TEXT,
                                onClick = { viewModel.setViewFormat(OcrViewFormat.RAW_TEXT) },
                                label = { Text(stringResource(R.string.ocr_raw_text)) },
                                leadingIcon = if (state.viewFormat == OcrViewFormat.RAW_TEXT) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null
                            )
                        }

                        // Disclaimer Banner
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Layout Disclaimer: Markdown formatting and structural positioning are approximated from document spatial analysis. Layout may vary from original PDF.",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }

                        // Full Screen Text Reader Area
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            val displayText = if (state.viewFormat == OcrViewFormat.MARKDOWN) state.markdownText else state.extractedText

                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                if (state.viewFormat == OcrViewFormat.MARKDOWN) {
                                    displayText.split("\n").forEach { line ->
                                        val trimmed = line.trim()
                                        when {
                                            trimmed.startsWith("# ") -> {
                                                Text(
                                                    text = trimmed.removePrefix("# ").trim(),
                                                    style = MaterialTheme.typography.headlineMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(vertical = 6.dp)
                                                )
                                            }
                                            trimmed.startsWith("## ") -> {
                                                Text(
                                                    text = trimmed.removePrefix("## ").trim(),
                                                    style = MaterialTheme.typography.titleLarge,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.secondary,
                                                    modifier = Modifier.padding(vertical = 4.dp)
                                                )
                                            }
                                            trimmed.startsWith("### Page ") -> {
                                                Text(
                                                    text = trimmed.removePrefix("### ").trim(),
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.tertiary,
                                                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
                                                )
                                            }
                                            trimmed == "---" -> {
                                                Divider(modifier = Modifier.padding(vertical = 12.dp))
                                            }
                                            trimmed.startsWith("- ") -> {
                                                Row(modifier = Modifier.padding(vertical = 3.dp)) {
                                                    Text("• ", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                                    Text(
                                                        text = trimmed.removePrefix("- ").trim(),
                                                        style = MaterialTheme.typography.bodyLarge
                                                    )
                                                }
                                            }
                                            else -> {
                                                if (trimmed.isNotEmpty()) {
                                                    Text(
                                                        text = line,
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        modifier = Modifier.padding(vertical = 3.dp)
                                                    )
                                                } else {
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Text(
                                        text = displayText,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
