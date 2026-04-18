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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yourname.pdftoolkit.BuildConfig
import com.yourname.pdftoolkit.R
import com.yourname.pdftoolkit.data.SafUriManager
import com.yourname.pdftoolkit.ui.navigation.Screen
import kotlinx.coroutines.launch

/**
 * Tool section enumeration for categorization.
 */
@Composable
fun getToolSections(): List<ToolSectionData> {
    return listOf(
        ToolSectionData(stringResource(R.string.category_quick_actions)),
        ToolSectionData(stringResource(R.string.category_organize)),
        ToolSectionData(stringResource(R.string.category_convert)),
        ToolSectionData(stringResource(R.string.category_security)),
        ToolSectionData(stringResource(R.string.category_image_tools)),
        ToolSectionData(stringResource(R.string.category_view_export))
    )
}

data class ToolSectionData(val title: String)

// Keep for backwards compatibility
enum class ToolSection(val title: String) {
    QUICK_ACTIONS("Quick Actions"),
    ORGANIZE("Organize"),
    CONVERT("Convert"),
    SECURITY("Security"),
    IMAGE_TOOLS("Image Tools"),
    VIEW_EXPORT("View & Export")
}

/**
 * Data class representing a PDF/Image tool.
 */
data class ToolItem(
    val id: String,
    val titleResId: Int,
    val descResId: Int,
    val icon: ImageVector,
    val section: ToolSection,
    val screen: Screen
) {
    @Composable
    fun getTitle(): String = stringResource(titleResId)
    
    @Composable
    fun getDescription(): String = stringResource(descResId)
}

/**
 * Tools Screen - Primary home screen with sectioned layout.
 * Organized in grid/card-based design with clear categorization.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    onNavigateToScreen: (Screen) -> Unit,
    onNavigateToRoute: ((String) -> Unit)? = null,
    onOpenPdfViewer: (Uri, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    /**
     * PDF picker using SAF (ACTION_OPEN_DOCUMENT).
     * ActivityResultContracts.OpenDocument() automatically uses ACTION_OPEN_DOCUMENT
     * with CATEGORY_OPENABLE for proper Android 10+ scoped storage compliance.
     */
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { selectedUri ->
            scope.launch {
                // Take persistable URI permission immediately
                // This is CRITICAL for reopening files later
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                val persistedFile = SafUriManager.addRecentFile(context, selectedUri, flags)
                
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
    
    val allTools = getAllTools()
    
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // Subtitle
        item {
            Text(
                text = stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        // Sections
        ToolSection.entries.forEach { section ->
            val sectionTools = allTools.filter { it.section == section }
            if (sectionTools.isNotEmpty()) {
                item {
                    SectionHeader(title = section.title)
                }
                
                item {
                    ToolGrid(
                        tools = sectionTools,
                        onToolClick = { tool ->
                            if (tool.screen == Screen.Home && tool.id == "view_pdf") {
                                // Special handling for View PDF
                                pdfPickerLauncher.launch(arrayOf("application/pdf"))
                            } else {
                                // Check if this is an image tool that needs special routing
                                val imageToolIds = listOf("image_compress", "image_resize", "image_convert", "image_metadata")
                                if (imageToolIds.contains(tool.id) && onNavigateToRoute != null) {
                                    // Use route with operation parameter for image tools
                                    val route = Screen.getRouteForToolId(tool.id)
                                    onNavigateToRoute(route)
                                } else {
                                    // Use screen object for other tools
                                    onNavigateToScreen(tool.screen)
                                }
                            }
                        }
                    )
                }
            }
        }
        
        // Bottom spacing
        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Divider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

@Composable
private fun ToolGrid(
    tools: List<ToolItem>,
    onToolClick: (ToolItem) -> Unit
) {
    // Use a 3-column grid for compact display
    val rows = tools.chunked(3)
    
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        rows.forEachIndexed { rowIndex, rowTools ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowTools.forEach { tool ->
                    ToolCard(
                        tool = tool,
                        onClick = { onToolClick(tool) },
                        modifier = Modifier.weight(1f)
                    )
                }
                // Fill remaining space if row is incomplete
                repeat(3 - rowTools.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolCard(
    tool: ToolItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isVisible by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        isVisible = true
    }
    
    val scale by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0.9f,
        animationSpec = tween(durationMillis = 200),
        label = "tool_card_scale"
    )
    
    Card(
        onClick = onClick,
        modifier = modifier
            .scale(scale)
            .aspectRatio(1f),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = tool.icon,
                    contentDescription = tool.getTitle(),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(24.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = tool.getTitle(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * Get all tools organized by section.
 * Total: 25+ tools
 */
@Composable
fun getAllTools(): List<ToolItem> = listOf(
    // SECTION 1: QUICK ACTIONS (Top, Always Visible)
    ToolItem(
        id = "merge",
        titleResId = R.string.tool_merge_pdf,
        descResId = R.string.desc_merge_pdfs,
        icon = Icons.Default.MergeType,
        section = ToolSection.QUICK_ACTIONS,
        screen = Screen.Merge
    ),
    ToolItem(
        id = "split",
        titleResId = R.string.tool_split_pdf,
        descResId = R.string.desc_split_pdf,
        icon = Icons.Default.CallSplit,
        section = ToolSection.QUICK_ACTIONS,
        screen = Screen.Split
    ),
    ToolItem(
        id = "compress",
        titleResId = R.string.tool_compress_pdf,
        descResId = R.string.desc_compress_pdf,
        icon = Icons.Default.Compress,
        section = ToolSection.QUICK_ACTIONS,
        screen = Screen.Compress
    ),
    ToolItem(
        id = "pdf_to_image",
        titleResId = R.string.tool_pdf_to_images,
        descResId = R.string.desc_pdf_to_images,
        icon = Icons.Default.PhotoLibrary,
        section = ToolSection.QUICK_ACTIONS,
        screen = Screen.PdfToImage
    ),
    ToolItem(
        id = "image_to_pdf",
        titleResId = R.string.tool_images_to_pdf,
        descResId = R.string.desc_images_to_pdf,
        icon = Icons.Default.Image,
        section = ToolSection.QUICK_ACTIONS,
        screen = Screen.Convert
    ),
    
    // SECTION 2: ORGANIZE
    ToolItem(
        id = "reorder",
        titleResId = R.string.tool_reorder_pages,
        descResId = R.string.desc_reorder_pages,
        icon = Icons.Default.SwapVert,
        section = ToolSection.ORGANIZE,
        screen = Screen.Reorder
    ),
    ToolItem(
        id = "rotate",
        titleResId = R.string.tool_rotate_pages,
        descResId = R.string.desc_rotate_pages,
        icon = Icons.Default.RotateRight,
        section = ToolSection.ORGANIZE,
        screen = Screen.Rotate
    ),
    ToolItem(
        id = "delete_pages",
        titleResId = R.string.tool_delete_pages,
        descResId = R.string.desc_delete_pages,
        icon = Icons.Default.Delete,
        section = ToolSection.ORGANIZE,
        screen = Screen.Organize
    ),
    ToolItem(
        id = "extract",
        titleResId = R.string.tool_extract_pages,
        descResId = R.string.desc_extract_pages,
        icon = Icons.Default.ContentCopy,
        section = ToolSection.ORGANIZE,
        screen = Screen.Extract
    ),
    
    // SECTION 3: CONVERT (PDF-CENTRIC)
    ToolItem(
        id = "html_to_pdf",
        titleResId = R.string.tool_html_to_pdf,
        descResId = R.string.desc_html_to_pdf,
        icon = Icons.Default.Language,
        section = ToolSection.CONVERT,
        screen = Screen.HtmlToPdf
    ),
    ToolItem(
        id = "scan_to_pdf",
        titleResId = R.string.tool_scan_to_pdf,
        descResId = R.string.desc_scan_to_pdf,
        icon = Icons.Default.CameraAlt,
        section = ToolSection.CONVERT,
        screen = Screen.ScanToPdf
    ),
    ToolItem(
        id = "ocr",
        titleResId = R.string.tool_ocr,
        descResId = R.string.desc_ocr,
        icon = Icons.Default.DocumentScanner,
        section = ToolSection.CONVERT,
        screen = Screen.Ocr
    ),
    ToolItem(
        id = "extract_text",
        titleResId = R.string.tool_extract_text,
        descResId = R.string.desc_extract_text,
        icon = Icons.Default.TextFields,
        section = ToolSection.CONVERT,
        screen = Screen.ExtractText
    ),
    
    // SECTION 4: SECURITY
    ToolItem(
        id = "lock",
        titleResId = R.string.tool_lock_pdf,
        descResId = R.string.desc_lock_pdf,
        icon = Icons.Default.Lock,
        section = ToolSection.SECURITY,
        screen = Screen.Security
    ),
    ToolItem(
        id = "unlock",
        titleResId = R.string.tool_unlock_pdf,
        descResId = R.string.desc_unlock_pdf,
        icon = Icons.Default.LockOpen,
        section = ToolSection.SECURITY,
        screen = Screen.Unlock
    ),
    ToolItem(
        id = "watermark",
        titleResId = R.string.tool_add_watermark,
        descResId = R.string.desc_add_watermark,
        icon = Icons.Default.WaterDrop,
        section = ToolSection.SECURITY,
        screen = Screen.Watermark
    ),
    ToolItem(
        id = "sign",
        titleResId = R.string.tool_sign_pdf,
        descResId = R.string.desc_sign,
        icon = Icons.Default.Draw,
        section = ToolSection.SECURITY,
        screen = Screen.SignPdf
    ),
    ToolItem(
        id = "flatten",
        titleResId = R.string.tool_flatten_pdf,
        descResId = R.string.desc_flatten_pdf,
        icon = Icons.Default.Layers,
        section = ToolSection.SECURITY,
        screen = Screen.Flatten
    ),
    
    // SECTION 5: IMAGE TOOLS (LOW-BLOAT ONLY)
    ToolItem(
        id = "image_compress",
        titleResId = R.string.tool_image_tools,
        descResId = R.string.desc_compress_image,
        icon = Icons.Default.Compress,
        section = ToolSection.IMAGE_TOOLS,
        screen = Screen.ImageTools
    ),
    ToolItem(
        id = "image_resize",
        titleResId = R.string.tool_image_tools,
        descResId = R.string.desc_resize_image,
        icon = Icons.Default.AspectRatio,
        section = ToolSection.IMAGE_TOOLS,
        screen = Screen.ImageTools
    ),
    ToolItem(
        id = "image_convert",
        titleResId = R.string.tool_image_tools,
        descResId = R.string.desc_convert_format,
        icon = Icons.Default.Transform,
        section = ToolSection.IMAGE_TOOLS,
        screen = Screen.ImageTools
    ),
    ToolItem(
        id = "image_metadata",
        titleResId = R.string.tool_image_tools,
        descResId = R.string.desc_strip_metadata,
        icon = Icons.Default.DeleteSweep,
        section = ToolSection.IMAGE_TOOLS,
        screen = Screen.ImageTools
    ),
    
    // SECTION 6: VIEW & EXPORT
    ToolItem(
        id = "view_pdf",
        titleResId = R.string.tool_view_metadata,
        descResId = R.string.desc_view_pdf,
        icon = Icons.Default.PictureAsPdf,
        section = ToolSection.VIEW_EXPORT,
        screen = Screen.Home // Special handling
    ),
    ToolItem(
        id = "page_numbers",
        titleResId = R.string.tool_page_numbers,
        descResId = R.string.desc_page_numbers,
        icon = Icons.Default.FormatListNumbered,
        section = ToolSection.VIEW_EXPORT,
        screen = Screen.PageNumber
    ),
    ToolItem(
        id = "metadata",
        titleResId = R.string.tool_view_metadata,
        descResId = R.string.desc_view_metadata,
        icon = Icons.Default.Info,
        section = ToolSection.VIEW_EXPORT,
        screen = Screen.Metadata
    )
)
