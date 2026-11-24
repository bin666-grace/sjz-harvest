package com.sjz.harvest.core.model

import com.sjz.harvest.core.config.UiProfile
import kotlin.math.roundToInt

/**
 * 坐标缩放规则（重要）：
 * - profile 中的坐标以 baseWidth × baseHeight（默认 1920×1080）为基准；
 * - 实际设备按「宽度比例」统一缩放，不做非等比拉伸；
 * - 只支持 16:9 左右的横屏分辨率；模拟器建议直接设为 1920×1080。
 */
object Geometry {
    fun widthFactor(profile: UiProfile, screenWidth: Int): Double =
        screenWidth.toDouble() / profile.baseWidth.toDouble()

    fun scalePoint(point: PointI, profile: UiProfile, screenWidth: Int): PointI =
        point.scaled(widthFactor(profile, screenWidth))

    fun scaleRect(rect: RectI, profile: UiProfile, screenWidth: Int): RectI =
        rect.scaled(widthFactor(profile, screenWidth))

    /**
     * 归一化坐标（0..1）→ 实际屏幕像素。
     *
     * 用于「程序设定」的固定点：横屏设备常常不是 16:9（实机 2712×1220），
     * 按宽度等比缩放会让 y 坐标偏大 25%。归一化后 x/y 分别乘实际宽高，
     * 不受宽高比影响。
     */
    fun scaleNormalized(x: Float, y: Float, screenWidth: Int, screenHeight: Int): PointI =
        PointI(
            (x * screenWidth).roundToInt().coerceIn(0, (screenWidth - 1).coerceAtLeast(0)),
            (y * screenHeight).roundToInt().coerceIn(0, (screenHeight - 1).coerceAtLeast(0)),
        )

    /** profile 坐标 -> 实际屏幕坐标；rect 为空表示全屏。 */
    fun resolveRect(
        rect: RectI?,
        profile: UiProfile,
        screenWidth: Int,
        screenHeight: Int,
    ): RectI = if (rect == null) {
        RectI(0, 0, screenWidth, screenHeight)
    } else {
        scaleRect(rect, profile, screenWidth)
    }

    fun isSupportedWidth(profile: UiProfile, screenWidth: Int): Boolean =
        profile.supportedWidths.isEmpty() || profile.supportedWidths.any {
            kotlin.math.abs(it - screenWidth) <= 2
        }

    /** 把 rect 限制在画面内，防止 OCR/裁剪越界。 */
    fun clamp(rect: RectI, screenWidth: Int, screenHeight: Int): RectI {
        val left = rect.x.coerceIn(0, screenWidth)
        val top = rect.y.coerceIn(0, screenHeight)
        val right = (rect.x + rect.w).coerceIn(left, screenWidth)
        val bottom = (rect.y + rect.h).coerceIn(top, screenHeight)
        return RectI.fromLTRB(left, top, right, bottom)
    }
}
