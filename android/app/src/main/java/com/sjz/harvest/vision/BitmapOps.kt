package com.sjz.harvest.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import com.sjz.harvest.core.model.Geometry
import com.sjz.harvest.core.model.RectI
import kotlin.math.max
import kotlin.math.min

/**
 * Android Bitmap 轻量工具（不引入 OpenCV）。
 *
 * 所有返回 Bitmap 的方法都会**创建新对象**（即使尺寸相同），
 * 调用方负责在合适时机 recycle，避免与输入 bitmap 的生命周期耦合。
 */
object BitmapOps {

    /**
     * 裁剪矩形区域。矩形会先被 clamp 到图像内；空矩形返回 1×1 透明图。
     */
    fun crop(src: Bitmap, rect: RectI): Bitmap {
        require(!src.isRecycled) { "源 Bitmap 已被回收" }
        val safe = Geometry.clamp(rect, src.width, src.height)
        if (safe.isEmpty()) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        val output = Bitmap.createBitmap(safe.w, safe.h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(
            src,
            Rect(safe.x, safe.y, safe.right, safe.bottom),
            Rect(0, 0, safe.w, safe.h),
            paint,
        )
        return output
    }

    /**
     * 放大（factor >= 1）。小字识别前通常放大 2×。
     * factor <= 1 时返回一份拷贝，保证调用方可以安全 recycle。
     */
    fun upscale(src: Bitmap, factor: Int): Bitmap {
        require(!src.isRecycled) { "源 Bitmap 已被回收" }
        if (factor <= 1) return copyOf(src)
        val targetWidth = src.width * factor
        val targetHeight = src.height * factor
        if (targetWidth <= 0 || targetHeight <= 0) return copyOf(src)
        val scaled = Bitmap.createScaledBitmap(src, targetWidth, targetHeight, true)
        return if (scaled === src) copyOf(src) else scaled
    }

    /** 转灰度图（用于材料探针/白色像素统计前的预处理）。 */
    fun toGrayscale(src: Bitmap): Bitmap {
        require(!src.isRecycled) { "源 Bitmap 已被回收" }
        val output = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        canvas.drawBitmap(src, 0f, 0f, paint)
        return output
    }

    /** 确保 bitmap 是 ARGB_8888（ML Kit 对软件位图更稳定）。 */
    fun ensureArgb8888(src: Bitmap): Bitmap {
        require(!src.isRecycled) { "源 Bitmap 已被回收" }
        return if (src.config == Bitmap.Config.ARGB_8888 && src.isMutable) {
            copyOf(src)
        } else {
            src.copy(Bitmap.Config.ARGB_8888, false) ?: copyOf(src)
        }
    }

    /**
     * 白色像素比例（0.0..1.0）。用于判断材料格是否已填充等简单探针。
     * threshold 为 RGB 三通道同时达到的最小值。
     */
    fun whiteRatio(bitmap: Bitmap, threshold: Int = 200): Double =
        pixelRatio(bitmap) { r, g, b ->
            r >= threshold && g >= threshold && b >= threshold
        }

    /** 近黑像素比例（0.0..1.0），用于全黑截屏兜底检测。 */
    fun blackRatio(bitmap: Bitmap, threshold: Int = 16): Double =
        pixelRatio(bitmap) { r, g, b ->
            r <= threshold && g <= threshold && b <= threshold
        }

    /** 是否几乎全黑（FLAG_SECURE/黑屏）。默认 98% 像素近黑即判定。 */
    fun isMostlyBlack(bitmap: Bitmap, threshold: Int = 16, minBlackRatio: Double = 0.98): Boolean {
        require(!bitmap.isRecycled) { "Bitmap 已被回收" }
        if (bitmap.width <= 0 || bitmap.height <= 0) return true
        return blackRatio(bitmap, threshold) >= minBlackRatio
    }

    private fun pixelRatio(
        bitmap: Bitmap,
        predicate: (r: Int, g: Int, b: Int) -> Boolean,
    ): Double {
        require(!bitmap.isRecycled) { "Bitmap 已被回收" }
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return 0.0

        // 采样步长：目标约 100×100 个采样点，既够稳定又不会卡主线程。
        val step = max(1, min(width, height) / 100)
        var hit = 0
        var total = 0
        val pixel = IntArray(1)
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                bitmap.getPixels(pixel, 0, 1, x, y, 1, 1)
                val color = pixel[0]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                if (predicate(r, g, b)) hit++
                total++
                x += step
            }
            y += step
        }
        return if (total == 0) 0.0 else hit.toDouble() / total.toDouble()
    }

    private fun copyOf(src: Bitmap): Bitmap =
        src.copy(Bitmap.Config.ARGB_8888, false)
            ?: Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
}
