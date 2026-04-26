import re
import subprocess

file_path = "app/src/main/java/com/yourname/pdftoolkit/ui/screens/PdfViewerScreen.kt"

with open(file_path, "r") as f:
    content = f.read()

# I will rewrite the whole `PdfPageWithAnnotations` correctly.
# First, let's locate `Box(` line 1245 inside PdfPageWithAnnotations and replace it
# The original starts at:
#     Box(
#         modifier = Modifier
#             .fillMaxWidth()
#             .onSizeChanged { size = it }
#             .then( ... gestures ... ) ) {

# Let's write the new `PdfPageWithAnnotations` from that `Box` onwards.
# I will use a simple regex replacement for the content.

pattern_body = re.compile(
    r'        Box\(\s*modifier = Modifier\s*\.fillMaxWidth\(\)\s*\.onSizeChanged \{ size = it \}.*?(?=    \}\n\})',
    re.DOTALL
)

# Wait, `Box` wraps the `Image` and the `Canvas` overlays.
# I will replace the inside of `Box(modifier = Modifier.fillMaxWidth().padding(...) ... )`
# This outer `Box` ends at `    }\n}` at the end of the function.

new_body = """        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged {
                    size = it
                    if (it.height > 0) {
                        onPageSizeMeasured(it.height.toFloat())
                    }
                }
                .then(
                    if (isEditMode && selectedTool != AnnotationTool.NONE) {
                        Modifier
                    } else {
                        Modifier.pointerInput(scale, pagePanX) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newScale = (scale * zoom).coerceIn(1f, 5f)
                                onScaleChange(newScale)

                                if (newScale > 1f) {
                                    val maxPanX = (size.width * (newScale - 1f)) / 2f
                                    onPagePanXChange((pagePanX + pan.x).coerceIn(-maxPanX, maxPanX))
                                } else {
                                    onPagePanXChange(0f)
                                }
                            }
                        }
                    }
                )
        ) {
            if (!shouldLoad) {
                // Show same-height placeholder without loading bitmap
                val heightDp = with(LocalDensity.current) { lastMeasuredPageHeight.toDp() }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(heightDp)
                        .background(Color.LightGray.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            } else if (bitmap != null) {
                val bitmapSnapshot = bitmap
                if (bitmapSnapshot != null && !bitmapSnapshot.isRecycled && bitmapSnapshot.width > 0 && bitmapSnapshot.height > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = pagePanX
                                clip = true
                            }
                    ) {
                        Image(
                            bitmap = bitmapSnapshot.asImageBitmap(),
                            contentDescription = "Page ${pageIndex + 1}",
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.FillWidth
                        )

                        // Search Highlights Overlay
                        if (pageMatches.isNotEmpty()) {
                            Canvas(modifier = Modifier.matchParentSize()) {
                                pageMatches.forEachIndexed { index, match ->
                                    val color = if (index == currentMatchIndexOnPage) {
                                        Color(0xFFFF8C00).copy(alpha = 0.5f)
                                    } else {
                                        Color.Yellow.copy(alpha = 0.4f)
                                    }

                                    match.rects.forEach { rect ->
                                        // Scale rect to current canvas size
                                        val scaleX = size.width.toFloat() / bitmapSnapshot.width.toFloat()
                                        val scaleY = size.height.toFloat() / bitmapSnapshot.height.toFloat()

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

                        // Annotation overlay
                        if (isEditMode || annotations.isNotEmpty()) {
                            Canvas(
                                modifier = Modifier
                                    .matchParentSize()
                                    .then(
                                        if (isEditMode && selectedTool != AnnotationTool.NONE) {
                                            Modifier.pointerInput(isEditMode, selectedTool, selectedColor) {
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
                                                            val strokeWidth = when (selectedTool) {
                                                                AnnotationTool.HIGHLIGHTER -> 20f
                                                                AnnotationTool.MARKER -> 8f
                                                                AnnotationTool.UNDERLINE -> 4f
                                                                else -> 8f
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

                                                            // Normalize coordinates based on current size
                                                            // Map touch points -> normalized (0.0 - 1.0) relative to page
                                                            val normalizedPoints = finalPoints.map {
                                                                Offset(it.x / size.width, it.y / size.height)
                                                            }

                                                            // Normalize stroke width relative to page width
                                                            val normalizedStrokeWidth = strokeWidth / size.width

                                                            onAddAnnotation(
                                                                AnnotationStroke(
                                                                    pageIndex = pageIndex,
                                                                    tool = selectedTool,
                                                                    color = selectedColor,
                                                                    points = normalizedPoints,
                                                                    strokeWidth = normalizedStrokeWidth
                                                                )
                                                            )
                                                            onCurrentStrokeChange(emptyList())
                                                        }
                                                    },
                                                    onDragCancel = {
                                                        onCurrentStrokeChange(emptyList())
                                                    }
                                                )
                                            }
                                        } else {
                                            Modifier
                                        }
                                    )
                            ) {
                                // Draw saved annotations (using normalized coordinates)
                                annotations.forEach { stroke ->
                                    val denormalizedPoints = stroke.points.map {
                                        Offset(it.x * size.width, it.y * size.height)
                                    }
                                    val denormalizedStrokeWidth = stroke.strokeWidth * size.width

                                    if (denormalizedPoints.size >= 2) {
                                        val path = androidx.compose.ui.graphics.Path()
                                        path.moveTo(denormalizedPoints.first().x, denormalizedPoints.first().y)
                                        for (i in 1 until denormalizedPoints.size) {
                                            path.lineTo(denormalizedPoints[i].x, denormalizedPoints[i].y)
                                        }

                                        val alpha = if (stroke.tool == AnnotationTool.HIGHLIGHTER) 0.4f else 1.0f
                                        val blendMode = if (stroke.tool == AnnotationTool.HIGHLIGHTER) {
                                            androidx.compose.ui.graphics.BlendMode.Multiply
                                        } else {
                                            androidx.compose.ui.graphics.BlendMode.SrcOver
                                        }

                                        drawPath(
                                            path = path,
                                            color = stroke.color.copy(alpha = alpha),
                                            style = Stroke(
                                                width = denormalizedStrokeWidth,
                                                cap = StrokeCap.Round,
                                                join = StrokeJoin.Round
                                            ),
                                            blendMode = blendMode
                                        )
                                    }
                                }

                                // Draw current stroke
                                if (currentDrawingPageIndex == pageIndex && currentStroke.isNotEmpty() && currentStroke.size >= 2) {
                                    val strokeWidth = when (selectedTool) {
                                        AnnotationTool.HIGHLIGHTER -> 20f
                                        AnnotationTool.MARKER -> 8f
                                        AnnotationTool.UNDERLINE -> 4f
                                        else -> 8f
                                    }

                                    // Real-time horizontal snapping preview for highlighter
                                    val pointsToDraw = if (selectedTool == AnnotationTool.HIGHLIGHTER && currentStroke.size >= 2) {
                                        val minX = currentStroke.minOf { it.x }
                                        val maxX = currentStroke.maxOf { it.x }
                                        val avgY = currentStroke.map { it.y }.average().toFloat()
                                        listOf(Offset(minX, avgY), Offset(maxX, avgY))
                                    } else {
                                        currentStroke
                                    }

                                    val path = androidx.compose.ui.graphics.Path()
                                    path.moveTo(pointsToDraw.first().x, pointsToDraw.first().y)
                                    for (i in 1 until pointsToDraw.size) {
                                        path.lineTo(pointsToDraw[i].x, pointsToDraw[i].y)
                                    }

                                    val alpha = if (selectedTool == AnnotationTool.HIGHLIGHTER) 0.4f else 1.0f
                                    val blendMode = if (selectedTool == AnnotationTool.HIGHLIGHTER) {
                                        androidx.compose.ui.graphics.BlendMode.Multiply
                                    } else {
                                        androidx.compose.ui.graphics.BlendMode.SrcOver
                                    }

                                    drawPath(
                                        path = path,
                                        color = selectedColor.copy(alpha = alpha),
                                        style = Stroke(
                                            width = strokeWidth,
                                            cap = StrokeCap.Round,
                                            join = StrokeJoin.Round
                                        ),
                                        blendMode = blendMode
                                    )
                                }
                            }
                        }
                    }
                } else {
                    InvalidBitmapPlaceholder()
                }
            } else {
                val heightDp = with(LocalDensity.current) { lastMeasuredPageHeight.toDp() }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(heightDp)
                        .background(Color.LightGray.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            }
        }"""

match_body = pattern_body.search(content)
if match_body:
    content = content.replace(match_body.group(0), new_body)

with open(file_path, "w") as f:
    f.write(content)
