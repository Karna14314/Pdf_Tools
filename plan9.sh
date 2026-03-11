echo "Let's check the code."
echo "I can just replace \`rememberTransformableState\` and \`.transformable(state = transformableState, lockRotationOnZoomPan = true)\` with:"
echo "Modifier.pointerInput(Unit) {
                        detectTransformGestures(panZoomLock = true) { centroid, panChange, zoomChange, rotationChange ->
                            if (isEditMode && selectedTool != AnnotationTool.NONE) return@detectTransformGestures

                            val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                            onScaleChange(newScale)

                            if (newScale > 1f) {
                                onOffsetChange(offsetX + panChange.x, offsetY + panChange.y)
                            } else {
                                onOffsetChange(0f, 0f)
                            }
                        }
                    }"
echo "Wait, \`detectTransformGestures(panZoomLock = true)\` means it waits for pan/zoom to establish before triggering. If we do this, it might work perfectly for panning!"
echo "And I will also change \`userScrollEnabled\` to \`(!isEditMode || selectedTool == AnnotationTool.NONE) && scale <= 1f\`."
