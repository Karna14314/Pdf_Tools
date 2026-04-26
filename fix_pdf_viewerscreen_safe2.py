import re
import subprocess

file_path = "app/src/main/java/com/yourname/pdftoolkit/ui/screens/PdfViewerScreen.kt"

with open(file_path, "r") as f:
    content = f.read()

# Instead of complex replacements, I will use simple precise replacements.

old_tail = """                } else {
                    // Invalid bitmap - show placeholder
                    InvalidBitmapPlaceholder()
                }
            } else {
                // Placeholder
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f / 1.414f)
                        .background(Color.LightGray.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            }

            // Search Highlights Overlay - use bitmap snapshot to avoid race conditions
            if (pageMatches.isNotEmpty()) {
                val bitmapSnapshot = bitmap
                if (bitmapSnapshot != null && !bitmapSnapshot.isRecycled && bitmapSnapshot.width > 0 && bitmapSnapshot.height > 0) {
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
            }

            // Annotation overlay (kept same)
            if ((isEditMode || annotations.isNotEmpty()) && bitmap != null) {
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
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = denormalizedStrokeWidth,
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                    join = androidx.compose.ui.graphics.StrokeJoin.Round
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
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = strokeWidth,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                join = androidx.compose.ui.graphics.StrokeJoin.Round
                            ),
                            blendMode = blendMode
                        )
                    }
                }
            }
        }
    }
}
"""

new_tail = """
                        // Search Highlights Overlay - use bitmap snapshot to avoid race conditions
                        if (pageMatches.isNotEmpty()) {
                            val bitmapSnapshot = bitmap
                            if (bitmapSnapshot != null && !bitmapSnapshot.isRecycled && bitmapSnapshot.width > 0 && bitmapSnapshot.height > 0) {
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
                        }

                        // Annotation overlay (kept same)
                        if ((isEditMode || annotations.isNotEmpty()) && bitmap != null) {
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
                                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                                width = denormalizedStrokeWidth,
                                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                                join = androidx.compose.ui.graphics.StrokeJoin.Round
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
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                                            width = strokeWidth,
                                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                            join = androidx.compose.ui.graphics.StrokeJoin.Round
                                        ),
                                        blendMode = blendMode
                                    )
                                }
                            }
                        }
                    } // close graphicsLayer Box
                } else {
                    // Invalid bitmap - show placeholder
                    InvalidBitmapPlaceholder()
                }
            } else {
                // Placeholder
                val heightDp = with(androidx.compose.ui.platform.LocalDensity.current) { lastMeasuredPageHeight.toDp() }
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
        }
    }
}
"""
content = content.replace(old_tail, new_tail)

with open(file_path, "w") as f:
    f.write(content)
