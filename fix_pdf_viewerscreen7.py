import re
import subprocess

file_path = "app/src/main/java/com/yourname/pdftoolkit/ui/screens/PdfViewerScreen.kt"
subprocess.run(["git", "checkout", "HEAD", "--", file_path])

with open(file_path, "r") as f:
    content = f.read()

# 1. Update loadPage to be in produceState and IO dispatcher
# Wait, `PdfPageWithAnnotations` already has `produceState` for `bitmap`:
#     val bitmap by produceState<Bitmap?>(initialValue = null, key1 = pageIndex) {
#         value = loadPage(pageIndex)
#     }
# We just need to wrap `loadPage(pageIndex)` in `withContext(Dispatchers.IO)`
content = content.replace(
    '        value = loadPage(pageIndex)',
    '        value = withContext(kotlinx.coroutines.Dispatchers.IO) { loadPage(pageIndex) }'
)

# 2. Add viewport size tracking
# In `PdfPagesContent`, add `var lastMeasuredPageHeight by remember { mutableStateOf(with(density) { 400.dp.toPx() }) }`
# And add `LaunchedEffect(viewportSize.width)` in `PdfViewerScreen` ? No, `PdfViewerScreen` doesn't have `viewportSize`. Wait, `PdfViewerScreen` has `var viewportSize by remember { mutableStateOf(IntSize.Zero) }`.
# Let's check `PdfViewerScreen.kt`.
