package com.yourname.pdftoolkit.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import java.io.File
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.luminance
import androidx.compose.material.icons.filled.Info
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import android.graphics.BitmapFactory
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.viewinterop.AndroidView

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.FileProvider
import com.yourname.pdftoolkit.R
import androidx.compose.ui.res.stringResource
import com.yourname.pdftoolkit.data.HistoryManager
import com.yourname.pdftoolkit.data.OperationType
import com.yourname.pdftoolkit.data.SafUriManager
import com.yourname.pdftoolkit.domain.operations.OfficeConverter

/**
 * Reflowable Word Document (DOCX) mobile viewer and interactive editor engine.
 * Renders paragraphs as rich e-book typography layouts or editable fields.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocxViewerScreen(
    uriString: String,
    displayName: String,
    onNavigateBack: () -> Unit,
    viewModel: DocxViewerViewModel = viewModel(),
    onOpenPdfViewer: (Uri, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.loadState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val currentMatchIndex by viewModel.currentMatchIndex.collectAsState()

    var isPrintLayout by remember { mutableStateOf(true) }
    var searchExpanded by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var isPrinting by remember { mutableStateOf(false) }
    var exportedPdfUri by remember { mutableStateOf<Uri?>(null) }
    var exportedPdfName by remember { mutableStateOf("") }
    var showExportDoneDialog by remember { mutableStateOf(false) }
    var cachedFile by remember { mutableStateOf<File?>(null) }
    var historyRecorded by remember { mutableStateOf(false) }
    val lazyListState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val officeConverter = remember { OfficeConverter() }

    // Copy SAF uri to cache, then load via POI file path
    LaunchedEffect(uriString) {
        historyRecorded = false
        cachedFile = null
        withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(uriString)
                val ext = displayName.substringAfterLast('.', "docx")
                val dest = File(context.cacheDir, "doc_view_${System.currentTimeMillis()}.$ext")
                val stream = context.contentResolver.openInputStream(uri)
                if (stream == null) {
                    withContext(Dispatchers.Main) {
                        viewModel.setLoadError("Unable to open document.")
                    }
                    return@withContext
                }
                stream.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                if (!dest.exists() || dest.length() == 0L) {
                    withContext(Dispatchers.Main) {
                        viewModel.setLoadError("Unable to open document.")
                    }
                    return@withContext
                }
                cachedFile = dest
                withContext(Dispatchers.Main) {
                    viewModel.loadWordFile(context, dest.absolutePath)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    val msg = if (e is SecurityException) {
                        context.getString(R.string.doc_access_expired)
                    } else {
                        e.localizedMessage ?: "Unable to open document."
                    }
                    viewModel.setLoadError(msg)
                }
            }
        }
    }

    // Record in Files history only (viewer opens are not tool operations,
    // so they stay out of the operation-history sidebar)
    LaunchedEffect(state) {
        val current = state
        if (current is DocxLoadState.Success && !historyRecorded) {
            historyRecorded = true
            withContext(Dispatchers.IO) {
                try {
                    Uri.parse(uriString)?.let { SafUriManager.addRecentFile(context, it) }
                } catch (e: Exception) { }
            }
        }
    }

    // Jump to search match (native legacy list only; WebView handles its own JS nav)
    LaunchedEffect(currentMatchIndex) {
        val current = state
        if (current is DocxLoadState.Success && current.docxBase64 == null &&
            currentMatchIndex >= 0 && currentMatchIndex < searchResults.size
        ) {
            lazyListState.animateScrollToItem(searchResults[currentMatchIndex].pageIndex)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.saveStatus.collect { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    fun shareCachedFile() {
        val file = cachedFile ?: return
        try {
            val shareUri = FileProvider.getUriForFile(
                context, "${context.packageName}.provider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.doc_share)))
        } catch (e: Exception) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.doc_share_failed)) }
        }
    }

    fun openInExternalApp() {
        val file = cachedFile ?: return
        try {
            val openUri = FileProvider.getUriForFile(
                context, "${context.packageName}.provider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(openUri, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.doc_open_in)))
        } catch (e: Exception) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.doc_no_app_found)) }
        }
    }

    fun printDocument() {
        val file = cachedFile ?: return
        if (file.name.endsWith(".doc", ignoreCase = true)) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.doc_print_unsupported)) }
            return
        }
        isPrinting = true
        scope.launch(Dispatchers.IO) {
            try {
                val tmpPdf = File(context.cacheDir, "print_doc_${System.currentTimeMillis()}.pdf")
                officeConverter.convertDocxToPdf(file, tmpPdf, context)
                withContext(Dispatchers.Main) {
                    isPrinting = false
                    try {
                        val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                        printManager.print(
                            file.nameWithoutExtension,
                            DocxPrintDocumentAdapter(context, tmpPdf),
                            PrintAttributes.Builder().build()
                        )
                    } catch (e: Exception) {
                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.doc_print_failed)) }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isPrinting = false
                    scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.doc_print_failed)) }
                }
            }
        }
    }

    val exportPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf"),
        onResult = { uri ->
            uri?.let {
                isExporting = true
                viewModel.exportToPdf(
                    context = context,
                    outputUri = it,
                    onSuccess = {
                        isExporting = false
                        exportedPdfUri = it
                        exportedPdfName = (state as? DocxLoadState.Success)?.fileName
                            ?.substringBeforeLast(".").let { base ->
                                if (base.isNullOrBlank()) "document.pdf" else "$base.pdf"
                            }
                        showExportDoneDialog = true
                        scope.launch {
                            HistoryManager.recordSuccess(
                                context = context,
                                operationType = OperationType.DOC_TO_PDF,
                                inputFileName = (state as? DocxLoadState.Success)?.fileName,
                                outputFileUri = it,
                                details = "Converted to PDF"
                            )
                        }
                    },
                    onFailure = { error ->
                        isExporting = false
                        scope.launch { snackbarHostState.showSnackbar(error) }
                    }
                )
            }
        }
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (searchExpanded) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            searchExpanded = false
                            viewModel.setSearchQuery("")
                        }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
                        }
                        TextField(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = { Text(stringResource(R.string.doc_search_hint)) },
                            modifier = Modifier.weight(1f),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            singleLine = true
                        )
                        if (searchResults.isNotEmpty()) {
                            Text(
                                text = "${currentMatchIndex + 1} of ${searchResults.size}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            IconButton(onClick = { viewModel.prevMatch() }) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.action_previous))
                            }
                            IconButton(onClick = { viewModel.nextMatch() }) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.action_next))
                            }
                        } else if (searchQuery.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.doc_no_matches),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                    }
                }
            } else {
                Column {
                    if (displayName.endsWith(".doc", ignoreCase = true)) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.doc_legacy_readonly),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                    TopAppBar(
                        title = {
                            Text(
                                text = when (val s = state) {
                                    is DocxLoadState.Success -> s.fileName
                                    else -> displayName.ifBlank { stringResource(R.string.doc_viewer) }
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onNavigateBack) {
                                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_back))
                            }
                        },
                        actions = {
                            if (state is DocxLoadState.Success) {
                                var showMenu by remember { mutableStateOf(false) }
                                IconButton(onClick = { searchExpanded = true }) {
                                    Icon(Icons.Default.Search, contentDescription = stringResource(R.string.doc_search))
                                }
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
                                }
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.doc_print)) },
                                        onClick = { showMenu = false; printDocument() },
                                        leadingIcon = { Icon(Icons.Default.Print, contentDescription = null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.doc_share)) },
                                        onClick = { showMenu = false; shareCachedFile() },
                                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.doc_open_in)) },
                                        onClick = { showMenu = false; openInExternalApp() },
                                        leadingIcon = { Icon(Icons.Default.OpenInNew, contentDescription = null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.doc_export_pdf)) },
                                        onClick = {
                                            showMenu = false
                                            val current = state as DocxLoadState.Success
                                            exportPdfLauncher.launch(current.fileName.substringBeforeLast(".") + ".pdf")
                                        },
                                        leadingIcon = { Icon(Icons.Default.PictureAsPdf, contentDescription = null) }
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            titleContentColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }
        },
        bottomBar = {
            if (state is DocxLoadState.Success) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DocViewerActionButton(
                            icon = if (isPrintLayout) Icons.Default.TextSnippet else Icons.Default.PictureAsPdf,
                            title = if (isPrintLayout) stringResource(R.string.doc_reflow) else stringResource(R.string.doc_print_layout)
                        ) { isPrintLayout = !isPrintLayout }
                        DocViewerActionButton(icon = Icons.Default.Search, title = stringResource(R.string.doc_search)) {
                            searchExpanded = true
                        }
                        DocViewerActionButton(icon = Icons.Default.Share, title = stringResource(R.string.doc_share)) {
                            shareCachedFile()
                        }
                        DocViewerActionButton(icon = Icons.Default.PictureAsPdf, title = stringResource(R.string.doc_export_pdf)) {
                            val current = state as DocxLoadState.Success
                            exportPdfLauncher.launch(current.fileName.substringBeforeLast(".") + ".pdf")
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize().padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (val currentState = state) {
                is DocxLoadState.Loading -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.doc_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                        )
                    }
                }
                is DocxLoadState.Success -> {
                    val document = currentState.document
                    if (document.elements.isEmpty() && currentState.docxBase64 == null) {
                        EmptyDocumentState()
                    } else if (currentState.docxBase64 != null) {
                        // High-fidelity WebView DOCX renderer (Print Layout / Reflow)
                        DocxWebView(
                            docxBase64 = currentState.docxBase64,
                            isPrintLayout = isPrintLayout,
                            searchQuery = searchQuery,
                            currentMatchIndex = currentMatchIndex,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // Native read-only stream for legacy .doc
                        SelectionContainer {
                            LazyColumn(
                                state = lazyListState,
                                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 80.dp)
                            ) {
                                itemsIndexed(document.elements) { index, element ->
                                    when (element) {
                                        is DocxBodyElement.Para -> {
                                            val isHighlighted = searchResults.getOrNull(currentMatchIndex)?.pageIndex == index
                                            DocxParagraphItem(
                                                paragraph = element.paragraph,
                                                isHighlighted = isHighlighted,
                                                searchQuery = searchQuery,
                                                isPrintLayout = false
                                            )
                                        }
                                        is DocxBodyElement.Table -> {
                                            DocxTableItem(
                                                table = element,
                                                searchQuery = searchQuery,
                                                isPrintLayout = false
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                is DocxLoadState.Error -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.doc_load_error),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = currentState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    if (showExportDoneDialog) {
        AlertDialog(
            onDismissRequest = { showExportDoneDialog = false },
            icon = {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = { Text(stringResource(R.string.doc_convert_done)) },
            text = { Text(exportedPdfName) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportDoneDialog = false
                        exportedPdfUri?.let { onOpenPdfViewer(it, exportedPdfName) }
                    }
                ) {
                    Text(stringResource(R.string.doc_open_pdf))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDoneDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }

    if (isExporting || isPrinting) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = {
                Text(
                    if (isExporting) stringResource(R.string.doc_exporting)
                    else stringResource(R.string.doc_printing),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Text(
                        stringResource(R.string.doc_converting),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        )
    }
}

@Composable
private fun DocViewerActionButton(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Icon(icon, contentDescription = title, tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}


private fun parseHexColor(hex: String?): Color? {
    if (hex == null) return null
    val cleanHex = hex.trim().replace("#", "")
    return try {
        if (cleanHex.length == 6) {
            Color(android.graphics.Color.parseColor("#$cleanHex"))
        } else if (cleanHex.length == 8) {
            Color(android.graphics.Color.parseColor("#$cleanHex"))
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}

private fun mapFontFamily(name: String?): FontFamily {
    if (name == null) return FontFamily.Default
    val lower = name.lowercase().trim()
    return when {
        lower.contains("times") || lower.contains("georgia") || lower.contains("serif") || lower.contains("cambria") -> FontFamily.Serif
        lower.contains("courier") || lower.contains("consolas") || lower.contains("monospace") || lower.contains("code") -> FontFamily.Monospace
        lower.contains("cursive") || lower.contains("comic") -> FontFamily.Cursive
        else -> FontFamily.SansSerif
    }
}

@Composable
fun DocxTableItem(table: DocxBodyElement.Table, searchQuery: String, isPrintLayout: Boolean = false) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        border = BorderStroke(0.5.dp, if (isPrintLayout) Color.LightGray else MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = if (isPrintLayout) Color(0xFFFAFAFA) else MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            table.rows.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            drawLine(
                                color = Color.LightGray.copy(alpha = 0.4f),
                                start = Offset(0f, size.height),
                                end = Offset(size.width, size.height),
                                strokeWidth = 0.5.dp.toPx()
                            )
                        },
                    verticalAlignment = Alignment.Top
                ) {
                    row.cells.forEachIndexed { cellIdx, cell ->
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .then(
                                    if (cellIdx < row.cells.size - 1)
                                        Modifier.drawBehind {
                                            drawLine(
                                                color = Color.LightGray.copy(alpha = 0.4f),
                                                start = Offset(size.width, 0f),
                                                end = Offset(size.width, size.height),
                                                strokeWidth = 0.5.dp.toPx()
                                            )
                                        }
                                    else Modifier
                                )
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            cell.paragraphs.forEach { para ->
                                DocxParagraphItem(
                                    paragraph = para,
                                    isHighlighted = false,
                                    searchQuery = searchQuery,
                                    isPrintLayout = isPrintLayout
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun buildAnnotatedStringForRuns(
    runs: List<DocxRun>,
    searchQuery: String,
    isPrintLayout: Boolean
): AnnotatedString {
    return buildAnnotatedString {
        runs.forEach { run ->
            if (run.text.isBlank() && run.imageUrl == null) return@forEach
            val cleanText = run.text.replace("\t", "")
            if (cleanText.isEmpty()) return@forEach
            val start = length
            append(cleanText)
            val end = length

            val isLink = run.hyperlinkUrl != null
            val runColor: Color = when {
                isLink -> Color(0xFF1A73E8)
                run.color != null && run.color != "000000" && run.color != "auto" -> {
                    try { Color(android.graphics.Color.parseColor("#${run.color}")) }
                    catch (e: Exception) { if (isPrintLayout) Color(0xFF1F1F1F) else Color.Unspecified }
                }
                isPrintLayout -> Color(0xFF1F1F1F)
                else -> Color.Unspecified
            }

            val spanStyle = SpanStyle(
                fontWeight = if (run.isBold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (run.isItalic) FontStyle.Italic else FontStyle.Normal,
                textDecoration = when {
                    isLink -> TextDecoration.Underline
                    run.isUnderline && run.isStrike -> TextDecoration.Underline + TextDecoration.LineThrough
                    run.isUnderline -> TextDecoration.Underline
                    run.isStrike -> TextDecoration.LineThrough
                    else -> TextDecoration.None
                },
                color = runColor,
                fontSize = if (run.fontSizePt != null && run.fontSizePt > 0) run.fontSizePt.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                fontFamily = mapFontFamily(run.fontFamily)
            )
            addStyle(spanStyle, start, end)

            if (run.hyperlinkUrl != null) {
                addStringAnnotation("URL", run.hyperlinkUrl, start, end)
            }
        }

        if (searchQuery.isNotEmpty()) {
            val fullText = toString()
            var idx = fullText.indexOf(searchQuery, ignoreCase = true)
            while (idx != -1) {
                addStyle(SpanStyle(background = Color.Yellow, color = Color.Black), idx, idx + searchQuery.length)
                idx = fullText.indexOf(searchQuery, idx + searchQuery.length, ignoreCase = true)
            }
        }
    }
}

@Composable
fun DocxParagraphItem(
    paragraph: DocxParagraph,
    isHighlighted: Boolean = false,
    searchQuery: String = "",
    isPrintLayout: Boolean = false
) {
    val context = LocalContext.current

    val textAlign = when (paragraph.alignment) {
        "CENTER" -> TextAlign.Center
        "RIGHT" -> TextAlign.Right
        "JUSTIFY" -> TextAlign.Justify
        else -> TextAlign.Left
    }

    val inkTextColor = if (isPrintLayout) Color(0xFF111827) else MaterialTheme.colorScheme.onSurface

    // Fidelity-first typography scale matching Word / reference viewers
    val baseFontSize = if (isPrintLayout) {
        when (paragraph.headingLevel) {
            1 -> 15.sp
            2 -> 12.5.sp
            3 -> 11.5.sp
            4 -> 10.5.sp
            else -> 10.sp
        }
    } else {
        when (paragraph.headingLevel) {
            1 -> 19.sp
            2 -> 15.5.sp
            3 -> 13.5.sp
            4 -> 12.5.sp
            else -> 11.5.sp
        }
    }

    val resolvedLineHeight = paragraph.exactLineHeightPt?.sp ?: (baseFontSize * paragraph.lineHeightMultiplier)

    val baseStyle = TextStyle(
        fontSize = baseFontSize,
        lineHeight = resolvedLineHeight,
        fontWeight = if (paragraph.isHeading) FontWeight.Bold else FontWeight.Normal,
        color = inkTextColor,
        textAlign = textAlign
    )

    // Accurate paragraph vertical spacing directly from document spacing rules
    val verticalPadding = paragraph.spacingAfterPt.coerceAtMost(6f).dp
    val spacingTop = paragraph.spacingBeforePt.coerceAtMost(6f).dp

    val backgroundColor = if (isHighlighted) Color.Yellow.copy(alpha = 0.3f) else Color.Transparent

    // Check if paragraph contains tab stops / tab-separated runs (e.g. Title on left, Date on right)
    val hasTabs = paragraph.tabStops.isNotEmpty() || paragraph.runs.any { it.isTab || it.text.contains("\t") }
    val tabRunIndex = paragraph.runs.indexOfFirst { it.isTab || it.text.contains("\t") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(top = spacingTop)
    ) {
        if (hasTabs && tabRunIndex != -1) {
            // Split into left and right tab portions
            val leftRuns = mutableListOf<DocxRun>()
            val rightRuns = mutableListOf<DocxRun>()

            paragraph.runs.forEachIndexed { idx, run ->
                if (idx < tabRunIndex) {
                    leftRuns.add(run)
                } else if (idx == tabRunIndex) {
                    val parts = run.text.split("\t", limit = 2)
                    if (parts[0].isNotEmpty()) leftRuns.add(run.copy(text = parts[0]))
                    if (parts.size > 1 && parts[1].isNotEmpty()) rightRuns.add(run.copy(text = parts[1]))
                } else {
                    rightRuns.add(run)
                }
            }

            val leftAnnotated = remember(leftRuns, searchQuery, isPrintLayout) {
                buildAnnotatedStringForRuns(leftRuns, searchQuery, isPrintLayout)
            }
            val rightAnnotated = remember(rightRuns, searchQuery, isPrintLayout) {
                buildAnnotatedStringForRuns(rightRuns, searchQuery, isPrintLayout)
            }

            SelectionContainer {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = paragraph.indentStartPt.dp, bottom = verticalPadding),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = leftAnnotated,
                        style = baseStyle.copy(textAlign = TextAlign.Left),
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = rightAnnotated,
                        style = baseStyle.copy(textAlign = TextAlign.Right),
                        modifier = Modifier.wrapContentWidth()
                    )
                }
            }
        } else {
            val annotatedString = remember(paragraph, searchQuery, isPrintLayout) {
                buildAnnotatedStringForRuns(paragraph.runs, searchQuery, isPrintLayout)
            }

            SelectionContainer {
                var layoutResult by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
                Text(
                    text = annotatedString,
                    style = baseStyle,
                    onTextLayout = { layoutResult = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = paragraph.indentStartPt.dp,
                            end = 0.dp,
                            bottom = verticalPadding
                        )
                        .pointerInput(annotatedString) {
                            detectTapGestures { offset ->
                                layoutResult?.let { layout ->
                                    val position = layout.getOffsetForPosition(offset)
                                    annotatedString.getStringAnnotations("URL", position, position)
                                        .firstOrNull()?.let { annotation ->
                                            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(annotation.item))) }
                                            catch (e: Exception) { }
                                        }
                                }
                            }
                        }
                )
            }
        }

        // Render embedded images with proper sizing using Coil AsyncImage
        paragraph.runs.forEach { run ->
            if (run.imageUrl != null) {
                coil.compose.AsyncImage(
                    model = run.imageUrl,
                    contentDescription = "Embedded Image",
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.FillWidth
                )
            }
        }

        // Comment annotation
        if (!paragraph.comment.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(paragraph.comment, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
fun EmptyDocumentState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = "Empty",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Document Contains No Text",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

private class DocxPrintDocumentAdapter(private val context: Context, private val file: File) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback?,
        extras: Bundle?
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback?.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder("print_output.pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .build()
        callback?.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor?,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback?
    ) {
        var input: java.io.InputStream? = null
        var output: java.io.OutputStream? = null
        try {
            input = java.io.FileInputStream(file)
            output = java.io.FileOutputStream(destination?.fileDescriptor)
            val buffer = ByteArray(1024)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } >= 0) {
                output.write(buffer, 0, bytesRead)
            }
            callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback?.onWriteFailed(e.localizedMessage)
        } finally {
            try { input?.close() } catch(e: Exception) {}
            try { output?.close() } catch(e: Exception) {}
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DocxWebView(
    docxBase64: String,
    isPrintLayout: Boolean,
    searchQuery: String,
    currentMatchIndex: Int,
    modifier: Modifier = Modifier
) {
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isPageLoaded by remember { mutableStateOf(false) }

    fun renderDocument() {
        val wv = webViewInstance ?: return
        // evaluateJavascript has a ~1MB Binder limit; multi-MB image-doc
        // base64 would be silently dropped. Chunk it and reassemble in JS.
        if (docxBase64.length < 500_000) {
            wv.evaluateJavascript("renderDocxBase64('$docxBase64', $isPrintLayout)", null)
            return
        }
        val sb = StringBuilder("clearDocxChunks();")
        var i = 0
        while (i < docxBase64.length) {
            val end = minOf(i + 128_000, docxBase64.length)
            sb.append("appendDocxChunk('").append(docxBase64.substring(i, end)).append("');")
            i = end
        }
        sb.append("renderDocxFromChunks($isPrintLayout)")
        wv.evaluateJavascript(sb.toString(), null)
    }

    // When layout mode changes (Print Layout vs Reflow)
    LaunchedEffect(isPrintLayout, isPageLoaded) {
        if (isPageLoaded && webViewInstance != null) {
            renderDocument()
        }
    }

    // When search query changes
    LaunchedEffect(searchQuery, isPageLoaded) {
        if (isPageLoaded && webViewInstance != null) {
            if (searchQuery.isBlank()) {
                webViewInstance?.evaluateJavascript("clearHighlights()", null)
            } else {
                val escaped = searchQuery
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\n", " ")
                    .replace("\r", "")
                webViewInstance?.evaluateJavascript("searchText('$escaped')", null)
            }
        }
    }

    // When match index changes (next / prev match)
    var lastMatchIndex by remember { mutableIntStateOf(-1) }
    LaunchedEffect(currentMatchIndex, isPageLoaded) {
        if (isPageLoaded && webViewInstance != null && currentMatchIndex != lastMatchIndex) {
            if (currentMatchIndex > lastMatchIndex) {
                webViewInstance?.evaluateJavascript("nextMatch()", null)
            } else if (currentMatchIndex < lastMatchIndex && currentMatchIndex >= 0) {
                webViewInstance?.evaluateJavascript("prevMatch()", null)
            }
            lastMatchIndex = currentMatchIndex
        }
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    // Local docx fetching from our own asset page
                    allowFileAccessFromFileURLs = true
                    allowUniversalAccessFromFileURLs = true
                    builtInZoomControls = true
                    displayZoomControls = false
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    setSupportZoom(true)
                }
                isVerticalScrollBarEnabled = true
                isHorizontalScrollBarEnabled = true
                setInitialScale(0)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        isPageLoaded = true
                        webViewInstance = this@apply
                        renderDocument()
                    }
                }

                loadUrl("file:///android_asset/docx_viewer/viewer.html")
                webViewInstance = this
            }
        },
        update = { wv ->
            webViewInstance = wv
        },
        modifier = modifier
    )
}


