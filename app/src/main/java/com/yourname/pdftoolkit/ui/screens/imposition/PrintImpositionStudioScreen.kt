package com.yourname.pdftoolkit.ui.screens.imposition

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yourname.pdftoolkit.domain.imposition.*
import com.yourname.pdftoolkit.ui.components.ResultDialog

data class ImpositionModeMeta(
    val mode: ImpositionToolMode,
    val icon: String,
    val title: String,
    val description: String
)

val impositionModes = listOf(
    ImpositionModeMeta(ImpositionToolMode.BOOKLET, "📖", "Booklet (Saddle Stitch)", "Dual-sided folded booklet layout for booklet printing"),
    ImpositionModeMeta(ImpositionToolMode.N_UP, "🔲", "N-Up Grid Layout", "Multi-page grid on a single sheet (2-up, 4-up, 8-up)"),
    ImpositionModeMeta(ImpositionToolMode.CARDS, "🃏", "Cards & Flashcards", "Business cards, flashcards, and repetitive sheet layouts"),
    ImpositionModeMeta(ImpositionToolMode.CROP_RESIZE, "✂️", "Crop & Resize Margins", "Adjust page margins, trim boundaries, and page fit"),
    ImpositionModeMeta(ImpositionToolMode.BLEED_GENERATOR, "🩸", "Bleed Generator", "Add professional printer bleed zones and edge margins"),
    ImpositionModeMeta(ImpositionToolMode.REGISTRATION_MARKS, "🎯", "Registration & Crop Marks", "Trim marks, center alignment lines, and color calibration targets"),
    ImpositionModeMeta(ImpositionToolMode.ZINE, "📰", "Zine Generator", "Folded 8-page mini-zines and cut-stack publications")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintImpositionStudioScreen(
    onBack: () -> Unit = {},
    viewModel: PrintImpositionViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.setSelectedFile(uri, uri.lastPathSegment ?: "document.pdf")
        }
    }

    var isModeMenuExpanded by remember { mutableStateOf(false) }
    var isPaperPresetMenuExpanded by remember { mutableStateOf(false) }

    val currentModeMeta = impositionModes.firstOrNull { it.mode == uiState.config.mode } ?: impositionModes[0]

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Print & Imposition Studio",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        if (uiState.fileName.isNotEmpty()) {
                            Text(
                                text = "${uiState.fileName} • ${uiState.pageCount} pages",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    FilledTonalButton(
                        onClick = { pdfPickerLauncher.launch("application/pdf") },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open PDF", fontSize = 12.sp)
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { viewModel.exportImposedPdf() },
                        enabled = uiState.fileUri != null && !uiState.isExporting,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (uiState.isExporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Exporting Imposed PDF...", fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.Download, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (uiState.fileUri != null) "Export Imposed PDF (${uiState.calculatedSheets.size} Sheets)"
                                else "Select a PDF to Export",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Unified Mode Selector Dropdown Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isModeMenuExpanded = true },
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(currentModeMeta.icon, fontSize = 22.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Imposition Mode",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = currentModeMeta.title,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = currentModeMeta.description,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "Select Mode",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    DropdownMenu(
                        expanded = isModeMenuExpanded,
                        onDismissRequest = { isModeMenuExpanded = false },
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        impositionModes.forEach { meta ->
                            val isSelected = meta.mode == uiState.config.mode
                            DropdownMenuItem(
                                leadingIcon = { Text(meta.icon, fontSize = 18.sp) },
                                text = {
                                    Column {
                                        Text(meta.title, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium)
                                        Text(meta.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                trailingIcon = {
                                    if (isSelected) {
                                        Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                                    }
                                },
                                onClick = {
                                    isModeMenuExpanded = false
                                    viewModel.updateConfig { it.copy(mode = meta.mode) }
                                }
                            )
                        }
                    }
                }
            }

            // 2. Interactive Live Preview Viewport
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (uiState.fileUri != null) {
                        InteractivePreviewEngine(
                            fileUri = uiState.fileUri,
                            sheetLayouts = uiState.calculatedSheets,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text("📄", fontSize = 42.sp)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "No PDF Selected",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Open a PDF to see live imposition layout sheets & print guides",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = { pdfPickerLauncher.launch("application/pdf") }
                            ) {
                                Icon(Icons.Default.FileOpen, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Choose PDF File", color = Color.White)
                            }
                        }
                    }

                    if (uiState.isExporting) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.85f)),
                            modifier = Modifier.align(Alignment.Center)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(12.dp))
                                Text(uiState.exportProgressMessage, color = Color.White, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }

            // 3. Imposition Controls Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "⚙️ ${currentModeMeta.title} Configuration",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    // Target Paper Size Selection
                    Column {
                        Text(
                            text = "Target Sheet Paper Size",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { isPaperPresetMenuExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        uiState.config.targetPaperSize.preset.displayName,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                                }
                            }

                            DropdownMenu(
                                expanded = isPaperPresetMenuExpanded,
                                onDismissRequest = { isPaperPresetMenuExpanded = false },
                                modifier = Modifier.fillMaxWidth(0.85f)
                            ) {
                                PaperPreset.entries.forEach { preset ->
                                    DropdownMenuItem(
                                        text = { Text(preset.displayName) },
                                        trailingIcon = {
                                            if (uiState.config.targetPaperSize.preset == preset) {
                                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                            }
                                        },
                                        onClick = {
                                            isPaperPresetMenuExpanded = false
                                            viewModel.updateConfig { cfg ->
                                                cfg.copy(targetPaperSize = cfg.targetPaperSize.copy(preset = preset))
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Landscape Toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Landscape Orientation", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                if (uiState.config.isLandscape) "Horizontal sheet alignment" else "Vertical sheet alignment",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.config.isLandscape,
                            onCheckedChange = { isLand ->
                                viewModel.updateConfig { it.copy(isLandscape = isLand) }
                            }
                        )
                    }

                    Divider()

                    // Mode-specific configuration parameters
                    when (uiState.config.mode) {
                        ImpositionToolMode.BOOKLET -> {
                            Text("Binding Direction", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                            BindingDirection.entries.forEach { dir ->
                                val isSelected = uiState.config.bindingDirection == dir
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { viewModel.updateConfig { it.copy(bindingDirection = dir) } }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { viewModel.updateConfig { it.copy(bindingDirection = dir) } }
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Column {
                                        Text(dir.displayName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                        Text(dir.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }

                        ImpositionToolMode.N_UP, ImpositionToolMode.CARDS -> {
                            Text("Grid Columns & Rows", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedTextField(
                                    value = uiState.config.gridCols.toString(),
                                    onValueChange = {
                                        val c = it.toIntOrNull() ?: 1
                                        viewModel.updateConfig { cfg -> cfg.copy(gridCols = maxOf(1, c)) }
                                    },
                                    label = { Text("Columns") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                OutlinedTextField(
                                    value = uiState.config.gridRows.toString(),
                                    onValueChange = {
                                        val r = it.toIntOrNull() ?: 1
                                        viewModel.updateConfig { cfg -> cfg.copy(gridRows = maxOf(1, r)) }
                                    },
                                    label = { Text("Rows") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    shape = RoundedCornerShape(10.dp)
                                )
                            }
                            Text(
                                "Total: ${uiState.config.gridCols * uiState.config.gridRows} pages per sheet",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        ImpositionToolMode.CROP_RESIZE -> {
                            Text("Page Fit Mode", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                            FitMode.entries.forEach { fit ->
                                val isSelected = uiState.config.fitMode == fit
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { viewModel.updateConfig { it.copy(fitMode = fit) } }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { viewModel.updateConfig { it.copy(fitMode = fit) } }
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(fit.displayName, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }

                        ImpositionToolMode.BLEED_GENERATOR, ImpositionToolMode.REGISTRATION_MARKS -> {
                            Text("Bleed & Mark Boundaries", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                            OutlinedTextField(
                                value = uiState.config.bleedMm.toString(),
                                onValueChange = {
                                    val b = it.toFloatOrNull() ?: 3f
                                    viewModel.updateConfig { cfg -> cfg.copy(bleedMm = b) }
                                },
                                label = { Text("Bleed Margin (mm)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = uiState.config.showCropMarks,
                                    onCheckedChange = { show -> viewModel.updateConfig { it.copy(showCropMarks = show) } }
                                )
                                Text("Show Printer Crop Marks (Corner Trim Guides)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = uiState.config.showRegistrationTargets,
                                    onCheckedChange = { show -> viewModel.updateConfig { it.copy(showRegistrationTargets = show) } }
                                )
                                Text("Show Registration Crosshairs & Targets", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }

                        ImpositionToolMode.ZINE -> {
                            Text("Zine Publication Type", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                            ZineType.entries.forEach { zine ->
                                val isSelected = uiState.config.zineType == zine
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { viewModel.updateConfig { it.copy(zineType = zine) } }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { viewModel.updateConfig { it.copy(zineType = zine) } }
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Column {
                                        Text(zine.displayName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                        Text(zine.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    // Error Alert Dialog
    if (uiState.errorMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearExportResult() },
            title = { Text("Export Issue", fontWeight = FontWeight.Bold) },
            text = { Text(uiState.errorMessage ?: "An unexpected error occurred.") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearExportResult() }) {
                    Text("OK")
                }
            }
        )
    }

    // Export Success Dialog
    if (uiState.exportedFile != null) {
        ResultDialog(
            isSuccess = true,
            title = "Export Complete!",
            message = "Imposed PDF successfully saved to Documents/PDF Toolkit:\n${uiState.exportedFile?.name}",
            onDismiss = { viewModel.clearExportResult() },
            onAction = {
                val uri = uiState.exportedUri
                if (uri != null) {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/pdf")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Open Imposed PDF"))
                }
                viewModel.clearExportResult()
            },
            actionText = "Open"
        )
    }
}
