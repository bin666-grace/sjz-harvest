package com.sjz.harvest.plan

import com.sjz.harvest.core.config.Plan
import com.sjz.harvest.core.config.PlanEntry
import com.sjz.harvest.core.model.Dept
import com.sjz.harvest.core.model.DeptState
import com.sjz.harvest.core.model.DeptStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SchedulerTest {

    private class FakePlan(private val plans: Map<Dept, List<PlanEntry>>) : PlanRepository {
        override fun next(dept: Dept): PlanEntry? =
            plans[dept]?.firstOrNull { it.enabled && it.count != 0 }

        override fun consume(dept: Dept, entry: PlanEntry) = Unit
        override fun add(dept: Dept, entry: PlanEntry) = Unit
        override fun remove(dept: Dept, item: String) = Unit
        override fun snapshot(): Plan = Plan()
        override suspend fun persist() = Unit
    }

    private fun status(dept: Dept, state: DeptState, remaining: Int? = null) =
        DeptStatus(dept = dept, state = state, remainingSec = remaining)

    @Test
    fun `有 DONE 立即唤醒`() {
        val statuses = mapOf(
            Dept.TECH to status(Dept.TECH, DeptState.DONE),
            Dept.WORK to status(Dept.WORK, DeptState.CRAFTING, 100),
        )
        assertEquals(0L, Scheduler.nextWakeSec(statuses, FakePlan(emptyMap()), 30))
    }

    @Test
    fun `有 IDLE 且该部门有计划立即唤醒`() {
        val statuses = mapOf(
            Dept.TECH to status(Dept.TECH, DeptState.IDLE),
            Dept.ARMOR to status(Dept.ARMOR, DeptState.CRAFTING, 300),
        )
        val plan = FakePlan(mapOf(Dept.TECH to listOf(PlanEntry("gun", -1))))
        assertEquals(0L, Scheduler.nextWakeSec(statuses, plan, 30))
    }

    @Test
    fun `IDLE 但计划为空时等待 CRAFTING 或回退`() {
        val statuses = mapOf(Dept.TECH to status(Dept.TECH, DeptState.IDLE))
        assertEquals(60L, Scheduler.nextWakeSec(statuses, FakePlan(emptyMap()), 30))
    }

    @Test
    fun `CRAFTING 取最小剩余时间加缓冲`() {
        val statuses = mapOf(
            Dept.TECH to status(Dept.TECH, DeptState.CRAFTING, 100),
            Dept.WORK to status(Dept.WORK, DeptState.CRAFTING, 40),
        )
        assertEquals(70L, Scheduler.nextWakeSec(statuses, FakePlan(emptyMap()), 30))
    }

    @Test
    fun `CRAFTING 结果不小于 15 秒`() {
        val statuses = mapOf(Dept.TECH to status(Dept.TECH, DeptState.CRAFTING, 3))
        assertEquals(15L, Scheduler.nextWakeSec(statuses, FakePlan(emptyMap()), 5))
    }

    @Test
    fun `全 UNKNOWN 或空状态等 30 秒`() {
        val unknown = mapOf(
            Dept.TECH to status(Dept.TECH, DeptState.UNKNOWN),
            Dept.WORK to status(Dept.WORK, DeptState.UNKNOWN),
        )
        assertEquals(30L, Scheduler.nextWakeSec(unknown, FakePlan(emptyMap()), 30))
        assertEquals(30L, Scheduler.nextWakeSec(emptyMap(), FakePlan(emptyMap()), 30))
    }

    @Test
    fun `其他混合状态回退 60 秒`() {
        val statuses = mapOf(
            Dept.TECH to status(Dept.TECH, DeptState.IDLE),
            Dept.WORK to status(Dept.WORK, DeptState.UNKNOWN),
        )
        assertEquals(60L, Scheduler.nextWakeSec(statuses, FakePlan(emptyMap()), 30))
    }
}
