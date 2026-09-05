package com.yourname.pdftoolkit.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yourname.pdftoolkit.R
import com.yourname.pdftoolkit.data.FileManager
import com.yourname.pdftoolkit.data.HistoryManager
import com.yourname.pdftoolkit.data.OperationType
import com.yourname.pdftoolkit.data.SafUriManager
import com.yourname.pdftoolkit.domain.operations.OfficeConverter
import com.yourname.pdftoolkit.util.FileOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DocToPdfViewModel : ViewModel() {
    private val converter = OfficeConverter()

    private val _isConverting = MutableStateFlow(false)
    val isConverting: StateFlow<Boolean> = _isConverting.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _resultFile = MutableStateFlow<File?>(null)
    val resultFile: StateFlow<File?> = _resultFile.asStateFlow()

    fun convert(context: android.content.Context, source: File) {
        if (_isConverting.value) return
        viewModelScope.launch {
            _isConverting.value = true
            _error.value = null
            _resultFile.value = null
            try {
                val out = File(
                    context.cacheDir,
                    source.nameWithoutExtension + "_${System.currentTimeMillis()}.pdf"
                )
                withContext(Dispatchers.IO) {
                    converter.convertDocxToPdf(source, out, context)
                }
                _resultFile.value = out
            } catch (e: Exception) {
                _error.value = e.localizedMessage ?: "Conversion failed"
            }
            _isConverting.value = false
        }
    }

    fun reset() {
        _resultFile.value = null
        _error.value = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocToPdfScreen(
    onNavigateBack: () -> Unit,
    onOpenPdfViewer: (Uri, String) -> Unit,
    viewModel: DocToPdfViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isConverting by viewModel.isConverting.collectAsState()
    val error by viewModel.error.collectAsState()
    val resultFile by viewModel.resultFile.collectAsState()

    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceFile by remember { mutableStateOf<File?>(null) }
    var sourceName by remember { mutableStateOf("") }

    val pickDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    val info = FileManager.getFileInfo(context, it)
                    val name = info?.name ?: "document.docx"
                    if (!name.endsWith(".docx", true) && !name.endsWith(".doc", true)) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.doc_unsupported),
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                        return@launch
                    }
                    val dest = File(context.cacheDir, "doctopdf_${System.currentTimeMillis()}_$name")
                    context.contentResolver.openInputStream(it)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    withContext(Dispatchers.Main) {
                        sourceUri = it
                        sourceFile = dest
                        sourceName = name
                        viewModel.reset()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    val saveCopyLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val out = resultFile
        if (uri != null && out != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        out.inputStream().use { input -> input.copyTo(output) }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // First-page preview of the converted PDF
    var previewBitmap by remember(resultFile) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(resultFile) {
        val file = resultFile
        if (file != null) {
            withContext(Dispatchers.IO) {
                try {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)?.use { pfd ->
                        PdfRenderer(pfd).use { renderer ->
                            if (renderer.pageCount > 0) {
                                renderer.openPage(0).use { page ->
                                    val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    previewBitmap = bmp
                                }
                            }
                        }
                    }
                    HistoryManager.recordSuccess(
                        context = context,
                        operationType = OperationType.DOC_TO_PDF,
                        inputFileName = sourceName,
                        outputFileName = file.name,
                        details = "Converted $sourceName to PDF"
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } else {
            previewBitmap = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tool_doc_to_pdf)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = sourceName.ifBlank { stringResource(R.string.doc_pick_file) },
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (sourceFile != null) FileManager.formatFileSize(sourceFile!!.length())
                            else stringResource(R.string.doc_no_file),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (sourceFile != null) {
                        IconButton(onClick = {
                            sourceUri = null
                            sourceFile = null
                            sourceName = ""
                            viewModel.reset()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_remove))
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = {
                    pickDocLauncher.launch(
                        arrayOf(
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/msword"
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.doc_pick_file))
            }

            Button(
                onClick = { sourceFile?.let { viewModel.convert(context, it) } },
                enabled = sourceFile != null && !isConverting,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isConverting) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.doc_converting))
                } else {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.doc_convert))
                }
            }

            error?.let {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            val result = resultFile
            if (result != null) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        previewBitmap?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)
                                    .clip(RoundedCornerShape(8.dp)).background(androidx.compose.ui.graphics.Color.White)
                            )
                        } ?: CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.doc_convert_done),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        val cached = File(context.cacheDir, "doctopdf_view_${System.currentTimeMillis()}.pdf")
                                        result.inputStream().use { input ->
                                            cached.outputStream().use { output -> input.copyTo(output) }
                                        }
                                        SafUriManager.addRecentFile(context, Uri.fromFile(cached))
                                        withContext(Dispatchers.Main) {
                                            onOpenPdfViewer(Uri.fromFile(cached), result.name)
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.OpenInNew, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.doc_open_pdf))
                            }
                            OutlinedButton(
                                onClick = {
                                    try {
                                        val shareUri = FileProvider.getUriForFile(
                                            context, "${context.packageName}.provider", result
                                        )
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "application/pdf"
                                            putExtra(Intent.EXTRA_STREAM, shareUri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(intent, context.getString(R.string.doc_share)))
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.doc_share))
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = { saveCopyLauncher.launch(sourceName.substringBeforeLast(".") + ".pdf") }
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.doc_save_copy))
                        }
                    }
                }
            }

            if (resultFile == null && !isConverting) {
                // Hint: open existing docs straight into the viewer
                Text(
                    text = stringResource(R.string.desc_view_doc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
