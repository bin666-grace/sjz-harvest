package com.sjz.harvest.game

import com.sjz.harvest.core.config.SafetyConfig
import com.sjz.harvest.core.config.TimingConfig
import com.sjz.harvest.core.config.UiProfile
import com.sjz.harvest.core.logging.AppLogger
import com.sjz.harvest.core.model.ActionKind
import com.sjz.harvest.core.model.ActionRecord
import com.sjz.harvest.core.model.CycleResult
import com.sjz.harvest.core.model.Dept
import com.sjz.harvest.core.model.DeptState
import com.sjz.harvest.core.model.Observation
import com.sjz.harvest.core.model.Page
import com.sjz.harvest.core.model.SjzException
import com.sjz.harvest.plan.PlanRepository
import com.sjz.harvest.plan.Scheduler
import kotlinx.coroutines.CancellationException

/**
 * 单个业务周期的编排：总览对齐 → 四部门收菜/补产 → 计算下次唤醒。
 *
 * 纯 Kotlin，不 import 任何 Android API；测试中用假 Observer/Crafter/
 * PlanRepository 即可覆盖所有分支。
 *
 * 安全语义：
 * - 单部门失败（物品找不到/缺材料/页面弹窗异常）只记录，继续其他部门；
 * - 页面/前台/截屏/输入整体异常 → 本轮停止（返回 stopped=true）；
 * - 每轮动作数超过 maxActionsPerCycle → 抛 SafetyLimitReached；
 * - 只有 craft 返回 SUCCESS 才消费计划并持久化；
 * - dry-run 下不消费计划。
 */
class HarvestFlow(
    private val observer: Observer,
    private val crafter: Crafter,
    private val plan: PlanRepository,
    private val profile: UiProfile,
    private val timing: TimingConfig,
    private val safety: SafetyConfig,
    private val logger: AppLogger,
) {

    /** 部门失败冷却截止时间（epoch ms）。跨周期保留，避免同一部门立即空转重试。 */
    private val failedUntilMs = HashMap<Dept, Long>()

    /** 连续「整轮状态不可识别」的周期数。 */
    private var unknownStreak = 0

    suspend fun runCycle(): CycleResult {
        logger.d(TAG, "开始业务周期 profile=${profile.name}")

        val actions = ArrayList<ActionRecord>()

        fun record(kind: ActionKind, detail: String) {
            actions.add(ActionRecord(kind = kind, detail = detail))
            val limit = safety.maxActionsPerCycle
            if (limit > 0 && actions.size > limit) {
                throw SjzException.SafetyLimitReached(
                    "本轮动作数 ${actions.size} 超过上限 $limit",
                )
            }
        }

        // 用户可能在周期开始瞬间切走：中止本轮而不是终止整个会话，
        // 由 AutomationController 等待游戏回到前台后继续。
        var observation = try {
            observer.observe()
        } catch (foreground: SjzException.GameNotForeground) {
            return abortedForeground(actions, foreground)
        }

        // ---- 1. 总览对齐：最多调用两次 recoverToDashboard ----
        if (observation.page != Page.DASHBOARD) {
            logger.w(TAG, "起始页面不是总览: ${observation.page}，尝试恢复")
            var attempt = 0
            while (attempt < 2 && observation.page != Page.DASHBOARD) {
                attempt++
                record(ActionKind.BACK, "恢复总览（第 $attempt 次，page=${observation.page}）")
                val recovered = crafter.recoverToDashboard()
                observation = observer.observe()
                logger.i(
                    TAG,
                    "恢复尝试 $attempt: recovered=$recovered, page=${observation.page}",
                )
            }
            if (observation.page != Page.DASHBOARD) {
                val message = "无法回到特勤处总览（page=${observation.page}）"
                logger.w(TAG, message)
                return CycleResult(
                    statuses = observation.statuses,
                    actions = actions.toList(),
                    nextWakeSec = RETRY_AFTER_STOP_SEC,
                    stopped = safety.stopOnUnknownPage,
                    error = message,
                )
            }
        }

        fun stoppedResult(message: String): CycleResult {
            logger.w(TAG, message)
            return CycleResult(
                statuses = observation.statuses,
                actions = actions.toList(),
                nextWakeSec = RETRY_AFTER_STOP_SEC,
                stopped = true,
                error = message,
            )
        }

        // ---- 2. 按固定顺序处理四个部门 ----
        // 实机四个部门位置固定；轮巡模式按配置顺序逐个点进去，
        // 不依赖状态文字/角标是否识别成功。
        val sweep = safety.sweepDepartments
        val order = if (sweep) sweepOrder() else Dept.entries.toList()
        for (dept in order) {
            val nowMs = System.currentTimeMillis()
            val blockedUntil = failedUntilMs[dept] ?: 0L
            if (blockedUntil > nowMs) {
                logger.d(
                    TAG,
                    "${dept.label} 处于失败冷却，剩余 ${(blockedUntil - nowMs + 999) / 1000}s，跳过",
                )
                continue
            }
            try {
                var status = observation.status(dept)

                // 已完成 → 收菜；轮巡模式下即使状态不是 DONE（OCR 漏字、
                // 角标漏检）也点进去看一眼。制造中的部门一定没有可收物品，
                // 轮巡跳过，避免无意义的页面往返。
                val sweepCollect = sweep && status.state != DeptState.CRAFTING
                if (status.state == DeptState.DONE || sweepCollect) {
                    record(
                        ActionKind.COLLECT,
                        if (sweepCollect) "轮巡收取 ${dept.label}" else "收取 ${dept.label}",
                    )
                    val collected = crafter.collect(dept)
                    if (!collected) {
                        // 轮巡收不到是正常情况（该部门没有完成物品），不算失败；
                        // 只有状态明确 DONE 却收不到才进入冷却。
                        if (!sweepCollect) {
                            logger.w(TAG, "收取 ${dept.label} 失败/未找到按钮，跳过该部门")
                            markFailed(dept)
                        } else {
                            logger.d(TAG, "轮巡 ${dept.label}：无可收取物品")
                        }
                        val refreshed = refreshDashboard {
                            record(ActionKind.BACK, "收取后恢复总览")
                        }
                        if (refreshed == null) {
                            return stoppedResult("收取 ${dept.label} 后无法恢复总览")
                        }
                        observation = refreshed
                        status = observation.status(dept)
                        if (!sweepCollect) continue
                    } else {
                        logger.i(TAG, "已收取 ${dept.label}")
                        failedUntilMs.remove(dept)

                        val refreshed = refreshDashboard {
                            record(ActionKind.BACK, "收取后恢复总览")
                        }
                        if (refreshed == null) {
                            return stoppedResult("收取 ${dept.label} 后无法恢复总览")
                        }
                        observation = refreshed
                        status = observation.status(dept)
                    }
                }

                // 空闲且有计划 → 补产。
                if (status.state == DeptState.IDLE) {
                    val entry = plan.next(dept)
                    if (entry == null) {
                        logger.d(TAG, "${dept.label} 空闲但无计划")
                        continue
                    }
                    record(ActionKind.CRAFT, "补产 ${dept.label}: ${entry.item}")
                    val outcome = crafter.craft(dept, entry)
                    if (outcome != CraftOutcome.SUCCESS) {
                        // 失败不扣计划；单部门失败不影响其他部门，但进入冷却。
                        logger.w(TAG, "补产 ${dept.label}/${entry.item} 失败: $outcome")
                        markFailed(dept)
                        val refreshed = refreshDashboard {
                            record(ActionKind.BACK, "补产失败后恢复总览")
                        }
                        if (refreshed == null) {
                            return stoppedResult("补产 ${dept.label} 失败后无法恢复总览")
                        }
                        observation = refreshed
                        continue
                    }

                    failedUntilMs.remove(dept)
                    // 只有成功才消费计划。
                    if (safety.dryRun) {
                        logger.i(TAG, "[dry-run] 补产成功，但不消费计划 ${entry.item}")
                    } else {
                        plan.consume(dept, entry)
                        try {
                            plan.persist()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            logger.e(TAG, "计划持久化失败（内存已扣减）", error)
                        }
                    }

                    val refreshed = refreshDashboard {
                        record(ActionKind.BACK, "补产后恢复总览")
                    }
                    if (refreshed == null) {
                        return stoppedResult("补产 ${dept.label} 后无法恢复总览")
                    }
                    observation = refreshed
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (limit: SjzException.SafetyLimitReached) {
                throw limit
            } catch (foreground: SjzException.GameNotForeground) {
                return abortedForeground(actions, foreground)
            } catch (pageError: SjzException.PageNotRecognized) {
                return stoppedResult(pageError.message ?: "页面未识别")
            } catch (captureError: SjzException.CaptureFailed) {
                return stoppedResult(captureError.message ?: "截屏失败")
            } catch (inputError: SjzException.InputUnavailable) {
                return stoppedResult(inputError.message ?: "输入不可用")
            } catch (serviceError: SjzException.AccessibilityServiceUnavailable) {
                return stoppedResult(serviceError.message ?: "无障碍服务不可用")
            } catch (error: Exception) {
                // 业务层未知异常：记录后继续下一个部门，避免整轮卡死。
                logger.e(TAG, "处理 ${dept.label} 时发生异常", error)
                markFailed(dept)
            }
        }

        // ---- 3. 重新观察并计算下次唤醒 ----
        val finalObservation: Observation = try {
            observer.observe()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.e(TAG, "周期结束观察失败，沿用周期内最后一次观察", error)
            observation
        }

        // 整轮都无法识别 → 累积计数；成功识别到任一状态则清零。
        val allUnknown = finalObservation.page != Page.DASHBOARD ||
            finalObservation.statuses.isEmpty() ||
            finalObservation.statuses.values.all { it.state == DeptState.UNKNOWN }
        unknownStreak = if (allUnknown) unknownStreak + 1 else 0
        val unknownLimit = safety.maxConsecutiveUnknownCycles
        if (unknownLimit > 0 && unknownStreak >= unknownLimit) {
            val message = "连续 $unknownStreak 个周期无法识别任何部门状态，停止以防空转"
            logger.w(TAG, message)
            return CycleResult(
                statuses = finalObservation.statuses,
                actions = actions.toList(),
                nextWakeSec = RETRY_AFTER_STOP_SEC,
                stopped = true,
                error = message,
            )
        }

        var nextWake = Scheduler.nextWakeSec(
            statuses = finalObservation.statuses,
            plan = plan,
            bufferSec = timing.cycleBufferSec,
        )
        // 若本轮立即唤醒的唯一原因是被冷却的部门，则等到冷却结束再试。
        if (nextWake == 0L) {
            val nowMs = System.currentTimeMillis()
            val hasActionable = finalObservation.statuses.values.any { status ->
                val blocked = (failedUntilMs[status.dept] ?: 0L) > nowMs
                !blocked && when (status.state) {
                    DeptState.DONE -> true
                    DeptState.IDLE -> plan.next(status.dept) != null
                    else -> false
                }
            }
            if (!hasActionable) {
                val minRemainingSec = failedUntilMs.values
                    .filter { it > nowMs }
                    .minOrNull()
                    ?.let { (it - nowMs + 999) / 1000 }
                if (minRemainingSec != null) {
                    nextWake = maxOf(nextWake, minRemainingSec)
                }
            }
        }
        logger.i(
            TAG,
            "周期结束 actions=${actions.size} page=${finalObservation.page} " +
                "nextWake=${nextWake}s unknownStreak=$unknownStreak",
        )
        return CycleResult(
            statuses = finalObservation.statuses,
            actions = actions.toList(),
            nextWakeSec = nextWake,
        )
    }

    /**
     * 游戏不在前台：本轮中止，但不把会话标记为 stopped。
     * 外层编排在下一轮开始前会等待游戏回到前台（有上限），
     * 这样用户临时切到其它应用不会导致整场自动化结束。
     */
    private fun abortedForeground(
        actions: List<ActionRecord>,
        error: SjzException.GameNotForeground,
    ): CycleResult {
        val message = "游戏不在前台，等待切回游戏后继续"
        logger.w(TAG, "本轮中止：${error.message ?: "游戏不在前台"}")
        return CycleResult(
            statuses = emptyMap(),
            actions = actions.toList(),
            nextWakeSec = 0,
            error = message,
        )
    }

    /** 轮巡顺序：配置里的部门在前，未列出的部门按枚举顺序补在后面。 */
    private fun sweepOrder(): List<Dept> {
        val configured = profile.dashboard.sweepOrder.mapNotNull { Dept.fromKey(it) }
        return configured + Dept.entries.filter { it !in configured }
    }

    /** 记录部门失败并进入冷却；backoff<=0 时不冷却。 */
    private fun markFailed(dept: Dept) {
        val backoffSec = timing.failureBackoffSec
        if (backoffSec <= 0) return
        failedUntilMs[dept] = System.currentTimeMillis() + backoffSec * 1000L
        logger.i(TAG, "${dept.label} 进入失败冷却 ${backoffSec}s")
    }

    /**
     * 观察当前页面；若不是总览，记录一次恢复动作并尝试 recoverToDashboard。
     * 返回 null 表示无法回到总览（调用方停止本轮）。
     */
    private suspend fun refreshDashboard(onRecover: () -> Unit): Observation? {
        var current = observer.observe()
        if (current.page == Page.DASHBOARD) return current

        logger.w(TAG, "动作后页面不是总览: ${current.page}，尝试恢复")
        onRecover()
        if (!crafter.recoverToDashboard()) return null
        current = observer.observe()
        return current.takeIf { it.page == Page.DASHBOARD }
    }

    companion object {
        private const val TAG = "HarvestFlow"
        private const val RETRY_AFTER_STOP_SEC = 60L
    }
}
