package com.yourname.pdftoolkit.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yourname.pdftoolkit.data.FileManager
import com.yourname.pdftoolkit.data.HistoryManager
import com.yourname.pdftoolkit.data.OperationType
import com.yourname.pdftoolkit.data.PdfFileInfo
import com.yourname.pdftoolkit.domain.operations.PdfOrganizer
import com.yourname.pdftoolkit.ui.components.*
import com.yourname.pdftoolkit.util.FileOpener
import com.yourname.pdftoolkit.util.OutputFolderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Data class representing a page that can be reordered.
 */
data class ReorderablePage(
    val originalIndex: Int,  // 1-based original page number
    val thumbnail: Bitmap?
)

/**
 * Screen for reordering PDF pages with visual previews.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReorderScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val organizer = remember { PdfOrganizer() }
    
    // State
    var selectedFile by remember { mutableStateOf<PdfFileInfo?>(null) }
    var pages by remember { mutableStateOf<List<ReorderablePage>>(emptyList()) }
    var isLoadingThumbnails by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var showResult by remember { mutableStateOf(false) }
    var resultSuccess by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }
    var resultUri by remember { mutableStateOf<Uri?>(null) }
    var useCustomLocation by remember { mutableStateOf(false) }
    var selectedPageIndex by remember { mutableStateOf<Int?>(null) }
    
    // Check if order has changed
    val hasOrderChanged = remember(pages) {
        pages.mapIndexed { index, page -> page.originalIndex != index + 1 }.any { it }
    }
    
    // File picker launcher
    val pickPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            selectedFile = FileManager.getFileInfo(context, uri)
            pages = emptyList()
            selectedPageIndex = null
            
            // Load page thumbnails
            scope.launch {
                isLoadingThumbnails = true
                val pageCount = organizer.getPageCount(context, uri)
                
                // Create placeholder pages first
                pages = (1..pageCount).map { ReorderablePage(it, null) }
                
                // Load thumbnails in background
                withContext(Dispatchers.IO) {
                    try {
                        organizer.getPageThumbnails(
                            context = context,
                            uri = uri,
                            width = 150,
                            height = 200
                        ) { pageNum, bitmap ->
                            // Update the specific page with its thumbnail
                            pages = pages.map { page ->
                                if (page.originalIndex == pageNum) {
                                    page.copy(thumbnail = bitmap)
                                } else {
                                    page
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Continue without thumbnails
                    }
                }
                isLoadingThumbnails = false
            }
        }
    }
    
    // Save file launcher (for custom location)
    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let { saveUri ->
            val file = selectedFile ?: return@let
            
            scope.launch {
                isProcessing = true
                progress = 0f
                
                context.contentResolver.openOutputStream(saveUri)?.use { outputStream ->
                    val newOrder = pages.map { it.originalIndex }
                    
                    val result = organizer.reorderPages(
                        context = context,
                        inputUri = file.uri,
                        outputStream = outputStream,
                        newOrder = newOrder,
                        onProgress = { progress = it }
                    )
                    
                    result.fold(
                        onSuccess = { organizeResult ->
                            resultSuccess = true
                            resultUri = saveUri
                            resultMessage = "Successfully reordered ${organizeResult.resultPageCount} pages."
                            
                            HistoryManager.recordSuccess(
                                context = context,
                                operationType = OperationType.REORDER,
                                inputFileName = file.name,
                                outputFileUri = saveUri,
                                outputFileName = "reordered_${file.name}",
                                details = "Reordered ${organizeResult.resultPageCount} pages"
                            )
                            
                            selectedFile = null
                            pages = emptyList()
                        },
                        onFailure = { error ->
                            resultSuccess = false
                            resultMessage = error.message ?: "Reorder failed"
                            
                            HistoryManager.recordFailure(
                                context = context,
                                operationType = OperationType.REORDER,
                                inputFileName = file.name,
                                errorMessage = error.message
                            )
                        }
                    )
                } ?: run {
                    resultSuccess = false
                    resultMessage = "Cannot create output file"
                }
                
                isProcessing = false
                showResult = true
            }
        }
    }
    
    // Function to reorder with default location
    fun reorderWithDefaultLocation() {
        scope.launch {
            isProcessing = true
            progress = 0f
            val file = selectedFile!!
            
            val result = withContext(Dispatchers.IO) {
                try {
                    val baseName = file.name.removeSuffix(".pdf")
                    val fileName = "${baseName}_reordered.pdf"
                    val outputResult = OutputFolderManager.createOutputStream(context, fileName)
                    
                    if (outputResult != null) {
                        val newOrder = pages.map { it.originalIndex }
                        
                        val organizeResult = organizer.reorderPages(
                            context = context,
                            inputUri = file.uri,
                            outputStream = outputResult.outputStream,
                            newOrder = newOrder,
                            onProgress = { progress = it }
                        )
                        
                        outputResult.outputStream.close()
                        
                        organizeResult.fold(
                            onSuccess = { oResult ->
                                Triple(
                                    true,
                                    "Successfully reordered ${oResult.resultPageCount} pages.\n\nSaved to: ${OutputFolderManager.getOutputFolderPath(context)}/${outputResult.outputFile.fileName}",
                                    outputResult.outputFile.contentUri
                                )
                            },
                            onFailure = { error ->
                                outputResult.outputFile.file.delete()
                                Triple(false, error.message ?: "Reorder failed", null)
                            }
                        )
                    } else {
                        Triple(false, "Cannot create output file", null)
                    }
                } catch (e: Exception) {
                    Triple(false, e.message ?: "Reorder failed", null)
                }
            }
            
            resultSuccess = result.first
            resultMessage = result.second
            resultUri = result.third
            
            if (resultSuccess && result.third != null) {
                HistoryManager.recordSuccess(
                    context = context,
                    operationType = OperationType.REORDER,
                    inputFileName = file.name,
                    outputFileUri = result.third,
                    outputFileName = "reordered_${file.name}",
                    details = "Reordered ${pages.size} pages"
                )
                selectedFile = null
                pages = emptyList()
            } else if (!resultSuccess) {
                HistoryManager.recordFailure(
                    context = context,
                    operationType = OperationType.REORDER,
                    inputFileName = file.name,
                    errorMessage = result.second
                )
            }
            
            isProcessing = false
            showResult = true
        }
    }
    
    // Move page functions
    fun movePageUp(index: Int) {
        if (index > 0) {
            val mutableList = pages.toMutableList()
            val temp = mutableList[index]
            mutableList[index] = mutableList[index - 1]
            mutableList[index - 1] = temp
            pages = mutableList
            selectedPageIndex = index - 1
        }
    }
    
    fun movePageDown(index: Int) {
        if (index < pages.size - 1) {
            val mutableList = pages.toMutableList()
            val temp = mutableList[index]
            mutableList[index] = mutableList[index + 1]
            mutableList[index + 1] = temp
            pages = mutableList
            selectedPageIndex = index + 1
        }
    }
    
    fun moveToFirst(index: Int) {
        if (index > 0) {
            val mutableList = pages.toMutableList()
            val page = mutableList.removeAt(index)
            mutableList.add(0, page)
            pages = mutableList
            selectedPageIndex = 0
        }
    }
    
    fun moveToLast(index: Int) {
        if (index < pages.size - 1) {
            val mutableList = pages.toMutableList()
            val page = mutableList.removeAt(index)
            mutableList.add(page)
            pages = mutableList
            selectedPageIndex = pages.size - 1
        }
    }
    
    fun resetOrder() {
        pages = pages.sortedBy { it.originalIndex }
        selectedPageIndex = null
    }
    
    Scaffold(
        topBar = {
            ToolTopBar(
                title = "Reorder Pages",
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
                        icon = Icons.Default.SwapVert,
                        title = "No PDF Selected",
                        subtitle = "Select a PDF to rearrange its pages",
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else if (isLoadingThumbnails && pages.all { it.thumbnail == null }) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Loading page previews...")
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                    ) {
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Selected file info
                        FileItemCard(
                            fileName = selectedFile!!.name,
                            fileSize = "${pages.size} pages • ${selectedFile!!.formattedSize}",
                            onRemove = { 
                                selectedFile = null
                                pages = emptyList()
                                selectedPageIndex = null
                            }
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Instructions and reset
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Tap a page to select, then use arrows to move",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (hasOrderChanged) {
                                    Text(
                                        text = "Pages have been reordered",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            
                            if (hasOrderChanged) {
                                TextButton(onClick = { resetOrder() }) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Reset")
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Page grid with thumbnails
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(bottom = 8.dp)
                        ) {
                            itemsIndexed(pages) { index, page ->
                                PagePreviewCard(
                                    page = page,
                                    currentPosition = index + 1,
                                    isSelected = selectedPageIndex == index,
                                    canMoveUp = index > 0,
                                    canMoveDown = index < pages.size - 1,
                                    onSelect = { selectedPageIndex = if (selectedPageIndex == index) null else index },
                                    onMoveUp = { movePageUp(index) },
                                    onMoveDown = { movePageDown(index) },
                                    onMoveToFirst = { moveToFirst(index) },
                                    onMoveToLast = { moveToLast(index) }
                                )
                            }
                        }
                    }
                }
                
                // Progress overlay
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
                                message = "Reordering pages..."
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
                        .padding(16.dp)
                ) {
                    if (selectedFile == null) {
                        ActionButton(
                            text = "Select PDF",
                            onClick = {
                                pickPdfLauncher.launch(arrayOf("application/pdf"))
                            },
                            icon = Icons.Default.FolderOpen
                        )
                    } else {
                        // Save location option
                        SaveLocationSelector(
                            useCustomLocation = useCustomLocation,
                            onUseCustomLocationChange = { useCustomLocation = it }
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        ActionButton(
                            text = "Save Reordered PDF",
                            onClick = {
                                if (useCustomLocation) {
                                    val baseName = selectedFile!!.name.removeSuffix(".pdf")
                                    saveFileLauncher.launch("${baseName}_reordered.pdf")
                                } else {
                                    reorderWithDefaultLocation()
                                }
                            },
                            enabled = hasOrderChanged && !isProcessing,
                            isLoading = isProcessing,
                            icon = Icons.Default.Save
                        )
                    }
                }
            }
        }
    }
    
    // Result dialog
    if (showResult) {
        ResultDialog(
            isSuccess = resultSuccess,
            title = if (resultSuccess) "Reorder Complete" else "Reorder Failed",
            message = resultMessage,
            onDismiss = { 
                showResult = false
                resultUri = null
            },
            onAction = resultUri?.let { uri ->
                { FileOpener.openPdf(context, uri) }
            },
            actionText = "Open PDF"
        )
    }
}

/**
 * Card showing a page preview with reorder controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PagePreviewCard(
    page: ReorderablePage,
    currentPosition: Int,
    isSelected: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelect: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveToFirst: () -> Unit,
    onMoveToLast: () -> Unit
) {
    val hasChanged = page.originalIndex != currentPosition
    
    Card(
        onClick = onSelect,
        modifier = Modifier
            .aspectRatio(0.7f)
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(12.dp)
                    )
                } else Modifier
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Thumbnail or placeholder
            if (page.thumbnail != null) {
                Image(
                    bitmap = page.thumbnail.asImageBitmap(),
                    contentDescription = "Page ${page.originalIndex}",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp)),
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
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
            
            // Position badge (top-left)
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
                shape = CircleShape,
                color = if (hasChanged) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
                }
            ) {
                Text(
                    text = "$currentPosition",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (hasChanged) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            
            // Original page number (if different)
            if (hasChanged) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f)
                ) {
                    Text(
                        text = "was ${page.originalIndex}",
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
            
            // Move controls (when selected)
            if (isSelected) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                        )
                        .padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Move to first
                        IconButton(
                            onClick = onMoveToFirst,
                            enabled = canMoveUp,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.KeyboardDoubleArrowUp,
                                contentDescription = "Move to first",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        
                        // Move up
                        IconButton(
                            onClick = onMoveUp,
                            enabled = canMoveUp,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                contentDescription = "Move up",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        
                        // Move down
                        IconButton(
                            onClick = onMoveDown,
                            enabled = canMoveDown,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Move down",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        
                        // Move to last
                        IconButton(
                            onClick = onMoveToLast,
                            enabled = canMoveDown,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.KeyboardDoubleArrowDown,
                                contentDescription = "Move to last",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
