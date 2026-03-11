import re

file_path = "app/src/main/java/com/yourname/pdftoolkit/ui/screens/PdfViewerScreen.kt"

with open(file_path, "r") as f:
    content = f.read()

# Replace transformable block
old_transformable_state = """    // Use transformable state for smooth zoom and pan
    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        // Only handle gestures when not in annotation drawing mode
        if (isEditMode && selectedTool != AnnotationTool.NONE) return@rememberTransformableState

        // Calculate new scale
        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
        onScaleChange(newScale)

        if (newScale > 1f) {
            // When zoomed, allow free panning (horizontal only)
            // Vertical panning is handled by LazyColumn scroll
            onOffsetChange(offsetX + panChange.x, offsetY)
        } else {
            // Reset when zoomed out
            onOffsetChange(0f, 0f)
        }
    }"""

new_transformable_state = """    // Removed transformableState in favor of pointerInput"""

# Replace the Modifier.transformable usage
old_modifier = """                if (isEditMode && selectedTool != AnnotationTool.NONE) {
                    Modifier // No gesture handling when drawing
                } else {
                    Modifier.transformable(
                        state = transformableState,
                        lockRotationOnZoomPan = true
                    )
                }"""

new_modifier = """                if (isEditMode && selectedTool != AnnotationTool.NONE) {
                    Modifier // No gesture handling when drawing
                } else {
                    Modifier.pointerInput(Unit) {
                        detectTransformGestures(panZoomLock = true) { _, panChange, zoomChange, _ ->
                            val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                            onScaleChange(newScale)

                            if (newScale > 1f) {
                                // Allow both horizontal and vertical panning when zoomed
                                onOffsetChange(offsetX + panChange.x, offsetY + panChange.y)
                            } else {
                                onOffsetChange(0f, 0f)
                            }
                        }
                    }
                }"""

# Replace LazyColumn scroll behavior
old_lazy_column_scroll = """            // Enable scroll always - let LazyColumn handle composition
            userScrollEnabled = !isEditMode || selectedTool == AnnotationTool.NONE,"""

new_lazy_column_scroll = """            // Enable scroll only if we aren't drawing, AND if we are not zoomed in
            userScrollEnabled = (!isEditMode || selectedTool == AnnotationTool.NONE) && scale <= 1f,"""

content = content.replace(old_transformable_state, new_transformable_state)
content = content.replace(old_modifier, new_modifier)
content = content.replace(old_lazy_column_scroll, new_lazy_column_scroll)

with open(file_path, "w") as f:
    f.write(content)

print("Replaced transformable logic")
