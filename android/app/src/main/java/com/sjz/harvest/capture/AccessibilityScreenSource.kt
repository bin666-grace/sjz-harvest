package com.sjz.harvest.capture

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import com.sjz.harvest.core.config.CaptureConfig
import com.sjz.harvest.core.logging.AppLogger
import com.sjz.harvest.core.model.Geometry
import com.sjz.harvest.core.model.RectI
import com.sjz.harvest.core.model.SjzException
import com.sjz.harvest.service.ScreenshotFailureException
import com.sjz.harvest.service.SjzAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 基于 AccessibilityService.takeScreenshot 的 ScreenSource。
 *
 * - 串行 + 最小间隔，避免触发系统 ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT；
 * - 间隔错误固定等待 450ms 后重试，其他错误按 capture.retries 重试；
 * - 采样检测全黑（FLAG_SECURE/窗口未合成），连续 3 次全黑后报错；
 * - 支持 region 裁剪；
 * - rotateIfPortrait=true 且截图高>宽时旋转 90°（兜底，会记录警告；
 *   此时手势坐标与截图坐标可能不一致，推荐模拟器固定横屏）。
 */
class AccessibilityScreenSource(
    private val serviceProvider: () -> SjzAccessibilityService?,
    private val config: CaptureConfig,
    private val logger: AppLogger,
) : ScreenSource {

    private val captureMutex = Mutex()

    @Volatile
    private var lastCaptureAtMs: Long = 0L

    @Volatile
    private var cachedSize: Pair<Int, Int>? = null

    @Volatile
    private var closed: Boolean = false

    override suspend fun capture(region: RectI?): Frame = captureMutex.withLock {
        if (closed) throw SjzException.CaptureFailed("截屏源已关闭")
        val service = serviceProvider() ?: throw SjzException.AccessibilityServiceUnavailable()

        val minInterval = config.minIntervalMs.coerceAtLeast(MIN_INTERVAL_MS)
        val maxAttempts = config.retries.coerceAtLeast(0) + 1
        var attempt = 0
        var blackCount = 0
        var lastError: SjzException.CaptureFailed? = null

        while (attempt < maxAttempts) {
            attempt++

            val elapsed = SystemClock.elapsedRealtime() - lastCaptureAtMs
            if (lastCaptureAtMs > 0L && elapsed < minInterval) {
                delay(minInterval - elapsed)
            }

            val raw: Bitmap = try {
                service.captureBitmap()
            } catch (e: SjzException.CaptureFailed) {
                lastCaptureAtMs = SystemClock.elapsedRealtime()
                lastError = e
                val failure = e.cause as? ScreenshotFailureException
                when {
                    failure?.secureWindow == true -> {
                        // 重试没有意义，直接给出明确提示。
                        throw SjzException.CaptureFailed(
                            "截屏被安全窗口屏蔽（可能启用了 FLAG_SECURE）: ${e.message}",
                            e,
                        )
                    }

                    failure?.intervalTooShort == true -> {
                        logger.w(
                            TAG,
                            "截屏过于频繁，${RETRY_DELAY_MS}ms 后重试 ($attempt/$maxAttempts)",
                        )
                        delay(RETRY_DELAY_MS)
                    }

                    else -> {
                        logger.w(
                            TAG,
                            "截屏失败: ${e.message} ($attempt/$maxAttempts)",
                        )
                        if (attempt < maxAttempts) delay(RETRY_DELAY_MS)
                    }
                }
                continue
            }
            lastCaptureAtMs = SystemClock.elapsedRealtime()

            if (cachedSize == null) {
                cachedSize = raw.width to raw.height
            }

            if (isAllBlack(raw)) {
                blackCount++
                raw.recycle()
                logger.w(TAG, "截屏全黑（采样），第 $blackCount 次")
                val blackError = SjzException.CaptureFailed(
                    "截屏连续全黑，可能被 FLAG_SECURE 保护或游戏窗口未渲染",
                )
                lastError = blackError
                if (blackCount >= MAX_BLACK_ATTEMPTS) {
                    throw blackError
                }
                if (attempt < maxAttempts) delay(RETRY_DELAY_MS)
                continue
            }

            val processed = try {
                transform(raw, region)
            } catch (e: SjzException.CaptureFailed) {
                if (!raw.isRecycled) raw.recycle()
                throw e
            } catch (e: Exception) {
                if (!raw.isRecycled) raw.recycle()
                throw SjzException.CaptureFailed("处理截图失败: ${e.message}", e)
            }
            return@withLock Frame(processed, System.currentTimeMillis())
        }

        throw lastError ?: SjzException.CaptureFailed("截屏失败：未知原因")
    }

    override fun screenSize(): Pair<Int, Int> {
        val service = serviceProvider()
        if (service != null) {
            val size = service.screenSize()
            if (size.first > 0 && size.second > 0) return size
        }
        return cachedSize ?: (0 to 0)
    }

    override fun close() {
        closed = true
    }

    // ------------------------------------------------------------------
    // 图像处理
    // ------------------------------------------------------------------

    private fun transform(raw: Bitmap, region: RectI?): Bitmap {
        var current = raw

        if (config.rotateIfPortrait && current.height > current.width) {
            logger.w(
                TAG,
                "截图为竖屏方向 ${current.width}x${current.height}，旋转 90° 兜底；" +
                    "手势坐标可能与截图不一致，建议模拟器固定横屏 1920x1080",
            )
            val rotated = Bitmap.createBitmap(
                current,
                0,
                0,
                current.width,
                current.height,
                Matrix().apply { postRotate(90f) },
                true,
            )
            if (rotated !== current) {
                current.recycle()
                current = rotated
            }
        }

        if (region == null) return current

        val safeRegion = Geometry.clamp(region, current.width, current.height)
        if (safeRegion.isEmpty()) {
            if (!current.isRecycled) current.recycle()
            throw SjzException.CaptureFailed("裁剪区域无效: $region")
        }
        if (safeRegion.x == 0 && safeRegion.y == 0 &&
            safeRegion.w == current.width && safeRegion.h == current.height
        ) {
            return current
        }

        val cropped = try {
            Bitmap.createBitmap(current, safeRegion.x, safeRegion.y, safeRegion.w, safeRegion.h)
        } catch (e: Exception) {
            if (!current.isRecycled) current.recycle()
            throw SjzException.CaptureFailed("裁剪截图失败: ${e.message}", e)
        }
        if (cropped !== current) current.recycle()
        return cropped
    }

    /** 网格采样，全部接近纯黑则视为全黑。 */
    private fun isAllBlack(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return true
        val stepX = (width / SAMPLE_GRID).coerceAtLeast(1)
        val stepY = (height / SAMPLE_GRID).coerceAtLeast(1)
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if (r > BLACK_THRESHOLD || g > BLACK_THRESHOLD || b > BLACK_THRESHOLD) {
                    return false
                }
                x += stepX
            }
            y += stepY
        }
        return true
    }

    companion object {
        private const val TAG = "ScreenSource"

        /** 系统限制约 333ms，这里最低 400ms。 */
        private const val MIN_INTERVAL_MS = 400L
        private const val RETRY_DELAY_MS = 450L
        private const val MAX_BLACK_ATTEMPTS = 3
        private const val SAMPLE_GRID = 32
        private const val BLACK_THRESHOLD = 8
    }
}
