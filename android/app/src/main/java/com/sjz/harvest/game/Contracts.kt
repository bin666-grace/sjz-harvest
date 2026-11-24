package com.sjz.harvest.game

import com.sjz.harvest.core.config.PlanEntry
import com.sjz.harvest.core.model.Dept
import com.sjz.harvest.core.model.Observation

/** 只读感知：业务层通过它获取当前页面和部门状态。 */
interface Observer {
    suspend fun observe(): Observation
}

/**
 * 前台包名守卫。
 *
 * 每个业务周期开始时会检查游戏是否在前台，但用户可能在周期中途切走
 * （例如查看本 App 或通知）。此时继续截屏会把 App 自己的界面当成游戏，
 * 进而误判页面/误点击。业务层在每次截屏和点击前调用本接口，
 * 不是游戏时抛 [com.sjz.harvest.core.model.SjzException.GameNotForeground]。
 */
fun interface ForegroundGuard {
    suspend fun ensureGameForeground()

    companion object {
        /** 测试/不校验前台的场景。 */
        val ALWAYS_OK = ForegroundGuard { }
    }
}

enum class CraftOutcome {
    SUCCESS,
    ITEM_NOT_FOUND,
    MISSING_MATERIALS,
    BUY_FAILED,
    PAGE_ERROR,
    ABORTED,
    SKIPPED,
}

/** 写操作：收菜与补产。实现内部负责点击、等待与页面校验。 */
interface Crafter {
    suspend fun collect(dept: Dept): Boolean

    suspend fun craft(dept: Dept, entry: PlanEntry): CraftOutcome

    /** 用返回键/已知入口尝试回到特勤处总览；返回是否成功。 */
    suspend fun recoverToDashboard(): Boolean
}
