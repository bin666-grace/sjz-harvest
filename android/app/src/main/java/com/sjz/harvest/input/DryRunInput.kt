package com.sjz.harvest.input

import com.sjz.harvest.core.logging.AppLogger
import com.sjz.harvest.core.model.PointI

/**
 * dry-run 输入后端：完整记录意图但绝不执行。
 * 用于首次校准 / 识别验证。
 */
class DryRunInput(
    private val logger: AppLogger,
) : InputController {

    override suspend fun tap(point: PointI) {
        logger.i(TAG, "[dry-run] tap (${point.x}, ${point.y})")
    }

    override suspend fun swipe(from: PointI, to: PointI, durationMs: Long) {
        logger.i(TAG, "[dry-run] swipe (${from.x}, ${from.y}) -> (${to.x}, ${to.y}) ${durationMs}ms")
    }

    override suspend fun back() {
        logger.i(TAG, "[dry-run] back")
    }

    override fun isAvailable(): Boolean = true

    override fun backendName(): String = "dry_run"

    override fun close() {
        // 无资源。
    }

    companion object {
        private const val TAG = "DryRunInput"
    }
}
