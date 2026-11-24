package com.sjz.harvest.input

import com.sjz.harvest.core.config.InputConfig
import com.sjz.harvest.core.logging.AppLogger
import com.sjz.harvest.core.model.PointI
import com.sjz.harvest.core.model.SjzException
import com.sjz.harvest.service.SjzAccessibilityService

/**
 * 无障碍手势输入后端。真正的手势派发在主线程由 SjzAccessibilityService 完成，
 * 本类只负责参数整理、按压时长/滑动时长默认值与可用性判断。
 */
class AccessibilityGestureInput(
    private val serviceProvider: () -> SjzAccessibilityService?,
    private val config: InputConfig,
    private val screenSize: Pair<Int, Int>,
    private val logger: AppLogger,
    /**
     * 每次手势前校验前台包名。null = 不校验（诊断/手动场景）。
     * 自动化必须传入游戏包名，防止用户中途切走后点击落到其他应用。
     */
    private val requiredPackage: String? = null,
) : InputController {

    override suspend fun tap(point: PointI) {
        val service = requireService()
        val bounds = currentBounds(service)
        val target = clamp(point, bounds)
        service.tapWithHold(target, config.tapHoldMs.coerceIn(1L, 2_000L))
        logger.d(TAG, "tap (${target.x}, ${target.y}) bounds=${bounds.first}x${bounds.second}")
    }

    override suspend fun swipe(from: PointI, to: PointI, durationMs: Long) {
        val service = requireService()
        val bounds = currentBounds(service)
        val start = clamp(from, bounds)
        val end = clamp(to, bounds)
        val duration = if (durationMs > 0) durationMs else config.swipeDurationMs
        service.swipe(start, end, duration)
        logger.d(
            TAG,
            "swipe (${start.x}, ${start.y}) -> (${end.x}, ${end.y}) ${duration}ms " +
                "bounds=${bounds.first}x${bounds.second}",
        )
    }

    override suspend fun back() {
        val service = requireService()
        service.back()
        logger.d(TAG, "back")
    }

    override fun isAvailable(): Boolean = serviceProvider() != null

    override fun backendName(): String = "accessibility"

    override fun close() {
        // 服务由系统管理，这里没有需要释放的资源。
    }

    private fun requireService(): SjzAccessibilityService {
        val service = serviceProvider()
            ?: throw SjzException.AccessibilityServiceUnavailable()
        val expected = requiredPackage
        if (expected != null) {
            val foreground = service.foregroundPackage()
            if (foreground != expected) {
                throw SjzException.InputUnavailable(
                    "前台不是目标游戏（当前：${foreground ?: "未知"}），已阻止输入",
                )
            }
        }
        return service
    }

    /**
     * 每次手势前重新读取显示器尺寸。
     *
     * 不能用创建输入后端时的尺寸：那时 App 在前台（竖屏），游戏切到前台后
     * 显示器会旋转成横屏；固定尺寸会把横坐标截短，点击落到错误位置。
     */
    private fun currentBounds(service: SjzAccessibilityService): Pair<Int, Int> {
        val live = service.screenSize()
        if (live.first > 0 && live.second > 0) return live
        return screenSize
    }

    private fun clamp(point: PointI, bounds: Pair<Int, Int>): PointI {
        val width = bounds.first
        val height = bounds.second
        if (width <= 0 || height <= 0) return point
        return PointI(
            point.x.coerceIn(0, width - 1),
            point.y.coerceIn(0, height - 1),
        )
    }

    companion object {
        private const val TAG = "A11yInput"
    }
}
