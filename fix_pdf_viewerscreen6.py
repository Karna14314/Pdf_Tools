import re
import subprocess

file_path = "app/src/main/java/com/yourname/pdftoolkit/ui/screens/PdfViewerScreen.kt"

with open(file_path, "r") as f:
    content = f.read()

# 1. Stroke and StrokeJoin
content = content.replace('import androidx.compose.ui.graphics.drawscope.Stroke', 'import androidx.compose.ui.graphics.drawscope.Stroke\nimport androidx.compose.ui.graphics.StrokeCap\nimport androidx.compose.ui.graphics.StrokeJoin')
if 'import androidx.compose.ui.graphics.drawscope.Stroke' not in content:
    content = content.replace('import androidx.compose.ui.graphics.Color', 'import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.graphics.drawscope.Stroke\nimport androidx.compose.ui.graphics.StrokeCap\nimport androidx.compose.ui.graphics.StrokeJoin')

# 2. currentDrawingPageIndex
content = content.replace('currentDrawingPageIndex == pageIndex', 'currentDrawingPageIndex == pageIndex') # Need to make sure it's passed or defined
# I see I missed adding it to the updated signature.
content = content.replace(
    '    lastMeasuredPageHeight: Float = 0f,\n    onPageSizeMeasured: (Float) -> Unit = {}',
    '    currentDrawingPageIndex: Int,\n    lastMeasuredPageHeight: Float = 0f,\n    onPageSizeMeasured: (Float) -> Unit = {}'
)
# And pass it in PdfPagesContent
content = content.replace(
    '                        onPageSizeMeasured = { height -> lastMeasuredPageHeight = height },\n                        isEditMode = isEditMode,',
    '                        onPageSizeMeasured = { height -> lastMeasuredPageHeight = height },\n                        currentDrawingPageIndex = currentDrawingPageIndex,\n                        isEditMode = isEditMode,'
)

# 3. `onPageSizeMeasured` and `lastMeasuredPageHeight` error
# They were in the signature but probably I replaced the signature wrongly
content = content.replace(
    '    pageMatches: List<SearchMatch>,\n    currentMatchIndexOnPage: Int\n) {',
    '    pageMatches: List<SearchMatch>,\n    currentMatchIndexOnPage: Int,\n    currentDrawingPageIndex: Int,\n    lastMeasuredPageHeight: Float = 0f,\n    onPageSizeMeasured: (Float) -> Unit = {}\n) {'
)
# Wait, I already did this replace in fix_pdf_viewerscreen4.py but it might have missed.

# 4. Height Dp error
content = content.replace('heightDp', 'height')
content = content.replace('height(height)', 'height(heightDp)')

with open(file_path, "w") as f:
    f.write(content)
