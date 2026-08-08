package com.yourname.pdftoolkit.ui.screens
import com.yourname.pdftoolkit.util.safeLaunch

import com.yourname.pdftoolkit.R

import androidx.compose.ui.res.stringResource

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yourname.pdftoolkit.data.SafUriManager
import com.yourname.pdftoolkit.util.PrintUtils
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import com.tom_roush.pdfbox.text.TextPosition
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag


/**
 * PDF Viewer Screen with annotation support.
 * Supports zoom, scroll, page navigation, highlighting, and marking.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    pdfUri: Uri?,
    pdfName: String = stringResource(R.string.pdf_document_default_name),
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

    // Stroke width configurations
    val highlighterWidth by viewModel.highlighterWidth.collectAsState()
    val markerWidth by viewModel.markerWidth.collectAsState()
    val underlineWidth by viewModel.underlineWidth.collectAsState()
    val eraserWidth by viewModel.eraserWidth.collectAsState()
    var showThicknessSlider by remember { mutableStateOf(false) }

    // Text selection state
    var selectPageIndex by remember { mutableIntStateOf(-1) }
    var selectStartCharIndex by remember { mutableIntStateOf(-1) }
    var selectEndCharIndex by remember { mutableIntStateOf(-1) }

    // Clear selection if active tool or selected tool changes
    LaunchedEffect(toolState, selectedAnnotationTool) {
        if (toolState !is PdfTool.None || selectedAnnotationTool != AnnotationTool.NONE) {
            selectPageIndex = -1
            selectStartCharIndex = -1
            selectEndCharIndex = -1
        }
    }

    // Auto-hide thickness slider when annotation tool is NONE
    LaunchedEffect(selectedAnnotationTool) {
        if (selectedAnnotationTool == AnnotationTool.NONE) {
            showThicknessSlider = false
        }
    }

    // Handle back button for interactive tools
    BackHandler(enabled = toolState !is PdfTool.None) {
        if (toolState is PdfTool.Search) {
            viewModel.clearSearch()
        }
        viewModel.setTool(PdfTool.None)
    }

    // Local UI state
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var showControls by remember { mutableStateOf(true) }
    var showPageSelector by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    // Ensure controls are visible when tool changes
    LaunchedEffect(toolState) {
        showControls = true
    }

    // Password state
    var showPasswordDialog by remember { mutableStateOf(false) }
    var isPasswordError by remember { mutableStateOf(false) }
    var pdfLoadTrigger by remember { mutableStateOf(0) } // To force reload
    
    // Annotation drawing state (transient)
    var currentStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var currentDrawingPageIndex by remember { mutableIntStateOf(-1) }
    var showColorPicker by remember { mutableStateOf(false) }
    
    // Save document launcher
    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let { outputUri ->
            if (annotations.isNotEmpty()) {
                viewModel.saveAnnotations(context.applicationContext, outputUri)
            }
        }
    }
    
    val listState = rememberLazyListState()
    
    // Track visible page based on scroll position using derivedStateOf to prevent excessive recompositions
    val currentPage by remember(listState) {
        androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex + 1 }
    }

    // Scroll-driven toolbar visibility
    val nestedScrollConnection = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource
            ): Offset {
                if (toolState is PdfTool.None && scale <= 1f) {
                    if (available.y < -10f) {
                        showControls = false
                    } else if (available.y > 10f) {
                        showControls = true
                    }
                }
                return Offset.Zero
            }
        }
    }

    // Show floating page indicator when scrolling
    var showPageIndicator by remember { mutableStateOf(false) }
    LaunchedEffect(listState.isScrollInProgress, currentPage) {
        if (listState.isScrollInProgress) {
            showPageIndicator = true
        } else if (showPageIndicator) {
            kotlinx.coroutines.delay(1500)
            showPageIndicator = false
        }
    }

    // Handle Save State
    LaunchedEffect(saveState) {
        when (val state = saveState) {
            is SaveState.Success -> {
                SafUriManager.addRecentFile(context, state.uri)
                Toast.makeText(context, context.getString(R.string.pdf_annotations_saved), Toast.LENGTH_SHORT).show()
            }
            is SaveState.Error -> {
                Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
            }
            else -> {}
        }
    }

    // Auto-scroll to search result
    LaunchedEffect(searchState.currentMatchIndex, searchState.matches) {
        if (searchState.matches.isNotEmpty()) {
            val match = searchState.matches.getOrNull(searchState.currentMatchIndex)
            if (match != null) {
                listState.animateScrollToItem(match.pageIndex)
            }
        }
    }

    // Load PDF when screen opens or password/trigger changes
    LaunchedEffect(pdfUri, pdfLoadTrigger) {
        if (pdfUri != null) {
            // Check URI permissions first
            if (!SafUriManager.canAccessUri(context, pdfUri)) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        pdfUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    Log.w("PdfViewerScreen", "Failed to take persistable permission: ${e.message}")
                }
            }
            
             viewModel.loadPdf(context.applicationContext, pdfUri, "")
        }
    }
    
    // Handle UI State
    val errorMessage = (uiState as? PdfViewerUiState.Error)?.message
    val totalPages = (uiState as? PdfViewerUiState.Loaded)?.totalPages ?: 0

    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
             val isPasswordIssue = errorMessage.contains("password", ignoreCase = true) ||
                                     errorMessage.contains("encrypted", ignoreCase = true)
             if (isPasswordIssue) {
                 showPasswordDialog = true
                 isPasswordError = true // Assume error if we are here
             }
        }
    }
    
    Scaffold(
        modifier = Modifier.nestedScroll(nestedScrollConnection),
        topBar = {
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                if (toolState is PdfTool.Search) {
                    // Search mode top bar
                    TopAppBar(
                        title = {
                            OutlinedTextField(
                                value = searchState.query,
                                onValueChange = { viewModel.search(it) },
                                placeholder = { Text(stringResource(R.string.pdf_search_placeholder)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent
                                ),
                                trailingIcon = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (searchState.isLoading) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp
                                            )
                                            IconButton(
                                                onClick = { viewModel.stopSearch() },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Stop,
                                                    contentDescription = stringResource(R.string.cd_stop_search),
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        if (searchState.query.isNotEmpty()) {
                                            Text(
                                                text = if (searchState.matches.isNotEmpty())
                                                    "${searchState.currentMatchIndex + 1}/${searchState.matches.size}"
                                                else if (!searchState.isLoading && searchState.query.length >= 2) stringResource(R.string.pdf_search_no_matches) else "",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (searchState.matches.isNotEmpty())
                                                    MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { 
                                viewModel.clearSearch()
                                viewModel.setTool(PdfTool.None)
                            }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cd_close_search))
                            }
                        },
                        actions = {
                            // Navigate search results
                            if (searchState.matches.isNotEmpty()) {
                                IconButton(onClick = { viewModel.prevMatch() }) {
                                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.action_previous))
                                }
                                IconButton(onClick = { viewModel.nextMatch() }) {
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.action_next))
                                }
                            }
                            if (searchState.query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.search("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.cd_clear_search))
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                } else {
                    // Normal top bar
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = pdfName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onNavigateBack) {
                                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_back))
                            }
                        },
                        actions = {
                            // Search button
                            IconButton(onClick = {
                                viewModel.setTool(PdfTool.Search)
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }) {
                                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.pdf_search))
                            }
                            
                            val isEditMode = toolState is PdfTool.Edit

                            // Save annotations button (only in edit mode with annotations)
                            if (isEditMode && annotations.isNotEmpty()) {
                                if (saveState is SaveState.Saving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    IconButton(
                                        onClick = {
                                            val fileName = "annotated_${pdfName}_${System.currentTimeMillis()}.pdf"
                                            saveDocumentLauncher.safeLaunch(fileName, context)
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Save,
                                            contentDescription = stringResource(R.string.cd_save_annotations),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                            
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
                                    contentDescription = if (isEditMode) stringResource(R.string.cd_done_editing) else stringResource(R.string.cd_edit),
                                    tint = if (isEditMode) MaterialTheme.colorScheme.primary else LocalContentColor.current
                                )
                            }
                            
                        // More options menu
                        Box {
                            var showMenu by remember { mutableStateOf(false) }
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
                            }
                            DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            if (pdfUri != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.pdf_share)) },
                                    leadingIcon = { Icon(Icons.Default.Share, null) },
                                    onClick = {
                                        showMenu = false
                                        sharePdf(context, pdfUri)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.pdf_open_with)) },
                                    leadingIcon = { Icon(Icons.Default.OpenInNew, null) },
                                    onClick = {
                                        showMenu = false
                                        openWithExternalApp(context, pdfUri)
                                    }
                                )
                                Divider()
                            }
                            if (totalPages > 1) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.pdf_go_to_page)) },
                                    leadingIcon = { Icon(Icons.Default.ViewList, null) },
                                    onClick = {
                                        showMenu = false
                                        showPageSelector = true
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.pdf_reset_zoom)) },
                                leadingIcon = { Icon(Icons.Default.FitScreen, null) },
                                onClick = {
                                    showMenu = false
                                    scale = 1f
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                            )
                            if (pdfUri != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.pdf_print)) },
                                    leadingIcon = { Icon(Icons.Default.Print, null) },
                                    onClick = {
                                        showMenu = false
                                        PrintUtils.printPdf(context, pdfUri, pdfName)
                                    }
                                )
                            }
                            if (annotations.isNotEmpty()) {
                                Divider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.pdf_clear_all_annotations)) },
                                    leadingIcon = { Icon(Icons.Default.ClearAll, null) },
                                    onClick = {
                                        showMenu = false
                                        showClearDialog = true
                                    }
                                )
                            }
                            Divider()
                            // Tools navigation
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.pdf_compress_this)) },
                                leadingIcon = { Icon(Icons.Default.Compress, null) },
                                onClick = {
                                    showMenu = false
                                    onNavigateToTool?.invoke("compress", pdfUri, pdfName)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.pdf_add_watermark_option)) },
                                leadingIcon = { Icon(Icons.Default.WaterDrop, null) },
                                onClick = {
                                    showMenu = false
                                    onNavigateToTool?.invoke("watermark", pdfUri, pdfName)
                                }
                            )
                        }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                    )
                )
                } // end else (normal top bar)
            }
        },
        bottomBar = {
            Column {
                val isEditMode = toolState is PdfTool.Edit

                // Brush size selection slider
                AnimatedVisibility(
                    visible = isEditMode && showControls && showThicknessSlider && selectedAnnotationTool != AnnotationTool.NONE,
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it }
                ) {
                    ThicknessSliderPanel(
                        tool = selectedAnnotationTool,
                        color = selectedColor,
                        highlighterWidth = highlighterWidth,
                        markerWidth = markerWidth,
                        underlineWidth = underlineWidth,
                        eraserWidth = eraserWidth,
                        onHighlighterWidthChange = { viewModel.setHighlighterWidth(it) },
                        onMarkerWidthChange = { viewModel.setMarkerWidth(it) },
                        onUnderlineWidthChange = { viewModel.setUnderlineWidth(it) },
                        onEraserWidthChange = { viewModel.setEraserWidth(it) }
                    )
                }

                // Annotation toolbar
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
                        canUndo = annotations.isNotEmpty(),
                        onBrushSizeClick = { showThicknessSlider = !showThicknessSlider }
                    )
                }
                
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
                .testTag("PdfPagesContent")
                .pointerInput(toolState, selectedAnnotationTool, scale, offsetX, offsetY, viewportSize) {
                    val isDrawing = toolState is PdfTool.Edit && selectedAnnotationTool != AnnotationTool.NONE

                    if (!isDrawing) {
                        detectTapGestures(
                            onTap = {
                                if (toolState is PdfTool.None) {
                                    showControls = !showControls
                                }
                            },
                            onDoubleTap = { tapOffset ->
                                val newScale = if (scale >= 2f) 1f else 2.5f

                                if (newScale > 1f) {
                                    val centerX = viewportSize.width / 2f
                                    val focusX = tapOffset.x - centerX
                                    val newOffsetX = (-focusX * (newScale - 1f))
                                        .coerceIn(
                                            -((viewportSize.width * newScale - viewportSize.width) / 2f),
                                            (viewportSize.width * newScale - viewportSize.width) / 2f
                                        )
                                    offsetX = newOffsetX
                                    offsetY = 0f
                                } else {
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                                scale = newScale
                            }
                        )
                    }
                }
        ) {
            when (uiState) {
                is PdfViewerUiState.Loading -> {
                    LoadingState()
                }
                
                is PdfViewerUiState.Error -> {
                    // Handled by side effect, but show basic error here if not password
                    if (isPasswordError) {
                        // Password dialog will show
                        LoadingState() // Keep showing loading/clean state behind dialog
                    } else {
                        ErrorState(
                            message = (uiState as PdfViewerUiState.Error).message,
                            onGoBack = onNavigateBack
                        )
                    }
                }
                
                is PdfViewerUiState.Loaded -> {
                    val isEditMode = toolState is PdfTool.Edit
                    PdfPagesContent(
                        totalPages = totalPages,
                        currentPage = currentPage,
                        loadPage = { viewModel.loadPage(it) },
                        getPageState = { viewModel.getPageState(it) },
                        onRetryPage = { viewModel.retryPage(it) },
                        onReleasePage = { viewModel.releasePage(it) },
                        scale = scale,
                        onScaleChange = { scale = it },
                        offsetX = offsetX,
                        onOffsetChange = { x, y ->
                            offsetX = x
                            offsetY = y
                        },
                        listState = listState,
                        isEditMode = isEditMode,
                        selectedTool = selectedAnnotationTool,
                        selectedColor = selectedColor,
                        annotations = annotations,
                        currentStroke = currentStroke,
                        onCurrentStrokeChange = { currentStroke = it },
                        onAddAnnotation = { stroke ->
                            viewModel.addAnnotation(stroke)
                            currentStroke = emptyList()
                        },
                        currentDrawingPageIndex = currentDrawingPageIndex,
                        onDrawingPageIndexChange = { currentDrawingPageIndex = it },
                        // Pass search state
                        searchState = searchState,
                        onViewportSizeChange = { viewportSize = it },
                        highlighterWidth = highlighterWidth,
                        markerWidth = markerWidth,
                        underlineWidth = underlineWidth,
                        eraserWidth = eraserWidth,
                        selectPageIndex = selectPageIndex,
                        selectStartCharIndex = selectStartCharIndex,
                        selectEndCharIndex = selectEndCharIndex,
                        onSelectionChange = { pIdx, start, end ->
                            selectPageIndex = pIdx
                            selectStartCharIndex = start
                            selectEndCharIndex = end
                        },
                        viewModel = viewModel
                    )
                }

                PdfViewerUiState.Idle -> {
                    // Initial state
                }
            }

            // Floating Page Indicator
            AnimatedVisibility(
                visible = showPageIndicator && totalPages > 0,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .navigationBarsPadding()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(16.dp),
                    shadowElevation = 4.dp
                ) {
                    Text(
                        text = "$currentPage / $totalPages",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            // Save Blocking Overlay
            val currentSaveState = saveState
            if (currentSaveState is SaveState.Saving) {
                 BackHandler(enabled = true) {
                     // Prevent back navigation while saving
                 }
                 Box(
                     modifier = Modifier
                         .fillMaxSize()
                         .background(Color.Black.copy(alpha = 0.5f))
                         .clickable(enabled = false) {},
                     contentAlignment = Alignment.Center
                 ) {
                     Card(
                         modifier = Modifier.padding(32.dp),
                         colors = CardDefaults.cardColors(
                             containerColor = MaterialTheme.colorScheme.surface
                         )
                     ) {
                         Column(
                             modifier = Modifier.padding(24.dp),
                             horizontalAlignment = Alignment.CenterHorizontally
                         ) {
                             LinearProgressIndicator(
                                 progress = currentSaveState.progress,
                                 modifier = Modifier.fillMaxWidth(0.7f)
                             )
                             Spacer(modifier = Modifier.height(16.dp))
                             Text(
                                 text = stringResource(R.string.pdf_saving_annotations, (currentSaveState.progress * 100).toInt()),
                                 style = MaterialTheme.typography.titleMedium
                             )
                         }
                     }
                 }
            }
        }
    }

    // Clear Annotations Dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.pdf_clear_all_dialog_title)) },
            text = { Text(stringResource(R.string.pdf_clear_annotations_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAnnotations()
                        showClearDialog = false
                    }
                ) {
                    Text(stringResource(R.string.action_clear_all), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    
    // Page selector dialog
    if (showPageSelector) {
        PageSelectorDialog(
            currentPage = currentPage,
            totalPages = totalPages,
            onPageSelected = { page ->
                scope.launch { listState.animateScrollToItem(page - 1) }
                showPageSelector = false
            },
            onDismiss = { showPageSelector = false }
        )
    }
    
    // Color picker dialog
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
    
    // Password dialog
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
                onNavigateBack() // Close viewer if cancelled
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
    canUndo: Boolean,
    onBrushSizeClick: () -> Unit
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
                label = stringResource(R.string.pdf_tool_pan),
                isSelected = selectedTool == AnnotationTool.NONE,
                onClick = {
                    onToolSelected(AnnotationTool.NONE)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            )
            ToolButton(
                icon = Icons.Default.Highlight,
                label = stringResource(R.string.pdf_highlighter),
                isSelected = selectedTool == AnnotationTool.HIGHLIGHTER,
                onClick = {
                    onToolSelected(AnnotationTool.HIGHLIGHTER)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            )
            ToolButton(
                icon = Icons.Default.Gesture,
                label = stringResource(R.string.pdf_marker),
                isSelected = selectedTool == AnnotationTool.MARKER,
                onClick = {
                    onToolSelected(AnnotationTool.MARKER)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            )
            ToolButton(
                icon = Icons.Default.FormatUnderlined,
                label = stringResource(R.string.pdf_underline),
                isSelected = selectedTool == AnnotationTool.UNDERLINE,
                onClick = {
                    onToolSelected(AnnotationTool.UNDERLINE)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            )
            ToolButton(
                icon = Icons.Default.AutoFixHigh,
                label = stringResource(R.string.pdf_tool_eraser),
                isSelected = selectedTool == AnnotationTool.ERASER,
                onClick = {
                    onToolSelected(AnnotationTool.ERASER)
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
                onClick = onBrushSizeClick,
                enabled = selectedTool != AnnotationTool.NONE
            ) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = stringResource(R.string.pdf_brush_size),
                    tint = if (selectedTool != AnnotationTool.NONE) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.38f)
                )
            }
            IconButton(
                onClick = onUndoClick,
                enabled = canUndo
            ) {
                Icon(
                    Icons.Default.Undo,
                    contentDescription = stringResource(R.string.pdf_undo),
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
        Color.Yellow to stringResource(R.string.color_yellow),
        Color.Green to stringResource(R.string.color_green),
        Color.Cyan to stringResource(R.string.color_cyan),
        Color.Magenta to stringResource(R.string.color_pink),
        Color.Red to stringResource(R.string.color_red),
        Color.Blue to stringResource(R.string.color_blue),
        Color(0xFF614700) to stringResource(R.string.color_brown),
        Color.Black to stringResource(R.string.color_black)
    )
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_select_color)) },
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
                Text(stringResource(R.string.action_cancel))
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
                    contentDescription = stringResource(R.string.cd_selected),
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
private fun LoadingState(totalPages: Int? = null) {
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
            text = if (totalPages != null && totalPages > 0) {
                stringResource(R.string.pdf_opening_pages, totalPages)
            } else {
                stringResource(R.string.pdf_opening)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (totalPages != null && totalPages > 50) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.pdf_large_file_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
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
            text = stringResource(R.string.pdf_unable_to_open),
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
            Text(stringResource(R.string.action_go_back))
        }
    }
}

/**
 * PDF Pages Content with smooth zoom and pan.
 * 
 * Uses LazyColumn with beyondBoundsLayout to preload pages outside viewport.
 * This ensures pages are available when panning while zoomed.
 */
@Composable
private fun PdfPagesContent(
    totalPages: Int,
    currentPage: Int,
    loadPage: suspend (Int) -> Bitmap?,
    getPageState: (Int) -> PdfViewerViewModel.PageRenderState,
    onRetryPage: (Int) -> Unit,
    onReleasePage: (Int) -> Unit,
    scale: Float,
    onScaleChange: (Float) -> Unit,
    offsetX: Float,
    onOffsetChange: (Float, Float) -> Unit,
    listState: LazyListState,
    isEditMode: Boolean,
    selectedTool: AnnotationTool,
    selectedColor: Color,
    annotations: List<AnnotationStroke>,
    currentStroke: List<Offset>,
    onCurrentStrokeChange: (List<Offset>) -> Unit,
    onAddAnnotation: (AnnotationStroke) -> Unit,
    currentDrawingPageIndex: Int,
    onDrawingPageIndexChange: (Int) -> Unit,
    // Search params
    searchState: SearchState,
    onViewportSizeChange: (IntSize) -> Unit,
    
    // New parameters
    highlighterWidth: Float,
    markerWidth: Float,
    underlineWidth: Float,
    eraserWidth: Float,
    selectPageIndex: Int,
    selectStartCharIndex: Int,
    selectEndCharIndex: Int,
    onSelectionChange: (Int, Int, Int) -> Unit,
    viewModel: PdfViewerViewModel
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var pageSize by remember { mutableStateOf(IntSize.Zero) }

    // Use rememberUpdatedState to get latest values inside pointerInput without restarting
    val currentScale by rememberUpdatedState(scale)
    val currentOffsetX by rememberUpdatedState(offsetX)
    val currentOnScaleChange by rememberUpdatedState(onScaleChange)
    val currentOnOffsetChange by rememberUpdatedState(onOffsetChange)
    val currentContainerSize by rememberUpdatedState(containerSize)

    // Wrapper box to clip the scaled content so it doesn't bleed outside container bounds
    Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged {
                containerSize = it
                onViewportSizeChange(it)
            }
            .then(
                if (isEditMode && selectedTool != AnnotationTool.NONE) {
                    Modifier // No gesture handling when drawing
                } else {
                    // Issue 2 Fix: Custom gesture handler using awaitEachGesture
                    // Key is Unit so it never restarts. Use rememberUpdatedState for all state access.
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)

                            do {
                                val event = awaitPointerEvent()
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()

                                val newScale = (currentScale * zoomChange).coerceIn(1f, 5f)

                                val containerWidth = currentContainerSize.width.toFloat()
                                val scaledContentWidth = containerWidth * newScale
                                val maxOffsetX = ((scaledContentWidth - containerWidth) / 2f).coerceAtLeast(0f)

                                currentOnScaleChange(newScale)

                                if (newScale > 1f) {
                                    val newOffsetX = (currentOffsetX + panChange.x)
                                        .coerceIn(-maxOffsetX, maxOffsetX)
                                    currentOnOffsetChange(newOffsetX, 0f)
                                    if (panChange.y != 0f) {
                                        listState.dispatchRawDelta(-panChange.y)
                                    }
                                } else {
                                    currentOnOffsetChange(0f, 0f)
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                }
            )
    ) {
        val density = LocalDensity.current
        val extraBottomPaddingDp = remember(scale, containerSize.height) {
            if (scale > 1f && containerSize.height > 0) {
                with(density) {
                    (containerSize.height.toFloat() * ((scale - 1f) / scale)).toDp()
                }
            } else {
                0.dp
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = 0f
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f) // Top-center anchor
                }
        ) {
            LazyColumn(
                state = listState,
                // Keep vertical scrolling owned by LazyColumn so zoomed pages never pan into blank viewport space.
                userScrollEnabled = (!isEditMode || selectedTool == AnnotationTool.NONE) && scale <= 1f,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp + extraBottomPaddingDp)
            ) {
                items(
                    count = totalPages,
                    key = { it }
                ) { index ->
                    val pageMatches = remember(searchState.matches, index) {
                        searchState.matches.filter { it.pageIndex == index }
                    }
                    val currentMatchIndexOnPage = remember(searchState.currentMatchIndex, searchState.matches, pageMatches, index) {
                        val currentGlobalResult = searchState.matches.getOrNull(searchState.currentMatchIndex)
                        if (currentGlobalResult != null && currentGlobalResult.pageIndex == index) {
                            pageMatches.indexOf(currentGlobalResult)
                        } else {
                            -1
                        }
                    }
                    val pageAnnotations = remember(annotations, index) {
                        annotations.filter { it.pageIndex == index }
                    }

                    PdfPageWithAnnotations(
                        pageIndex = index,
                        loadPage = loadPage,
                        isEditMode = isEditMode,
                        selectedTool = selectedTool,
                        selectedColor = selectedColor,
                        annotations = pageAnnotations,
                        currentStroke = if (currentDrawingPageIndex == index) currentStroke else emptyList(),
                        onCurrentStrokeChange = { stroke ->
                            onDrawingPageIndexChange(index)
                            onCurrentStrokeChange(stroke)
                        },
                        onAddAnnotation = onAddAnnotation,
                        pageMatches = pageMatches,
                        currentMatchIndexOnPage = currentMatchIndexOnPage,
                        onPageSizeChanged = { size ->
                            // Track size of the currently visible page for accurate pan bounds
                            // currentPage is 1-indexed, index is 0-indexed
                            if (index == currentPage - 1 && size.width > 0 && size.height > 0) {
                                pageSize = size
                            }
                        },
                        pageState = getPageState(index),
                        onRetry = onRetryPage,
                        onRelease = onReleasePage,
                        highlighterWidth = highlighterWidth,
                        markerWidth = markerWidth,
                        underlineWidth = underlineWidth,
                        eraserWidth = eraserWidth,
                        selectPageIndex = selectPageIndex,
                        selectStartCharIndex = selectStartCharIndex,
                        selectEndCharIndex = selectEndCharIndex,
                        onSelectionChange = onSelectionChange,
                        viewModel = viewModel,
                        listState = listState
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun PdfPageWithAnnotations(
    pageIndex: Int,
    loadPage: suspend (Int) -> Bitmap?,
    isEditMode: Boolean,
    selectedTool: AnnotationTool,
    selectedColor: Color,
    annotations: List<AnnotationStroke>,
    currentStroke: List<Offset>,
    onCurrentStrokeChange: (List<Offset>) -> Unit,
    onAddAnnotation: (AnnotationStroke) -> Unit,
    // Search params
    pageMatches: List<SearchMatch>,
    currentMatchIndexOnPage: Int,
    // Page size callback for zoom/pan bounds
    onPageSizeChanged: ((IntSize) -> Unit)? = null,
    // Page state for error handling
    pageState: PdfViewerViewModel.PageRenderState = PdfViewerViewModel.PageRenderState.Idle,
    onRetry: (Int) -> Unit = {},
    onRelease: (Int) -> Unit = {},
    
    // New parameters
    highlighterWidth: Float,
    markerWidth: Float,
    underlineWidth: Float,
    eraserWidth: Float,
    selectPageIndex: Int,
    selectStartCharIndex: Int,
    selectEndCharIndex: Int,
    onSelectionChange: (Int, Int, Int) -> Unit,
    viewModel: PdfViewerViewModel,
    listState: LazyListState
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // Local copy of page text data loaded when selected or long pressed
    var pageTextData by remember { mutableStateOf<PageTextData?>(null) }

    LaunchedEffect(selectPageIndex) {
        if (selectPageIndex != pageIndex) {
            pageTextData = null
        }
    }

    // Load bitmap lazily
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = pageIndex) {
        value = loadPage(pageIndex)
    }

    DisposableEffect(pageIndex) {
        onDispose {
            onRelease(pageIndex)
        }
    }

    // Shimmer animation for loading state
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    )
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .shadow(
                elevation = 2.dp,
                shape = RectangleShape,
                clip = false
            )
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged {
                    size = it
                    onPageSizeChanged?.invoke(it)
                }
                .heightIn(min = 200.dp)
                .then(
                    if ((!isEditMode || selectedTool == AnnotationTool.NONE) && bitmap != null) {
                        Modifier.pointerInput(pageIndex, bitmap, size) {
                            detectTapGestures(
                                onTap = {
                                    if (selectPageIndex != -1) {
                                        onSelectionChange(-1, -1, -1)
                                    }
                                },
                                onLongPress = { touchOffset ->
                                    if (listState.isScrollInProgress) return@detectTapGestures
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    scope.launch {
                                        val textData = viewModel.getPageText(pageIndex)
                                        if (textData != null && textData.positions.isNotEmpty()) {
                                            pageTextData = textData
                                            val scaleX = size.width.toFloat() / bitmap!!.width.toFloat()
                                            val scaleY = size.height.toFloat() / bitmap!!.height.toFloat()
                                            val closest = findClosestCharIndex(touchOffset.x, touchOffset.y, textData.positions, scaleX, scaleY)
                                            if (closest != -1) {
                                                val bounds = findWordBounds(closest, textData.text, textData.positions)
                                                onSelectionChange(pageIndex, bounds.first, bounds.second)
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    } else Modifier
                )
        ) {
            when {
                bitmap != null -> {
                    // PDF page image
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = stringResource(R.string.cd_page_number, pageIndex + 1),
                        modifier = Modifier
                            .fillMaxWidth(),
                        contentScale = ContentScale.FillWidth
                    )
                }
                pageState is PdfViewerViewModel.PageRenderState.Error -> {
                    // Error state with retry button
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f / 1.414f)
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.pdf_failed_to_render_page, pageIndex + 1),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { onRetry(pageIndex) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
                else -> {
                    // Loading shimmer skeleton
                    val brush = Brush.linearGradient(
                        colors = shimmerColors,
                        start = Offset(translateAnim - 200f, 0f),
                        end = Offset(translateAnim, 0f)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f / 1.414f)
                            .background(brush)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(32.dp)
                                .align(Alignment.Center),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
            
            // Search Highlights Overlay
            if (pageMatches.isNotEmpty() && bitmap != null) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    pageMatches.forEachIndexed { index, match ->
                        val color = if (index == currentMatchIndexOnPage) {
                            Color(0xFFFF8C00).copy(alpha = 0.5f)
                        } else {
                            Color.Yellow.copy(alpha = 0.4f)
                        }
                        
                        match.rects.forEach { rect ->
                            val scaleX = size.width.toFloat() / bitmap!!.width.toFloat()
                            val scaleY = size.height.toFloat() / bitmap!!.height.toFloat()
                            
                            drawRect(
                                color = color,
                                topLeft = Offset(rect.left * scaleX, rect.top * scaleY),
                                size = androidx.compose.ui.geometry.Size(
                                    width = (rect.width()) * scaleX,
                                    height = (rect.height()) * scaleY
                                )
                            )
                        }
                    }
                }
            }



            // Text Selection Overlay
            val currentPositions = pageTextData?.positions
            if (selectPageIndex == pageIndex && selectStartCharIndex >= 0 && currentPositions != null && 
                selectStartCharIndex < currentPositions.size && selectEndCharIndex > selectStartCharIndex && 
                selectEndCharIndex <= currentPositions.size && bitmap != null) {
                
                val scaleX = size.width.toFloat() / bitmap!!.width.toFloat()
                val scaleY = size.height.toFloat() / bitmap!!.height.toFloat()
                
                val selectedPositions = currentPositions.subList(selectStartCharIndex, selectEndCharIndex)
                val lines = mutableListOf<MutableList<TextPosition>>()
                selectedPositions.forEach { tp ->
                    val matchingLine = lines.find { Math.abs(it.first().yDirAdj - tp.yDirAdj) < 4f }
                    if (matchingLine != null) {
                        matchingLine.add(tp)
                    } else {
                        lines.add(mutableListOf(tp))
                    }
                }
                
                val rects = lines.map { line ->
                    val minLeft = line.minOf { it.xDirAdj }
                    val maxRight = line.maxOf { it.xDirAdj + it.widthDirAdj }
                    val minTop = line.minOf { it.yDirAdj - it.heightDir }
                    val maxBottom = line.maxOf { it.yDirAdj + it.heightDir * 0.2f }
                    RectF(
                        minLeft * PdfViewerViewModel.RENDER_SCALE * scaleX,
                        minTop * PdfViewerViewModel.RENDER_SCALE * scaleY,
                        maxRight * PdfViewerViewModel.RENDER_SCALE * scaleX,
                        maxBottom * PdfViewerViewModel.RENDER_SCALE * scaleY
                    )
                }
                
                val firstChar = currentPositions[selectStartCharIndex]
                val lastChar = currentPositions[selectEndCharIndex - 1]
                val handleStartX = firstChar.xDirAdj * PdfViewerViewModel.RENDER_SCALE * scaleX
                val handleStartY = firstChar.yDirAdj * PdfViewerViewModel.RENDER_SCALE * scaleY
                val handleEndX = (lastChar.xDirAdj + lastChar.widthDirAdj) * PdfViewerViewModel.RENDER_SCALE * scaleX
                val handleEndY = lastChar.yDirAdj * PdfViewerViewModel.RENDER_SCALE * scaleY
                
                var draggingHandle by remember { mutableStateOf<String?>(null) }
                
                // Remember updated states for drag callbacks to avoid restarting pointerInput
                val currentStart by rememberUpdatedState(selectStartCharIndex)
                val currentEnd by rememberUpdatedState(selectEndCharIndex)
                val currentScaleX by rememberUpdatedState(scaleX)
                val currentScaleY by rememberUpdatedState(scaleY)
                val currentPositionsState by rememberUpdatedState(currentPositions)
                val currentHandleStartX by rememberUpdatedState(handleStartX)
                val currentHandleStartY by rememberUpdatedState(handleStartY)
                val currentHandleEndX by rememberUpdatedState(handleEndX)
                val currentHandleEndY by rememberUpdatedState(handleEndY)

                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    onSelectionChange(-1, -1, -1)
                                }
                            )
                        }
                        .pointerInput(pageIndex) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val startDist = (offset - Offset(currentHandleStartX, currentHandleStartY)).getDistance()
                                    val endDist = (offset - Offset(currentHandleEndX, currentHandleEndY)).getDistance()
                                    val threshold = 40.dp.toPx()
                                    if (startDist < threshold && startDist < endDist) {
                                        draggingHandle = "start"
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    } else if (endDist < threshold) {
                                        draggingHandle = "end"
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    } else {
                                        draggingHandle = null
                                    }
                                },
                                onDrag = { change, _ ->
                                    val handle = draggingHandle ?: return@detectDragGestures
                                    change.consume()
                                    val positions = currentPositionsState
                                    val closestIndex = findClosestCharIndex(change.position.x, change.position.y, positions, currentScaleX, currentScaleY)
                                    if (closestIndex != -1) {
                                        if (handle == "start") {
                                            if (closestIndex < currentEnd) {
                                                onSelectionChange(pageIndex, closestIndex, currentEnd)
                                            }
                                        } else {
                                            if (closestIndex > currentStart) {
                                                onSelectionChange(pageIndex, currentStart, closestIndex + 1)
                                            }
                                        }
                                    }
                                },
                                onDragEnd = {
                                    draggingHandle = null
                                }
                            )
                        }
                ) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        rects.forEach { rect ->
                            drawRoundRect(
                                color = Color(0xFF2196F3).copy(alpha = 0.3f),
                                topLeft = Offset(rect.left, rect.top),
                                size = androidx.compose.ui.geometry.Size(rect.width(), rect.height()),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                            )
                        }
                        
                        // Draw handle circles
                        drawCircle(
                            color = Color(0xFF2196F3),
                            radius = 8.dp.toPx(),
                            center = Offset(handleStartX, handleStartY)
                        )
                        drawCircle(
                            color = Color(0xFF2196F3),
                            radius = 8.dp.toPx(),
                            center = Offset(handleEndX, handleEndY)
                        )
                    }
                    
                    // Render glassmorphic floating action menu
                    val context = LocalContext.current
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val menuWidth = 180.dp
                    val menuHeight = 44.dp
                    val menuLeft = with(density) { (handleStartX + handleEndX) / 2f - menuWidth.toPx() / 2f }
                    val menuTop = with(density) { (rects.minOfOrNull { it.top } ?: 0f) - 60.dp.toPx() }
                    
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    x = menuLeft.roundToInt().coerceIn(8.dp.toPx().toInt(), size.width - menuWidth.toPx().toInt() - 8.dp.toPx().toInt()),
                                    y = menuTop.roundToInt().coerceAtLeast(8.dp.toPx().toInt())
                                )
                            }
                            .width(menuWidth)
                            .height(menuHeight)
                            .shadow(6.dp, RoundedCornerShape(8.dp))
                            .background(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable(enabled = false) {}, // prevent click-through
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val clipboardManager = LocalClipboardManager.current
                            
                            TextButton(
                                onClick = {
                                    val selectedText = currentPositions.subList(selectStartCharIndex, selectEndCharIndex)
                                        .joinToString("") { it.unicode }
                                    clipboardManager.setText(AnnotatedString(selectedText))
                                    Toast.makeText(context, context.getString(R.string.pdf_copied_to_clipboard), Toast.LENGTH_SHORT).show()
                                    onSelectionChange(-1, -1, -1)
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.action_copy), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.action_copy), style = MaterialTheme.typography.bodySmall)
                            }
                            
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(20.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant)
                            )
                            
                            TextButton(
                                onClick = {
                                    val pdfPageWidth = bitmap!!.width.toFloat() / PdfViewerViewModel.RENDER_SCALE
                                    val pdfPageHeight = bitmap!!.height.toFloat() / PdfViewerViewModel.RENDER_SCALE
                                    
                                    lines.forEach { line ->
                                        val minLeft = line.minOf { it.xDirAdj }
                                        val maxRight = line.maxOf { it.xDirAdj + it.widthDirAdj }
                                        val avgY = line.map { it.yDirAdj + it.heightDir / 2f }.average().toFloat()
                                        val height = line.map { it.heightDir }.maxOrNull() ?: 10f
                                        
                                        val normStartX = minLeft / pdfPageWidth
                                        val normEndX = maxRight / pdfPageWidth
                                        val normY = avgY / pdfPageHeight
                                        val normStrokeWidth = height / pdfPageWidth
                                        
                                        viewModel.addAnnotation(
                                            AnnotationStroke(
                                                pageIndex = pageIndex,
                                                tool = AnnotationTool.HIGHLIGHTER,
                                                color = selectedColor,
                                                points = listOf(Offset(normStartX, normY), Offset(normEndX, normY)),
                                                strokeWidth = normStrokeWidth
                                            )
                                        )
                                    }
                                    Toast.makeText(context, context.getString(R.string.pdf_selection_highlighted), Toast.LENGTH_SHORT).show()
                                    onSelectionChange(-1, -1, -1)
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Icon(Icons.Default.BorderColor, contentDescription = stringResource(R.string.pdf_highlighter), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.pdf_highlighter), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            
            // Annotation overlay (kept same but normalized/denormalized)
            if ((isEditMode || annotations.isNotEmpty()) && bitmap != null) {
                Canvas(
                    modifier = Modifier
                        .matchParentSize()
                        .then(
                            if (isEditMode && selectedTool != AnnotationTool.NONE) {
                                Modifier.pointerInput(isEditMode, selectedTool, selectedColor, highlighterWidth, markerWidth, underlineWidth, eraserWidth) {
                                    if (!isEditMode || selectedTool == AnnotationTool.NONE) return@pointerInput
                                    
                                    var localStroke = mutableListOf<Offset>()

                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            localStroke = mutableListOf(offset)
                                            onCurrentStrokeChange(localStroke)
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            localStroke.add(change.position)
                                            onCurrentStrokeChange(localStroke.toList())
                                        },
                                        onDragEnd = {
                                            if (localStroke.isNotEmpty()) {
                                                if (selectedTool == AnnotationTool.ERASER) {
                                                    val normalizedPoints = localStroke.map {
                                                        Offset(
                                                            x = (it.x / size.width).coerceIn(0f, 1f),
                                                            y = (it.y / size.height).coerceIn(0f, 1f)
                                                        )
                                                    }
                                                    val normalizedWidth = eraserWidth / size.width
                                                    viewModel.eraseAnnotations(pageIndex, normalizedPoints, normalizedWidth)
                                                    localStroke = mutableListOf()
                                                    onCurrentStrokeChange(emptyList())
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    return@detectDragGestures
                                                }

                                                val rawWidth = when (selectedTool) {
                                                    AnnotationTool.HIGHLIGHTER -> highlighterWidth
                                                    AnnotationTool.MARKER -> markerWidth
                                                    AnnotationTool.UNDERLINE -> underlineWidth
                                                    else -> markerWidth
                                                }
                                                
                                                // For highlighter: snap to a clean horizontal rectangle
                                                val finalPoints = if (selectedTool == AnnotationTool.HIGHLIGHTER && localStroke.size >= 2) {
                                                    val minX = localStroke.minOf { it.x }
                                                    val maxX = localStroke.maxOf { it.x }
                                                    val avgY = localStroke.map { it.y }.average().toFloat()
                                                    // Create a straight horizontal line at the average Y
                                                    listOf(Offset(minX, avgY), Offset(maxX, avgY))
                                                } else {
                                                    localStroke.toList()
                                                }
                                                
                                                // Normalize all coordinates to [0f, 1f] using size.width/height
                                                val normalizedPoints = finalPoints.map {
                                                    Offset(
                                                        x = (it.x / size.width).coerceIn(0f, 1f),
                                                        y = (it.y / size.height).coerceIn(0f, 1f)
                                                    )
                                                }
                                                val normalizedWidth = rawWidth / size.width
                                                
                                                onAddAnnotation(
                                                    AnnotationStroke(
                                                        pageIndex = pageIndex,
                                                        tool = selectedTool,
                                                        color = selectedColor,
                                                        points = normalizedPoints,
                                                        strokeWidth = normalizedWidth
                                                    )
                                                )
                                                localStroke = mutableListOf()
                                                onCurrentStrokeChange(emptyList())
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            }
                                        }
                                    )
                                }
                            } else Modifier
                        )
                ) {
                    annotations.forEach { stroke ->
                        if (stroke.points.isNotEmpty()) {
                            val path = androidx.compose.ui.graphics.Path().apply {
                                moveTo(stroke.points.first().x * size.width, stroke.points.first().y * size.height)
                                for (i in 1 until stroke.points.size) {
                                    lineTo(stroke.points[i].x * size.width, stroke.points[i].y * size.height)
                                }
                            }
                            // Highlighter uses semi-transparent Multiply blend so text shows through
                            // Marker and other tools render opaque
                            val blendMode = if (stroke.tool == AnnotationTool.HIGHLIGHTER) BlendMode.Multiply else BlendMode.SrcOver
                            val drawColor = if (stroke.tool == AnnotationTool.HIGHLIGHTER) {
                                stroke.color.copy(alpha = 0.35f)
                            } else {
                                stroke.color
                            }
                            val drawStrokeWidth = stroke.strokeWidth * size.width
                            drawPath(
                                path = path,
                                color = drawColor,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = drawStrokeWidth,
                                    cap = StrokeCap.Round,
                                    join = androidx.compose.ui.graphics.StrokeJoin.Round
                                ),
                                blendMode = blendMode
                            )
                        }
                    }
                    if (currentStroke.isNotEmpty() && selectedTool != AnnotationTool.ERASER) {
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(currentStroke.first().x, currentStroke.first().y)
                            for (i in 1 until currentStroke.size) {
                                lineTo(currentStroke[i].x, currentStroke[i].y)
                            }
                        }
                        val rawStrokeWidth = when (selectedTool) {
                            AnnotationTool.HIGHLIGHTER -> highlighterWidth
                            AnnotationTool.MARKER -> markerWidth
                            AnnotationTool.UNDERLINE -> underlineWidth
                            else -> markerWidth
                        }
                        val liveBlendMode = if (selectedTool == AnnotationTool.HIGHLIGHTER) BlendMode.Multiply else BlendMode.SrcOver
                        val liveColor = if (selectedTool == AnnotationTool.HIGHLIGHTER) {
                            selectedColor.copy(alpha = 0.35f)
                        } else {
                            selectedColor
                        }
                        drawPath(
                            path = path,
                            color = liveColor,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = rawStrokeWidth,
                                cap = StrokeCap.Round
                            ),
                            blendMode = liveBlendMode
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PageSelectorDialog(
    currentPage: Int,
    totalPages: Int,
    onPageSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var pageInput by remember { mutableStateOf(currentPage.toString()) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_go_to_page)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.pdf_enter_page_number_range, totalPages),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = pageInput,
                    onValueChange = { pageInput = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val page = pageInput.toIntOrNull()
                    if (page != null && page in 1..totalPages) {
                        onPageSelected(page)
                    }
                }
            ) {
                Text(stringResource(R.string.action_go))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
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
        title = { Text(stringResource(R.string.pdf_password_required)) },
        text = {
            Column {
                if (isError) {
                    Text(
                        text = stringResource(R.string.pdf_password_incorrect_retry),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(
                    text = stringResource(R.string.pdf_password_protected_enter),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.label_password)) },
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
                Text(stringResource(R.string.action_open))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

private fun sharePdf(context: Context, pdfUri: Uri) {
    try {
        // Convert file:// URI to FileProvider content:// URI if needed
        val shareUri = if (pdfUri.scheme == "file") {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                java.io.File(pdfUri.path!!)
            )
        } else {
            pdfUri // already a content:// URI, use directly
        }
        
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, context.getString(R.string.pdf_share))
        context.startActivity(chooser)
    } catch (e: Exception) {
        android.util.Log.e("PdfViewerScreen", "Share failed", e)
        Toast.makeText(context, "${context.getString(R.string.pdf_unable_to_open)}: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun openWithExternalApp(context: Context, pdfUri: Uri) {
    try {
        // Convert file:// URI to FileProvider content:// URI if needed
        val viewUri = if (pdfUri.scheme == "file") {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                java.io.File(pdfUri.path!!)
            )
        } else {
            pdfUri // already a content:// URI, use directly
        }
        
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(viewUri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, context.getString(R.string.pdf_open_with))
        context.startActivity(chooser)
    } catch (e: android.content.ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.pdf_no_app_found), Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        android.util.Log.e("PdfViewerScreen", "Open with failed", e)
        Toast.makeText(context, "${context.getString(R.string.pdf_unable_to_open)}: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun findClosestCharIndex(
    touchX: Float,
    touchY: Float,
    positions: List<TextPosition>,
    scaleX: Float,
    scaleY: Float
): Int {
    if (positions.isEmpty()) return -1

    // Estimate page width from positions
    val pageWidth = positions.maxOf {
        (it.xDirAdj + it.widthDirAdj) * PdfViewerViewModel.RENDER_SCALE * scaleX
    }

    fun findClosestInBand(bandLeft: Float, bandRight: Float): Int {
        var closest = -1
        var minDist = Float.MAX_VALUE
        for (i in positions.indices) {
            val tp = positions[i]
            val cx = (tp.xDirAdj + tp.widthDirAdj / 2f) * PdfViewerViewModel.RENDER_SCALE * scaleX
            if (cx < bandLeft || cx > bandRight) continue
            val cy = (tp.yDirAdj + tp.heightDir / 2f) * PdfViewerViewModel.RENDER_SCALE * scaleY
            val dx = touchX - cx
            val dy = (touchY - cy) * 2f
            val d = dx * dx + dy * dy
            if (d < minDist) { minDist = d; closest = i }
        }
        return closest
    }

    // First pass: global search
    var closestIndex = -1
    var minDistance = Float.MAX_VALUE

    for (i in positions.indices) {
        val tp = positions[i]
        val charCenterX = (tp.xDirAdj + tp.widthDirAdj / 2f) * PdfViewerViewModel.RENDER_SCALE * scaleX
        val charCenterY = (tp.yDirAdj + tp.heightDir / 2f) * PdfViewerViewModel.RENDER_SCALE * scaleY

        val dx = touchX - charCenterX
        val dy = (touchY - charCenterY) * 2f

        val distance = dx * dx + dy * dy
        if (distance < minDistance) {
            minDistance = distance
            closestIndex = i
        }
    }

    // Maximum touch distance threshold: ~32dp from touch point to nearest character
    val maxAllowedDistancePx = (32f * scaleX * PdfViewerViewModel.RENDER_SCALE).coerceAtLeast(48f)
    val maxAllowedDistSq = maxAllowedDistancePx * maxAllowedDistancePx
    if (minDistance > maxAllowedDistSq) {
        return -1
    }

    // If the global match is too far horizontally (>10% page width), search within a band
    if (closestIndex >= 0 && pageWidth > 0f) {
        val closestPos = positions[closestIndex]
        val closestCenterX = (closestPos.xDirAdj + closestPos.widthDirAdj / 2f) * PdfViewerViewModel.RENDER_SCALE * scaleX
        val hDist = kotlin.math.abs(touchX - closestCenterX)
        if (hDist > pageWidth * 0.1f) {
            val bandHalf = pageWidth * 0.15f
            val bandResult = findClosestInBand(touchX - bandHalf, touchX + bandHalf)
            if (bandResult >= 0) closestIndex = bandResult
        }
    }

    return closestIndex
}

private fun findWordBounds(
    charIndex: Int,
    text: String,
    positions: List<TextPosition>
): Pair<Int, Int> {
    if (positions.isEmpty() || charIndex < 0 || charIndex >= positions.size) {
        return Pair(0, 0)
    }

    fun isWordChar(charStr: String): Boolean {
        if (charStr.isEmpty()) return false
        val c = charStr[0]
        return c.isLetterOrDigit() || c == '\'' || c == '_'
    }

    // Check if two adjacent positions belong to the same word
    fun isSameWord(idx1: Int, idx2: Int): Boolean {
        if (idx2 < 0 || idx2 >= positions.size) return false
        if (!isWordChar(positions[idx1].unicode) || !isWordChar(positions[idx2].unicode)) return false
        val right1 = positions[idx1].xDirAdj + positions[idx1].widthDirAdj
        val left2 = positions[idx2].xDirAdj
        val gap = left2 - right1
        val avgWidth = (positions[idx1].widthDirAdj + positions[idx2].widthDirAdj) / 2f
        return gap < avgWidth * 0.35f
    }

    var start = charIndex
    while (start > 0 && isSameWord(start - 1, start)) {
        start--
    }

    var end = charIndex + 1
    while (end < positions.size && isSameWord(end - 1, end)) {
        end++
    }

    return Pair(start, end)
}

@Composable
private fun ThicknessSliderPanel(
    tool: AnnotationTool,
    color: Color,
    highlighterWidth: Float,
    markerWidth: Float,
    underlineWidth: Float,
    eraserWidth: Float,
    onHighlighterWidthChange: (Float) -> Unit,
    onMarkerWidthChange: (Float) -> Unit,
    onUnderlineWidthChange: (Float) -> Unit,
    onEraserWidthChange: (Float) -> Unit
) {
    val currentWidth: Float
    val onWidthChange: (Float) -> Unit
    val valueRange: ClosedFloatingPointRange<Float>
    val title: String

    when (tool) {
        AnnotationTool.HIGHLIGHTER -> {
            currentWidth = highlighterWidth
            onWidthChange = onHighlighterWidthChange
            valueRange = 5f..50f
            title = stringResource(R.string.pdf_thickness_highlighter)
        }
        AnnotationTool.MARKER -> {
            currentWidth = markerWidth
            onWidthChange = onMarkerWidthChange
            valueRange = 2f..25f
            title = stringResource(R.string.pdf_thickness_marker)
        }
        AnnotationTool.UNDERLINE -> {
            currentWidth = underlineWidth
            onWidthChange = onUnderlineWidthChange
            valueRange = 1f..12f
            title = stringResource(R.string.pdf_thickness_underline)
        }
        AnnotationTool.ERASER -> {
            currentWidth = eraserWidth
            onWidthChange = onEraserWidthChange
            valueRange = 5f..50f
            title = stringResource(R.string.pdf_thickness_eraser)
        }
        else -> return
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .shadow(4.dp, RoundedCornerShape(12.dp)),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.pdf_px_unit, currentWidth.roundToInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val strokeRadius = currentWidth / 2f
                        if (tool == AnnotationTool.HIGHLIGHTER) {
                            val rectHeight = currentWidth.coerceAtMost(size.height)
                            drawRect(
                                color = color.copy(alpha = 0.35f),
                                topLeft = Offset(0f, (size.height - rectHeight) / 2f),
                                size = androidx.compose.ui.geometry.Size(size.width, rectHeight)
                            )
                        } else if (tool == AnnotationTool.UNDERLINE) {
                            val lineY = size.height - 8f
                            drawLine(
                                color = color,
                                start = Offset(4f, lineY),
                                end = Offset(size.width - 4f, lineY),
                                strokeWidth = currentWidth,
                                cap = StrokeCap.Round
                            )
                        } else {
                            drawCircle(
                                color = color,
                                radius = strokeRadius.coerceAtMost(size.width / 2f - 4f),
                                center = Offset(size.width / 2f, size.height / 2f)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Slider(
                    value = currentWidth,
                    onValueChange = onWidthChange,
                    valueRange = valueRange,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )
            }
        }
    }
}
