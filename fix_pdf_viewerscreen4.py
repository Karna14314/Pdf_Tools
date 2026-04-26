import re
import subprocess

file_path = "app/src/main/java/com/yourname/pdftoolkit/ui/screens/PdfViewerScreen.kt"

with open(file_path, "r") as f:
    content = f.read()

# Create dynamic variable `lastMeasuredPageHeight` outside PdfPagesContent
# But wait, PdfPagesContent takes `totalPages`, `loadPage`, etc.
# We should add `var lastMeasuredPageHeight by remember { mutableFloatStateOf(400f * density) }`
# wait, better to track it locally inside `PdfPagesContent`

# Wait, `estimatedPageHeight` logic:
# `val density = LocalDensity.current`
# `var lastMeasuredPageHeight by remember { mutableFloatStateOf(with(density) { 400.dp.toPx() }) }`
# And then pass it down or update it inside `PdfPageWithAnnotations`?
# In `PdfPageWithAnnotations`, `onSizeChanged { size = it; onPageSizeMeasured(it.height.toFloat()) }`
# Let's just track it inside `PdfPagesContent`

# In `PdfPagesContent`:
content = content.replace(
    'val animatedScale by animateFloatAsState(',
    'val density = LocalDensity.current\n    var lastMeasuredPageHeight by remember { mutableStateOf(with(density) { 400.dp.toPx() }) }\n    val animatedScale by animateFloatAsState('
)

# Pass it to `PdfPageWithAnnotations`
content = content.replace(
    '                    PdfPageWithAnnotations(\n                        pageIndex = index,',
    '                    PdfPageWithAnnotations(\n                        pageIndex = index,\n                        lastMeasuredPageHeight = lastMeasuredPageHeight,\n                        onPageSizeMeasured = { height -> lastMeasuredPageHeight = height },'
)

# Update `PdfPageWithAnnotations` signature
content = content.replace(
    '    pageMatches: List<SearchMatch>,\n    currentMatchIndexOnPage: Int\n) {',
    '    pageMatches: List<SearchMatch>,\n    currentMatchIndexOnPage: Int,\n    lastMeasuredPageHeight: Float = 0f,\n    onPageSizeMeasured: (Float) -> Unit = {}\n) {'
)

# Replace the inner part of `PdfPageWithAnnotations` starting at `Box(modifier = Modifier.fillMaxWidth().onSizeChanged { size = it }`
# Wait, let's look at `PdfPageWithAnnotations` body
