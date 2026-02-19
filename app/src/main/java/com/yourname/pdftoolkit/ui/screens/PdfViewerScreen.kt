package com.yourname.pdftoolkit.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commit
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yourname.pdftoolkit.data.SafUriManager
import kotlinx.coroutines.launch

/**
 * PDF Viewer Screen with annotation support using Jetpack PDF Library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    pdfUri: Uri?,
    pdfName: String = "PDF Document",
    onNavigateBack: () -> Unit,
    onNavigateToTool: ((String, Uri?, String?) -> Unit)? = null,
    viewModel: PdfViewerViewModel = viewModel()
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    
    // ViewModel state
    val uiState by viewModel.uiState.collectAsState()
    val toolState by viewModel.toolState.collectAsState()
    val searchState by viewModel.searchState.collectAsState()
    val saveState by viewModel.saveState.collectAsState()
    val selectedAnnotationTool by viewModel.selectedAnnotationTool.collectAsState()
    val selectedColor by viewModel.selectedColor.collectAsState()
    val annotations by viewModel.annotations.collectAsState()

    var showControls by remember { mutableStateOf(true) }
    var showColorPicker by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    // Password state
    var showPasswordDialog by remember { mutableStateOf(false) }
    var isPasswordError by remember { mutableStateOf(false) }

    // Save document launcher
    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let { outputUri ->
            if (annotations.isNotEmpty()) {
                viewModel.saveAnnotations(context.applicationContext, outputUri)
            } else {
                Toast.makeText(context, "No annotations to save", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Handle Save State
    LaunchedEffect(saveState) {
        when (val state = saveState) {
            is SaveState.Success -> {
                SafUriManager.addRecentFile(context, state.uri)
                Toast.makeText(context, "Annotations saved successfully!", Toast.LENGTH_SHORT).show()
            }
            is SaveState.Error -> {
                Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
            }
            else -> {}
        }
    }
    
    // Handle UI State errors
    val errorMessage = (uiState as? PdfViewerUiState.Error)?.message
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
             val isPasswordIssue = errorMessage.contains("password", ignoreCase = true) ||
                                     errorMessage.contains("encrypted", ignoreCase = true)
             if (isPasswordIssue) {
                 showPasswordDialog = true
                 isPasswordError = true
             }
        }
    }
    
    Scaffold(
        topBar = {
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                // TopAppBar (Simplified for brevity, keeping main actions)
                TopAppBar(
                    title = {
                        Text(
                            text = pdfName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        val isEditMode = toolState is PdfTool.Edit

                        // Edit/Annotate toggle
                        IconButton(
                            onClick = {
                                if (isEditMode) {
                                    viewModel.setTool(PdfTool.None)
                                } else {
                                    viewModel.setTool(PdfTool.Edit)
                                }
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        ) {
                            Icon(
                                if (isEditMode) Icons.Default.Check else Icons.Default.Edit,
                                contentDescription = if (isEditMode) "Done Editing" else "Edit",
                                tint = if (isEditMode) MaterialTheme.colorScheme.primary else LocalContentColor.current
                            )
                        }

                        if (isEditMode) {
                             IconButton(onClick = {
                                 viewModel.undoAnnotation()
                             }) {
                                 Icon(Icons.Default.Undo, contentDescription = "Undo")
                             }

                             if (saveState is SaveState.Saving) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                             } else {
                                IconButton(onClick = {
                                    val fileName = "annotated_${pdfName}_${System.currentTimeMillis()}.pdf"
                                    saveDocumentLauncher.launch(fileName)
                                }) {
                                    Icon(Icons.Default.Save, contentDescription = "Save", tint = MaterialTheme.colorScheme.primary)
                                }
                             }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        },
        bottomBar = {
            val isEditMode = toolState is PdfTool.Edit
            AnimatedVisibility(
                visible = isEditMode && showControls,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it }
            ) {
                AnnotationToolbar(
                    selectedTool = selectedAnnotationTool,
                    selectedColor = selectedColor,
                    onToolSelected = { viewModel.setAnnotationTool(it) },
                    onColorPickerClick = { showColorPicker = true },
                    onUndoClick = { viewModel.undoAnnotation() },
                    canUndo = annotations.isNotEmpty()
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Embed the EditablePdfViewerFragment
            if (pdfUri != null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        FragmentContainerView(ctx).apply {
                            id = View.generateViewId()
                        }
                    },
                    update = { container ->
                        val fragmentManager = (context as? FragmentActivity)?.supportFragmentManager
                        if (fragmentManager != null) {
                            // CRITICAL FIX: Use unique tag per PDF to force fresh fragment creation
                            // This prevents fragment reuse issues with the alpha androidx.pdf library
                            val tag = "pdf_viewer_${pdfUri.hashCode()}_${System.currentTimeMillis()}"
                            
                            // Always remove any existing fragments to ensure clean state
                            fragmentManager.fragments.filterIsInstance<EditablePdfViewerFragment>().forEach { oldFragment ->
                                fragmentManager.commit {
                                    remove(oldFragment)
                                }
                            }
                            
                            // Create fresh fragment with PDF URI set BEFORE adding to manager
                            val fragment = EditablePdfViewerFragment().apply {
                                // CRITICAL: Set documentUri before fragment is added
                                loadPdf(pdfUri)
                            }
                            
                            // Add the new fragment
                            fragmentManager.commit {
                                replace(container.id, fragment, tag)
                            }
                            
                            // Update fragment state (annotations, tools, colors)
                            val tool = if (toolState is PdfTool.Edit) selectedAnnotationTool else AnnotationTool.NONE
                            fragment.setAnnotationMode(tool)
                            fragment.setAnnotationColor(selectedColor.toArgb())
                            fragment.setAnnotations(annotations)
                            fragment.setOnAnnotationAddedListener { stroke ->
                                viewModel.addAnnotation(stroke)
                            }
                        }
                    }
                )
            } else {
                LoadingState()
            }

            // Overlays (Save blocking, etc)
            val currentSaveState = saveState
            if (currentSaveState is SaveState.Saving) {
                 BackHandler(enabled = true) {}
                 Box(
                     modifier = Modifier
                         .fillMaxSize()
                         .background(Color.Black.copy(alpha = 0.5f))
                         .clickable(enabled = false) {},
                     contentAlignment = Alignment.Center
                 ) {
                     CircularProgressIndicator()
                 }
            }
        }
    }

    // Dialogs
    if (showColorPicker) {
        ColorPickerDialog(
            selectedColor = selectedColor,
            onColorSelected = { 
                viewModel.setColor(it)
                showColorPicker = false
            },
            onDismiss = { showColorPicker = false }
        )
    }
    
    if (showPasswordDialog) {
        PasswordDialog(
            onConfirm = { input ->
                showPasswordDialog = false
                if (pdfUri != null) {
                    viewModel.loadPdf(context.applicationContext, pdfUri, input)
                }
            },
            onDismiss = { 
                showPasswordDialog = false
                onNavigateBack()
            },
            isError = isPasswordError
        )
    }
}

@Composable
private fun AnnotationToolbar(
    selectedTool: AnnotationTool,
    selectedColor: Color,
    onToolSelected: (AnnotationTool) -> Unit,
    onColorPickerClick: () -> Unit,
    onUndoClick: () -> Unit,
    canUndo: Boolean
) {
    val haptic = LocalHapticFeedback.current

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolButton(
                icon = Icons.Default.PanTool,
                label = "Pan",
                isSelected = selectedTool == AnnotationTool.NONE,
                onClick = {
                    onToolSelected(AnnotationTool.NONE)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            )
            ToolButton(
                icon = Icons.Default.Highlight,
                label = "Highlight",
                isSelected = selectedTool == AnnotationTool.HIGHLIGHTER,
                onClick = {
                    onToolSelected(AnnotationTool.HIGHLIGHTER)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            )
            ToolButton(
                icon = Icons.Default.Gesture,
                label = "Marker",
                isSelected = selectedTool == AnnotationTool.MARKER,
                onClick = {
                    onToolSelected(AnnotationTool.MARKER)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            )
            ToolButton(
                icon = Icons.Default.FormatUnderlined,
                label = "Underline",
                isSelected = selectedTool == AnnotationTool.UNDERLINE,
                onClick = {
                    onToolSelected(AnnotationTool.UNDERLINE)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            )
            IconButton(onClick = onColorPickerClick) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(selectedColor)
                        .padding(2.dp)
                )
            }
            IconButton(
                onClick = onUndoClick,
                enabled = canUndo
            ) {
                Icon(
                    Icons.Default.Undo,
                    contentDescription = "Undo",
                    tint = if (canUndo) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.38f)
                )
            }
        }
    }
}

@Composable
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primaryContainer 
                    else Color.Transparent
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ColorPickerDialog(
    selectedColor: Color,
    onColorSelected: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = listOf(
        Color.Yellow to "Yellow",
        Color.Green to "Green",
        Color.Cyan to "Cyan",
        Color.Magenta to "Pink",
        Color.Red to "Red",
        Color.Blue to "Blue",
        Color(0xFF614700) to "Brown",
        Color.Black to "Black"
    )
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Color") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    colors.take(4).forEach { (color, name) ->
                        ColorOption(
                            color = color,
                            name = name,
                            isSelected = selectedColor == color,
                            onClick = { onColorSelected(color) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    colors.drop(4).forEach { (color, name) ->
                        ColorOption(
                            color = color,
                            name = name,
                            isSelected = selectedColor == color,
                            onClick = { onColorSelected(color) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ColorOption(
    color: Color,
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape),
            color = color,
            onClick = onClick,
            shape = CircleShape,
            border = if (isSelected) {
                ButtonDefaults.outlinedButtonBorder
            } else null
        ) {
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    modifier = Modifier.padding(12.dp),
                    tint = Color.Black.copy(alpha = 0.7f)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun LoadingState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            strokeWidth = 4.dp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Loading PDF...",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorState(
    message: String,
    onGoBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Unable to open PDF",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onGoBack) {
            Text("Go Back")
        }
    }
}

@Composable
private fun PasswordDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    isError: Boolean = false
) {
    var password by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Password Required") },
        text = {
            Column {
                if (isError) {
                    Text(
                        text = "Incorrect password. Please try again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(
                    text = "This PDF is password protected. Please enter the password to open it.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password) },
                enabled = password.isNotEmpty()
            ) {
                Text("Open")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun sharePdf(context: Context, pdfUri: Uri) {
    try {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, pdfUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "Share PDF")
        context.startActivity(chooser)
    } catch (e: Exception) {
        Toast.makeText(context, "Unable to share PDF", Toast.LENGTH_SHORT).show()
    }
}

private fun openWithExternalApp(context: Context, pdfUri: Uri) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(pdfUri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Open with")
        context.startActivity(chooser)
    } catch (e: Exception) {
        Toast.makeText(context, "No app found to open PDF", Toast.LENGTH_SHORT).show()
    }
}
