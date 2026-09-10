package com.yourname.pdftoolkit.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yourname.pdftoolkit.R
import com.yourname.pdftoolkit.data.FileManager
import com.yourname.pdftoolkit.data.HistoryManager
import com.yourname.pdftoolkit.data.OperationType
import com.yourname.pdftoolkit.domain.operations.MergeItem
import com.yourname.pdftoolkit.domain.operations.MergeItemType
import com.yourname.pdftoolkit.domain.operations.MergePageItem
import com.yourname.pdftoolkit.domain.operations.PdfMerger
import com.yourname.pdftoolkit.ui.components.*
import com.yourname.pdftoolkit.util.FileOpener
import com.yourname.pdftoolkit.util.OutputFolderManager
import com.yourname.pdftoolkit.util.safeLaunch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen for merging multiple PDF files and images into a single PDF output.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeScreen(
    onNavigateBack: () -> Unit,
    viewModel: MergeViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pdfMerger = remember { PdfMerger() }

    val selectedFiles by viewModel.selectedItems.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val pageItems by viewModel.pageItems.collectAsState()

    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var showResult by remember { mutableStateOf(false) }
    var resultSuccess by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }
    var resultUri by remember { mutableStateOf<Uri?>(null) }
    var useCustomLocation by remember { mutableStateOf(false) }

    // Launcher for selecting multiple PDFs or Images
    val pickFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addFiles(context, uris)
        }
    }

    // Custom save launcher
    val savePdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let { outputUri ->
            performMerge(
                context = context,
                scope = scope,
                pdfMerger = pdfMerger,
                selectedItems = selectedFiles,
                outputUri = outputUri,
                onProgress = { progress = it },
                onProcessing = { isProcessing = it },
                onResult = { success, message, resUri ->
                    resultSuccess = success
                    resultMessage = message
                    resultUri = resUri
                    if (success) viewModel.clearItems()
                    showResult = true
                }
            )
        }
    }

    fun mergeWithDefaultLocation() {
        scope.launch {
            isProcessing = true
            progress = 0f
            val fileCount = selectedFiles.size
            val firstFileName = selectedFiles.firstOrNull()?.name

            val result = withContext(Dispatchers.IO) {
                try {
                    val fileName = FileManager.generateOutputFileName("merged")
                    val outputResult = OutputFolderManager.createOutputStream(context, fileName)

                    if (outputResult != null) {
                        val mergeResult = pdfMerger.mergeItems(
                            context = context,
                            items = selectedFiles,
                            outputStream = outputResult.outputStream,
                            onProgress = { progress = it }
                        )

                        outputResult.outputStream.close()

                        mergeResult.fold(
                            onSuccess = {
                                Triple(
                                    true,
                                    context.getString(
                                        R.string.merge_success_message,
                                        fileCount,
                                        OutputFolderManager.getOutputFolderPath(context),
                                        outputResult.outputFile.fileName
                                    ),
                                    outputResult.outputFile.contentUri
                                )
                            },
                            onFailure = { error ->
                                outputResult.outputFile.file.delete()
                                Triple(false, error.message ?: context.getString(R.string.merge_failed), null)
                            }
                        )
                    } else {
                        Triple(false, context.getString(R.string.error_cannot_create_output), null)
                    }
                } catch (e: Exception) {
                    Triple(false, e.message ?: context.getString(R.string.merge_failed), null)
                }
            }

            resultSuccess = result.first
            resultMessage = result.second
            resultUri = result.third

            if (resultSuccess && result.third != null) {
                HistoryManager.recordSuccess(
                    context = context,
                    operationType = OperationType.MERGE,
                    inputFileName = firstFileName,
                    outputFileUri = result.third,
                    outputFileName = "merged_${fileCount}_files.pdf",
                    details = "Merged $fileCount files"
                )
            } else if (!resultSuccess) {
                HistoryManager.recordFailure(
                    context = context,
                    operationType = OperationType.MERGE,
                    inputFileName = firstFileName,
                    errorMessage = result.second
                )
            }

            if (resultSuccess) viewModel.clearItems()
            isProcessing = false
            showResult = true
        }
    }

    Scaffold(
        topBar = {
            ToolTopBar(
                title = stringResource(R.string.merge_title),
                onNavigateBack = onNavigateBack
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Dual-view switch bar (File View | Page View)
            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                TabRow(
                    selectedTabIndex = if (viewMode == MergeViewMode.FILE_VIEW) 0 else 1,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = viewMode == MergeViewMode.FILE_VIEW,
                        onClick = { viewModel.setViewMode(MergeViewMode.FILE_VIEW) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.merge_file_view))
                            }
                        }
                    )
                    Tab(
                        selected = viewMode == MergeViewMode.PAGE_VIEW,
                        onClick = { viewModel.setViewMode(MergeViewMode.PAGE_VIEW) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.merge_page_view))
                            }
                        }
                    )
                }
            }

            // Main Content Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (selectedFiles.isEmpty()) {
                    EmptyState(
                        icon = Icons.Default.MergeType,
                        title = stringResource(R.string.merge_empty_title),
                        subtitle = stringResource(R.string.merge_empty_subtitle),
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    when (viewMode) {
                        MergeViewMode.FILE_VIEW -> {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(vertical = 16.dp)
                            ) {
                                item {
                                    Text(
                                        text = stringResource(R.string.merge_selected_files, selectedFiles.size),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                }

                                itemsIndexed(
                                    items = selectedFiles,
                                    key = { index, file -> "${file.id}-$index" }
                                ) { index, item ->
                                    MergeItemCard(
                                        index = index + 1,
                                        item = item,
                                        onRemove = { viewModel.removeItem(index) },
                                        onMoveUp = if (index > 0) { { viewModel.moveItemUp(index) } } else null,
                                        onMoveDown = if (index < selectedFiles.lastIndex) { { viewModel.moveItemDown(index) } } else null
                                    )
                                }

                                item {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                pickFilesLauncher.safeLaunch(
                                                    arrayOf("application/pdf", "image/*"),
                                                    context
                                                )
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(stringResource(R.string.merge_add_files))
                                        }
                                    }
                                }

                                item {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = useCustomLocation,
                                            onCheckedChange = { useCustomLocation = it }
                                        )
                                        Text(
                                            text = stringResource(R.string.merge_custom_location),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                    if (!useCustomLocation) {
                                        Text(
                                            text = stringResource(
                                                R.string.merge_default_path,
                                                OutputFolderManager.getOutputFolderPath(context)
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(start = 48.dp)
                                        )
                                    }
                                }
                            }
                        }

                        MergeViewMode.PAGE_VIEW -> {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(16.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(pageItems, key = { it.id }) { pageItem ->
                                    MergePageThumbnailCard(
                                        pageItem = pageItem,
                                        getThumbnail = { viewModel.getPageThumbnail(context, pageItem) }
                                    )
                                }
                            }
                        }
                    }
                }

                if (isProcessing) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp)
                            .align(Alignment.Center)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            OperationProgress(
                                progress = progress,
                                message = stringResource(R.string.merge_progress)
                            )
                        }
                    }
                }
            }

            // Bottom action area
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 3.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (selectedFiles.isEmpty()) {
                        ActionButton(
                            text = stringResource(R.string.merge_add_files),
                            onClick = {
                                pickFilesLauncher.safeLaunch(
                                    arrayOf("application/pdf", "image/*"),
                                    context
                                )
                            },
                            icon = Icons.Default.FolderOpen
                        )
                    } else {
                        ActionButton(
                            text = stringResource(R.string.merge_n_files, selectedFiles.size),
                            onClick = {
                                if (useCustomLocation) {
                                    val fileName = FileManager.generateOutputFileName("merged")
                                    savePdfLauncher.safeLaunch(fileName, context)
                                } else {
                                    mergeWithDefaultLocation()
                                }
                            },
                            enabled = selectedFiles.isNotEmpty(),
                            isLoading = isProcessing,
                            icon = Icons.Default.MergeType
                        )
                    }
                }
            }
        }
    }

    if (showResult) {
        ResultDialog(
            isSuccess = resultSuccess,
            title = if (resultSuccess) stringResource(R.string.merge_complete) else stringResource(R.string.merge_failed),
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

private fun performMerge(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    pdfMerger: PdfMerger,
    selectedItems: List<MergeItem>,
    outputUri: Uri,
    onProgress: (Float) -> Unit,
    onProcessing: (Boolean) -> Unit,
    onResult: (Boolean, String, Uri?) -> Unit
) {
    scope.launch {
        onProcessing(true)
        onProgress(0f)

        val outputStream = context.contentResolver.openOutputStream(outputUri)
        if (outputStream != null) {
            val result = pdfMerger.mergeItems(
                context = context,
                items = selectedItems,
                outputStream = outputStream,
                onProgress = onProgress
            )

            outputStream.close()

            result.fold(
                onSuccess = {
                    onResult(true, "Successfully merged ${selectedItems.size} files", outputUri)
                },
                onFailure = { error ->
                    onResult(false, error.message ?: "Merge failed", null)
                }
            )
        } else {
            onResult(false, "Cannot create output file", null)
        }

        onProcessing(false)
    }
}

@Composable
private fun MergeItemCard(
    index: Int,
    item: MergeItem,
    onRemove: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(
                        text = index.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // File Type Icon
            Icon(
                imageVector = if (item.type == MergeItemType.IMAGE) Icons.Default.Image else Icons.Default.PictureAsPdf,
                contentDescription = null,
                tint = if (item.type == MergeItemType.IMAGE) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (item.type == MergeItemType.PDF) {
                        "${item.formattedSize} • ${item.pageCount} page(s)"
                    } else {
                        "${item.formattedSize} • Image"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Reorder buttons
            Column {
                IconButton(
                    onClick = { onMoveUp?.invoke() },
                    enabled = onMoveUp != null,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.action_move_up),
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = { onMoveDown?.invoke() },
                    enabled = onMoveDown != null,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.action_move_down),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_remove),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun MergePageThumbnailCard(
    pageItem: MergePageItem,
    getThumbnail: suspend () -> Bitmap?
) {
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(pageItem) {
        thumbnail = getThumbnail()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .clip(RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (thumbnail != null && !thumbnail!!.isRecycled) {
                Image(
                    bitmap = thumbnail!!.asImageBitmap(),
                    contentDescription = pageItem.sourceLabel,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (pageItem.isImage) Icons.Default.Image else Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }

            // Top badge: Page Number
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "${pageItem.overallPageNumber}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            // Top right badge: Type indicator
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(20.dp)
                    .background(
                        if (pageItem.isImage) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (pageItem.isImage) Icons.Default.Image else Icons.Default.PictureAsPdf,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }

            // Bottom overlay label
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Text(
                    text = pageItem.sourceLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
