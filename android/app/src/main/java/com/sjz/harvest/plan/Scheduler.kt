package com.sjz.harvest.plan

import com.sjz.harvest.core.model.Dept
import com.sjz.harvest.core.model.DeptState
import com.sjz.harvest.core.model.DeptStatus

/**
 * 计算下一次唤醒间隔（秒）。纯 Kotlin，可在 JVM 单测中运行。
 *
 * 规则（docs/02-architecture.md 6.5）：
 * - 有 DONE → 0（立即收菜）；
 * - 有 IDLE 且有计划 → 0（立即补产）；
 * - 有 CRAFTING → max(15, 最小剩余秒 + buffer)；
 * - 全 UNKNOWN（或没有状态）→ 30（再试一次）；
 * - 其他 → 60。
 */
object Scheduler {

    private const val UNKNOWN_RETRY_SEC = 30L
    private const val IDLE_FALLBACK_SEC = 60L
    private const val MIN_CRAFTING_SEC = 15L

    fun nextWakeSec(
        statuses: Map<Dept, DeptStatus>,
        plan: PlanRepository,
        bufferSec: Int,
    ): Long {
        val safeBuffer = bufferSec.coerceAtLeast(0)

        if (statuses.values.any { it.state == DeptState.DONE }) return 0L

        val hasIdle = statuses.values.any { it.state == DeptState.IDLE }
        if (hasIdle) {
            val idleHasPlan = statuses.values
                .filter { it.state == DeptState.IDLE }
                .any { plan.next(it.dept) != null }
            if (idleHasPlan) return 0L
        }

        val crafting = statuses.values.filter { it.state == DeptState.CRAFTING }
        if (crafting.isNotEmpty()) {
            val minRemaining = crafting.mapNotNull { it.remainingSec }.minOrNull() ?: 0
            return maxOf(MIN_CRAFTING_SEC, minRemaining.toLong() + safeBuffer.toLong())
        }

        val allUnknown = statuses.isEmpty() ||
            statuses.values.all { it.state == DeptState.UNKNOWN }
        if (allUnknown) return UNKNOWN_RETRY_SEC

        return IDLE_FALLBACK_SEC
    }
}
