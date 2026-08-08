package com.yourname.pdftoolkit.ui.screens
import com.yourname.pdftoolkit.util.safeLaunch

import androidx.compose.ui.res.stringResource
import com.yourname.pdftoolkit.R

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import com.yourname.pdftoolkit.ui.components.PdfThumbnailGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yourname.pdftoolkit.data.FileManager
import com.yourname.pdftoolkit.data.HistoryManager
import com.yourname.pdftoolkit.data.OperationType
import com.yourname.pdftoolkit.data.PdfFileInfo
import com.yourname.pdftoolkit.domain.operations.PdfRotator
import com.yourname.pdftoolkit.domain.operations.PdfSplitter
import com.yourname.pdftoolkit.domain.operations.RotationAngle
import com.yourname.pdftoolkit.ui.components.*
import com.yourname.pdftoolkit.util.FileOpener
import com.yourname.pdftoolkit.util.OutputFolderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen for rotating PDF pages.
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
    var rotationAngle by remember { mutableStateOf(RotationAngle.ROTATE_90) }
    var rotateAllPages by remember { mutableStateOf(true) }
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
            
            scope.launch {
                pageCount = pdfSplitter.getPageCount(context, uri)
            }
        }
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
                    val result = if (rotateAllPages) {
                        pdfRotator.rotateAllPages(
                            context = context,
                            inputUri = file.uri,
                            outputStream = outputStream,
                            angle = rotationAngle,
                            onProgress = { progress = it }
                        )
                    } else {
                        val rotations = selectedPages.associateWith { rotationAngle }
                        pdfRotator.rotateSpecificPages(
                            context = context,
                            inputUri = file.uri,
                            outputStream = outputStream,
                            rotations = rotations,
                            onProgress = { progress = it }
                        )
                    }
                    
                    outputStream.close()
                    
                    result.fold(
                        onSuccess = { count ->
                            resultSuccess = true
                            resultMessage = "Successfully rotated $count pages by ${rotationAngle.degrees}°"
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
                        val rotateResult = if (rotateAllPages) {
                            pdfRotator.rotateAllPages(
                                context = context,
                                inputUri = file.uri,
                                outputStream = outputResult.outputStream,
                                angle = rotationAngle,
                                onProgress = { progress = it }
                            )
                        } else {
                            val rotations = selectedPages.associateWith { rotationAngle }
                            pdfRotator.rotateSpecificPages(
                                context = context,
                                inputUri = file.uri,
                                outputStream = outputResult.outputStream,
                                rotations = rotations,
                                onProgress = { progress = it }
                            )
                        }
                        
                        outputResult.outputStream.close()
                        
                        rotateResult.fold(
                            onSuccess = { count ->
                                Triple(true, "Successfully rotated $count pages by ${rotationAngle.degrees}°\n\nSaved to: ${OutputFolderManager.getOutputFolderPath(context)}/${outputResult.outputFile.fileName}", outputResult.outputFile.contentUri)
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
                    details = "Rotated by ${rotationAngle.degrees}°"
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
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.action_remove),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Rotation angle selection
                        Text(
                            text = "Rotation Angle",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RotationAngle.entries.forEach { angle ->
                                RotationAngleChip(
                                    angle = angle,
                                    isSelected = rotationAngle == angle,
                                    onClick = { rotationAngle = angle },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Rotate mode selection
                        Text(
                            text = "Pages to Rotate",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = rotateAllPages,
                                onClick = { rotateAllPages = true },
                                label = { Text(stringResource(R.string.rotate_all_pages)) },
                                leadingIcon = if (rotateAllPages) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                } else null,
                                modifier = Modifier.weight(1f)
                            )
                            
                            FilterChip(
                                selected = !rotateAllPages,
                                onClick = { rotateAllPages = false },
                                label = { Text(stringResource(R.string.rotate_select_pages)) },
                                leadingIcon = if (!rotateAllPages) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                } else null,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Page selection (if not rotating all)
                        if (!rotateAllPages) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Select Pages (${selectedPages.size})",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                
                                TextButton(onClick = { selectedPages = emptySet() }) {
                                    Text(stringResource(R.string.action_clear))
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            PdfThumbnailGrid(
                                uri = selectedFile!!.uri,
                                pageCount = pageCount,
                                selectedPages = selectedPages,
                                onPageSelected = { pageNum ->
                                    selectedPages = if (pageNum in selectedPages) {
                                        selectedPages - pageNum
                                    } else {
                                        selectedPages + pageNum
                                    }
                                },
                                rotationDegrees = { pageNum ->
                                    if (pageNum in selectedPages) {
                                        when (rotationAngle) {
                                            RotationAngle.ROTATE_90 -> 90f
                                            RotationAngle.ROTATE_180 -> 180f
                                            RotationAngle.ROTATE_270 -> 270f
                                        }
                                    } else {
                                        0f
                                    }
                                },
                                columns = 3,
                                modifier = Modifier.fillMaxWidth().weight(1f)
                            )
                        } else {
                            // "All Pages" preview block
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Preview (First Page)",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                com.yourname.pdftoolkit.ui.components.PdfThumbnailCard(
                                    uri = selectedFile!!.uri,
                                    pageNumber = 1,
                                    organizer = remember { com.yourname.pdftoolkit.domain.operations.PdfOrganizer() },
                                    isSelected = true,
                                    onClick = {},
                                    rotationDegrees = when (rotationAngle) {
                                        RotationAngle.ROTATE_90 -> 90f
                                        RotationAngle.ROTATE_180 -> 180f
                                        RotationAngle.ROTATE_270 -> 270f
                                    },
                                    modifier = Modifier.width(200.dp)
                                )
                            }
                        }
                    }
                }
                
                // Progress overlay
                // Progress overlay
                if (isProcessing) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(),
                        exit = fadeOut(),
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
                modifier = Modifier.fillMaxWidth(),
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
                        val pageText = if (rotateAllPages) "all pages" else "${selectedPages.size} pages"
                        ActionButton(
                            text = "Rotate $pageText",
                            onClick = {
                                if (useCustomLocation) {
                                    val fileName = FileManager.generateOutputFileName("rotated")
                                    savePdfLauncher.safeLaunch(fileName, context)
                                } else {
                                    rotateWithDefaultLocation()
                                }
                            },
                            enabled = rotateAllPages || selectedPages.isNotEmpty(),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RotationAngleChip(
    angle: RotationAngle,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = when (angle) {
                    RotationAngle.ROTATE_90 -> Icons.Default.RotateRight
                    RotationAngle.ROTATE_180 -> Icons.Default.Sync
                    RotationAngle.ROTATE_270 -> Icons.Default.RotateLeft
                },
                contentDescription = null,
                tint = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = "${angle.degrees}°",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

