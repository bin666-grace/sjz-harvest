package com.sjz.harvest.game

import com.sjz.harvest.capture.ScreenSource
import com.sjz.harvest.core.config.DoneBadgeProfile
import com.sjz.harvest.core.model.OcrResult
import com.sjz.harvest.core.model.PointI
import com.sjz.harvest.vision.AlertBadgeDetector
import com.sjz.harvest.vision.OcrEngine

/**
 * 一次截屏 + OCR 的结果。
 *
 * 只保留文字、屏幕尺寸与角标位置，Bitmap 在 [ScreenOcrSource] 内立即回收。
 * 纯 Kotlin 数据类，便于 JVM 单测直接构造。
 */
data class OcrSnapshot(
    val result: OcrResult,
    val width: Int,
    val height: Int,
    /** 总览卡片「可收取」黄色角标中心（实际屏幕像素）；空 = 未检测到。 */
    val alertBadges: List<PointI> = emptyList(),
)

/**
 * 业务层获取画面的唯一入口。
 *
 * 把「截屏 → OCR → 回收 Bitmap」封装在一个接口后面：
 * - 生产实现 [ScreenOcrSource] 依赖 Android Bitmap；
 * - 单测用脚本化的假实现直接返回 [OcrSnapshot]，不需要 Robolectric。
 */
interface ScreenOcr {
    suspend fun capture(hint: String): OcrSnapshot
}

/**
 * 生产实现：截屏后同步 OCR，并保证 Bitmap 在返回前回收。
 *
 * 每次截屏前校验游戏在前台：用户可能中途切走，续截会把本 App 的界面
 * 当成游戏继续操作。
 */
class ScreenOcrSource(
    private val source: ScreenSource,
    private val ocr: OcrEngine,
    private val foregroundGuard: ForegroundGuard = ForegroundGuard.ALWAYS_OK,
    /** 总览黄色角标识别参数；null/禁用时不扫描像素。 */
    private val doneBadge: DoneBadgeProfile? = null,
) : ScreenOcr {

    override suspend fun capture(hint: String): OcrSnapshot {
        foregroundGuard.ensureGameForeground()
        val frame = source.capture()
        return try {
            val result = ocr.recognize(frame.bitmap, hint)
            OcrSnapshot(
                result = result,
                width = frame.width,
                height = frame.height,
                alertBadges = detectBadges(frame.bitmap),
            )
        } finally {
            frame.recycle()
        }
    }

    /**
     * 颜色扫描失败不能影响截屏/OCR 主流程，因此吞掉异常返回空列表。
     * 像素数组在扫描后交给 GC；单帧约 13MB（2712×1220）。
     */
    private fun detectBadges(bitmap: android.graphics.Bitmap): List<PointI> {
        val profile = doneBadge ?: return emptyList()
        if (!profile.enabled) return emptyList()
        return runCatching {
            val width = bitmap.width
            val height = bitmap.height
            if (width <= 0 || height <= 0) return@runCatching emptyList()
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            AlertBadgeDetector.detect(profile, pixels, width, height)
        }.getOrElse { emptyList() }
    }
}
