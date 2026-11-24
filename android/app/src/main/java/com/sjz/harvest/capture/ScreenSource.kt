package com.sjz.harvest.capture

import android.graphics.Bitmap
import com.sjz.harvest.core.model.RectI

data class Frame(
    val bitmap: Bitmap,
    val capturedAtMs: Long,
) {
    val width: Int get() = bitmap.width
    val height: Int get() = bitmap.height

    fun crop(region: RectI): Bitmap =
        Bitmap.createBitmap(bitmap, region.x, region.y, region.w, region.h)

    fun recycle() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

interface ScreenSource {
    suspend fun capture(region: RectI? = null): Frame

    fun screenSize(): Pair<Int, Int>

    fun close()
}
