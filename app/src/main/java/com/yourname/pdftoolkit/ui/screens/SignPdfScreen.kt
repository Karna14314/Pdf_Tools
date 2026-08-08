package com.yourname.pdftoolkit.ui.screens
import com.yourname.pdftoolkit.util.safeLaunch

import androidx.compose.ui.res.stringResource
import com.yourname.pdftoolkit.R

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yourname.pdftoolkit.domain.operations.*
import com.yourname.pdftoolkit.util.FileOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * ViewModel for Sign PDF Screen.
 */
class SignPdfViewModel : ViewModel() {
    private val _state = MutableStateFlow(SignPdfUiState())
    val state: StateFlow<SignPdfUiState> = _state.asStateFlow()
    
    fun setSourcePdf(uri: Uri, name: String) {
        _state.value = _state.value.copy(sourceUri = uri, sourceName = name)
    }
    
    fun addPathPoint(x: Float, y: Float) {
        val currentPath = _state.value.currentPath.toMutableList()
        currentPath.add(SignaturePoint(x, y))
        _state.value = _state.value.copy(currentPath = currentPath)
    }
    
    fun finishCurrentPath() {
        if (_state.value.currentPath.isNotEmpty()) {
            val currentPaths = _state.value.signaturePaths.toMutableList()
            currentPaths.add(SignaturePath(_state.value.currentPath))
            _state.value = _state.value.copy(
                signaturePaths = currentPaths,
                currentPath = emptyList()
            )
        }
    }
    
    fun clearSignature() {
        _state.value = _state.value.copy(
            signaturePaths = emptyList(),
            currentPath = emptyList()
        )
    }
    
    fun setPageIndex(index: Int) {
        _state.value = _state.value.copy(pageIndex = index)
    }
    
    fun setSignaturePosition(x: Float, y: Float) {
        _state.value = _state.value.copy(signatureX = x, signatureY = y)
    }
    
    fun setSignatureSize(width: Float, height: Float) {
        _state.value = _state.value.copy(signatureWidth = width, signatureHeight = height)
    }

    fun setSignaturePlacement(x: Float, y: Float, width: Float, height: Float) {
        _state.value = _state.value.copy(
            signatureX = x,
            signatureY = y,
            signatureWidth = width,
            signatureHeight = height
        )
    }
    
    fun toggleAddDate() {
        _state.value = _state.value.copy(addDate = !_state.value.addDate)
    }
    
    fun toggleAddName() {
        _state.value = _state.value.copy(addName = !_state.value.addName)
    }
    
    fun setName(name: String) {
        _state.value = _state.value.copy(signerName = name)
    }
    
    fun signPdf(
        context: android.content.Context,
        outputUri: Uri
    ) {
        val currentState = _state.value
        val sourceUri = currentState.sourceUri ?: return
        
        if (currentState.signaturePaths.isEmpty()) return
        
        if (_state.value.isProcessing) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isProcessing = true, progress = 0, error = null)
            
            val signer = PdfSigner(context)
            
            val signatureData = SignatureData(
                paths = currentState.signaturePaths,
                strokeWidth = 3f,
                strokeColor = Color.BLACK
            )
            
            val placement = SignaturePlacement(
                pageIndex = currentState.pageIndex,
                x = currentState.signatureX,
                y = currentState.signatureY,
                width = currentState.signatureWidth,
                height = currentState.signatureHeight
            )
            
            val extras = SignatureExtras(
                addDate = currentState.addDate,
                addName = currentState.addName,
                name = currentState.signerName
            )
            
            val result = signer.addSignature(
                inputUri = sourceUri,
                outputUri = outputUri,
                signatureData = signatureData,
                placement = placement,
                extras = extras,
                progressCallback = { progress ->
                    _state.value = _state.value.copy(progress = progress)
                }
            )
            
            _state.value = _state.value.copy(
                isProcessing = false,
                isComplete = result.success,
                error = result.errorMessage,
                resultUri = if (result.success) outputUri else null
            )
        }
    }
    
    fun reset() {
        _state.value = SignPdfUiState()
    }
}

data class SignPdfUiState(
    val sourceUri: Uri? = null,
    val sourceName: String = "",
    val signaturePaths: List<SignaturePath> = emptyList(),
    val currentPath: List<SignaturePoint> = emptyList(),
    val pageIndex: Int = 0,
    val signatureX: Float = 50f,
    val signatureY: Float = 50f,
    val signatureWidth: Float = 200f,
    val signatureHeight: Float = 100f,
    val addDate: Boolean = true,
    val addName: Boolean = false,
    val signerName: String = "",
    val isProcessing: Boolean = false,
    val progress: Int = 0,
    val isComplete: Boolean = false,
    val error: String? = null,
    val resultUri: Uri? = null
)

private data class SignaturePagePreview(
    val bitmap: Bitmap,
    val pageWidth: Float,
    val pageHeight: Float
)

private data class SignaturePlacementRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
)

private suspend fun renderSignaturePagePreview(
    context: android.content.Context,
    uri: Uri,
    pageIndex: Int
): SignaturePagePreview? = withContext(Dispatchers.IO) {
    var tempFile: File? = null
    var pfd: ParcelFileDescriptor? = null
    var renderer: PdfRenderer? = null
    var page: PdfRenderer.Page? = null

    try {
        val temp = File.createTempFile("sign_preview_", ".pdf", context.cacheDir)
        tempFile = temp

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(temp).use { output ->
                input.copyTo(output)
            }
        } ?: return@withContext null

        pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = PdfRenderer(pfd)

        if (renderer.pageCount == 0) return@withContext null

        val safePageIndex = pageIndex.coerceIn(0, renderer.pageCount - 1)
        page = renderer.openPage(safePageIndex)

        val pageWidth = page.width.toFloat()
        val pageHeight = page.height.toFloat()
        val maxPreviewWidth = 1600
        val scale = minOf(2f, maxPreviewWidth / pageWidth).coerceAtLeast(1f)
        val bitmapWidth = (page.width * scale).toInt().coerceAtLeast(1)
        val bitmapHeight = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)

        page.render(
            bitmap,
            null,
            null,
            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
        )

        SignaturePagePreview(
            bitmap = bitmap,
            pageWidth = pageWidth,
            pageHeight = pageHeight
        )
    } catch (e: Exception) {
        null
    } finally {
        try {
            page?.close()
        } catch (_: Exception) {
        }
        try {
            renderer?.close()
        } catch (_: Exception) {
        }
        try {
            pfd?.close()
        } catch (_: Exception) {
        }
        try {
            tempFile?.delete()
        } catch (_: Exception) {
        }
    }
}

private fun pdfRectToImageRect(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    pageWidth: Float,
    pageHeight: Float,
    imageSize: Size
): SignaturePlacementRect {
    if (pageWidth <= 0f || pageHeight <= 0f || imageSize.width <= 0f || imageSize.height <= 0f) {
        return SignaturePlacementRect(0f, 0f, 0f, 0f)
    }

    val left = (x / pageWidth) * imageSize.width
    val rectWidth = (width / pageWidth) * imageSize.width
    val rectHeight = (height / pageHeight) * imageSize.height
    val top = imageSize.height - (((y + height) / pageHeight) * imageSize.height)

    return SignaturePlacementRect(left, top, rectWidth, rectHeight)
}

/**
 * Sign PDF Screen - Add handwritten signatures to PDF documents.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignPdfScreen(
    onNavigateBack: () -> Unit,
    viewModel: SignPdfViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    var pagePreview by remember { mutableStateOf<SignaturePagePreview?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    val latestPagePreview by rememberUpdatedState(pagePreview)
    
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val name = context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                cursor.getString(nameIndex)
            } ?: "Selected PDF"
            viewModel.setSourcePdf(it, name)
        }
    }
    
    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let { viewModel.signPdf(context, it) }
    }

    LaunchedEffect(state.sourceUri, state.pageIndex) {
        pagePreview?.bitmap?.let { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        pagePreview = null
        previewError = null
        dragStart = null
        dragEnd = null

        val sourceUri = state.sourceUri ?: return@LaunchedEffect
        val preview = renderSignaturePagePreview(context, sourceUri, state.pageIndex)
        if (preview != null) {
            pagePreview = preview
        } else {
            previewError = "Unable to render page preview"
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            latestPagePreview?.bitmap?.let { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tool_sign_pdf)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Source PDF Selection
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Source PDF",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    if (state.sourceUri != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.PictureAsPdf,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = state.sourceName,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            IconButton(onClick = { pdfPickerLauncher.safeLaunch(arrayOf("application/pdf"), context) }) {
                                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_change))
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = { pdfPickerLauncher.safeLaunch(arrayOf("application/pdf"), context) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.FileOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.action_select_pdf))
                        }
                    }
                }
            }
            
            // Signature Pad
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Draw Your Signature",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        TextButton(onClick = { viewModel.clearSignature() }) {
                            Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.action_clear))
                        }
                    }
                    
                    // Signature Canvas
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(androidx.compose.ui.graphics.Color.White)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(8.dp)
                            )
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        viewModel.addPathPoint(offset.x, offset.y)
                                    },
                                    onDrag = { change, _ ->
                                        viewModel.addPathPoint(change.position.x, change.position.y)
                                    },
                                    onDragEnd = {
                                        viewModel.finishCurrentPath()
                                    }
                                )
                            }
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            // Draw completed paths
                            for (signaturePath in state.signaturePaths) {
                                if (signaturePath.points.size > 1) {
                                    val path = Path()
                                    val firstPoint = signaturePath.points.first()
                                    path.moveTo(firstPoint.x, firstPoint.y)
                                    
                                    for (i in 1 until signaturePath.points.size) {
                                        val point = signaturePath.points[i]
                                        path.lineTo(point.x, point.y)
                                    }
                                    
                                    drawPath(
                                        path = path,
                                        color = androidx.compose.ui.graphics.Color.Black,
                                        style = Stroke(
                                            width = 3f,
                                            cap = StrokeCap.Round,
                                            join = StrokeJoin.Round
                                        )
                                    )
                                }
                            }
                            
                            // Draw current path
                            if (state.currentPath.size > 1) {
                                val path = Path()
                                val firstPoint = state.currentPath.first()
                                path.moveTo(firstPoint.x, firstPoint.y)
                                
                                for (i in 1 until state.currentPath.size) {
                                    val point = state.currentPath[i]
                                    path.lineTo(point.x, point.y)
                                }
                                
                                drawPath(
                                    path = path,
                                    color = androidx.compose.ui.graphics.Color.Black,
                                    style = Stroke(
                                        width = 3f,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round
                                    )
                                )
                            }
                        }
                        
                        // Placeholder text
                        if (state.signaturePaths.isEmpty() && state.currentPath.isEmpty()) {
                            Text(
                                text = "Sign here",
                                modifier = Modifier.align(Alignment.Center),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
            
            // Signature Placement
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Placement",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    // Page Selection
                    OutlinedTextField(
                        value = (state.pageIndex + 1).toString(),
                        onValueChange = { value ->
                            value.toIntOrNull()?.let { page ->
                                if (page > 0) viewModel.setPageIndex(page - 1)
                            }
                        },
                        label = { Text(stringResource(R.string.label_page_number)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) }
                    )

                    val preview = pagePreview
                    when {
                        state.sourceUri == null -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surface),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Select a PDF to place the signature",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        preview == null && previewError == null -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surface),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                        previewError != null -> {
                            Text(
                                text = previewError.orEmpty(),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        preview != null -> {
                            BoxWithConstraints(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outline,
                                        RoundedCornerShape(8.dp)
                                    )
                            ) {
                                val aspectRatio = preview.bitmap.width.toFloat() / preview.bitmap.height.toFloat()

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(aspectRatio)
                                ) {
                                    Image(
                                        bitmap = preview.bitmap.asImageBitmap(),
                                        contentDescription = "Page ${state.pageIndex + 1}",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.FillBounds
                                    )

                                    Canvas(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .pointerInput(preview.pageWidth, preview.pageHeight) {
                                                detectDragGestures(
                                                    onDragStart = { offset ->
                                                        dragStart = offset
                                                        dragEnd = offset
                                                    },
                                                    onDrag = { change, _ ->
                                                        change.consume()
                                                        dragEnd = change.position
                                                    },
                                                    onDragEnd = {
                                                        val start = dragStart
                                                        val end = dragEnd
                                                        if (start != null && end != null && size.width > 0 && size.height > 0) {
                                                            val left = minOf(start.x, end.x).coerceIn(0f, size.width.toFloat())
                                                            val right = maxOf(start.x, end.x).coerceIn(0f, size.width.toFloat())
                                                            val top = minOf(start.y, end.y).coerceIn(0f, size.height.toFloat())
                                                            val bottom = maxOf(start.y, end.y).coerceIn(0f, size.height.toFloat())
                                                            val rectWidth = right - left
                                                            val rectHeight = bottom - top

                                                            if (rectWidth > 0f && rectHeight > 0f) {
                                                                val pdfX = (left / size.width.toFloat()) * preview.pageWidth
                                                                val pdfWidth = (rectWidth / size.width.toFloat()) * preview.pageWidth
                                                                val pdfHeight = (rectHeight / size.height.toFloat()) * preview.pageHeight
                                                                val pdfY = preview.pageHeight - ((bottom / size.height.toFloat()) * preview.pageHeight)

                                                                viewModel.setSignaturePlacement(pdfX, pdfY, pdfWidth, pdfHeight)
                                                            }
                                                        }
                                                        dragStart = null
                                                        dragEnd = null
                                                    }
                                                )
                                            }
                                    ) {
                                        val activeStart = dragStart
                                        val activeEnd = dragEnd
                                        val rect = if (activeStart != null && activeEnd != null) {
                                            SignaturePlacementRect(
                                                left = minOf(activeStart.x, activeEnd.x),
                                                top = minOf(activeStart.y, activeEnd.y),
                                                width = kotlin.math.abs(activeEnd.x - activeStart.x),
                                                height = kotlin.math.abs(activeEnd.y - activeStart.y)
                                            )
                                        } else {
                                            pdfRectToImageRect(
                                                x = state.signatureX,
                                                y = state.signatureY,
                                                width = state.signatureWidth,
                                                height = state.signatureHeight,
                                                pageWidth = preview.pageWidth,
                                                pageHeight = preview.pageHeight,
                                                imageSize = size
                                            )
                                        }

                                        if (rect.width > 0f && rect.height > 0f) {
                                            drawRect(
                                                color = androidx.compose.ui.graphics.Color(0xFF1E88E5).copy(alpha = 0.12f),
                                                topLeft = Offset(rect.left, rect.top),
                                                size = Size(rect.width, rect.height)
                                            )
                                            drawRect(
                                                color = androidx.compose.ui.graphics.Color(0xFF1E88E5),
                                                topLeft = Offset(rect.left, rect.top),
                                                size = Size(rect.width, rect.height),
                                                style = Stroke(
                                                    width = 3f,
                                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 10f), 0f)
                                                )
                                            )
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.TouchApp,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Page ${state.pageIndex + 1} • tap and drag to reposition",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            
            // Additional Options
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Additional Info",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.sign_add_date), modifier = Modifier.weight(1f))
                        Switch(
                            checked = state.addDate,
                            onCheckedChange = { viewModel.toggleAddDate() }
                        )
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.sign_add_name), modifier = Modifier.weight(1f))
                        Switch(
                            checked = state.addName,
                            onCheckedChange = { viewModel.toggleAddName() }
                        )
                    }
                    
                    AnimatedVisibility(visible = state.addName) {
                        OutlinedTextField(
                            value = state.signerName,
                            onValueChange = { viewModel.setName(it) },
                            label = { Text(stringResource(R.string.sign_your_name)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }
            
            // Processing State
            AnimatedVisibility(visible = state.isProcessing) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.sign_progress, state.progress))
                        LinearProgressIndicator(
                            progress = state.progress / 100f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            
            // Success State
            AnimatedVisibility(visible = state.isComplete && !state.isProcessing) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "PDF Signed Successfully!",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        state.resultUri?.let { uri ->
                            FilledTonalButton(
                                onClick = { scope.launch(Dispatchers.IO) { FileOpener.openPdf(context, uri) } }
                            ) {
                                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.action_open))
                            }
                        }
                    }
                }
            }
            
            // Error State
            state.error?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Sign Button
            Button(
                onClick = {
                    val fileName = "signed_${System.currentTimeMillis()}.pdf"
                    saveDocumentLauncher.safeLaunch(fileName, context)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.sourceUri != null && 
                         state.signaturePaths.isNotEmpty() &&
                         !state.isProcessing
            ) {
                Icon(Icons.Default.Draw, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.tool_sign_pdf))
            }
            
            // Reset Button
            if (state.isComplete) {
                OutlinedButton(
                    onClick = { viewModel.reset() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.sign_another_pdf))
                }
            }
        }
    }
}
