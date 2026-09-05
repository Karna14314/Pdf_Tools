package com.yourname.pdftoolkit.ui.screens.imposition

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourname.pdftoolkit.domain.imposition.SheetLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Interactive visual preview engine for imposition layout layouts.
 * Supports multi-sheet navigation, pinch-to-zoom, pan, printable margins, gutters, rotation visualizers, bleeds, and registration marks.
 */
@Composable
fun InteractivePreviewEngine(
    fileUri: Uri?,
    sheetLayouts: List<SheetLayout>,
    modifier: Modifier = Modifier,
    onSheetSelected: (Int) -> Unit = {}
) {
    if (sheetLayouts.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Select a PDF document to view imposition preview",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
        }
        return
    }

    var currentSheetIndex by remember { mutableIntStateOf(0) }
    val currentSheet = sheetLayouts.getOrNull(currentSheetIndex) ?: sheetLayouts.first()

    // Interactive Pan & Zoom states
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val context = LocalContext.current

    // Cache of page bitmaps rendered asynchronously via PdfRenderer
    val pageBitmapCache = remember { mutableStateMapOf<Int, Bitmap>() }

    LaunchedEffect(fileUri) {
        if (fileUri != null) {
            withContext(Dispatchers.IO) {
                try {
                    val pfd = context.contentResolver.openFileDescriptor(fileUri, "r")
                    pfd?.use { descriptor ->
                        val pdfRenderer = PdfRenderer(descriptor)
                        val pageCount = pdfRenderer.pageCount
                        val sampleCount = minOf(pageCount, 16) // Cache up to 16 pages for fast preview

                        for (i in 0 until sampleCount) {
                            val page = pdfRenderer.openPage(i)
                            val w = page.width / 2
                            val h = page.height / 2
                            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            page.close()
                            pageBitmapCache[i] = bitmap
                        }
                        pdfRenderer.close()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E24), RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFF33333F), RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        // Top Toolbar Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Sheet ${currentSheetIndex + 1} of ${sheetLayouts.size}",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { scale = maxOf(0.5f, scale - 0.25f) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.ZoomOut, contentDescription = "Zoom Out", tint = Color.White)
                }
                Text(
                    text = "${(scale * 100).toInt()}%",
                    color = Color.LightGray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                IconButton(
                    onClick = { scale = minOf(4f, scale + 0.25f) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.ZoomIn, contentDescription = "Zoom In", tint = Color.White)
                }
            }

            // Pagination controls
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    enabled = currentSheetIndex > 0,
                    onClick = {
                        if (currentSheetIndex > 0) {
                            currentSheetIndex--
                            onSheetSelected(currentSheetIndex)
                        }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.ChevronLeft,
                        contentDescription = "Previous Sheet",
                        tint = if (currentSheetIndex > 0) Color.White else Color.Gray
                    )
                }
                IconButton(
                    enabled = currentSheetIndex < sheetLayouts.size - 1,
                    onClick = {
                        if (currentSheetIndex < sheetLayouts.size - 1) {
                            currentSheetIndex++
                            onSheetSelected(currentSheetIndex)
                        }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "Next Sheet",
                        tint = if (currentSheetIndex < sheetLayouts.size - 1) Color.White else Color.Gray
                    )
                }
            }
        }

        // Sheet Canvas Viewport
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 4f)
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
            ) {
                val canvasW = size.width
                val canvasH = size.height

                // Calculate sheet scaling to fit viewport
                val sheetW = currentSheet.widthPt
                val sheetH = currentSheet.heightPt

                val scaleToFit = minOf(
                    (canvasW - 32f) / sheetW,
                    (canvasH - 32f) / sheetH
                )

                val displayW = sheetW * scaleToFit
                val displayH = sheetH * scaleToFit

                val sheetLeft = (canvasW - displayW) / 2f
                val sheetTop = (canvasH - displayH) / 2f

                // Draw Sheet Base Paper Background
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(sheetLeft, sheetTop),
                    size = Size(displayW, displayH),
                    cornerRadius = CornerRadius(4f, 4f)
                )

                // Draw Printable Margin Bounds
                val marginL = currentSheet.marginLeftPt * scaleToFit
                val marginT = currentSheet.marginTopPt * scaleToFit
                val marginR = currentSheet.marginRightPt * scaleToFit
                val marginB = currentSheet.marginBottomPt * scaleToFit

                if (marginL > 0 || marginT > 0 || marginR > 0 || marginB > 0) {
                    drawRect(
                        color = Color(0x40007ACC),
                        topLeft = Offset(sheetLeft + marginL, sheetTop + marginT),
                        size = Size(
                            maxOf(0f, displayW - marginL - marginR),
                            maxOf(0f, displayH - marginT - marginB)
                        ),
                        style = Stroke(
                            width = 1.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                        )
                    )
                }

                // Render Placed Source Pages
                for (placement in currentSheet.placements) {
                    val pageX = sheetLeft + placement.xPt * scaleToFit
                    val pageY = sheetTop + placement.yPt * scaleToFit
                    val pageW = placement.widthPt * scaleToFit
                    val pageH = placement.heightPt * scaleToFit

                    if (placement.sourcePageIndex < 0) {
                        // Blank page placeholder
                        drawRoundRect(
                            color = Color(0xFFE5E7EB),
                            topLeft = Offset(pageX, pageY),
                            size = Size(pageW, pageH),
                            cornerRadius = CornerRadius(3f, 3f)
                        )
                        drawRect(
                            color = Color(0xFF9CA3AF),
                            topLeft = Offset(pageX, pageY),
                            size = Size(pageW, pageH),
                            style = Stroke(width = 1f)
                        )
                    } else {
                        val bitmap = pageBitmapCache[placement.sourcePageIndex]
                        if (bitmap != null && !bitmap.isRecycled) {
                            val imageBitmap = bitmap.asImageBitmap()
                            val destOffset = androidx.compose.ui.unit.IntOffset(pageX.toInt(), pageY.toInt())
                            val destSize = androidx.compose.ui.unit.IntSize(pageW.toInt(), pageH.toInt())

                            drawImage(
                                image = imageBitmap,
                                dstOffset = destOffset,
                                dstSize = destSize
                            )
                        } else {
                            // Page representation box
                            drawRoundRect(
                                color = Color(0xFFDBEAFE),
                                topLeft = Offset(pageX, pageY),
                                size = Size(pageW, pageH),
                                cornerRadius = CornerRadius(3f, 3f)
                            )
                        }

                        // Draw Page Boundary Box
                        drawRect(
                            color = Color(0xFF2563EB),
                            topLeft = Offset(pageX, pageY),
                            size = Size(pageW, pageH),
                            style = Stroke(width = 1.5f)
                        )
                    }
                }

                // Draw Crop Marks visualizer
                if (currentSheet.showCropMarks) {
                    val markLength = 10f
                    val markOffset = 3f

                    for (p in currentSheet.placements) {
                        val px = sheetLeft + p.xPt * scaleToFit
                        val py = sheetTop + p.yPt * scaleToFit
                        val pw = p.widthPt * scaleToFit
                        val ph = p.heightPt * scaleToFit

                        // Corner lines
                        drawLine(Color.Red, Offset(px - markOffset - markLength, py), Offset(px - markOffset, py), 1.5f)
                        drawLine(Color.Red, Offset(px, py - markOffset - markLength), Offset(px, py - markOffset), 1.5f)

                        drawLine(Color.Red, Offset(px + pw + markOffset, py), Offset(px + pw + markOffset + markLength, py), 1.5f)
                        drawLine(Color.Red, Offset(px + pw, py - markOffset - markLength), Offset(px + pw, py - markOffset), 1.5f)

                        drawLine(Color.Red, Offset(px - markOffset - markLength, py + ph), Offset(px - markOffset, py + ph), 1.5f)
                        drawLine(Color.Red, Offset(px, py + ph + markOffset), Offset(px, py + ph + markOffset + markLength), 1.5f)

                        drawLine(Color.Red, Offset(px + pw + markOffset, py + ph), Offset(px + pw + markOffset + markLength, py + ph), 1.5f)
                        drawLine(Color.Red, Offset(px + pw, py + ph + markOffset), Offset(px + pw, py + ph + markOffset + markLength), 1.5f)
                    }
                }
            }
        }
    }
}
