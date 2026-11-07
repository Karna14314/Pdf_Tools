package com.yourname.pdftoolkit.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourname.pdftoolkit.domain.operations.NormalizedRect
import com.yourname.pdftoolkit.domain.operations.SplitOrientation
import kotlin.math.abs

private enum class DragTarget {
    NONE, NEW, MOVE,
    LEFT, TOP, RIGHT, BOTTOM,
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
}

private val DimColor = Color.Black.copy(alpha = 0.45f)

/** Remembers where the current gesture went down (drag callbacks only report positions after touch slop). */
private class DownAnchor {
    var position: Offset = Offset.Unspecified
}

/**
 * Interactive crop rectangles drawn over a rendered page.
 *
 * Must be laid out with exactly the same size as the page image below it.
 * - Drag outside any rectangle to draw a new one (replaces the active rectangle).
 * - Drag inside a rectangle to move it (it becomes the active one).
 * - Drag a corner or an edge of the active rectangle to resize it.
 *
 * @param rects one entry per editable area; null means "not drawn yet"
 * @param activeIndex index of the rectangle that receives new drawings and shows handles
 * @param showLabels draw a numbered badge on each rectangle (used when there are several)
 */
@Composable
fun CropRectOverlay(
    rects: List<NormalizedRect?>,
    activeIndex: Int,
    onRectChange: (index: Int, rect: NormalizedRect?) -> Unit,
    onActiveIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showLabels: Boolean = false
) {
    val density = LocalDensity.current
    val touchSlopPx = with(density) { 24.dp.toPx() }
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
    val textMeasurer = rememberTextMeasurer()

    val currentRects by rememberUpdatedState(rects)
    val currentActive by rememberUpdatedState(activeIndex)
    val onChange by rememberUpdatedState(onRectChange)
    val onActive by rememberUpdatedState(onActiveIndexChange)
    val anchor = remember { DownAnchor() }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    anchor.position = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position
                }
            }
            .pointerInput(Unit) {
            var target = DragTarget.NONE
            var editIndex = 0
            var initial: NormalizedRect? = null
            var start = Offset.Zero
            var current = Offset.Zero

            fun restoreIfInvalid() {
                if (target == DragTarget.NEW) {
                    val drawn = currentRects.getOrNull(editIndex)
                    if (drawn == null || !drawn.isUsable()) onChange(editIndex, initial)
                }
                target = DragTarget.NONE
            }

            detectDragGestures(
                onDragStart = { pos ->
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    // Anchor the gesture where the finger touched down, not where the slop was crossed.
                    start = if (anchor.position.isSpecified) anchor.position else pos
                    current = pos

                    val order = listOf(currentActive) + currentRects.indices.filter { it != currentActive }
                    var hit: Pair<Int, DragTarget>? = null
                    for (i in order) {
                        val r = currentRects.getOrNull(i) ?: continue
                        val t = hitTest(r, start, w, h, touchSlopPx, withHandles = i == currentActive)
                        if (t != DragTarget.NONE) {
                            hit = i to t
                            break
                        }
                    }
                    editIndex = hit?.first ?: currentActive
                    target = hit?.second ?: DragTarget.NEW
                    initial = currentRects.getOrNull(editIndex)
                    if (editIndex != currentActive) onActive(editIndex)
                },
                onDrag = { change, amount ->
                    change.consume()
                    current += amount
                    val updated = computeRect(
                        target, initial, start, current,
                        size.width.toFloat(), size.height.toFloat()
                    )
                    if (updated != null) onChange(editIndex, updated)
                },
                onDragEnd = { restoreIfInvalid() },
                onDragCancel = { restoreIfInvalid() }
            )
        }
    ) {
        val drawn = rects.mapIndexedNotNull { i, r -> r?.let { i to it.toPx(size) } }
        if (drawn.isEmpty()) return@Canvas

        val holes = Path().apply { drawn.forEach { addRect(it.second) } }
        clipPath(holes, ClipOp.Difference) { drawRect(DimColor) }

        // Draw inactive rectangles first so the active one stays on top.
        drawn.sortedBy { it.first == activeIndex }.forEach { (index, rect) ->
            val color = colors[index % colors.size]
            val isActive = index == activeIndex
            drawRect(
                color = color,
                topLeft = rect.topLeft,
                size = rect.size,
                style = Stroke(width = (if (isActive) 2.5.dp else 1.5.dp).toPx())
            )
            if (isActive) drawHandles(rect, color)
            if (showLabels) drawBadge(textMeasurer, "${index + 1}", rect.topLeft + Offset(14.dp.toPx(), 14.dp.toPx()), color)
        }
    }
}

/**
 * A draggable line that splits the page in two areas.
 * Tap or drag anywhere to move the line.
 *
 * @param position line position as a fraction of the width (vertical) or height (horizontal)
 * @param firstLabel label drawn in the left/top area, [secondLabel] in the right/bottom area
 */
@Composable
fun SplitLineOverlay(
    orientation: SplitOrientation,
    position: Float,
    onPositionChange: (Float) -> Unit,
    firstLabel: String,
    secondLabel: String,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val textMeasurer = rememberTextMeasurer()
    val onChange by rememberUpdatedState(onPositionChange)

    fun Size.fractionOf(pos: Offset): Float {
        val raw = if (orientation == SplitOrientation.VERTICAL) pos.x / width else pos.y / height
        return raw.coerceIn(NormalizedRect.MIN_SIZE, 1f - NormalizedRect.MIN_SIZE)
    }

    Canvas(
        modifier = modifier
            .pointerInput(orientation) {
                detectTapGestures { pos ->
                    onChange(Size(size.width.toFloat(), size.height.toFloat()).fractionOf(pos))
                }
            }
            .pointerInput(orientation) {
                detectDragGestures(
                    onDragStart = { pos ->
                        onChange(Size(size.width.toFloat(), size.height.toFloat()).fractionOf(pos))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        onChange(Size(size.width.toFloat(), size.height.toFloat()).fractionOf(change.position))
                    }
                )
            }
    ) {
        val vertical = orientation == SplitOrientation.VERTICAL
        val linePx = if (vertical) size.width * position else size.height * position

        val firstArea = if (vertical) Rect(0f, 0f, linePx, size.height) else Rect(0f, 0f, size.width, linePx)
        val secondArea = if (vertical) Rect(linePx, 0f, size.width, size.height) else Rect(0f, linePx, size.width, size.height)
        drawRect(primary.copy(alpha = 0.12f), firstArea.topLeft, firstArea.size)
        drawRect(tertiary.copy(alpha = 0.12f), secondArea.topLeft, secondArea.size)

        val lineStart = if (vertical) Offset(linePx, 0f) else Offset(0f, linePx)
        val lineEnd = if (vertical) Offset(linePx, size.height) else Offset(size.width, linePx)
        drawLine(Color.White, lineStart, lineEnd, strokeWidth = 5.dp.toPx())
        drawLine(primary, lineStart, lineEnd, strokeWidth = 2.5.dp.toPx())

        val handleCenter = if (vertical) Offset(linePx, size.height / 2) else Offset(size.width / 2, linePx)
        drawCircle(Color.White, radius = 14.dp.toPx(), center = handleCenter)
        drawCircle(primary, radius = 11.dp.toPx(), center = handleCenter)

        // Badges sit near the page edge so they never cover the line handle.
        fun badgeAt(area: Rect) = if (vertical) {
            Offset(area.center.x, size.height * 0.12f)
        } else {
            Offset(size.width * 0.12f, area.center.y)
        }
        drawBadge(textMeasurer, firstLabel, badgeAt(firstArea), primary)
        drawBadge(textMeasurer, secondLabel, badgeAt(secondArea), tertiary)
    }
}

private fun NormalizedRect.toPx(size: Size): Rect =
    Rect(left * size.width, top * size.height, right * size.width, bottom * size.height)

private fun hitTest(
    rect: NormalizedRect,
    pos: Offset,
    w: Float,
    h: Float,
    slop: Float,
    withHandles: Boolean
): DragTarget {
    val r = rect.toPx(Size(w, h))
    if (withHandles) {
        val nearL = abs(pos.x - r.left) <= slop
        val nearR = abs(pos.x - r.right) <= slop
        val nearT = abs(pos.y - r.top) <= slop
        val nearB = abs(pos.y - r.bottom) <= slop
        val withinX = pos.x in (r.left - slop)..(r.right + slop)
        val withinY = pos.y in (r.top - slop)..(r.bottom + slop)
        when {
            nearL && nearT -> return DragTarget.TOP_LEFT
            nearR && nearT -> return DragTarget.TOP_RIGHT
            nearL && nearB -> return DragTarget.BOTTOM_LEFT
            nearR && nearB -> return DragTarget.BOTTOM_RIGHT
            nearL && withinY -> return DragTarget.LEFT
            nearR && withinY -> return DragTarget.RIGHT
            nearT && withinX -> return DragTarget.TOP
            nearB && withinX -> return DragTarget.BOTTOM
        }
    }
    return if (r.contains(pos)) DragTarget.MOVE else DragTarget.NONE
}

private fun computeRect(
    target: DragTarget,
    initial: NormalizedRect?,
    start: Offset,
    current: Offset,
    w: Float,
    h: Float
): NormalizedRect? {
    if (w <= 0f || h <= 0f) return null
    val cx = (current.x / w).coerceIn(0f, 1f)
    val cy = (current.y / h).coerceIn(0f, 1f)
    val min = NormalizedRect.MIN_SIZE

    if (target == DragTarget.NEW) {
        val sx = (start.x / w).coerceIn(0f, 1f)
        val sy = (start.y / h).coerceIn(0f, 1f)
        return NormalizedRect(sx, sy, cx, cy).normalized()
    }
    val r = initial ?: return null

    if (target == DragTarget.MOVE) {
        val dx = ((current.x - start.x) / w).coerceIn(-r.left, 1f - r.right)
        val dy = ((current.y - start.y) / h).coerceIn(-r.top, 1f - r.bottom)
        return NormalizedRect(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy)
    }

    var left = r.left
    var top = r.top
    var right = r.right
    var bottom = r.bottom
    if (target in setOf(DragTarget.LEFT, DragTarget.TOP_LEFT, DragTarget.BOTTOM_LEFT)) {
        left = minOf(cx, r.right - min).coerceAtLeast(0f)
    }
    if (target in setOf(DragTarget.RIGHT, DragTarget.TOP_RIGHT, DragTarget.BOTTOM_RIGHT)) {
        right = maxOf(cx, r.left + min).coerceAtMost(1f)
    }
    if (target in setOf(DragTarget.TOP, DragTarget.TOP_LEFT, DragTarget.TOP_RIGHT)) {
        top = minOf(cy, r.bottom - min).coerceAtLeast(0f)
    }
    if (target in setOf(DragTarget.BOTTOM, DragTarget.BOTTOM_LEFT, DragTarget.BOTTOM_RIGHT)) {
        bottom = maxOf(cy, r.top + min).coerceAtMost(1f)
    }
    return NormalizedRect(left, top, right, bottom)
}

private fun DrawScope.drawHandles(rect: Rect, color: Color) {
    val corner = 8.dp.toPx()
    val edge = 5.dp.toPx()
    val border = 2.dp.toPx()
    listOf(rect.topLeft, rect.topRight, rect.bottomLeft, rect.bottomRight).forEach {
        drawCircle(Color.White, radius = corner + border, center = it)
        drawCircle(color, radius = corner, center = it)
    }
    listOf(rect.centerLeft, rect.centerRight, rect.topCenter, rect.bottomCenter).forEach {
        drawCircle(Color.White, radius = edge + border, center = it)
        drawCircle(color, radius = edge, center = it)
    }
}

private fun DrawScope.drawBadge(textMeasurer: TextMeasurer, label: String, center: Offset, color: Color) {
    val layout = textMeasurer.measure(
        label,
        style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    )
    val radius = maxOf(layout.size.width, layout.size.height) / 2f + 6.dp.toPx()
    drawCircle(color, radius = radius, center = center)
    drawText(
        layout,
        topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f)
    )
}
