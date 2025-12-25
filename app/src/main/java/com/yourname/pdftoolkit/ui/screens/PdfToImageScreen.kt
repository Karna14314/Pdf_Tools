package com.yourname.pdftoolkit.ui.screens

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import com.yourname.pdftoolkit.data.PdfFileInfo
import com.yourname.pdftoolkit.domain.operations.ImageConverter
import com.yourname.pdftoolkit.domain.operations.ImageFormat
import com.yourname.pdftoolkit.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Screen for converting PDF pages to images.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfToImageScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imageConverter = remember { ImageConverter() }
    
    // State
    var selectedFile by remember { mutableStateOf<PdfFileInfo?>(null) }
    var imageFormat by remember { mutableStateOf(ImageFormat.PNG) }
    var dpi by remember { mutableStateOf(150f) }
    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var showResult by remember { mutableStateOf(false) }
    var resultSuccess by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }
    
    // File picker launcher
    val pickPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            selectedFile = FileManager.getFileInfo(context, uri)
        }
    }
    
    // Convert PDF to images
    fun convertPdfToImages() {
        val file = selectedFile ?: return
        
        scope.launch {
            isProcessing = true
            progress = 0f
            
            var savedCount = 0
            
            val result = imageConverter.pdfToImages(
                context = context,
                inputUri = file.uri,
                format = imageFormat,
                dpi = dpi.toInt(),
                pageNumbers = null, // All pages
                outputCallback = { pageNumber, bitmap ->
                    // Save each bitmap to gallery
                    val saved = saveBitmapToGallery(
                        context = context,
                        bitmap = bitmap,
                        fileName = "${file.name.removeSuffix(".pdf")}_page_$pageNumber",
                        format = imageFormat
                    )
                    if (saved) savedCount++
                },
                onProgress = { progress = it }
            )
            
            result.fold(
                onSuccess = { _ ->
                    resultSuccess = true
                    resultMessage = "Successfully saved $savedCount images to your gallery"
                    selectedFile = null
                },
                onFailure = { error ->
                    resultSuccess = false
                    resultMessage = error.message ?: "Conversion failed"
                }
            )
            
            isProcessing = false
            showResult = true
        }
    }
    
    Scaffold(
        topBar = {
            ToolTopBar(
                title = "PDF to Images",
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
                        icon = Icons.Default.Image,
                        title = "No PDF Selected",
                        subtitle = "Select a PDF file to convert its pages to images",
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
                                            text = "Size: ${selectedFile!!.formattedSize}",
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
                        
                        // Image format selection
                        item {
                            Text(
                                text = "Output Format",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ImageFormat.entries.forEach { format ->
                                    FilterChip(
                                        selected = imageFormat == format,
                                        onClick = { imageFormat = format },
                                        label = { Text(format.extension.uppercase()) }
                                    )
                                }
                            }
                        }
                        
                        // DPI slider
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
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Resolution (DPI)",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = "${dpi.toInt()} DPI",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    
                                    Spacer(modifier = Modifier.height(8.dp))
                                    
                                    Slider(
                                        value = dpi,
                                        onValueChange = { dpi = it },
                                        valueRange = 72f..300f,
                                        steps = 4
                                    )
                                    
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "72 (Fast)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "300 (HD)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        
                        // Info card
                        item {
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
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "Images will be saved to your device's gallery (Pictures folder).",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
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
                                    message = "Converting pages to images..."
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
                        ActionButton(
                            text = "Convert to Images",
                            onClick = { convertPdfToImages() },
                            isLoading = isProcessing,
                            icon = Icons.Default.Image
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
            title = if (resultSuccess) "Conversion Complete" else "Conversion Failed",
            message = resultMessage,
            onDismiss = { showResult = false }
        )
    }
}

/**
 * Save bitmap to device gallery using MediaStore.
 */
private suspend fun saveBitmapToGallery(
    context: android.content.Context,
    bitmap: Bitmap,
    fileName: String,
    format: ImageFormat
): Boolean = withContext(Dispatchers.IO) {
    try {
        val mimeType = format.mimeType
        val extension = format.extension
        val compressFormat = if (format == ImageFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ - Use MediaStore
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$fileName.$extension")
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PDF Toolkit")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: return@withContext false
            
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(compressFormat, 95, outputStream)
            }
            
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, contentValues, null, null)
            
            true
        } else {
            // Legacy storage
            @Suppress("DEPRECATION")
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val appDir = File(picturesDir, "PDF Toolkit")
            if (!appDir.exists()) appDir.mkdirs()
            
            val file = File(appDir, "$fileName.$extension")
            FileOutputStream(file).use { outputStream ->
                bitmap.compress(compressFormat, 95, outputStream)
            }
            
            // Notify gallery
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DATA, file.absolutePath)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            }
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            
            true
        }
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}
