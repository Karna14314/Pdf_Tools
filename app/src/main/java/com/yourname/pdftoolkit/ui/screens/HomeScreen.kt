package com.yourname.pdftoolkit.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yourname.pdftoolkit.R
import com.yourname.pdftoolkit.data.SafUriManager
import com.yourname.pdftoolkit.ui.navigation.Screen
import kotlinx.coroutines.launch

/**
 * Category tabs for organizing PDF tools.
 */
@Composable
fun getToolCategories(): List<ToolCategoryData> {
    return listOf(
        ToolCategoryData(stringResource(R.string.category_organize), Icons.Default.Folder),
        ToolCategoryData(stringResource(R.string.category_convert), Icons.Default.Transform),
        ToolCategoryData(stringResource(R.string.category_markup), Icons.Default.Draw),
        ToolCategoryData(stringResource(R.string.category_security), Icons.Default.Lock),
        ToolCategoryData(stringResource(R.string.category_optimize), Icons.Default.Speed)
    )
}

data class ToolCategoryData(val title: String, val icon: ImageVector)

// Keep for backwards compatibility - will be removed in future refactor
enum class ToolCategory(val title: String, val icon: ImageVector) {
    ORGANIZE("Organize", Icons.Default.Folder),
    CONVERT("Convert", Icons.Default.Transform),
    MARKUP("Markup", Icons.Default.Draw),
    SECURITY("Security", Icons.Default.Lock),
    OPTIMIZE("Optimize", Icons.Default.Speed)
}

// Supported file types - PDF only
private val pdfMimeTypes = arrayOf("application/pdf")

/**
 * Home screen displaying all available PDF tools organized by category.
 * Uses tabs for category navigation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToFeature: (Screen) -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onOpenPdfViewer: (Uri, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedCategory by remember { mutableStateOf(ToolCategory.ORGANIZE) }
    
    // PDF file picker
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        // uri is null when user cancels - just do nothing
        uri?.let { selectedUri ->
            scope.launch {
                // Persist SAF permission immediately so reopening from recent files won't fail.
                val persistedFile = SafUriManager.addRecentFile(
                    context,
                    selectedUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

                val name = persistedFile?.name?.substringBeforeLast('.') ?: run {
                    var displayName = "PDF Document"
                    context.contentResolver.query(selectedUri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIndex >= 0) {
                                displayName = c.getString(nameIndex)?.substringBeforeLast('.') ?: displayName
                            }
                        }
                    }
                    displayName
                }

                onOpenPdfViewer(selectedUri, name)
            }
        }
    }
    

    
    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.home_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    // Open PDF button
                    IconButton(
                        onClick = {
                            pdfPickerLauncher.launch(pdfMimeTypes)
                        }
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = stringResource(R.string.cd_open_pdf)
                        )
                    }
                    // Settings button
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.action_settings)
                        )
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    pdfPickerLauncher.launch(pdfMimeTypes)
                },
                icon = { Icon(Icons.Default.FileOpen, contentDescription = null) },
                text = { Text(stringResource(R.string.fab_open_pdf)) },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Category tabs
            ScrollableTabRow(
                selectedTabIndex = ToolCategory.entries.indexOf(selectedCategory),
                edgePadding = 16.dp,
                containerColor = MaterialTheme.colorScheme.surface,
                divider = {}
            ) {
                ToolCategory.entries.forEach { category ->
                    val categoryTitle = getCategoryTitle(category)
                    Tab(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                        text = { Text(categoryTitle) },
                        icon = {
                            Icon(
                                imageVector = category.icon,
                                contentDescription = categoryTitle,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    )
                }
            }
            
            // Content
            val features = getFeaturesByCategory(selectedCategory)
            
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp, horizontal = 0.dp)
            ) {
                itemsIndexed(
                    items = features,
                    key = { _, feature -> feature.title }
                ) { index, feature ->
                    var isVisible by remember { mutableStateOf(false) }
                    
                    LaunchedEffect(selectedCategory) {
                        isVisible = false
                        kotlinx.coroutines.delay(index * 50L)
                        isVisible = true
                    }
                    
                    val scale by animateFloatAsState(
                        targetValue = if (isVisible) 1f else 0.8f,
                        animationSpec = tween(durationMillis = 300),
                        label = "card_scale"
                    )
                    
                    FeatureCard(
                        feature = feature,
                        onClick = {
                            val screen = Screen.fromToolId(feature.toolId)
                            onNavigateToFeature(screen)
                        },
                        modifier = Modifier.scale(scale)
                    )
                }
                
                // Bottom spacing for FAB
                item {
                    Spacer(modifier = Modifier.height(72.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeatureCard(
    feature: PdfFeature,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = feature.icon,
                    contentDescription = feature.title,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .padding(12.dp)
                        .size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = feature.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = feature.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = stringResource(R.string.action_navigate),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Data class representing a PDF tool feature.
 */
data class PdfFeature(
    val toolId: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val category: ToolCategory
)

/**
 * Returns the localized title for a ToolCategory.
 */
@Composable
private fun getCategoryTitle(category: ToolCategory): String {
    return when (category) {
        ToolCategory.ORGANIZE -> stringResource(R.string.category_organize)
        ToolCategory.CONVERT -> stringResource(R.string.category_convert)
        ToolCategory.MARKUP -> stringResource(R.string.category_markup)
        ToolCategory.SECURITY -> stringResource(R.string.category_security)
        ToolCategory.OPTIMIZE -> stringResource(R.string.category_optimize)
    }
}

/**
 * Get features filtered by category.
 */
@Composable
private fun getFeaturesByCategory(category: ToolCategory): List<PdfFeature> {
    return getPdfFeatures().filter { it.category == category }
}

/**
 * List of all available PDF tools organized by category.
 */
@Composable
fun getPdfFeatures(): List<PdfFeature> = listOf(
    // ORGANIZE category
    PdfFeature(
        toolId = "merge",
        title = stringResource(R.string.tool_merge_pdfs),
        description = stringResource(R.string.desc_merge_pdfs),
        icon = Icons.Default.MergeType,
        category = ToolCategory.ORGANIZE
    ),
    PdfFeature(
        toolId = "split",
        title = stringResource(R.string.tool_split_pdf),
        description = stringResource(R.string.desc_split_pdf),
        icon = Icons.Default.CallSplit,
        category = ToolCategory.ORGANIZE
    ),
    PdfFeature(
        toolId = "delete_pages",
        title = stringResource(R.string.tool_organize_pages),
        description = stringResource(R.string.desc_organize_pages),
        icon = Icons.Default.SwapVert,
        category = ToolCategory.ORGANIZE
    ),
    PdfFeature(
        toolId = "rotate",
        title = stringResource(R.string.tool_rotate_pages),
        description = stringResource(R.string.desc_rotate_pages),
        icon = Icons.Default.RotateRight,
        category = ToolCategory.ORGANIZE
    ),
    PdfFeature(
        toolId = "extract",
        title = stringResource(R.string.tool_extract_pages),
        description = stringResource(R.string.desc_extract_pages),
        icon = Icons.Default.ContentCopy,
        category = ToolCategory.ORGANIZE
    ),

    // CONVERT category
    PdfFeature(
        toolId = "image_to_pdf",
        title = stringResource(R.string.tool_images_to_pdf),
        description = stringResource(R.string.desc_images_to_pdf),
        icon = Icons.Default.Image,
        category = ToolCategory.CONVERT
    ),
    PdfFeature(
        toolId = "pdf_to_image",
        title = stringResource(R.string.tool_pdf_to_images),
        description = stringResource(R.string.desc_pdf_to_images),
        icon = Icons.Default.PhotoLibrary,
        category = ToolCategory.CONVERT
    ),
    PdfFeature(
        toolId = "html_to_pdf",
        title = stringResource(R.string.tool_html_to_pdf),
        description = stringResource(R.string.desc_html_to_pdf),
        icon = Icons.Default.Language,
        category = ToolCategory.CONVERT
    ),
    PdfFeature(
        toolId = "extract_text",
        title = stringResource(R.string.tool_extract_text),
        description = stringResource(R.string.desc_extract_text),
        icon = Icons.Default.TextFields,
        category = ToolCategory.CONVERT
    ),
    PdfFeature(
        toolId = "scan_to_pdf",
        title = stringResource(R.string.tool_scan_to_pdf),
        description = stringResource(R.string.desc_scan_to_pdf),
        icon = Icons.Default.CameraAlt,
        category = ToolCategory.CONVERT
    ),
    PdfFeature(
        toolId = "ocr",
        title = stringResource(R.string.tool_ocr),
        description = stringResource(R.string.desc_ocr),
        icon = Icons.Default.DocumentScanner,
        category = ToolCategory.CONVERT
    ),
    PdfFeature(
        toolId = "image_resize",
        title = stringResource(R.string.tool_image_tools),
        description = stringResource(R.string.desc_image_tools),
        icon = Icons.Default.Photo,
        category = ToolCategory.CONVERT
    ),

    // MARKUP category (Sign, Annotate, Fill Forms)
    PdfFeature(
        toolId = "sign",
        title = stringResource(R.string.tool_sign_pdf),
        description = stringResource(R.string.desc_sign_pdf),
        icon = Icons.Default.Draw,
        category = ToolCategory.MARKUP
    ),
    PdfFeature(
        toolId = "fill_forms",
        title = stringResource(R.string.tool_fill_forms),
        description = stringResource(R.string.desc_fill_forms),
        icon = Icons.Default.EditNote,
        category = ToolCategory.MARKUP
    ),
    PdfFeature(
        toolId = "annotate",
        title = stringResource(R.string.tool_annotate_pdf),
        description = stringResource(R.string.desc_annotate_pdf),
        icon = Icons.Default.Edit,
        category = ToolCategory.MARKUP
    ),

    // SECURITY category (focused on Lock/Unlock)
    PdfFeature(
        toolId = "lock",
        title = stringResource(R.string.tool_add_security),
        description = stringResource(R.string.desc_add_security),
        icon = Icons.Default.Lock,
        category = ToolCategory.SECURITY
    ),
    PdfFeature(
        toolId = "unlock",
        title = stringResource(R.string.tool_unlock_pdf),
        description = stringResource(R.string.desc_unlock_pdf),
        icon = Icons.Default.LockOpen,
        category = ToolCategory.SECURITY
    ),

    // OPTIMIZE category (includes compression, repair, metadata, watermark, flatten)
    PdfFeature(
        toolId = "compress",
        title = stringResource(R.string.tool_compress_pdf),
        description = stringResource(R.string.desc_compress_pdf),
        icon = Icons.Default.Compress,
        category = ToolCategory.OPTIMIZE
    ),
    PdfFeature(
        toolId = "repair",
        title = stringResource(R.string.tool_repair_pdf),
        description = stringResource(R.string.desc_repair_pdf),
        icon = Icons.Default.Build,
        category = ToolCategory.OPTIMIZE
    ),
    PdfFeature(
        toolId = "page_numbers",
        title = stringResource(R.string.tool_page_numbers),
        description = stringResource(R.string.desc_page_numbers),
        icon = Icons.Default.FormatListNumbered,
        category = ToolCategory.OPTIMIZE
    ),
    PdfFeature(
        toolId = "metadata",
        title = stringResource(R.string.tool_view_metadata),
        description = stringResource(R.string.desc_view_metadata),
        icon = Icons.Default.Info,
        category = ToolCategory.OPTIMIZE
    ),
    PdfFeature(
        toolId = "watermark",
        title = stringResource(R.string.tool_add_watermark),
        description = stringResource(R.string.desc_add_watermark),
        icon = Icons.Default.WaterDrop,
        category = ToolCategory.OPTIMIZE
    ),
    PdfFeature(
        toolId = "flatten",
        title = stringResource(R.string.tool_flatten_pdf),
        description = stringResource(R.string.desc_flatten_pdf),
        icon = Icons.Default.Layers,
        category = ToolCategory.OPTIMIZE
    )
)
