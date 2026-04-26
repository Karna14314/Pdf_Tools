import re

file_path = "app/src/main/java/com/yourname/pdftoolkit/ui/screens/PdfViewerScreen.kt"

with open(file_path, "r") as f:
    content = f.read()

# 1. Update loadPage in produceState to use Dispatchers.IO
content = content.replace(
    'value = loadPage(pageIndex)',
    'value = withContext(kotlinx.coroutines.Dispatchers.IO) { loadPage(pageIndex) }'
)

# 2. Add viewport size tracking in PdfPagesContent
# PdfPagesContent currently has:
#    val animatedScale by animateFloatAsState(
content = content.replace(
    '    val animatedScale by animateFloatAsState(',
    '    val density = androidx.compose.ui.platform.LocalDensity.current\n    var lastMeasuredPageHeight by remember { mutableStateOf(with(density) { 400.dp.toPx() }) }\n    val animatedScale by animateFloatAsState('
)

# 3. Update visiblePages in PdfPagesContent
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

# 4. Modify PdfPageWithAnnotations to accept lastMeasuredPageHeight and pass it
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
                    lastMeasuredPageHeight = lastMeasuredPageHeight,
                    onPageSizeMeasured = { height -> lastMeasuredPageHeight = height }
                )"""
)

content = content.replace(
"""    pageMatches: List<SearchMatch>,
    currentMatchIndexOnPage: Int
) {""",
"""    pageMatches: List<SearchMatch>,
    currentMatchIndexOnPage: Int,
    lastMeasuredPageHeight: Float = 0f,
    onPageSizeMeasured: (Float) -> Unit = {}
) {"""
)


# 5. Fix gesture container and graphic layer
old_box = """    Box(
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
                .onSizeChanged { size = it }
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f / 1.414f)
                        .background(Color.LightGray.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            } else if (bitmap != null) {
                // PDF page image - validate before use to prevent race conditions
                val bitmapSnapshot = bitmap
                if (bitmapSnapshot != null && !bitmapSnapshot.isRecycled && bitmapSnapshot.width > 0 && bitmapSnapshot.height > 0) {
                    Image(
                        bitmap = bitmapSnapshot.asImageBitmap(),
                        contentDescription = "Page ${pageIndex + 1}",
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = pagePanX
                                clip = true
                            },
                        contentScale = ContentScale.FillWidth
                    )
                } else {
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
            }"""

new_box = """    Box(
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
                // PDF page image - validate before use to prevent race conditions
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
                        )"""

content = content.replace(old_box, new_box)

# Now, we need to close the `Box` at the end of the `PdfPageWithAnnotations` function
# It ends with:
#                 }
#             }
#         }
#     }
# }
# Since we added one `Box` layer for `graphicsLayer`, we need one more `}`

# First, let's fix the invalid bitmap and placeholder sizes
old_placeholder = """                } else {
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

            // Search Highlights Overlay - use bitmap snapshot to avoid race conditions"""
new_placeholder = """                        // Search Highlights Overlay - use bitmap snapshot to avoid race conditions"""

# Wait, search highlights and annotations should be INSIDE the `Box` that we opened.
# Let's replace the entire rest of the function by finding the exact text.
