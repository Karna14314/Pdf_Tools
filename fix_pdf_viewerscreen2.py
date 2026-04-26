import re
import subprocess

file_path = "app/src/main/java/com/yourname/pdftoolkit/ui/screens/PdfViewerScreen.kt"

with open(file_path, "r") as f:
    content = f.read()

# Modify PdfPagesContent's signature and remove unused variables
# - remove `panY: Float`
# - remove `onPanYChange: (Float) -> Unit`
# - remove `animatedPanY`

content = content.replace("panY: Float,", "")
content = content.replace("onPanYChange: (Float) -> Unit,", "")

content = content.replace(
"""    // Animate panY changes
    val animatedPanY by animateFloatAsState(
        targetValue = panY,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "pan_y"
    )""", "")

# Update `visiblePages` inside `PdfPagesContent`
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

# And in `PdfViewerScreen`, remove `panY` and `onPanYChange` calls
content = content.replace("panY = panY,", "")
content = content.replace("onPanYChange = { panY = it },", "")


with open(file_path, "w") as f:
    f.write(content)
