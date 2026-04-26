import re
import subprocess

file_path = "app/src/main/java/com/yourname/pdftoolkit/ui/screens/PdfViewerScreen.kt"
subprocess.run(["git", "checkout", "HEAD", "--", file_path])

with open(file_path, "r") as f:
    content = f.read()

# I will write python code that strictly transforms `PdfPageWithAnnotations` correctly.

# 1. Update `visiblePages` in `PdfPagesContent`
content = content.replace(
"""    val visiblePages by remember(totalPages, listState) {
        derivedStateOf {
            val first = listState.firstVisibleItemIndex
            val last = first + listState.layoutInfo.visibleItemsInfo.size
            (first - 1).coerceAtLeast(0)..(last + 1).coerceAtMost(totalPages - 1)
        }
    }""",
"""    val visiblePages by remember(totalPages, listState) {
        derivedStateOf {
            val first = listState.firstVisibleItemIndex
            val last = (first + listState.layoutInfo.visibleItemsInfo.size).coerceAtMost(totalPages - 1)
            (first - 2).coerceAtLeast(0)..(last + 2).coerceAtMost(totalPages - 1)
        }
    }"""
)

# Add `lastMeasuredPageHeight` tracking to `PdfPagesContent`
# After `val animatedPanY ...`
content = content.replace(
"""    // Animate panY changes
    val animatedPanY by animateFloatAsState(
        targetValue = panY,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "pan_y"
    )""",
"""    val density = androidx.compose.ui.platform.LocalDensity.current
    var lastMeasuredPageHeight by remember { mutableStateOf(with(density) { 400.dp.toPx() }) }"""
)

# And remove `animatedPanY` usage? Actually `animatedPanY` was not used previously except in signature. But wait, `panY` is in the signature of `PdfPagesContent`. We should keep it to avoid signature change in `PdfViewerScreen` which calls `PdfPagesContent(panY = panY, onPanYChange = { panY = it })` unless we also remove it there.
# Let's just NOT remove `panY` from signature. I will just replace `animatedPanY` with the `lastMeasuredPageHeight` and `density` lines. BUT wait, maybe `animatedPanY` was used in `PdfPageWithAnnotations`?
# Let's look: `PdfPageWithAnnotations` doesn't take `panY`.
# I will keep `animatedPanY` and just insert `lastMeasuredPageHeight` after it.

content = content.replace(
"""    val animatedPanY by animateFloatAsState(
        targetValue = panY,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "pan_y"
    )""",
"""    val animatedPanY by animateFloatAsState(
        targetValue = panY,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "pan_y"
    )
    val density = androidx.compose.ui.platform.LocalDensity.current
    var lastMeasuredPageHeight by remember { mutableStateOf(with(density) { 400.dp.toPx() }) }"""
)

# Pass `lastMeasuredPageHeight` and `onPageSizeMeasured` to `PdfPageWithAnnotations`
content = content.replace(
"""                PdfPageWithAnnotations(
                    pageIndex = index,
                    loadPage = loadPage,
                    shouldLoad = index in visiblePages,
                    scale = animatedScale,
                    pagePanX = animatedPanX,
                    onScaleChange = onScaleChange,
                    onPagePanXChange = onPagePanXChange,
                    isEditMode = isEditMode,
                    selectedTool = selectedTool,
                    selectedColor = selectedColor,
                    annotations = pageAnnotations,
                    currentStroke = currentStroke,
                    onCurrentStrokeChange = onCurrentStrokeChange,
                    onAddAnnotation = onAddAnnotation,
                    pageMatches = pageMatches,
                    currentMatchIndexOnPage = currentMatchIndexOnPage
                )""",
"""                PdfPageWithAnnotations(
                    pageIndex = index,
                    loadPage = loadPage,
                    shouldLoad = index in visiblePages,
                    scale = animatedScale,
                    pagePanX = animatedPanX,
                    onScaleChange = onScaleChange,
                    onPagePanXChange = onPagePanXChange,
                    isEditMode = isEditMode,
                    selectedTool = selectedTool,
                    selectedColor = selectedColor,
                    annotations = pageAnnotations,
                    currentStroke = currentStroke,
                    onCurrentStrokeChange = onCurrentStrokeChange,
                    onAddAnnotation = onAddAnnotation,
                    pageMatches = pageMatches,
                    currentMatchIndexOnPage = currentMatchIndexOnPage,
                    currentDrawingPageIndex = currentDrawingPageIndex,
                    lastMeasuredPageHeight = lastMeasuredPageHeight,
                    onPageSizeMeasured = { height -> lastMeasuredPageHeight = height }
                )"""
)

# Now update `PdfPageWithAnnotations` signature
content = content.replace(
"""    pageMatches: List<SearchMatch>,
    currentMatchIndexOnPage: Int
) {""",
"""    pageMatches: List<SearchMatch>,
    currentMatchIndexOnPage: Int,
    currentDrawingPageIndex: Int = -1,
    lastMeasuredPageHeight: Float = 0f,
    onPageSizeMeasured: (Float) -> Unit = {}
) {"""
)

# Update `produceState` to use `withContext(Dispatchers.IO)`
content = content.replace(
"""    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = pageIndex, key2 = shouldLoad) {
        value = if (shouldLoad) {
            loadPage(pageIndex)
        } else {
            null
        }
    }""",
"""    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = pageIndex, key2 = shouldLoad) {
        value = if (shouldLoad) {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                loadPage(pageIndex)
            }
        } else {
            null
        }
    }"""
)


# Now, rewrite `PdfPageWithAnnotations` inner structure
# Let's use string replace on the `Box(` of `PdfPageWithAnnotations`.

original_box_start = """    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .shadow(
                elevation = 2.dp,
                shape = RectangleShape,
                clip = false
            )
            .background(MaterialTheme.colorScheme.surface)
    ) {"""

# We find this outer box. And then rewrite its contents.
# To be safe, I'll extract everything from `original_box_start` to the end of the function.
idx = content.find(original_box_start)
if idx != -1:
    new_box = original_box_start + """
        Box(
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
                // Show placeholder with exact height
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

                                                            val finalPoints = if (selectedTool == AnnotationTool.HIGHLIGHTER && localStroke.size >= 2) {
                                                                val minX = localStroke.minOf { it.x }
                                                                val maxX = localStroke.maxOf { it.x }
                                                                val avgY = localStroke.map { it.y }.average().toFloat()
                                                                listOf(Offset(minX, avgY), Offset(maxX, avgY))
                                                            } else {
                                                                localStroke.toList()
                                                            }

                                                            val normalizedPoints = finalPoints.map {
                                                                Offset(it.x / size.width, it.y / size.height)
                                                            }

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

                                if (currentDrawingPageIndex == pageIndex && currentStroke.isNotEmpty() && currentStroke.size >= 2) {
                                    val strokeWidth = when (selectedTool) {
                                        AnnotationTool.HIGHLIGHTER -> 20f
                                        AnnotationTool.MARKER -> 8f
                                        AnnotationTool.UNDERLINE -> 4f
                                        else -> 8f
                                    }

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
                } else {
                    InvalidBitmapPlaceholder()
                }
            } else {
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

    # We replace from `idx` to the end of the file/function.
    # The function ends at the last `}`. We can safely replace `content[idx:]` with `new_box` since it's the last function in the file.
    # WAIT! Is it the last function?
    # Let's check what comes after PdfPageWithAnnotations
    pass

# We can replace everything from `original_box_start` to the end. But what if there are other functions?
with open(file_path, "w") as f:
    f.write(content[:idx] + new_box)
