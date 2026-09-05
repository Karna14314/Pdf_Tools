package com.yourname.pdftoolkit.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yourname.pdftoolkit.R
import com.yourname.pdftoolkit.data.FileManager
import com.yourname.pdftoolkit.data.HistoryManager
import com.yourname.pdftoolkit.data.OperationType
import com.yourname.pdftoolkit.data.PdfFileInfo
import com.yourname.pdftoolkit.domain.operations.PdfRotator
import com.yourname.pdftoolkit.domain.operations.PdfSplitter
import com.yourname.pdftoolkit.ui.components.*
import com.yourname.pdftoolkit.util.FileOpener
import com.yourname.pdftoolkit.util.OutputFolderManager
import com.yourname.pdftoolkit.util.safeLaunch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen for rotating PDF pages.
 * Supports per-page custom rotation and batch operations (+90°, -90°, Reset).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RotateScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pdfRotator = remember { PdfRotator() }
    val pdfSplitter = remember { PdfSplitter() }

    // State
    var selectedFile by remember { mutableStateOf<PdfFileInfo?>(null) }
    var pageCount by remember { mutableStateOf(0) }

    // Per-page rotation mapping: page number (1-indexed) -> added degrees (0, 90, 180, 270)
    var pageRotations by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }

    // Multi-selection state for batch actions
    var selectedPages by remember { mutableStateOf<Set<Int>>(emptySet()) }

    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var showResult by remember { mutableStateOf(false) }
    var resultSuccess by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }
    var resultUri by remember { mutableStateOf<Uri?>(null) }
    var useCustomLocation by remember { mutableStateOf(false) }

    // File picker launcher
    val pickPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val fileInfo = FileManager.getFileInfo(context, uri)
            selectedFile = fileInfo
            selectedPages = emptySet()
            pageRotations = emptyMap()

            scope.launch {
                pageCount = pdfSplitter.getPageCount(context, uri)
            }
        }
    }

    // Helper functions for rotation
    fun rotatePage(pageNum: Int) {
        val current = pageRotations[pageNum] ?: 0
        val next = (current + 90) % 360
        pageRotations = if (next == 0) {
            pageRotations - pageNum
        } else {
            pageRotations + (pageNum to next)
        }
    }

    fun rotateAllBy(deltaDegrees: Int) {
        if (pageCount <= 0) return
        val updated = pageRotations.toMutableMap()
        for (p in 1..pageCount) {
            val current = updated[p] ?: 0
            val newDeg = ((current + deltaDegrees) % 360 + 360) % 360
            if (newDeg == 0) {
                updated.remove(p)
            } else {
                updated[p] = newDeg
            }
        }
        pageRotations = updated
    }

    fun rotateSelectedBy(deltaDegrees: Int) {
        if (selectedPages.isEmpty()) return
        val updated = pageRotations.toMutableMap()
        for (p in selectedPages) {
            val current = updated[p] ?: 0
            val newDeg = ((current + deltaDegrees) % 360 + 360) % 360
            if (newDeg == 0) {
                updated.remove(p)
            } else {
                updated[p] = newDeg
            }
        }
        pageRotations = updated
    }

    fun resetAll() {
        pageRotations = emptyMap()
    }

    fun resetSelected() {
        if (selectedPages.isEmpty()) return
        pageRotations = pageRotations - selectedPages
    }

    // Save file launcher (for custom location)
    val savePdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let { outputUri ->
            val file = selectedFile ?: return@let
            scope.launch {
                isProcessing = true
                progress = 0f

                val outputStream = context.contentResolver.openOutputStream(outputUri)
                if (outputStream != null) {
                    val result = pdfRotator.rotateSpecificPagesWithDegrees(
                        context = context,
                        inputUri = file.uri,
                        outputStream = outputStream,
                        rotations = pageRotations,
                        onProgress = { progress = it }
                    )

                    outputStream.close()

                    result.fold(
                        onSuccess = { count ->
                            resultSuccess = true
                            resultMessage = if (count > 0) {
                                "Successfully applied rotation to $count pages"
                            } else {
                                "Saved PDF without changes"
                            }
                            resultUri = outputUri
                        },
                        onFailure = { error ->
                            resultSuccess = false
                            resultMessage = error.message ?: "Rotation failed"
                        }
                    )
                } else {
                    resultSuccess = false
                    resultMessage = "Cannot create output file"
                }

                isProcessing = false
                showResult = true
            }
        }
    }

    // Function to rotate with default location
    fun rotateWithDefaultLocation() {
        scope.launch {
            isProcessing = true
            progress = 0f
            val originalFile = selectedFile!!

            val result = withContext(Dispatchers.IO) {
                try {
                    val fileName = FileManager.generateOutputFileName("rotated")
                    val outputResult = OutputFolderManager.createOutputStream(context, fileName)

                    if (outputResult != null) {
                        val file = selectedFile!!
                        val rotateResult = pdfRotator.rotateSpecificPagesWithDegrees(
                            context = context,
                            inputUri = file.uri,
                            outputStream = outputResult.outputStream,
                            rotations = pageRotations,
                            onProgress = { progress = it }
                        )

                        outputResult.outputStream.close()

                        rotateResult.fold(
                            onSuccess = { count ->
                                val msg = if (count > 0) {
                                    "Successfully applied rotation to $count pages"
                                } else {
                                    "Saved PDF without changes"
                                }
                                Triple(
                                    true,
                                    "$msg\n\nSaved to: ${OutputFolderManager.getOutputFolderPath(context)}/${outputResult.outputFile.fileName}",
                                    outputResult.outputFile.contentUri
                                )
                            },
                            onFailure = { error ->
                                outputResult.outputFile.file.delete()
                                Triple(false, error.message ?: "Rotation failed", null)
                            }
                        )
                    } else {
                        Triple(false, "Cannot create output file", null)
                    }
                } catch (e: Exception) {
                    Triple(false, e.message ?: "Rotation failed", null)
                }
            }

            resultSuccess = result.first
            resultMessage = result.second
            resultUri = result.third

            // Record in history
            if (resultSuccess && result.third != null) {
                HistoryManager.recordSuccess(
                    context = context,
                    operationType = OperationType.ROTATE,
                    inputFileName = originalFile.name,
                    outputFileUri = result.third,
                    outputFileName = "rotated_${originalFile.name}",
                    details = "Rotated ${pageRotations.size} pages"
                )
            } else if (!resultSuccess) {
                HistoryManager.recordFailure(
                    context = context,
                    operationType = OperationType.ROTATE,
                    inputFileName = originalFile.name,
                    errorMessage = result.second
                )
            }

            isProcessing = false
            showResult = true
        }
    }

    Scaffold(
        topBar = {
            ToolTopBar(
                title = stringResource(R.string.tool_rotate_pages),
                onNavigateBack = onNavigateBack
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Content area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (selectedFile == null) {
                    EmptyState(
                        icon = Icons.Default.RotateRight,
                        title = stringResource(R.string.metadata_no_pdf_selected),
                        subtitle = stringResource(R.string.rotate_no_pdf_subtitle),
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                    ) {
                        Spacer(modifier = Modifier.height(16.dp))

                        // Selected file info
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PictureAsPdf,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = selectedFile!!.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        text = "$pageCount pages",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    )
                                }
                                IconButton(onClick = {
                                    selectedFile = null
                                    selectedPages = emptySet()
                                    pageRotations = emptyMap()
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.action_remove),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Batch Toolbar Controls
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = if (selectedPages.isNotEmpty()) {
                                    "Batch Actions (${selectedPages.size} selected)"
                                } else {
                                    "Batch Actions (All Pages)"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        if (selectedPages.isNotEmpty()) rotateSelectedBy(90) else rotateAllBy(90)
                                    },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.RotateRight,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("+90°", style = MaterialTheme.typography.labelMedium)
                                }

                                OutlinedButton(
                                    onClick = {
                                        if (selectedPages.isNotEmpty()) rotateSelectedBy(-90) else rotateAllBy(-90)
                                    },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.RotateLeft,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("-90°", style = MaterialTheme.typography.labelMedium)
                                }

                                OutlinedButton(
                                    onClick = {
                                        if (selectedPages.isNotEmpty()) resetSelected() else resetAll()
                                    },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Reset", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Header for Thumbnail Grid
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Tap thumbnail to rotate +90°",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (selectedPages.isNotEmpty()) {
                                    TextButton(onClick = { selectedPages = emptySet() }) {
                                        Text(stringResource(R.string.action_clear))
                                    }
                                }
                                TextButton(onClick = {
                                    selectedPages = if (selectedPages.size == pageCount) emptySet() else (1..pageCount).toSet()
                                }) {
                                    Text(if (selectedPages.size == pageCount) "Deselect All" else "Select All")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Thumbnail Grid
                        PdfThumbnailGrid(
                            uri = selectedFile!!.uri,
                            pageCount = pageCount,
                            selectedPages = selectedPages,
                            onPageSelected = { pageNum ->
                                // Tap rotates individual page directly
                                rotatePage(pageNum)
                            },
                            topRightBadge = { pageNum, _, isSel ->
                                Row(
                                    modifier = Modifier.padding(4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Multi-selection checkbox / icon button
                                    IconButton(
                                        onClick = {
                                            selectedPages = if (pageNum in selectedPages) {
                                                selectedPages - pageNum
                                            } else {
                                                selectedPages + pageNum
                                            }
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Surface(
                                            shape = androidx.compose.foundation.shape.CircleShape,
                                            color = if (isSel) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f),
                                            border = androidx.compose.foundation.BorderStroke(1.5.dp, androidx.compose.ui.graphics.Color.White),
                                            modifier = Modifier.size(22.dp)
                                        ) {
                                            if (isSel) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = stringResource(R.string.cd_selected),
                                                    tint = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.padding(2.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            rotationDegrees = { pageNum ->
                                (pageRotations[pageNum] ?: 0).toFloat()
                            },
                            columns = 3,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    }
                }

                // Progress overlay
                if (isProcessing) {
                    Box(
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                OperationProgress(
                                    progress = progress,
                                    message = "Rotating pages..."
                                )
                            }
                        }
                    }
                }
            }

            // Bottom action area
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                tonalElevation = 3.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    if (selectedFile == null) {
                        ActionButton(
                            text = "Select PDF",
                            onClick = {
                                pickPdfLauncher.safeLaunch(arrayOf("application/pdf"), context)
                            },
                            icon = Icons.Default.FolderOpen
                        )
                    } else {
                        val modifiedCount = pageRotations.size
                        val buttonText = if (modifiedCount > 0) {
                            "Save PDF ($modifiedCount rotated)"
                        } else {
                            "Save PDF"
                        }
                        ActionButton(
                            text = buttonText,
                            onClick = {
                                if (useCustomLocation) {
                                    val fileName = FileManager.generateOutputFileName("rotated")
                                    savePdfLauncher.safeLaunch(fileName, context)
                                } else {
                                    rotateWithDefaultLocation()
                                }
                            },
                            enabled = true,
                            isLoading = isProcessing,
                            icon = Icons.Default.RotateRight
                        )
                    }
                }
            }
        }
    }

    // Result dialog with View option
    if (showResult) {
        ResultDialog(
            isSuccess = resultSuccess,
            title = if (resultSuccess) "Rotation Complete" else "Rotation Failed",
            message = resultMessage,
            onDismiss = {
                showResult = false
                resultUri = null
            },
            onAction = resultUri?.let { uri ->
                { scope.launch(Dispatchers.IO) { FileOpener.openPdf(context, uri) } }
            },
            actionText = stringResource(R.string.action_open_pdf)
        )
    }
}
