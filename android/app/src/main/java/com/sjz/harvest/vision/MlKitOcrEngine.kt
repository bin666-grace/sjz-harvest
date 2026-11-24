package com.sjz.harvest.vision

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.sjz.harvest.core.logging.AppLogger
import com.sjz.harvest.core.model.OcrResult
import com.sjz.harvest.core.model.RectI
import com.sjz.harvest.core.model.TextElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlin.math.roundToInt

/**
 * ML Kit 中文 bundled 模型 OCR。
 *
 * 关键点：
 * - recognizer 非线程安全，用 [Mutex] 串行；
 * - 小图（宽度 < 1600）识别前放大 [UPSCALE_FACTOR] 倍，提高小字识别率；
 * - 返回坐标再除回放大倍数，保证是**原图（屏幕）坐标**；
 * - 失败重试 [retries] 次，最终返回 [OcrResult.EMPTY] 并记日志。
 *
 * 输入 bitmap 由调用方管理生命周期，本类不会 recycle 它；
 * 内部放大产生的副本在 finally 中回收。
 */
class MlKitOcrEngine(
    private val logger: AppLogger,
    private val retries: Int = 2,
) : OcrEngine {

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    private val mutex = Mutex()

    @Volatile
    private var closed = false

    override suspend fun recognize(bitmap: Bitmap, hint: String): OcrResult {
        if (closed) {
            logger.w(TAG, "recognize 调用时引擎已关闭，返回空结果")
            return OcrResult.EMPTY
        }
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            logger.w(TAG, "recognize 收到无效 Bitmap（hint=$hint）")
            return OcrResult.EMPTY
        }

        return mutex.withLock {
            if (closed) {
                logger.w(TAG, "recognize 等待锁期间引擎被关闭，返回空结果")
                return@withLock OcrResult.EMPTY
            }

            var upscaled: Bitmap? = null
            try {
                // 1080p 及以上截图原样识别即可；再放大 2× 只会增加内存和耗时，
                // 对 ML Kit 的小字识别提升有限。小图（低分辨率模拟器）才放大。
                val factor = if (bitmap.width >= LARGE_IMAGE_WIDTH_PX) 1 else UPSCALE_FACTOR
                val input = if (factor == 1) bitmap else BitmapOps.upscale(bitmap, factor)
                if (input !== bitmap) upscaled = input
                val scale = input.width.toDouble() / bitmap.width.toDouble()

                val attempts = retries.coerceAtLeast(0) + 1
                var lastError: Throwable? = null
                for (attempt in 1..attempts) {
                    try {
                        val text: Text = recognizer
                            .process(InputImage.fromBitmap(input, 0))
                            .await()
                        val result = toOcrResult(text, scale)
                        logger.d(
                            TAG,
                            "OCR 成功 hint=$hint attempt=$attempt " +
                                "elements=${result.elements.size}",
                        )
                        return@withLock result
                    } catch (cancelled: CancellationException) {
                        // 协程取消必须向上传播，不能当作识别失败重试。
                        throw cancelled
                    } catch (error: Exception) {
                        lastError = error
                        logger.w(
                            TAG,
                            "OCR 失败 hint=$hint attempt=$attempt/${attempts}: " +
                                "${error.javaClass.simpleName}: ${error.message}",
                        )
                    }
                }
                logger.e(
                    TAG,
                    "OCR 最终失败 hint=$hint，已重试 $retries 次，返回空结果",
                    lastError,
                )
                OcrResult.EMPTY
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logger.e(TAG, "OCR 预处理失败 hint=$hint", error)
                OcrResult.EMPTY
            } finally {
                upscaled?.let { if (!it.isRecycled) it.recycle() }
            }
        }
    }

    override fun close() {
        closed = true
        try {
            recognizer.close()
        } catch (error: Exception) {
            logger.w(TAG, "关闭 OCR recognizer 失败: ${error.message}")
        }
    }

    /**
     * 把 ML Kit 的行结果转换为统一模型，并把包围盒坐标除以放大倍数。
     */
    private fun toOcrResult(text: Text, scale: Double): OcrResult {
        val safeScale = if (scale <= 0.0) 1.0 else scale
        val elements = ArrayList<TextElement>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val content = line.text
                if (content.isBlank()) continue
                val box = line.boundingBox ?: continue
                val rect = RectI(
                    x = (box.left / safeScale).roundToInt(),
                    y = (box.top / safeScale).roundToInt(),
                    w = ((box.right - box.left) / safeScale).roundToInt().coerceAtLeast(1),
                    h = ((box.bottom - box.top) / safeScale).roundToInt().coerceAtLeast(1),
                )
                elements.add(TextElement(content.trim(), rect))
            }
        }
        val fullText = elements.joinToString(separator = "\n") { it.text }
        return OcrResult(elements, fullText)
    }

    companion object {
        private const val TAG = "MlKitOcr"
        private const val LARGE_IMAGE_WIDTH_PX = 1600
        private const val UPSCALE_FACTOR = 2
    }
}
