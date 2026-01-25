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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.yourname.pdftoolkit.data.FileManager
import com.yourname.pdftoolkit.data.PdfFileInfo
import com.yourname.pdftoolkit.domain.operations.PdfSecurityManager
import com.yourname.pdftoolkit.domain.operations.PdfSecurityOptions
import com.yourname.pdftoolkit.ui.components.*
import com.yourname.pdftoolkit.util.FileOpener
import com.yourname.pdftoolkit.util.OutputFolderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen for adding password protection to PDF.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val securityManager = remember { PdfSecurityManager() }
    
    // State
    var selectedFile by remember { mutableStateOf<PdfFileInfo?>(null) }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var allowPrinting by remember { mutableStateOf(true) }
    var allowCopying by remember { mutableStateOf(false) }
    var allowModifying by remember { mutableStateOf(false) }
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
            selectedFile = FileManager.getFileInfo(context, uri)
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
                    val options = PdfSecurityOptions(
                        password = password,
                        allowPrinting = allowPrinting,
                        allowCopying = allowCopying,
                        allowModifying = allowModifying
                    )
                    
                    val result = securityManager.encryptPdf(
                        context = context,
                        inputUri = file.uri,
                        outputStream = outputStream,
                        options = options,
                        onProgress = { progress = it }
                    )
                    
                    outputStream.close()
                    
                    result.fold(
                        onSuccess = {
                            resultSuccess = true
                            resultMessage = "PDF protected successfully!"
                            resultUri = outputUri
                            selectedFile = null
                            password = ""
                            confirmPassword = ""
                        },
                        onFailure = { error ->
                            resultSuccess = false
                            resultMessage = error.message ?: "Encryption failed"
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
    
    // Function to protect with default location
    fun protectWithDefaultLocation() {
        scope.launch {
            isProcessing = true
            progress = 0f
            
            val result = withContext(Dispatchers.IO) {
                try {
                    val file = selectedFile!!
                    val baseName = file.name.removeSuffix(".pdf")
                    val fileName = "${baseName}_protected.pdf"
                    val outputResult = OutputFolderManager.createOutputStream(context, fileName)
                    
                    if (outputResult != null) {
                        val options = PdfSecurityOptions(
                            password = password,
                            allowPrinting = allowPrinting,
                            allowCopying = allowCopying,
                            allowModifying = allowModifying
                        )
                        
                        val encryptResult = securityManager.encryptPdf(
                            context = context,
                            inputUri = file.uri,
                            outputStream = outputResult.outputStream,
                            options = options,
                            onProgress = { progress = it }
                        )
                        
                        outputResult.outputStream.close()
                        
                        encryptResult.fold(
                            onSuccess = {
                                Triple(true, "PDF protected successfully!\n\nSaved to: ${OutputFolderManager.getOutputFolderPath(context)}/${outputResult.outputFile.fileName}", outputResult.outputFile.contentUri)
                            },
                            onFailure = { error ->
                                outputResult.outputFile.file.delete()
                                Triple(false, error.message ?: "Encryption failed", null)
                            }
                        )
                    } else {
                        Triple(false, "Cannot create output file", null)
                    }
                } catch (e: Exception) {
                    Triple(false, e.message ?: "Encryption failed", null)
                }
            }
            
            resultSuccess = result.first
            resultMessage = result.second
            resultUri = result.third
            if (resultSuccess) {
                selectedFile = null
                password = ""
                confirmPassword = ""
            }
            isProcessing = false
            showResult = true
        }
    }
    
    Scaffold(
        topBar = {
            ToolTopBar(
                title = "Add Security",
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
                        icon = Icons.Default.Security,
                        title = "No PDF Selected",
                        subtitle = "Select a PDF to protect with a password",
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
                            FileItemCard(
                                fileName = selectedFile!!.name,
                                fileSize = selectedFile!!.formattedSize,
                                onRemove = { selectedFile = null }
                            )
                        }
                        
                        // Password section
                        item {
                            Text(
                                text = "Password",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
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
                                    // Password field
                                    OutlinedTextField(
                                        value = password,
                                        onValueChange = { password = it },
                                        label = { Text("Password") },
                                        placeholder = { Text("Enter password to protect PDF") },
                                        supportingText = { 
                                            Text("This password will be required to open the PDF")
                                        },
                                        visualTransformation = if (showPassword) {
                                            VisualTransformation.None
                                        } else {
                                            PasswordVisualTransformation()
                                        },
                                        trailingIcon = {
                                            IconButton(onClick = { showPassword = !showPassword }) {
                                                Icon(
                                                    imageVector = if (showPassword) {
                                                        Icons.Default.VisibilityOff
                                                    } else {
                                                        Icons.Default.Visibility
                                                    },
                                                    contentDescription = "Toggle visibility"
                                                )
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        isError = password.isNotEmpty() && password.length < 4
                                    )
                                    
                                    // Confirm password field
                                    OutlinedTextField(
                                        value = confirmPassword,
                                        onValueChange = { confirmPassword = it },
                                        label = { Text("Confirm Password") },
                                        placeholder = { Text("Re-enter password") },
                                        visualTransformation = if (showPassword) {
                                            VisualTransformation.None
                                        } else {
                                            PasswordVisualTransformation()
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        isError = confirmPassword.isNotEmpty() && password != confirmPassword,
                                        supportingText = if (confirmPassword.isNotEmpty() && password != confirmPassword) {
                                            { Text("Passwords don't match", color = MaterialTheme.colorScheme.error) }
                                        } else null
                                    )
                                }
                            }
                        }
                        
                        // Permissions section
                        item {
                            Text(
                                text = "Permissions",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
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
                                    PermissionItem(
                                        title = "Allow Printing",
                                        icon = Icons.Default.Print,
                                        checked = allowPrinting,
                                        onCheckedChange = { allowPrinting = it }
                                    )
                                    
                                    Divider(modifier = Modifier.padding(horizontal = 16.dp))
                                    
                                    PermissionItem(
                                        title = "Allow Copying",
                                        icon = Icons.Default.ContentCopy,
                                        checked = allowCopying,
                                        onCheckedChange = { allowCopying = it }
                                    )
                                    
                                    Divider(modifier = Modifier.padding(horizontal = 16.dp))
                                    
                                    PermissionItem(
                                        title = "Allow Editing",
                                        icon = Icons.Default.Edit,
                                        checked = allowModifying,
                                        onCheckedChange = { allowModifying = it }
                                    )
                                }
                            }
                        }
                        
                        // Save location option
                        item {
                            SaveLocationSelector(
                                useCustomLocation = useCustomLocation,
                                onUseCustomLocationChange = { useCustomLocation = it }
                            )
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
                                message = "Encrypting PDF..."
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
                        ActionButton(
                            text = "Protect PDF",
                            onClick = {
                                if (useCustomLocation) {
                                    val baseName = selectedFile!!.name.removeSuffix(".pdf")
                                    savePdfLauncher.launch("${baseName}_protected.pdf")
                                } else {
                                    protectWithDefaultLocation()
                                }
                            },
                            enabled = password.isNotBlank() && password.length >= 4 && password == confirmPassword,
                            isLoading = isProcessing,
                            icon = Icons.Default.Lock
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
            title = if (resultSuccess) "Protection Added" else "Failed",
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

@Composable
private fun PermissionItem(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
