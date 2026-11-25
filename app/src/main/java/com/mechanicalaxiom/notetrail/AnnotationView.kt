package com.mechanicalaxiom.notetrail

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.util.AttributeSet
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import kotlin.collections.iterator
import kotlin.math.*

class AnnotationView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Multi-Page Support: Map<PageNumber, List<Annotation>>
    internal val pageAnnotations = mutableMapOf<Int, MutableList<Annotation>>()
    
    // Cache for intrinsic page sizes (at zoom 1.0)
    private val intrinsicPageSizes = mutableMapOf<Int, PointF>()

    var currentPage: Int = 0
        set(value) {
            if (field != value) {
                field = value
                selectedAnnotation = null
                selectedAnnotations.clear()
                invalidate()
            }
        }

    // PDF Page Metrics (Provided by Activity)
    var pageOffsets = mutableMapOf<Int, RectF>() // pageIndex -> RectF in doc space
    var currentZoom: Float = 1.0f
        set(value) {
            field = value
            invalidate()
        }

    private fun getAnnotationsForCurrentPage(): MutableList<Annotation> {
        return pageAnnotations.getOrPut(currentPage) { mutableListOf() }
    }

    private val currentPath = Path()
    private val currentPoints = mutableListOf<PointF>()
    private var currentPaint = Paint().apply {
        isAntiAlias = true
        isDither = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 8f
        color = Color.BLACK
    }

    // Selection state
    var selectedAnnotation: Annotation? = null
    val selectedAnnotations = mutableListOf<Annotation>()
    private val selectionGlowPaint = Paint().apply {
        color = "#8042A5F5".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 12f
        maskFilter = BlurMaskFilter(15f, BlurMaskFilter.Blur.NORMAL)
    }

    // Lasso state
    private val lassoPath = Path()
    private val lassoPaint = Paint().apply {
        color = "#42A5F5".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    // Alignment Guides state
    private var showGuideX: Float? = null
    private var showGuideY: Float? = null
    private val guidePaint = Paint().apply {
        color = Color.MAGENTA
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)
    }

    // Shape-Snap logic
    private val snapHandler = Handler(Looper.getMainLooper())
    private var isSnapping = false
    private val snapRunnable = Runnable {
        if (!currentPath.isEmpty) {
            performShapeSnap()
        }
    }
    
    private val triPath = Path() // Reuse path for ghost drawing

    enum class Mode { HAND, PEN, BRUSH, PENCIL, HIGHLIGHTER, ERASER, LINE, RECT, CIRCLE, TEXT, SELECT, CALLOUT, LASSO, IMAGE, TRIANGLE, ARROW }
    var mode: Mode = Mode.HAND
        set(value) {
            field = value
            isEnabled = value != Mode.HAND
            selectedAnnotation = null
            selectedAnnotations.clear()
            updatePaintForMode()
            invalidate()
        }

    var penColor: Int = Color.BLACK
        set(value) {
            field = value
            updatePaintForMode()
        }
    var penSize: Float = 8f
        set(value) {
            field = value
            updatePaintForMode()
        }
    var eraserSize: Float = 50f
        set(value) {
            field = value
            updatePaintForMode()
        }
        
    var isFilled: Boolean = false
        set(value) {
            field = value
            updatePaintForMode()
        }

    // Scroll offset for coordinate sync
    var scrollXOffset: Float = 0f
    var scrollYOffset: Float = 0f

    // Preview properties
    private var showPreview = false
    private var previewX = 0f
    private var previewY = 0f
    private var previewSize = 0f
    private var previewColor = Color.BLACK
    private var isEraserPreview = false
    var isLocked = false

    private val ghostPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        alpha = 100 // Semi-transparent for ghost effect
    }

    private val previewPaint = Paint().apply { isAntiAlias = true }
    private val eraserDashEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)

    // Callbacks
    var onTextRequest: ((Float, Float, Boolean) -> Unit)? = null
    var onSelectionChanged: ((Boolean) -> Unit)? = null
    var onAnnotationsUpdated: (() -> Unit)? = null
    var onHapticFeedback: (() -> Unit)? = null
    var onScrollRequested: ((Float, Float) -> Unit)? = null
    var onTouchIntercepted: ((MotionEvent) -> Unit)? = null
    var onDrawingStateChanged: ((Boolean) -> Unit)? = null
    var onOutsideTap: (() -> Unit)? = null

    private var lastX = 0f
    private var lastY = 0f
    private var lastXDoc = 0f
    private var lastYDoc = 0f
    private var startX = 0f
    private var startY = 0f

    init {
        isEnabled = false
        // Removed global software rendering for performance.
        // We will enable it locally when needed (e.g., Eraser).
    }

    private fun updatePaintForMode() {
        currentPaint = Paint().apply {
            isAntiAlias = true
            isDither = true
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }

        when (mode) {
            Mode.PEN, Mode.LINE, Mode.RECT, Mode.CIRCLE, Mode.TEXT, Mode.SELECT, Mode.CALLOUT, Mode.IMAGE, Mode.TRIANGLE, Mode.ARROW -> {
                currentPaint.color = penColor
                currentPaint.strokeWidth = penSize
                if (mode == Mode.TEXT || mode == Mode.CALLOUT) {
                    currentPaint.style = Paint.Style.FILL
                    currentPaint.textSize = penSize * 4f
                } else {
                    currentPaint.style = if (isFilled && (mode == Mode.RECT || mode == Mode.CIRCLE || mode == Mode.TRIANGLE)) Paint.Style.FILL else Paint.Style.STROKE
                }
            }
            Mode.PENCIL -> {
                currentPaint.color = penColor
                currentPaint.strokeWidth = penSize * 0.5f
                currentPaint.alpha = 180
                currentPaint.style = Paint.Style.STROKE
            }
            Mode.BRUSH -> {
                currentPaint.color = penColor
                currentPaint.strokeWidth = penSize * 2.0f
                currentPaint.maskFilter = BlurMaskFilter(currentPaint.strokeWidth / 4f, BlurMaskFilter.Blur.NORMAL)
                currentPaint.style = Paint.Style.STROKE
            }
            Mode.HIGHLIGHTER -> {
                currentPaint.color = penColor and 0x80FFFFFF.toInt()
                currentPaint.strokeWidth = penSize * 4f
                currentPaint.style = Paint.Style.STROKE
                currentPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            }
            Mode.ERASER -> {
                currentPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                currentPaint.strokeWidth = eraserSize
                currentPaint.style = Paint.Style.STROKE
            }
            Mode.HAND, Mode.LASSO -> {}
        }
    }

    fun addTextAnnotationWithPaint(text: String, x: Float, y: Float, customPaint: Paint) {
        val annotation = Annotation.Text(text, x, y, customPaint, currentPage, System.currentTimeMillis())
        getAnnotationsForCurrentPage().add(annotation)
        onAnnotationsUpdated?.invoke()
        invalidate()
    }

    private fun performShapeSnap() {
        val bounds = RectF()
        currentPath.computeBounds(bounds, true)
        val width = bounds.width()
        val height = bounds.height()
        if (width < 20 || height < 20) return

        val ratio = width / height
        
        // Better Circle detection: check distance between start and end point
        val startPoint = currentPoints.firstOrNull()
        val endPoint = currentPoints.lastOrNull()
        val distStartEnd = if (startPoint != null && endPoint != null) {
            hypot((startPoint.x - endPoint.x).toDouble(), (startPoint.y - endPoint.y).toDouble())
        } else Double.MAX_VALUE

        currentPath.reset()
        
        when (ratio) {
            in 0.85..1.15 -> {
                if (distStartEnd < 50) {
                    // Snap to Circle
                    val radius = (width + height) / 4f
                    currentPath.addCircle(bounds.centerX(), bounds.centerY(), radius, Path.Direction.CW)
                } else {
                    // Snap to Rect
                    currentPath.addRect(bounds, Path.Direction.CW)
                }
            }
            !in 0.33..3.0 -> {
                // Snap to Line
                currentPath.moveTo(startX, startY)
                currentPath.lineTo(lastXDoc, lastYDoc)
            }
            else -> {
                // Snap to Rect
                currentPath.addRect(bounds, Path.Direction.CW)
            }
        }
        isSnapping = true
        onHapticFeedback?.invoke()
        invalidate()
    }

    fun updateIntrinsicSize(pageIdx: Int, width: Float, height: Float) {
        intrinsicPageSizes[pageIdx] = PointF(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        // Use saveLayer only when eraser or brush is active to handle PorterDuff.Mode.CLEAR or BlurMaskFilter correctly
        val needsSoftwareLayer = mode == Mode.ERASER || mode == Mode.BRUSH
        if (needsSoftwareLayer) {
            canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        } else {
            canvas.save()
        }
        
        // Sync drawing with PDF View viewport (Multi-page support)
        for ((pageIdx, pageRect) in pageOffsets) {
            // Optimization: Only draw if the page is visible on screen
            if (canvas.quickReject(pageRect.left, pageRect.top, pageRect.right, pageRect.bottom)) {
                continue
            }

            val annotations = pageAnnotations[pageIdx] ?: continue
            
            canvas.withTranslation(pageRect.left, pageRect.top) {
                withScale(currentZoom, currentZoom) {
                    for (annotation in annotations) {
                        if (annotation == selectedAnnotation || selectedAnnotations.contains(annotation)) {
                            val annBounds = annotation.getBounds()
                            canvas.drawRect(annBounds, selectionGlowPaint)
                        }
                        annotation.draw(canvas)
                    }
                }
            }
        }
        
        // Draw current drawing path or shape (Relative to current page)
        if (mode != Mode.HAND && mode != Mode.TEXT && mode != Mode.SELECT && mode != Mode.LASSO) {
            val pageRect = pageOffsets[currentPage]
            if (pageRect != null) {
                canvas.withTranslation(pageRect.left, pageRect.top) {
                    withScale(currentZoom, currentZoom) {
                        ghostPaint.color = currentPaint.color
                        ghostPaint.strokeWidth = currentPaint.strokeWidth
                        ghostPaint.alpha = if (isSnapping) 255 else 120
                        
                        when (mode) {
                            Mode.RECT -> {
                                canvas.drawRect(min(startX, lastXDoc), min(startY, lastYDoc), max(startX, lastXDoc), max(startY, lastYDoc), ghostPaint)
                            }
                            Mode.CIRCLE -> {
                                val radius = hypot((lastXDoc - startX).toDouble(), (lastYDoc - startY).toDouble()).toFloat()
                                canvas.drawCircle(startX, startY, radius, ghostPaint)
                            }
                            Mode.TRIANGLE -> {
                                triPath.reset()
                                triPath.moveTo(startX, lastYDoc)
                                triPath.lineTo(lastXDoc, lastYDoc)
                                triPath.lineTo((startX + lastXDoc) / 2f, startY)
                                triPath.close()
                                canvas.drawPath(triPath, ghostPaint)
                            }
                            Mode.LINE, Mode.ARROW -> {
                                canvas.drawLine(startX, startY, lastXDoc, lastYDoc, ghostPaint)
                            }
                            else -> {
                                canvas.drawPath(currentPath, ghostPaint)
                            }
                        }
                    }
                }
            }
        }

        if (mode == Mode.LASSO) {
            canvas.withTranslation(-scrollXOffset, -scrollYOffset) {
                drawPath(lassoPath, lassoPaint)
            }
        }

        // Draw Alignment Guides (Screen Space)
        showGuideX?.let { canvas.drawLine(it, 0f, it, height.toFloat(), guidePaint) }
        showGuideY?.let { canvas.drawLine(0f, it, width.toFloat(), it, guidePaint) }

        // Draw Magnifier/Preview layer (Screen Space)
        if (showPreview) {
            previewPaint.reset()
            previewPaint.isAntiAlias = true
            if (isEraserPreview) {
                previewPaint.color = Color.LTGRAY
                previewPaint.alpha = 180
                previewPaint.pathEffect = eraserDashEffect
                previewPaint.style = Paint.Style.STROKE
                previewPaint.strokeWidth = 3f
            } else {
                previewPaint.color = previewColor
                previewPaint.alpha = 180
                previewPaint.style = Paint.Style.FILL
            }
            canvas.drawCircle(previewX, previewY, previewSize / 2f, previewPaint)
            previewPaint.pathEffect = null
            previewPaint.style = Paint.Style.FILL
            previewPaint.color = Color.WHITE
            previewPaint.alpha = 255
            canvas.drawCircle(previewX, previewY, 4f, previewPaint)
        }

        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isLocked) return false

        // Two fingers for scrolling/zooming: Handled by PDF viewer via redirection
        if (event.pointerCount >= 2) {
            onTouchIntercepted?.invoke(event)
            snapHandler.removeCallbacks(snapRunnable)
            currentPath.reset()
            currentPoints.clear()
            invalidate()
            return true
        }

        if (mode == Mode.HAND) return false

        // Drawing Modes
        // Determine target page on ACTION_DOWN and lock it
        if (event.action == MotionEvent.ACTION_DOWN) {
            var targetPage = -1
            for ((pageIdx, rect) in pageOffsets) {
                if (rect.contains(event.x, event.y)) {
                    targetPage = pageIdx
                    break
                }
            }
            if (targetPage == -1) return true
            currentPage = targetPage
            
            onOutsideTap?.invoke()
        }
        
        val pageRect = pageOffsets[currentPage] ?: return true
        // Convert screen coordinates to page-relative document coordinates
        val docX = (event.x - pageRect.left) / currentZoom
        val docY = (event.y - pageRect.top) / currentZoom
        
        lastXDoc = docX
        lastYDoc = docY
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                performClick()
                startX = docX
                startY = docY
                lastX = event.x
                lastY = event.y

                currentPath.reset()
                currentPath.moveTo(docX, docY)
                currentPoints.clear()
                currentPoints.add(PointF(docX, docY))
                isSnapping = false
                onDrawingStateChanged?.invoke(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val dxScreen = event.x - lastX
                val dyScreen = event.y - lastY

                // Restart snap timer if moving significantly
                if (abs(dxScreen) > 5 || abs(dyScreen) > 5) {
                    snapHandler.removeCallbacks(snapRunnable)
                    if (mode == Mode.PEN || mode == Mode.PENCIL || mode == Mode.BRUSH) {
                        snapHandler.postDelayed(snapRunnable, 600)
                    }
                }

                when (mode) {
                    Mode.PEN, Mode.PENCIL, Mode.BRUSH, Mode.HIGHLIGHTER, Mode.ERASER, Mode.LASSO -> {
                        if (mode == Mode.LASSO) {
                            if (lassoPath.isEmpty) lassoPath.moveTo(event.x, event.y)
                            else lassoPath.lineTo(event.x, event.y)
                        } else {
                            val pressure = event.getPressure(0)
                            val baseWidth = when (mode) {
                                Mode.HIGHLIGHTER -> penSize * 4f
                                Mode.ERASER -> eraserSize
                                else -> penSize
                            }
                            currentPaint.strokeWidth = baseWidth * (0.5f + pressure)
                            
                            if (currentPoints.isNotEmpty()) {
                                val lastP = currentPoints.last()
                                currentPath.quadTo(lastP.x, lastP.y, (docX + lastP.x) / 2, (docY + lastP.y) / 2)
                                currentPoints.add(PointF(docX, docY))
                            } else {
                                currentPath.moveTo(docX, docY)
                                currentPoints.add(PointF(docX, docY))
                            }
                        }
                    }
                    else -> {}
                }
                lastX = event.x
                lastY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                snapHandler.removeCallbacks(snapRunnable)
                if (mode == Mode.LASSO) {
                    performLassoSelection()
                    lassoPath.reset()
                } else if (mode == Mode.TEXT) {
                    // Check if it was a simple tap (not a long drag)
                    val dist = hypot((docX - startX).toDouble(), (docY - startY).toDouble())
                    if (dist < 10) {
                        onTextRequest?.invoke(event.x, event.y, false)
                    }
                } else if (mode == Mode.RECT) {
                    getAnnotationsForCurrentPage().add(Annotation.ShapeAnnotation(Annotation.ShapeAnnotation.Type.RECT, startX, startY, docX, docY, Paint(currentPaint), currentPage, System.currentTimeMillis()))
                    onAnnotationsUpdated?.invoke()
                } else if (mode == Mode.CIRCLE) {
                    getAnnotationsForCurrentPage().add(Annotation.ShapeAnnotation(Annotation.ShapeAnnotation.Type.CIRCLE, startX, startY, docX, docY, Paint(currentPaint), currentPage, System.currentTimeMillis()))
                    onAnnotationsUpdated?.invoke()
                } else if (mode == Mode.TRIANGLE) {
                    getAnnotationsForCurrentPage().add(Annotation.ShapeAnnotation(Annotation.ShapeAnnotation.Type.TRIANGLE, startX, startY, docX, docY, Paint(currentPaint), currentPage, System.currentTimeMillis()))
                    onAnnotationsUpdated?.invoke()
                } else if (mode == Mode.LINE || mode == Mode.ARROW) {
                    getAnnotationsForCurrentPage().add(Annotation.ShapeAnnotation(Annotation.ShapeAnnotation.Type.LINE, startX, startY, docX, docY, Paint(currentPaint), currentPage, System.currentTimeMillis()))
                    onAnnotationsUpdated?.invoke()
                } else if (!currentPath.isEmpty) {
                    val annotation = Annotation.PathAnnotation(
                        Path(currentPath), 
                        Paint(currentPaint), 
                        currentPage,
                        ArrayList(currentPoints),
                        System.currentTimeMillis()
                    )
                    getAnnotationsForCurrentPage().add(annotation)
                    onAnnotationsUpdated?.invoke()
                }
                isSnapping = false
                currentPath.reset()
                currentPoints.clear()
                onDrawingStateChanged?.invoke(false)
                invalidate()
            }
        }
        return true
    }

    private fun performLassoSelection() {
        val pageList = getAnnotationsForCurrentPage()
        val region = Region()
        val bounds = RectF()
        lassoPath.computeBounds(bounds, true)
        region.setPath(lassoPath, Region(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt()))
        
        for (annotation in pageList) {
            val annBounds = annotation.getBounds()
            val pageRect = pageOffsets[annotation.pageIdx] ?: continue
            
            // Convert annotation bounds to screen space for intersection check with lasso
            val screenBounds = RectF(
                annBounds.left * currentZoom,
                (pageRect.top + annBounds.top) * currentZoom - scrollYOffset,
                annBounds.right * currentZoom,
                (pageRect.top + annBounds.bottom) * currentZoom - scrollYOffset
            )
            
            if (region.quickReject(screenBounds.left.toInt(), screenBounds.top.toInt(), screenBounds.right.toInt(), screenBounds.bottom.toInt()).not()) {
                 selectedAnnotations.add(annotation)
            }
        }
        if (selectedAnnotations.isNotEmpty()) onSelectionChanged?.invoke(true)
    }

    fun undo() {
        val pageList = getAnnotationsForCurrentPage()
        if (pageList.isNotEmpty()) {
            pageList.removeAt(pageList.size - 1)
            selectedAnnotation = null
            onAnnotationsUpdated?.invoke()
            invalidate()
        }
    }

    fun clear() {
        pageAnnotations.clear()
        selectedAnnotation = null
        selectedAnnotations.clear()
        onAnnotationsUpdated?.invoke()
        invalidate()
    }

    fun exportToData(): AnnotationPageData {
        val pagesMap = mutableMapOf<String, PageAnnotations>()
        for ((pageIdx, annotations) in pageAnnotations) {
            // Use intrinsic dimensions if available, otherwise fallback to current visible dimensions
            val size = intrinsicPageSizes[pageIdx] ?: pageOffsets[pageIdx]?.let { RectF(it).apply { 
                val w = width() / currentZoom
                val h = height() / currentZoom
                set(0f, 0f, w, h)
            } }?.let { PointF(it.width(), it.height()) } ?: continue

            val strokes = annotations.filterIsInstance<Annotation.PathAnnotation>().map { ann ->
                StrokeData(
                    points = ann.points.map { p ->
                        NormalizedPoint(p.x / size.x, p.y / size.y)
                    },
                    color = ann.paint.color,
                    width = ann.paint.strokeWidth,
                    isHighlighter = ann.paint.xfermode is PorterDuffXfermode && ann.paint.alpha < 255,
                    isEraser = ann.paint.xfermode is PorterDuffXfermode && ann.paint.alpha == 255,
                    timestamp = ann.timestamp
                )
            }
            val texts = annotations.filterIsInstance<Annotation.Text>().map { ann ->
                TextAnnotationData(
                    content = ann.text,
                    x = ann.x / size.x,
                    y = ann.y / size.y,
                    color = ann.paint.color,
                    fontSize = ann.paint.textSize,
                    timestamp = ann.timestamp
                )
            }
            val shapes = annotations.filterIsInstance<Annotation.ShapeAnnotation>().map { ann ->
                ShapeAnnotationData(
                    type = ann.type.name,
                    x1 = ann.x1 / size.x,
                    y1 = ann.y1 / size.y,
                    x2 = ann.x2 / size.x,
                    y2 = ann.y2 / size.y,
                    color = ann.paint.color,
                    width = ann.paint.strokeWidth,
                    timestamp = ann.timestamp
                )
            }
            pagesMap[pageIdx.toString()] = PageAnnotations(strokes, texts, shapes)
        }
        return AnnotationPageData(pagesMap, System.currentTimeMillis())
    }

    fun importFromData(data: AnnotationPageData) {
        for ((pageKey, pageAnns) in data.pages) {
            val pageIdx = pageKey.toIntOrNull() ?: continue
            val size = intrinsicPageSizes[pageIdx] ?: pageOffsets[pageIdx]?.let { RectF(it).apply { 
                val w = width() / currentZoom
                val h = height() / currentZoom
                set(0f, 0f, w, h)
            } }?.let { PointF(it.width(), it.height()) } ?: PointF(1000f, 1000f) // Default fallback

            val list = mutableListOf<Annotation>()
            
            // Import Strokes
            for (stroke in pageAnns.strokes) {
                val path = Path()
                val points = stroke.points.map { p ->
                    PointF(p.x * size.x, p.y * size.y)
                }
                if (points.isNotEmpty()) {
                    path.moveTo(points[0].x, points[0].y)
                    for (i in 1 until points.size) {
                        val prev = points[i - 1]
                        val curr = points[i]
                        path.quadTo(prev.x, prev.y, (curr.x + prev.x) / 2, (curr.y + prev.y) / 2)
                    }
                }
                val paint = Paint().apply {
                    isAntiAlias = true
                    isDither = true
                    style = Paint.Style.STROKE
                    strokeJoin = Paint.Join.ROUND
                    strokeCap = Paint.Cap.ROUND
                    color = stroke.color
                    strokeWidth = stroke.width
                    if (stroke.isHighlighter) {
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                        alpha = 128
                    }
                    if (stroke.isEraser) {
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                        alpha = 255
                    }
                }
                list.add(Annotation.PathAnnotation(path, paint, pageIdx, points, stroke.timestamp))
            }
            
            // Import Texts
            for (textData in pageAnns.texts) {
                val paint = Paint().apply {
                    isAntiAlias = true
                    color = textData.color
                    textSize = textData.fontSize
                    style = Paint.Style.FILL
                }
                list.add(Annotation.Text(
                    textData.content,
                    textData.x * size.x,
                    textData.y * size.y,
                    paint,
                    pageIdx,
                    textData.timestamp
                ))
            }

            // Import Shapes
            for (shapeData in pageAnns.shapes) {
                val paint = Paint().apply {
                    isAntiAlias = true
                    color = shapeData.color
                    strokeWidth = shapeData.width
                    style = Paint.Style.STROKE
                }
                val type = try { Annotation.ShapeAnnotation.Type.valueOf(shapeData.type) } catch(_: Exception) { Annotation.ShapeAnnotation.Type.RECT }
                list.add(Annotation.ShapeAnnotation(
                    type,
                    shapeData.x1 * size.x,
                    shapeData.y1 * size.y,
                    shapeData.x2 * size.x,
                    shapeData.y2 * size.y,
                    paint,
                    pageIdx,
                    shapeData.timestamp
                ))
            }

            // MERGE LOGIC: Keep existing annotations for other pages, update only the target page
            pageAnnotations[pageIdx] = list
        }
        invalidate()
    }

    sealed class Annotation(open val pageIdx: Int, open val timestamp: Long = System.currentTimeMillis()) {
        abstract val paint: Paint
        abstract fun draw(canvas: Canvas)
        abstract fun move(dx: Float, dy: Float)
        abstract fun hitTest(x: Float, y: Float): Boolean
        abstract fun getBounds(): RectF

        data class ShapeAnnotation(
            val type: Type,
            var x1: Float,
            var y1: Float,
            var x2: Float,
            var y2: Float,
            override val paint: Paint,
            override val pageIdx: Int,
            override val timestamp: Long = System.currentTimeMillis()
        ) : Annotation(pageIdx, timestamp) {
            enum class Type { RECT, CIRCLE, LINE, TRIANGLE }
            override fun draw(canvas: Canvas) {
                when (type) {
                    Type.RECT -> canvas.drawRect(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2), paint)
                    Type.CIRCLE -> {
                        val radius = hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat()
                        canvas.drawCircle(x1, y1, radius, paint)
                    }
                    Type.LINE -> canvas.drawLine(x1, y1, x2, y2, paint)
                    Type.TRIANGLE -> {
                        val path = Path().apply {
                            moveTo(x1, y2)
                            lineTo(x2, y2)
                            lineTo((x1 + x2) / 2f, y1)
                            close()
                        }
                        canvas.drawPath(path, paint)
                    }
                }
            }
            override fun move(dx: Float, dy: Float) {
                x1 += dx
                y1 += dy
                x2 += dx
                y2 += dy
            }
            override fun hitTest(x: Float, y: Float): Boolean = getBounds().contains(x, y)
            override fun getBounds(): RectF {
                val rect = RectF(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
                val padding = paint.strokeWidth / 2f
                rect.inset(-padding - 20f, -padding - 20f)
                return rect
            }
        }

        data class PathAnnotation(
            val path: Path, 
            override val paint: Paint, 
            override val pageIdx: Int,
            val points: List<PointF> = emptyList(),
            override val timestamp: Long = System.currentTimeMillis()
        ) : Annotation(pageIdx, timestamp) {
            override fun draw(canvas: Canvas) = canvas.drawPath(path, paint)
            override fun move(dx: Float, dy: Float) {
                val matrix = Matrix()
                matrix.setTranslate(dx, dy)
                path.transform(matrix)
                points.forEach { it.offset(dx, dy) }
            }
            override fun hitTest(x: Float, y: Float): Boolean {
                val bounds = getBounds()
                val outSet = 25f
                return RectF(bounds.left - outSet, bounds.top - outSet, bounds.right + outSet, bounds.bottom + outSet).contains(x, y)
            }
            override fun getBounds(): RectF {
                val bounds = RectF()
                path.computeBounds(bounds, true)
                val strokePadding = paint.strokeWidth / 2f
                bounds.inset(-strokePadding, -strokePadding)
                return bounds
            }
        }

        data class Text(var text: String, var x: Float, var y: Float, override val paint: Paint, override val pageIdx: Int, override val timestamp: Long = System.currentTimeMillis()) : Annotation(pageIdx, timestamp) {
            override fun draw(canvas: Canvas) = canvas.drawText(text, x, y, paint)
            override fun move(dx: Float, dy: Float) {
                x += dx
                y += dy
            }
            override fun hitTest(x: Float, y: Float): Boolean {
                return getBounds().contains(x, y)
            }
            override fun getBounds(): RectF {
                val textBounds = Rect()
                paint.getTextBounds(text, 0, text.length, textBounds)
                return RectF(x + textBounds.left - 10, y + textBounds.top - 10, x + textBounds.right + 10, y + textBounds.bottom + 10)
            }
        }

        data class Callout(var text: String, var anchorX: Float, var anchorY: Float, var textX: Float, var textY: Float, override val paint: Paint, override val pageIdx: Int, override val timestamp: Long = System.currentTimeMillis()) : Annotation(pageIdx, timestamp) {
            private val arrowPaint = Paint(paint).apply {
                style = Paint.Style.STROKE
                strokeWidth = 4f
            }
            override fun draw(canvas: Canvas) {
                canvas.drawLine(anchorX, anchorY, textX, textY, arrowPaint)
                canvas.drawText(text, textX, textY, paint)
                val angle = atan2((textY - anchorY).toDouble(), (textX - anchorX).toDouble())
                val arrowSize = 20f
                canvas.drawLine(anchorX, anchorY, (anchorX + arrowSize * cos(angle + 0.5)).toFloat(), (anchorY + arrowSize * sin(angle + 0.5)).toFloat(), arrowPaint)
                canvas.drawLine(anchorX, anchorY, (anchorX + arrowSize * cos(angle - 0.5)).toFloat(), (anchorY + arrowSize * sin(angle - 0.5)).toFloat(), arrowPaint)
            }
            override fun move(dx: Float, dy: Float) {
                anchorX += dx
                anchorY += dy
                textX += dx
                textY += dy
            }
            override fun hitTest(x: Float, y: Float): Boolean {
                return getBounds().contains(x, y)
            }
            override fun getBounds(): RectF {
                val textBounds = Rect()
                paint.getTextBounds(text, 0, text.length, textBounds)
                val rect = RectF(textX + textBounds.left, textY + textBounds.top, textX + textBounds.right, textY + textBounds.bottom)
                rect.union(anchorX, anchorY)
                rect.inset(-20f, -20f)
                return rect
            }
        }

        data class ImageAnnotation(val bitmap: Bitmap, var x: Float, var y: Float, override val paint: Paint, override val pageIdx: Int, override val timestamp: Long = System.currentTimeMillis()) : Annotation(pageIdx, timestamp) {
            override fun draw(canvas: Canvas) {
                canvas.drawBitmap(bitmap, x, y, paint)
            }
            override fun move(dx: Float, dy: Float) {
                x += dx
                y += dy
            }
            override fun hitTest(x: Float, y: Float): Boolean {
                return getBounds().contains(x, y)
            }
            override fun getBounds(): RectF {
                return RectF(x, y, x + bitmap.width, y + bitmap.height)
            }
        }
    }
}
