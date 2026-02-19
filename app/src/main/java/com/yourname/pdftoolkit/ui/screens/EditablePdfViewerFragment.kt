package com.yourname.pdftoolkit.ui.screens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.children
import androidx.pdf.viewer.fragment.PdfViewerFragment
import androidx.recyclerview.widget.RecyclerView
import com.yourname.pdftoolkit.R

class EditablePdfViewerFragment : PdfViewerFragment() {

    private var inkOverlay: InkOverlayView? = null
    private var onAnnotationAddedListener: ((AnnotationStroke) -> Unit)? = null
    private var currentAnnotations: List<AnnotationStroke> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // CRITICAL: Post these operations to ensure PDF is fully loaded first
        view.post {
            hideZoomButtons(view)
            setupInkLayer(view)
        }
    }

    fun loadPdf(uri: Uri) {
        // CRITICAL FIX: Set documentUri BEFORE the fragment is added to ensure proper initialization
        // The androidx.pdf library requires documentUri to be set before onViewCreated
        documentUri = uri
    }

    fun setAnnotationMode(tool: AnnotationTool) {
        inkOverlay?.setTool(tool)
        // CRITICAL: Update overlay clickability to allow touch pass-through when not annotating
        inkOverlay?.isClickable = (tool != AnnotationTool.NONE)
    }

    fun setAnnotationColor(color: Int) {
        inkOverlay?.setColor(color)
    }

    fun undo() {
        // Trigger undo in ViewModel via listener? No, ViewModel handles undo logic on its list.
        // We just need to refresh the view with the new list.
        // But if we want local undo while drawing (before commit), InkOverlayView handles it?
        // Usually undo applies to committed strokes.
        // So we should rely on ViewModel's list update.
    }

    fun clearAnnotations() {
        // Handled by ViewModel list update.
    }

    fun setAnnotations(annotations: List<AnnotationStroke>) {
        currentAnnotations = annotations
        inkOverlay?.updateAnnotations(annotations)
    }

    fun setOnAnnotationAddedListener(listener: (AnnotationStroke) -> Unit) {
        onAnnotationAddedListener = listener
    }

    private fun hideZoomButtons(root: View) {
        // Hide zoom buttons container only (keep 3-dot menu visible)
        val zoomContainerId = resources.getIdentifier("zoom_buttons_container", "id", "androidx.pdf")
        if (zoomContainerId != 0) {
            root.findViewById<View>(zoomContainerId)?.visibility = View.GONE
        }
    }
    
    private fun setupInkLayer(root: View) {
        val zoomView = findZoomView(root)
        if (zoomView != null && zoomView is ViewGroup) {
            // Check if already added
            if (inkOverlay == null) {
                val context = root.context
                // Find page container (RecyclerView or LinearLayout inside ZoomView)
                val pageContainer = findPageContainer(zoomView)

                val overlay = InkOverlayView(context, pageContainer)
                inkOverlay = overlay

                // CRITICAL: Make overlay completely transparent to all events by default
                overlay.isClickable = false
                overlay.isFocusable = false
                overlay.isFocusableInTouchMode = false
                
                // CRITICAL: Ensure overlay doesn't intercept any touch events
                overlay.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                
                // CRITICAL: Make the overlay truly transparent to touch events
                overlay.setOnTouchListener { _, _ -> false } // Always return false to pass through

                // Set initial listener
                overlay.setOnAnnotationAddedListener { stroke ->
                    onAnnotationAddedListener?.invoke(stroke)
                }
                overlay.updateAnnotations(currentAnnotations)

                zoomView.addView(overlay, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))
                overlay.bringToFront()
            }
        }
    }

    private fun findZoomView(view: View): View? {
        if (view.javaClass.name.contains("ZoomView")) {
            return view
        }
        if (view is ViewGroup) {
            for (child in view.children) {
                val found = findZoomView(child)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findPageContainer(zoomView: ViewGroup): ViewGroup? {
        // Recursively look for RecyclerView or a linear layout that holds pages
        // In androidx.pdf, it's often a RecyclerView.
        // Or if it's ZoomView -> LinearLayout -> Pages

        // Check direct children first
        for (child in zoomView.children) {
            if (child is RecyclerView) return child
            if (child is ViewGroup && child.childCount > 0) {
                 // Heuristic: Check if grandchild looks like a page?
                 // Or just return the first ViewGroup child that isn't the zoom buttons?
                 return child as ViewGroup
            }
        }
        return zoomView // Fallback to ZoomView itself if pages are direct children
    }
}

/**
 * Custom Ink Overlay View.
 *
 * Note: This implementation emulates the desired behavior of androidx.ink (Highlighter with 30% alpha,
 * flat tip, non-stacking) using standard Android Graphics APIs for stability and compatibility,
 * as the androidx.ink library is in early alpha.
 *
 * It maps touch events to PDF pages to allow saving annotations via PdfViewerViewModel.
 */
class InkOverlayView @JvmOverloads constructor(
    context: Context,
    private val pageContainer: ViewGroup?,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val annotations = mutableListOf<AnnotationStroke>()
    private val currentPoints = mutableListOf<Offset>()
    private var currentTool = AnnotationTool.NONE
    private var currentColor = android.graphics.Color.YELLOW

    private var onAnnotationAdded: ((AnnotationStroke) -> Unit)? = null

    private val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private val layerPaint = Paint()

    init {
        // CRITICAL: Make view transparent to touches by default
        isClickable = false
        isFocusable = false
        
        // CRITICAL: Disable all touch interception by default
        // This ensures pinch-to-zoom and double-tap work properly
        setWillNotDraw(false) // We still need to draw annotations
        
        // Ensure this view doesn't block accessibility or touch events
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        
        // Listen for scroll/zoom changes to redraw annotations
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            invalidate()
        }
    }
    
    fun setTool(tool: AnnotationTool) {
        currentTool = tool
        // Update whether we should intercept touches
        isClickable = (tool != AnnotationTool.NONE)
        isFocusable = (tool != AnnotationTool.NONE)
        
        // When switching to/from annotation mode, ensure proper touch handling
        if (tool == AnnotationTool.NONE) {
            // Clear any in-progress drawing
            currentPoints.clear()
            invalidate()
        }
    }

    fun setColor(color: Int) {
        currentColor = color
    }

    fun updateAnnotations(newAnnotations: List<AnnotationStroke>) {
        annotations.clear()
        annotations.addAll(newAnnotations)
        invalidate()
    }

    fun setOnAnnotationAddedListener(listener: (AnnotationStroke) -> Unit) {
        onAnnotationAdded = listener
    }
    
    // Note: View doesn't have onInterceptTouchEvent, only ViewGroup does
    // We handle touch events directly in onTouchEvent instead
    
    override fun dispatchDraw(canvas: Canvas) {
        // Non-stacking logic for highlighter: Use a layer
        val highlighters = annotations.filter { it.tool == AnnotationTool.HIGHLIGHTER }
        val others = annotations.filter { it.tool != AnnotationTool.HIGHLIGHTER }

        // Draw others
        for (stroke in others) {
            drawStroke(canvas, stroke)
        }

        // Draw current stroke if not highlighter
        if (currentTool != AnnotationTool.HIGHLIGHTER && currentPoints.isNotEmpty()) {
            drawCurrentStroke(canvas)
        }

        // Draw highlighters in layer
        if (highlighters.isNotEmpty() || (currentTool == AnnotationTool.HIGHLIGHTER && currentPoints.isNotEmpty())) {
            val count = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), layerPaint.apply {
                alpha = (255 * 0.3).toInt()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // blendMode = android.graphics.BlendMode.MULTIPLY // Requires API 29+ check orcompat
                }
                xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            })

            // Draw opaque inside layer
            for (stroke in highlighters) {
                drawStroke(canvas, stroke, overrideAlpha = 255)
            }

            if (currentTool == AnnotationTool.HIGHLIGHTER && currentPoints.isNotEmpty()) {
                drawCurrentStroke(canvas, overrideAlpha = 255)
            }

            canvas.restoreToCount(count)
        }
    }

    private fun drawStroke(canvas: Canvas, stroke: AnnotationStroke, overrideAlpha: Int? = null) {
        val pageView = findPageView(stroke.pageIndex) ?: return

        paint.color = stroke.color.toArgb()
        if (overrideAlpha != null) paint.alpha = overrideAlpha

        // Scale stroke width relative to page width
        paint.strokeWidth = stroke.strokeWidth * pageView.width
        if (stroke.tool == AnnotationTool.HIGHLIGHTER) {
             paint.strokeWidth = 30f // visual consistency or use saved width?
             // Use saved width but ensure it's reasonable
             // stroke.strokeWidth is normalized (0..1).
             // Ideally we use that.
             paint.strokeWidth = (stroke.strokeWidth * pageView.width).coerceAtLeast(1f)
             paint.strokeCap = Paint.Cap.SQUARE
        } else {
             paint.strokeCap = Paint.Cap.ROUND
        }

        if (stroke.points.isNotEmpty()) {
            val path = Path()
            // Map normalized points to screen
            // screenX = pageView.x + point.x * pageView.width
            // screenY = pageView.y + point.y * pageView.height
            // Note: pageView.x/y are relative to pageContainer.
            // But we are drawing on InkOverlayView which is added to ZoomView.
            // If pageContainer == ZoomView, then pageView.x is correct.
            // If pageContainer is child of ZoomView, we need to map coordinates?
            // Assuming InkOverlayView and pageContainer are siblings or InkOverlayView is child of ZoomView
            // and pageContainer is child of ZoomView (or is ZoomView).

            // Safer: Map pageView bounds to InkOverlayView coordinates.
            val pageBounds = getPageBoundsInOverlay(pageView)

            val first = stroke.points.first()
            path.moveTo(pageBounds.left + first.x * pageBounds.width(), pageBounds.top + first.y * pageBounds.height())

            for (i in 1 until stroke.points.size) {
                val p = stroke.points[i]
                path.lineTo(pageBounds.left + p.x * pageBounds.width(), pageBounds.top + p.y * pageBounds.height())
            }
            canvas.drawPath(path, paint)
        }
    }

    private fun drawCurrentStroke(canvas: Canvas, overrideAlpha: Int? = null) {
        paint.color = currentColor
        if (overrideAlpha != null) paint.alpha = overrideAlpha

        paint.strokeWidth = if (currentTool == AnnotationTool.MARKER) 8f else 4f
        if (currentTool == AnnotationTool.HIGHLIGHTER) {
            paint.strokeWidth = 30f
            paint.strokeCap = Paint.Cap.SQUARE
        } else {
            paint.strokeCap = Paint.Cap.ROUND
        }

        if (currentPoints.isNotEmpty()) {
            val path = Path()
            path.moveTo(currentPoints.first().x, currentPoints.first().y)
            for (i in 1 until currentPoints.size) {
                path.lineTo(currentPoints[i].x, currentPoints[i].y)
            }
            canvas.drawPath(path, paint)
        }
    }

    private fun findPageView(pageIndex: Int): View? {
        if (pageContainer == null) return null
        if (pageContainer is RecyclerView) {
            val holder = pageContainer.findViewHolderForAdapterPosition(pageIndex)
            return holder?.itemView
        }
        // Fallback: Linear Layout
        if (pageIndex < pageContainer.childCount) {
            return pageContainer.getChildAt(pageIndex)
        }
        return null
    }

    private fun getPageBoundsInOverlay(pageView: View): Rect {
        val bounds = Rect()
        pageView.getDrawingRect(bounds)
        pageContainer?.offsetDescendantRectToMyCoords(pageView, bounds)
        // If pageContainer is not same as overlay parent (ZoomView), logic needed?
        // We added overlay to ZoomView.
        // pageContainer is found inside ZoomView.
        // If pageContainer != ZoomView, offset again.
        if (pageContainer != parent) {
             // Assuming simple hierarchy for now: ZoomView -> PageContainer -> Page
             // Or ZoomView -> Page (pageContainer == ZoomView)
             // If ZoomView -> PageContainer, then pageContainer's parent is ZoomView (our parent).
             // So coords in pageContainer need offset by pageContainer.x/y?
             // offsetDescendantRectToMyCoords gives coords relative to pageContainer.
             // If pageContainer is sibling of overlay (both in ZoomView), then we need pageContainer.left/top.
             bounds.offset(pageContainer?.left ?: 0, pageContainer?.top ?: 0)
        }
        return bounds
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // CRITICAL: Always pass through multi-touch gestures (pinch-to-zoom)
        if (event.pointerCount > 1) {
            return false // Don't consume - let PDF viewer handle pinch
        }
        
        // CRITICAL: Only intercept touches when actively drawing
        // This allows the underlying PDF viewer to handle pan/zoom/double-tap gestures
        if (currentTool == AnnotationTool.NONE) {
            return false // Don't consume the event - let it pass through
        }

        val x = event.x
        val y = event.y

        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                currentPoints.clear()
                currentPoints.add(Offset(x, y))
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // If a second pointer comes down while drawing, cancel the stroke
                if (event.pointerCount > 1) {
                    currentPoints.clear()
                    invalidate()
                    return false
                }
                currentPoints.add(Offset(x, y))
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Finalize stroke
                if (currentPoints.isNotEmpty()) {
                    val first = currentPoints.first()
                    val (pageIndex, pageView) = findPageUnder(first.x, first.y)

                    if (pageIndex != -1 && pageView != null) {
                        // Normalize points
                        val pageBounds = getPageBoundsInOverlay(pageView)
                        val normalizedPoints = currentPoints.map {
                            Offset(
                                (it.x - pageBounds.left) / pageBounds.width(),
                                (it.y - pageBounds.top) / pageBounds.height()
                            )
                        }

                        val stroke = AnnotationStroke(
                            pageIndex = pageIndex,
                            tool = currentTool,
                            color = Color(currentColor),
                            points = normalizedPoints,
                            strokeWidth = if (currentTool == AnnotationTool.HIGHLIGHTER) 30f / pageBounds.width() else 8f / pageBounds.width()
                        )

                        onAnnotationAdded?.invoke(stroke)
                    }
                }
                currentPoints.clear()
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // Multi-touch started/ended - cancel current stroke and pass through
                currentPoints.clear()
                invalidate()
                return false
            }
        }
        return false
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // CRITICAL FIX: When not in annotation mode, make overlay completely transparent to touches
        // This ensures pan/zoom/double-tap gestures work properly
        if (currentTool == AnnotationTool.NONE) {
            return false // Don't intercept - pass through to underlying view
        }
        
        // CRITICAL: Always pass through multi-touch gestures
        if (event.pointerCount > 1) {
            // Cancel any current drawing
            if (currentPoints.isNotEmpty()) {
                currentPoints.clear()
                invalidate()
            }
            return false
        }
        
        return super.dispatchTouchEvent(event)
    }

    private fun findPageUnder(x: Float, y: Float): Pair<Int, View?> {
        if (pageContainer == null) return -1 to null

        if (pageContainer is RecyclerView) {
            // CRITICAL FIX: Properly map overlay coordinates to RecyclerView coordinates
            // Account for RecyclerView's position and scroll offset
            val rx = x - pageContainer.left + pageContainer.scrollX
            val ry = y - pageContainer.top + pageContainer.scrollY

            val child = pageContainer.findChildViewUnder(rx, ry)
            if (child != null) {
                val pos = pageContainer.getChildAdapterPosition(child)
                return pos to child
            }
        } else {
            // Linear scan for non-RecyclerView containers
            for (i in 0 until pageContainer.childCount) {
                val child = pageContainer.getChildAt(i)
                val bounds = getPageBoundsInOverlay(child)
                if (bounds.contains(x.toInt(), y.toInt())) {
                    return i to child
                }
            }
        }
        return -1 to null
    }
}
