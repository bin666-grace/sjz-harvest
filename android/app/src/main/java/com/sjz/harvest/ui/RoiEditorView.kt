package com.sjz.harvest.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.sjz.harvest.R
import com.sjz.harvest.core.model.PointI
import com.sjz.harvest.core.model.RectI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 校准用的图片编辑器：
 * - 图片按 fit-center 缩放显示，保留截图原始比例；
 * - 矩形模式：在图片上拖动绘制；
 * - 点模式：在图片上单击选择；
 * - 内部始终保存「基准坐标」（profile.baseWidth / baseHeight），
 *   显示时再换算回图片/控件坐标。
 */
class RoiEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class Mode { RECT, POINT }

    var mode: Mode = Mode.RECT
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var baseWidth: Int = 1920
        set(value) {
            field = value.coerceAtLeast(1)
            invalidate()
        }

    var baseHeight: Int = 1080
        set(value) {
            field = value.coerceAtLeast(1)
            invalidate()
        }

    /** 用户通过触摸改变选择时回调（基准坐标）。程序化 setExisting 不触发。 */
    var onSelectionChanged: ((RectI?, PointI?) -> Unit)? = null

    private var bitmap: Bitmap? = null
    private var rect: RectI? = null
    private var point: PointI? = null

    val selectedRect: RectI? get() = rect
    val selectedPoint: PointI? get() = point
    val hasBitmap: Boolean get() = bitmap != null

    private val imageRect = RectF()
    private var imageScale = 1f

    private var downX = 0f
    private var downY = 0f
    private var downInImage = false
    private var draggingRect = false
    private var dragStartBase: PointI? = null

    private val accentColor = context.getColor(R.color.sjz_accent)
    private val okColor = context.getColor(R.color.sjz_ok)

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = accentColor
        alpha = 70
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = accentColor
        strokeWidth = dp(2f)
    }
    private val pointFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = okColor
        alpha = 120
    }
    private val pointStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = okColor
        strokeWidth = dp(2f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(12f)
        setShadowLayer(dp(3f), 0f, 0f, Color.BLACK)
    }
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.sjz_log_muted)
        textSize = sp(13f)
        textAlign = Paint.Align.CENTER
    }

    /** 设置/替换当前截图。Bitmap 由调用方持有，本 View 不回收。 */
    fun setBitmap(newBitmap: Bitmap?) {
        bitmap = newBitmap
        invalidate()
    }

    fun setExisting(rect: RectI?, point: PointI?) {
        this.rect = rect
        this.point = point
        invalidate()
    }

    fun clearSelection() {
        setExisting(null, null)
    }

    private fun updateImageGeometry() {
        val bmp = bitmap ?: return
        val vw = (width - paddingLeft - paddingRight).toFloat()
        val vh = (height - paddingTop - paddingBottom).toFloat()
        if (vw <= 0f || vh <= 0f) return
        imageScale = min(vw / bmp.width, vh / bmp.height)
        val drawW = bmp.width * imageScale
        val drawH = bmp.height * imageScale
        val left = paddingLeft + (vw - drawW) / 2f
        val top = paddingTop + (vh - drawH) / 2f
        imageRect.set(left, top, left + drawW, top + drawH)
    }

    /** 控件坐标 -> 原图像素 -> 基准坐标。 */
    private fun viewToBase(x: Float, y: Float): PointI? {
        val bmp = bitmap ?: return null
        if (imageScale <= 0f || imageRect.isEmpty) return null
        if (x < imageRect.left || x > imageRect.right || y < imageRect.top || y > imageRect.bottom) {
            return null
        }
        val imageX = ((x - imageRect.left) / imageScale)
            .coerceIn(0f, bmp.width.toFloat())
        val imageY = ((y - imageRect.top) / imageScale)
            .coerceIn(0f, bmp.height.toFloat())
        val factor = baseWidth.toFloat() / bmp.width.toFloat()
        return PointI(
            (imageX * factor).roundToInt().coerceIn(0, baseWidth),
            (imageY * factor).roundToInt().coerceIn(0, baseHeight),
        )
    }

    /** 基准坐标 -> 控件坐标。 */
    private fun baseToView(basePoint: PointI): PointF? {
        val bmp = bitmap ?: return null
        if (imageScale <= 0f || imageRect.isEmpty) return null
        val factor = bmp.width.toFloat() / baseWidth.toFloat()
        val imageX = basePoint.x * factor
        val imageY = basePoint.y * factor
        return PointF(
            imageRect.left + imageX * imageScale,
            imageRect.top + imageY * imageScale,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(context.getColor(R.color.sjz_editor_background))

        val bmp = bitmap
        if (bmp == null) {
            canvas.drawText(
                context.getString(R.string.calibration_editor_placeholder),
                width / 2f,
                height / 2f,
                placeholderPaint,
            )
            return
        }

        updateImageGeometry()
        if (imageRect.isEmpty) return
        canvas.drawBitmap(bmp, null, imageRect, bitmapPaint)

        if (mode == Mode.RECT) {
            rect?.let { drawRectSelection(canvas, it) }
        } else {
            point?.let { drawPointSelection(canvas, it) }
        }

        val label = currentLabel()
        if (label != null) {
            canvas.drawText(label, paddingLeft + dp(10f), paddingTop + dp(20f), labelPaint)
        }
    }

    private fun currentLabel(): String? = when (mode) {
        Mode.RECT -> rect?.let {
            context.getString(
                R.string.calibration_rect_fmt,
                it.x, it.y, it.right, it.bottom, it.w, it.h,
            )
        }

        Mode.POINT -> point?.let {
            context.getString(R.string.calibration_point_fmt, it.x, it.y)
        }
    }

    private fun drawRectSelection(canvas: Canvas, baseRect: RectI) {
        val topLeft = baseToView(PointI(baseRect.x, baseRect.y)) ?: return
        val bottomRight = baseToView(PointI(baseRect.right, baseRect.bottom)) ?: return
        val drawRect = RectF(
            min(topLeft.x, bottomRight.x),
            min(topLeft.y, bottomRight.y),
            max(topLeft.x, bottomRight.x),
            max(topLeft.y, bottomRight.y),
        )
        canvas.drawRect(drawRect, fillPaint)
        canvas.drawRect(drawRect, strokePaint)
    }

    private fun drawPointSelection(canvas: Canvas, basePoint: PointI) {
        val center = baseToView(basePoint) ?: return
        val radius = dp(9f)
        canvas.drawCircle(center.x, center.y, radius, pointFillPaint)
        canvas.drawCircle(center.x, center.y, radius, pointStrokePaint)
        canvas.drawLine(center.x - radius * 1.9f, center.y, center.x + radius * 1.9f, center.y, pointStrokePaint)
        canvas.drawLine(center.x, center.y - radius * 1.9f, center.x, center.y + radius * 1.9f, pointStrokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        updateImageGeometry()
        if (imageRect.isEmpty) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downInImage = event.x in imageRect.left..imageRect.right &&
                    event.y in imageRect.top..imageRect.bottom
                if (!downInImage) return false

                if (mode == Mode.RECT) {
                    dragStartBase = viewToBase(event.x, event.y)
                    draggingRect = dragStartBase != null
                    rect = dragStartBase?.let { RectI(it.x, it.y, 1, 1) }
                    point = null
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!downInImage) return false
                if (mode == Mode.RECT && draggingRect) {
                    val start = dragStartBase ?: return true
                    val clampedX = event.x.coerceIn(imageRect.left, imageRect.right)
                    val clampedY = event.y.coerceIn(imageRect.top, imageRect.bottom)
                    val current = viewToBase(clampedX, clampedY) ?: return true
                    rect = RectI.fromLTRB(
                        min(start.x, current.x),
                        min(start.y, current.y),
                        max(start.x, current.x),
                        max(start.y, current.y),
                    )
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!downInImage) {
                    downInImage = false
                    return false
                }
                if (mode == Mode.POINT) {
                    val moved = abs(event.x - downX) + abs(event.y - downY)
                    if (moved < dp(16f)) {
                        val selected = viewToBase(event.x, event.y)
                        if (selected != null) {
                            point = selected
                            rect = null
                            notifySelection()
                        }
                    }
                } else if (mode == Mode.RECT) {
                    val currentRect = rect
                    if (currentRect != null &&
                        currentRect.w >= 2 &&
                        currentRect.h >= 2
                    ) {
                        point = null
                        notifySelection()
                    } else {
                        rect = null
                    }
                    draggingRect = false
                    dragStartBase = null
                }
                invalidate()
                performClick()
                downInImage = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                draggingRect = false
                dragStartBase = null
                downInImage = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun notifySelection() {
        onSelectionChanged?.invoke(rect, point)
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        resources.displayMetrics,
    )

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics,
    )
}
