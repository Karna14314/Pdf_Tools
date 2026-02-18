package com.yourname.pdftoolkit.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yourname.pdftoolkit.data.FileManager
import com.yourname.pdftoolkit.data.HistoryManager
import com.yourname.pdftoolkit.data.SafUriManager
import com.yourname.pdftoolkit.data.OperationType
import com.yourname.pdftoolkit.data.PdfFileInfo
import com.yourname.pdftoolkit.domain.operations.CompressionLevel
import com.yourname.pdftoolkit.domain.operations.PdfCompressor
import com.yourname.pdftoolkit.ui.components.*
import com.yourname.pdftoolkit.util.FileOpener
import com.yourname.pdftoolkit.util.OutputFolderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen for compressing PDF files.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompressScreen(
    onNavigateBack: () -> Unit,
    initialUri: Uri? = null,
    initialName: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pdfCompressor = remember { PdfCompressor() }
    
    // State
    var selectedFile by remember { mutableStateOf<PdfFileInfo?>(null) }
    var compressionSliderValue by remember { mutableStateOf(50f) }
    // Derive CompressionLevel from slider position
    val compressionLevel = when {
        compressionSliderValue < 25f -> CompressionLevel.LOW
        compressionSliderValue < 50f -> CompressionLevel.MEDIUM
        compressionSliderValue < 75f -> CompressionLevel.HIGH
        else -> CompressionLevel.MAXIMUM
    }
    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var showResult by remember { mutableStateOf(false) }
    var resultSuccess by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }
    var resultUri by remember { mutableStateOf<Uri?>(null) }
    var useCustomLocation by remember { mutableStateOf(false) }
    
    // Auto-load initial file if provided
    LaunchedEffect(initialUri) {
        if (initialUri != null && selectedFile == null) {
            selectedFile = FileManager.getFileInfo(context, initialUri)
        }
    }
    
    // File picker launcher
    val pickPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            selectedFile = FileManager.getFileInfo(context, uri)
        }
    }
    
    // Save file launcher (for custom location)
    val savePdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let { outputUri ->
            selectedFile?.let { file ->
                scope.launch {
                    isProcessing = true
                    progress = 0f
                    
                    val outputStream = context.contentResolver.openOutputStream(outputUri)
                    if (outputStream != null) {
                        val result = pdfCompressor.compressPdf(
                            context = context,
                            inputUri = file.uri,
                            outputStream = outputStream,
                            level = compressionLevel,
                            qualityPercent = compressionSliderValue.toInt(),
                            onProgress = { progress = it }
                        )
                        
                        outputStream.close()
                        
                        // Get compressed file size
                        val compressedInfo = FileManager.getFileInfo(context, outputUri)
                        
                        result.fold(
                            onSuccess = { compressionResult ->
                                val actualCompressedSize = compressedInfo?.size ?: compressionResult.compressedSize
                                val originalBytes = file.size
                                val savedBytes = originalBytes - actualCompressedSize
                                val savedPercent = if (originalBytes > 0) {
                                    (savedBytes.toFloat() / originalBytes * 100).toInt()
                                } else 0
                                
                                // IMPORTANT: Copy the file to cache for "Open PDF" functionality
                                // CreateDocument URIs lose read permission after the operation
                                val cachedUri = copyToViewerCache(context, outputUri)
                                
                                if (savedBytes > 0) {
                                    resultSuccess = true
                                    resultUri = cachedUri ?: outputUri
                                    resultMessage = buildString {
                                        append("Compression successful!\n\n")
                                        append("Before: ${file.formattedSize}\n")
                                        append("After: ${compressedInfo?.formattedSize ?: "Unknown"}\n")
                                        append("Saved: ${FileManager.formatFileSize(savedBytes)} ($savedPercent%)")
                                    }
                                } else {
                                    resultSuccess = true
                                    resultUri = cachedUri ?: outputUri
                                    resultMessage = buildString {
                                        append("Compressed PDF saved.\n\n")
                                        append("Before: ${file.formattedSize}\n")
                                        append("After: ${compressedInfo?.formattedSize ?: "Unknown"}\n\n")
                                        append("Note: This PDF may already be optimized or contain mostly text.")
                                    }
                                }
                                selectedFile = null
                            },
                            onFailure = { error ->
                                resultSuccess = false
                                resultMessage = error.message ?: "Compression failed"
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
    }
    
    // Function to compress with default location
    fun compressWithDefaultLocation() {
        scope.launch {
            isProcessing = true
            progress = 0f
            val originalFile = selectedFile!!
            
            val result = withContext(Dispatchers.IO) {
                try {
                    val fileName = FileManager.generateOutputFileName("compressed")
                    val outputResult = OutputFolderManager.createOutputStream(context, fileName)
                    
                    if (outputResult != null) {
                        val compressResult = pdfCompressor.compressPdf(
                            context = context,
                            inputUri = originalFile.uri,
                            outputStream = outputResult.outputStream,
                            level = compressionLevel,
                            qualityPercent = compressionSliderValue.toInt(),
                            onProgress = { progress = it }
                        )
                        
                        outputResult.outputStream.close()
                        
                        compressResult.fold(
                            onSuccess = { cResult ->
                                val compressedSize = outputResult.outputFile.file.length()
                                val originalBytes = originalFile.size
                                val savedBytes = originalBytes - compressedSize
                                val savedPercent = if (originalBytes > 0) {
                                    (savedBytes.toFloat() / originalBytes * 100).toInt()
                                } else 0
                                
                                val message = buildString {
                                    if (savedBytes > 0) {
                                        append("Compression successful!\n\n")
                                        append("Before: ${originalFile.formattedSize}\n")
                                        append("After: ${FileManager.formatFileSize(compressedSize)}\n")
                                        append("Saved: ${FileManager.formatFileSize(savedBytes)} ($savedPercent%)\n\n")
                                    } else {
                                        append("Compressed PDF saved.\n\n")
                                        append("Before: ${originalFile.formattedSize}\n")
                                        append("After: ${FileManager.formatFileSize(compressedSize)}\n\n")
                                        append("Note: This PDF may already be optimized.\n\n")
                                    }
                                    append("Saved to: ${OutputFolderManager.getOutputFolderPath(context)}/${outputResult.outputFile.fileName}")
                                }
                                Triple(true, message, outputResult.outputFile.contentUri)
                            },
                            onFailure = { error ->
                                outputResult.outputFile.file.delete()
                                Triple(false, error.message ?: "Compression failed", null)
                            }
                        )
                    } else {
                        Triple(false, "Cannot create output file", null)
                    }
                } catch (e: Exception) {
                    Triple(false, e.message ?: "Compression failed", null)
                }
            }
            
            resultSuccess = result.first
            resultMessage = result.second
            resultUri = result.third
            
            // Record in history
            if (resultSuccess && result.third != null) {
                // Add to recent files
                SafUriManager.addRecentFile(context, result.third!!)

                HistoryManager.recordSuccess(
                    context = context,
                    operationType = OperationType.COMPRESS,
                    inputFileName = originalFile.name,
                    outputFileUri = result.third,
                    outputFileName = "compressed_${originalFile.name}",
                    details = "Compressed from ${originalFile.formattedSize}"
                )
            } else if (!resultSuccess) {
                HistoryManager.recordFailure(
                    context = context,
                    operationType = OperationType.COMPRESS,
                    inputFileName = originalFile.name,
                    errorMessage = result.second
                )
            }
            
            if (resultSuccess) {
                selectedFile = null
            }
            isProcessing = false
            showResult = true
        }
    }
    
    Scaffold(
        topBar = {
            ToolTopBar(
                title = "Compress PDF",
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
                        icon = Icons.Default.Compress,
                        title = "No PDF Selected",
                        subtitle = "Select a PDF file to reduce its file size",
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(vertical = 16.dp)
                    ) {
                        // Selected file info
                        item {
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
                                            text = "Original size: ${selectedFile!!.formattedSize}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                        )
                                    }
                                    IconButton(onClick = { selectedFile = null }) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Remove",
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }
                        }
                        
                        // Compression level slider
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Compression Level",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Surface(
                                            shape = MaterialTheme.shapes.small,
                                            color = MaterialTheme.colorScheme.primary
                                        ) {
                                            Text(
                                                text = when (compressionLevel) {
                                                    CompressionLevel.LOW -> "Low"
                                                    CompressionLevel.MEDIUM -> "Medium"
                                                    CompressionLevel.HIGH -> "High"
                                                    CompressionLevel.MAXIMUM -> "Maximum"
                                                },
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                    
                                    Spacer(modifier = Modifier.height(4.dp))
                                    
                                    Text(
                                        text = when (compressionLevel) {
                                            CompressionLevel.LOW -> "Best quality, minor size reduction"
                                            CompressionLevel.MEDIUM -> "Good balance of quality and size"
                                            CompressionLevel.HIGH -> "Smaller file, reduced quality"
                                            CompressionLevel.MAXIMUM -> "Smallest file, lowest quality"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    
                                    Spacer(modifier = Modifier.height(12.dp))
                                    
                                    Slider(
                                        value = compressionSliderValue,
                                        onValueChange = { compressionSliderValue = it },
                                        valueRange = 0f..100f,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Better quality",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "Smaller file",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        
                        // Estimated result
                        item {
                            val estimatedSize = selectedFile?.let { file ->
                                pdfCompressor.estimateCompressedSize(
                                    file.size,
                                    compressionSliderValue.toInt()
                                )
                            } ?: 0L
                            
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Speed,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = "Estimated Result",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                        Text(
                                            text = "File size: ~${FileManager.formatFileSize(estimatedSize)}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                
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
                                    message = "Compressing PDF..."
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
                            text = "Compress PDF",
                            onClick = {
                                if (useCustomLocation) {
                                    val fileName = FileManager.generateOutputFileName("compressed")
                                    savePdfLauncher.launch(fileName)
                                } else {
                                    compressWithDefaultLocation()
                                }
                            },
                            isLoading = isProcessing,
                            icon = Icons.Default.Compress
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
            title = if (resultSuccess) "Compression Complete" else "Compression Failed",
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

// CompressionLevelOption removed - replaced by slider UI above

/**
 * Copy a URI to the viewer cache directory and return a FileProvider URI.
 * This is necessary for CreateDocument results where read permission is lost
 * after the save operation completes.
 */
private fun copyToViewerCache(context: android.content.Context, uri: Uri): Uri? {
    return try {
        val cacheDir = java.io.File(context.cacheDir, "viewer_cache")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        
        // Clean old cached files (older than 24 hours)
        val oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        cacheDir.listFiles()?.forEach { file ->
            if (file.lastModified() < oneDayAgo) {
                file.delete()
            }
        }
        
        val cachedFile = java.io.File(cacheDir, "view_${System.currentTimeMillis()}.pdf")
        
        context.contentResolver.openInputStream(uri)?.use { input ->
            cachedFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        
        if (cachedFile.exists() && cachedFile.length() > 0) {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                cachedFile
            )
        } else {
            null
        }
    } catch (e: Exception) {
        android.util.Log.e("CompressScreen", "Failed to copy to viewer cache", e)
        null
    }
}
