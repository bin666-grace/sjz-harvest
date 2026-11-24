package com.sjz.harvest.vision

import android.graphics.Bitmap
import com.sjz.harvest.core.model.OcrResult

interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap, hint: String = ""): OcrResult

    fun close()
}
