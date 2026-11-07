package com.yourname.pdftoolkit.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yourname.pdftoolkit.R
import com.yourname.pdftoolkit.domain.operations.SplitOrientation
import com.yourname.pdftoolkit.ui.components.*
import com.yourname.pdftoolkit.util.FileOpener
import com.yourname.pdftoolkit.util.safeLaunch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Screen for cropping PDF pages by drawing rectangles on the rendered page.
 * Modes: one area for all pages, one area per page (or odd/even), split each page in two.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(
    onNavigateBack: () -> Unit,
    viewModel: CropViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsState()

    val displayWidthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }

    val pickPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.setSource(context, it, displayWidthPx) }
    }

    Scaffold(
        topBar = {
            ToolTopBar(
                title = stringResource(R.string.tool_crop_pages),
                onNavigateBack = onNavigateBack
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (state.sourceUri == null || state.loadError) {
                    EmptyState(
                        icon = Icons.Default.Crop,
                        title = stringResource(R.string.metadata_no_pdf_selected),
                        subtitle = stringResource(R.string.crop_no_pdf_subtitle),
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    CropEditor(
                        state = state,
                        viewModel = viewModel,
                        onPageChange = { viewModel.goToPage(context, it) }
                    )
                }

                if (state.isProcessing) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.Center)
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
                                progress = state.progress,
                                message = stringResource(R.string.crop_progress)
                            )
                        }
                    }
                }
            }

            // Bottom action area
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                tonalElevation = 3.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    if (state.sourceUri == null || state.loadError) {
                        ActionButton(
                            text = stringResource(R.string.file_picker_select_pdf),
                            onClick = { pickPdfLauncher.safeLaunch(arrayOf("application/pdf"), context) },
                            icon = Icons.Default.FolderOpen
                        )
                    } else {
                        CropBottomOptions(state = state, onToggleShrink = viewModel::toggleShrinkMediaBox)
                        Spacer(modifier = Modifier.height(12.dp))
                        val buttonText = when {
                            !state.canSave && !state.isProcessing -> stringResource(R.string.crop_draw_first)
                            state.mode == CropMode.SPLIT ->
                                stringResource(R.string.crop_save_split, state.pageCount, state.pageCount * 2)
                            else -> stringResource(R.string.crop_save_pages, state.markedPages)
                        }
                        ActionButton(
                            text = buttonText,
                            onClick = { viewModel.save(context) },
                            enabled = state.canSave,
                            isLoading = state.isProcessing,
                            icon = Icons.Default.Crop
                        )
                    }
                }
            }
        }
    }

    if (state.loadError) {
        ResultDialog(
            isSuccess = false,
            title = stringResource(R.string.crop_failed_title),
            message = stringResource(R.string.crop_error_open),
            onDismiss = viewModel::dismissLoadError
        )
    }

    if (state.showResult) {
        ResultDialog(
            isSuccess = state.resultSuccess,
            title = stringResource(if (state.resultSuccess) R.string.crop_complete_title else R.string.crop_failed_title),
            message = state.resultMessage,
            onDismiss = viewModel::dismissResult,
            onAction = state.resultUri?.let { uri ->
                { scope.launch(Dispatchers.IO) { FileOpener.openPdf(context, uri) } }
            },
            actionText = stringResource(R.string.action_open_pdf)
        )
    }
}

@Composable
private fun CropEditor(
    state: CropUiState,
    viewModel: CropViewModel,
    onPageChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // Selected file
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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
                        text = state.sourceName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.crop_page_count, state.pageCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
                IconButton(onClick = viewModel::clearSource) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_remove),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Mode selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ModeTile(Icons.Default.Crop, stringResource(R.string.crop_mode_all), state.mode == CropMode.ALL_PAGES,
                { viewModel.setMode(CropMode.ALL_PAGES) }, Modifier.weight(1f))
            ModeTile(Icons.Default.AutoAwesomeMotion, stringResource(R.string.crop_mode_per_page), state.mode == CropMode.PER_PAGE,
                { viewModel.setMode(CropMode.PER_PAGE) }, Modifier.weight(1f))
            ModeTile(Icons.Default.VerticalSplit, stringResource(R.string.crop_mode_split), state.mode == CropMode.SPLIT,
                { viewModel.setMode(CropMode.SPLIT) }, Modifier.weight(1f))
        }

        // Mode options
        ModeOptions(state = state, viewModel = viewModel)

        // Hint
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.TouchApp,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = hintFor(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Page preview with overlay
        PagePreview(
            state = state,
            viewModel = viewModel,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )

        // Page navigation + contextual actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { onPageChange(state.currentPage - 1) },
                enabled = state.currentPage > 0
            ) {
                Icon(Icons.Default.ChevronLeft, contentDescription = stringResource(R.string.action_previous))
            }
            Text(
                text = stringResource(R.string.crop_page_indicator, state.currentPage + 1, state.pageCount),
                style = MaterialTheme.typography.labelLarge
            )
            IconButton(
                onClick = { onPageChange(state.currentPage + 1) },
                enabled = state.currentPage < state.pageCount - 1
            ) {
                Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.action_next))
            }
            Spacer(modifier = Modifier.weight(1f))

            val canCopy = state.mode == CropMode.PER_PAGE &&
                state.perPageVariant == PerPageVariant.EACH_PAGE &&
                state.editedRect != null &&
                state.currentPage < state.pageCount - 1
            if (canCopy) {
                TextButton(onClick = viewModel::copyToFollowingPages) {
                    Text(stringResource(R.string.crop_copy_to_following), style = MaterialTheme.typography.labelMedium)
                }
            }
            val canReset = state.mode == CropMode.SPLIT || state.editedRect != null
            if (canReset) {
                TextButton(onClick = viewModel::resetCurrent) {
                    Text(stringResource(R.string.crop_reset), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeOptions(state: CropUiState, viewModel: CropViewModel) {
    if (state.mode == CropMode.ALL_PAGES) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (state.mode) {
            CropMode.PER_PAGE -> {
                OptionChip(stringResource(R.string.crop_variant_each_page), state.perPageVariant == PerPageVariant.EACH_PAGE) {
                    viewModel.setPerPageVariant(PerPageVariant.EACH_PAGE)
                }
                OptionChip(stringResource(R.string.crop_variant_odd_even), state.perPageVariant == PerPageVariant.ODD_EVEN) {
                    viewModel.setPerPageVariant(PerPageVariant.ODD_EVEN)
                }
            }
            CropMode.SPLIT -> {
                OptionChip(stringResource(R.string.crop_variant_split_line), state.splitVariant == SplitVariant.LINE) {
                    viewModel.setSplitVariant(SplitVariant.LINE)
                }
                OptionChip(stringResource(R.string.crop_variant_two_areas), state.splitVariant == SplitVariant.TWO_AREAS) {
                    viewModel.setSplitVariant(SplitVariant.TWO_AREAS)
                }
                VerticalDivider()
                if (state.splitVariant == SplitVariant.LINE) {
                    OptionChip(
                        stringResource(R.string.crop_orientation_vertical),
                        state.splitOrientation == SplitOrientation.VERTICAL
                    ) { viewModel.setSplitOrientation(SplitOrientation.VERTICAL) }
                    OptionChip(
                        stringResource(R.string.crop_orientation_horizontal),
                        state.splitOrientation == SplitOrientation.HORIZONTAL
                    ) { viewModel.setSplitOrientation(SplitOrientation.HORIZONTAL) }
                } else {
                    OptionChip(stringResource(R.string.crop_area_number, 1), state.activeSplitArea == 0) {
                        viewModel.setActiveSplitArea(0)
                    }
                    OptionChip(stringResource(R.string.crop_area_number, 2), state.activeSplitArea == 1) {
                        viewModel.setActiveSplitArea(1)
                    }
                }
                VerticalDivider()
                OptionChip(stringResource(R.string.crop_reverse_order), state.reverseOrder, Icons.Default.SwapHoriz) {
                    viewModel.toggleReverseOrder()
                }
            }
            CropMode.ALL_PAGES -> Unit
        }
    }
}

@Composable
private fun PagePreview(
    state: CropUiState,
    viewModel: CropViewModel,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier.padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = state.pageBitmap
        if (bitmap != null && bitmap.width > 0 && bitmap.height > 0) {
            val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
            val pageRatio = bitmap.width.toFloat() / bitmap.height
            val boxRatio = maxWidth / maxHeight
            val (w, h) = if (pageRatio > boxRatio) {
                maxWidth to maxWidth / pageRatio
            } else {
                maxHeight * pageRatio to maxHeight
            }

            Box(
                modifier = Modifier
                    .size(w, h)
                    .shadow(4.dp, RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = stringResource(R.string.crop_page_indicator, state.currentPage + 1, state.pageCount),
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )

                when {
                    state.mode == CropMode.SPLIT && state.splitVariant == SplitVariant.LINE -> {
                        SplitLineOverlay(
                            orientation = state.splitOrientation,
                            position = state.splitPosition,
                            onPositionChange = viewModel::setSplitPosition,
                            firstLabel = if (state.reverseOrder) "2" else "1",
                            secondLabel = if (state.reverseOrder) "1" else "2",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    state.mode == CropMode.SPLIT -> {
                        CropRectOverlay(
                            rects = state.splitAreas,
                            activeIndex = state.activeSplitArea,
                            onRectChange = viewModel::setSplitArea,
                            onActiveIndexChange = viewModel::setActiveSplitArea,
                            showLabels = true,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    else -> {
                        CropRectOverlay(
                            rects = listOf(state.editedRect),
                            activeIndex = 0,
                            onRectChange = { _, rect -> viewModel.setEditedRect(rect) },
                            onActiveIndexChange = {},
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        if (state.isLoadingPage) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun CropBottomOptions(state: CropUiState, onToggleShrink: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.crop_shrink_page_size),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = stringResource(R.string.crop_shrink_page_size_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = state.shrinkMediaBox, onCheckedChange = { onToggleShrink() })
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.crop_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun hintFor(state: CropUiState): String = when (state.mode) {
    CropMode.ALL_PAGES ->
        stringResource(if (state.uniformRect == null) R.string.crop_hint_draw else R.string.crop_hint_adjust)
    CropMode.PER_PAGE -> when (state.perPageVariant) {
        PerPageVariant.EACH_PAGE ->
            stringResource(R.string.crop_hint_pages_marked, state.markedPages, state.pageCount)
        PerPageVariant.ODD_EVEN ->
            stringResource(if (state.isOddPage) R.string.crop_hint_odd else R.string.crop_hint_even)
    }
    CropMode.SPLIT -> stringResource(
        if (state.splitVariant == SplitVariant.LINE) R.string.crop_hint_split_line else R.string.crop_hint_two_areas
    )
}

@Composable
private fun ModeTile(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionChip(
    label: String,
    selected: Boolean,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = when {
            selected -> { { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) } }
            icon != null -> { { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) } }
            else -> null
        }
    )
}

@Composable
private fun VerticalDivider() {
    Box(
        modifier = Modifier
            .height(24.dp)
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}
