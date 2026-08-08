package com.yourname.pdftoolkit.ui.screens
import com.yourname.pdftoolkit.util.safeLaunch

import com.yourname.pdftoolkit.R

import androidx.compose.ui.res.stringResource

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
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
import com.yourname.pdftoolkit.domain.operations.EditableMetadata
import com.yourname.pdftoolkit.domain.operations.PdfMetadata
import com.yourname.pdftoolkit.domain.operations.PdfMetadataManager
import com.yourname.pdftoolkit.ui.components.*
import com.yourname.pdftoolkit.util.FileOpener
import com.yourname.pdftoolkit.util.OutputFolderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen for viewing and editing PDF metadata.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetadataScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val metadataManager = remember { PdfMetadataManager() }
    
    // State
    var selectedFile by remember { mutableStateOf<PdfFileInfo?>(null) }
    var metadata by remember { mutableStateOf<PdfMetadata?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(false) }
    
    // Editable fields
    var editTitle by remember { mutableStateOf("") }
    var editAuthor by remember { mutableStateOf("") }
    var editSubject by remember { mutableStateOf("") }
    var editKeywords by remember { mutableStateOf("") }
    var editCreator by remember { mutableStateOf("") }
    var editProducer by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }
    
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
            isEditing = false
            
            scope.launch {
                isLoading = true
                val result = metadataManager.readMetadata(context, uri)
                result.fold(
                    onSuccess = { meta ->
                        metadata = meta
                        editTitle = meta.title ?: ""
                        editAuthor = meta.author ?: ""
                        editSubject = meta.subject ?: ""
                        editKeywords = meta.keywords ?: ""
                        editCreator = meta.creator ?: ""
                        editProducer = meta.producer ?: ""
                    },
                    onFailure = {
                        metadata = null
                    }
                )
                isLoading = false
            }
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
                        val editedMetadata = EditableMetadata(
                            title = editTitle.trim(),
                            author = editAuthor.trim(),
                            subject = editSubject.trim(),
                            keywords = editKeywords.trim(),
                            creator = editCreator.trim(),
                            producer = editProducer.trim()
                        )
                        
                        val result = metadataManager.updateMetadata(
                            context = context,
                            inputUri = file.uri,
                            outputStream = outputStream,
                            metadata = editedMetadata,
                            onProgress = { progress = it }
                        )
                        
                        outputStream.close()
                        
                        result.fold(
                            onSuccess = {
                                resultSuccess = true
                                resultUri = outputUri
                                resultMessage = context.getString(R.string.metadata_updated_success)
                                isEditing = false
                            },
                            onFailure = { error ->
                                resultSuccess = false
                                resultMessage = error.message ?: context.getString(R.string.metadata_update_failed)
                            }
                        )
                    } else {
                        resultSuccess = false
                        resultMessage = context.getString(R.string.metadata_cannot_create_output)
                    }
                    
                    isProcessing = false
                    showResult = true
                }
            }
        }
    }
    
    // Function to save with default location
    fun saveWithDefaultLocation() {
        scope.launch {
            isProcessing = true
            progress = 0f
            
            val result = withContext(Dispatchers.IO) {
                try {
                    val file = selectedFile!!
                    val fileName = FileManager.generateOutputFileName("updated")
                    val outputResult = OutputFolderManager.createOutputStream(context, fileName)
                    
                    if (outputResult != null) {
                        val editedMetadata = EditableMetadata(
                            title = editTitle.trim(),
                            author = editAuthor.trim(),
                            subject = editSubject.trim(),
                            keywords = editKeywords.trim(),
                            creator = editCreator.trim(),
                            producer = editProducer.trim()
                        )
                        
                        val updateResult = metadataManager.updateMetadata(
                            context = context,
                            inputUri = file.uri,
                            outputStream = outputResult.outputStream,
                            metadata = editedMetadata,
                            onProgress = { progress = it }
                        )
                        
                        outputResult.outputStream.close()
                        
                        updateResult.fold(
                            onSuccess = {
                                Triple(true, context.getString(R.string.metadata_saved_to, context.getString(R.string.metadata_updated_success), "${OutputFolderManager.getOutputFolderPath(context)}/${outputResult.outputFile.fileName}"), outputResult.outputFile.contentUri)
                            },
                            onFailure = { error ->
                                outputResult.outputFile.file.delete()
                                Triple(false, error.message ?: context.getString(R.string.metadata_update_failed), null)
                            }
                        )
                    } else {
                        Triple(false, context.getString(R.string.metadata_cannot_create_output), null)
                    }
                } catch (e: Exception) {
                    Triple(false, e.message ?: context.getString(R.string.metadata_update_failed), null)
                }
            }
            
            resultSuccess = result.first
            resultMessage = result.second
            resultUri = result.third
            if (resultSuccess) {
                isEditing = false
            }
            isProcessing = false
            showResult = true
        }
    }
    
    // Function to strip all metadata
    fun stripMetadata() {
        scope.launch {
            isProcessing = true
            progress = 0f
            
            val result = withContext(Dispatchers.IO) {
                try {
                    val file = selectedFile!!
                    val fileName = FileManager.generateOutputFileName("stripped")
                    val outputResult = OutputFolderManager.createOutputStream(context, fileName)
                    
                    if (outputResult != null) {
                        val stripResult = metadataManager.removeMetadata(
                            context = context,
                            inputUri = file.uri,
                            outputStream = outputResult.outputStream,
                            onProgress = { progress = it }
                        )
                        
                        outputResult.outputStream.close()
                        
                        stripResult.fold(
                            onSuccess = {
                                Triple(true, context.getString(R.string.metadata_stripped_saved_to, "${OutputFolderManager.getOutputFolderPath(context)}/${outputResult.outputFile.fileName}"), outputResult.outputFile.contentUri)
                            },
                            onFailure = { error ->
                                outputResult.outputFile.file.delete()
                                Triple(false, error.message ?: context.getString(R.string.metadata_stripping_failed), null)
                            }
                        )
                    } else {
                        Triple(false, context.getString(R.string.metadata_cannot_create_output), null)
                    }
                } catch (e: Exception) {
                    Triple(false, e.message ?: context.getString(R.string.metadata_stripping_failed), null)
                }
            }
            
            resultSuccess = result.first
            resultMessage = result.second
            resultUri = result.third
            isProcessing = false
            showResult = true
        }
    }
    
    Scaffold(
        topBar = {
            ToolTopBar(
                title = stringResource(R.string.tool_view_metadata),
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
                when {
                    selectedFile == null -> {
                        EmptyState(
                            icon = Icons.Default.Info,
                            title = stringResource(R.string.metadata_no_pdf_selected),
                            subtitle = stringResource(R.string.metadata_no_pdf_selected_subtitle),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    isLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    metadata != null -> {
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
                                                text = stringResource(R.string.metadata_pages_and_size, metadata!!.pageCount, selectedFile!!.formattedSize),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                            )
                                        }
                                        IconButton(onClick = { 
                                            selectedFile = null
                                            metadata = null
                                        }) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = stringResource(R.string.action_remove),
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                }
                            }
                            
                            // Top Privacy Warning Banner (only when not editing)
                            if (!isEditing) {
                                item {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                        ),
                                        border = androidx.compose.foundation.BorderStroke(
                                            width = 1.dp,
                                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PrivacyTip,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = stringResource(R.string.metadata_strip_banner_title),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Text(
                                                    text = stringResource(R.string.metadata_strip_banner_subtitle),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            TextButton(
                                                onClick = { stripMetadata() },
                                                colors = ButtonDefaults.textButtonColors(
                                                    contentColor = MaterialTheme.colorScheme.error
                                                )
                                            ) {
                                                Text(stringResource(R.string.metadata_strip_all))
                                            }
                                        }
                                    }
                                }
                            }
                            
                            // Document Info section / Basic Metadata
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.metadata_basic_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    
                                    TextButton(
                                        onClick = { isEditing = !isEditing }
                                    ) {
                                        Icon(
                                            imageVector = if (isEditing) Icons.Default.Close else Icons.Default.Edit,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(if (isEditing) stringResource(R.string.action_cancel) else stringResource(R.string.action_edit))
                                    }
                                }
                            }
                            
                            // Basic Metadata fields card
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
                                            .padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        if (isEditing) {
                                            OutlinedTextField(
                                                value = editTitle,
                                                onValueChange = { editTitle = it },
                                                label = { Text(stringResource(R.string.label_title)) },
                                                modifier = Modifier.fillMaxWidth(),
                                                singleLine = true
                                            )
                                            
                                            OutlinedTextField(
                                                value = editAuthor,
                                                onValueChange = { editAuthor = it },
                                                label = { Text(stringResource(R.string.label_author)) },
                                                modifier = Modifier.fillMaxWidth(),
                                                singleLine = true
                                            )
                                            
                                            OutlinedTextField(
                                                value = editSubject,
                                                onValueChange = { editSubject = it },
                                                label = { Text(stringResource(R.string.label_subject)) },
                                                modifier = Modifier.fillMaxWidth(),
                                                singleLine = true
                                            )
                                            
                                            OutlinedTextField(
                                                value = editKeywords,
                                                onValueChange = { editKeywords = it },
                                                label = { Text(stringResource(R.string.label_keywords)) },
                                                placeholder = { Text(stringResource(R.string.metadata_keywords_placeholder)) },
                                                modifier = Modifier.fillMaxWidth(),
                                                singleLine = true
                                            )
                                        } else {
                                            MetadataRow(
                                                label = stringResource(R.string.label_title),
                                                value = metadata!!.title ?: stringResource(R.string.metadata_not_set)
                                            )
                                            MetadataRow(
                                                label = stringResource(R.string.label_author),
                                                value = metadata!!.author ?: stringResource(R.string.metadata_not_set)
                                            )
                                            MetadataRow(
                                                label = stringResource(R.string.label_subject),
                                                value = metadata!!.subject ?: stringResource(R.string.metadata_not_set)
                                            )
                                            MetadataRow(
                                                label = stringResource(R.string.label_keywords),
                                                value = metadata!!.keywords ?: stringResource(R.string.metadata_not_set)
                                            )
                                        }
                                    }
                                }
                            }
                            
                            // Collapsible Advanced Properties Card
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { showAdvanced = !showAdvanced }
                                                .padding(16.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.Settings,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = stringResource(R.string.metadata_advanced_properties),
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Icon(
                                                imageVector = if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = if (showAdvanced) stringResource(R.string.cd_collapse) else stringResource(R.string.cd_expand),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        
                                        AnimatedVisibility(visible = showAdvanced) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                                                verticalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                Divider(
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
                                                    modifier = Modifier.padding(bottom = 4.dp)
                                                )
                                                
                                                if (isEditing) {
                                                    OutlinedTextField(
                                                        value = editCreator,
                                                        onValueChange = { editCreator = it },
                                                        label = { Text(stringResource(R.string.label_creator)) },
                                                        modifier = Modifier.fillMaxWidth(),
                                                        singleLine = true
                                                    )
                                                    
                                                    OutlinedTextField(
                                                        value = editProducer,
                                                        onValueChange = { editProducer = it },
                                                        label = { Text(stringResource(R.string.label_producer)) },
                                                        modifier = Modifier.fillMaxWidth(),
                                                        singleLine = true
                                                    )
                                                } else {
                                                    MetadataRow(
                                                        label = stringResource(R.string.label_creator),
                                                        value = metadata!!.creator ?: stringResource(R.string.metadata_unknown)
                                                    )
                                                    MetadataRow(
                                                        label = stringResource(R.string.label_producer),
                                                        value = metadata!!.producer ?: stringResource(R.string.metadata_unknown)
                                                    )
                                                }
                                                
                                                MetadataRow(
                                                    label = stringResource(R.string.label_created),
                                                    value = metadata!!.creationDate ?: stringResource(R.string.metadata_unknown)
                                                )
                                                MetadataRow(
                                                    label = stringResource(R.string.label_modified),
                                                    value = metadata!!.modificationDate ?: stringResource(R.string.metadata_unknown)
                                                )
                                                MetadataRow(
                                                    label = stringResource(R.string.label_pdf_version),
                                                    value = metadata!!.pdfVersion ?: stringResource(R.string.metadata_unknown)
                                                )
                                                MetadataRow(
                                                    label = stringResource(R.string.label_encrypted),
                                                    value = if (metadata!!.isEncrypted) stringResource(R.string.label_yes) else stringResource(R.string.label_no)
                                                )
                                            }
                                        }
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
                                    message = stringResource(R.string.metadata_updating_progress)
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
                    when {
                        selectedFile == null -> {
                            ActionButton(
                                text = stringResource(R.string.action_select_pdf),
                                onClick = {
                                    pickPdfLauncher.safeLaunch(arrayOf("application/pdf"), context)
                                },
                                icon = Icons.Default.FolderOpen
                            )
                        }
                        isEditing -> {
                            // Save location option
                            SaveLocationSelector(
                                useCustomLocation = useCustomLocation,
                                onUseCustomLocationChange = { useCustomLocation = it }
                            )
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            ActionButton(
                                text = stringResource(R.string.action_save_changes),
                                onClick = {
                                    if (useCustomLocation) {
                                        val fileName = FileManager.generateOutputFileName("updated")
                                        savePdfLauncher.safeLaunch(fileName, context)
                                    } else {
                                        saveWithDefaultLocation()
                                    }
                                },
                                isLoading = isProcessing,
                                icon = Icons.Default.Save
                            )
                        }
                        else -> {
                            ActionButton(
                                text = stringResource(R.string.action_select_another_pdf),
                                onClick = {
                                    pickPdfLauncher.safeLaunch(arrayOf("application/pdf"), context)
                                },
                                icon = Icons.Default.FolderOpen
                            )
                        }
                    }
                }
            }
        }
    }
    
    // Result dialog with View option
    if (showResult) {
        ResultDialog(
            isSuccess = resultSuccess,
            title = if (resultSuccess) stringResource(R.string.metadata_update_complete) else stringResource(R.string.metadata_update_failed),
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

@Composable
private fun MetadataRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.6f)
        )
    }
}
