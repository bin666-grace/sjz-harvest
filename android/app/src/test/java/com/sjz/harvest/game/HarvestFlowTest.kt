package com.sjz.harvest.game

import com.sjz.harvest.core.config.Plan
import com.sjz.harvest.core.config.PlanEntry
import com.sjz.harvest.core.config.SafetyConfig
import com.sjz.harvest.core.config.TimingConfig
import com.sjz.harvest.core.config.UiProfile
import com.sjz.harvest.core.logging.NoopLogger
import com.sjz.harvest.core.model.ActionKind
import com.sjz.harvest.core.model.Dept
import com.sjz.harvest.core.model.DeptState
import com.sjz.harvest.core.model.DeptStatus
import com.sjz.harvest.core.model.Observation
import com.sjz.harvest.core.model.Page
import com.sjz.harvest.core.model.SjzException
import com.sjz.harvest.plan.PlanRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HarvestFlowTest {

    private class FakeObserver(var current: Observation) : Observer {
        var calls = 0
        override suspend fun observe(): Observation {
            calls++
            return current
        }
    }

    private class ThrowingObserver(private val error: Throwable) : Observer {
        override suspend fun observe(): Observation = throw error
    }

    private class FakeCrafter : Crafter {
        var collectResult = true
        val collectCalls = mutableListOf<Dept>()
        val craftCalls = mutableListOf<Pair<Dept, PlanEntry>>()
        val outcomes = mutableMapOf<String, CraftOutcome>()
        val recoverResults = ArrayDeque<Boolean>()
        var recoverCalls = 0

        var onCollect: ((Dept) -> Unit)? = null
        var onCraft: ((Dept, PlanEntry) -> Unit)? = null
        var onRecover: ((Int) -> Unit)? = null

        override suspend fun collect(dept: Dept): Boolean {
            collectCalls.add(dept)
            onCollect?.invoke(dept)
            return collectResult
        }

        override suspend fun craft(dept: Dept, entry: PlanEntry): CraftOutcome {
            craftCalls.add(dept to entry)
            onCraft?.invoke(dept, entry)
            return outcomes[entry.item] ?: CraftOutcome.SUCCESS
        }

        override suspend fun recoverToDashboard(): Boolean {
            recoverCalls++
            onRecover?.invoke(recoverCalls)
            return recoverResults.removeFirstOrNull() ?: false
        }
    }

    private class FakePlan(initial: Map<Dept, List<PlanEntry>> = emptyMap()) : PlanRepository {
        private val data = LinkedHashMap<Dept, MutableList<PlanEntry>>().apply {
            initial.forEach { (dept, entries) -> put(dept, entries.toMutableList()) }
        }

        val consumeCalls = mutableListOf<Pair<Dept, PlanEntry>>()
        var persistCalls = 0

        override fun next(dept: Dept): PlanEntry? =
            data[dept]?.firstOrNull { it.enabled && it.count != 0 }

        override fun consume(dept: Dept, entry: PlanEntry) {
            consumeCalls.add(dept to entry)
            val entries = data[dept] ?: return
            val index = entries.indexOfFirst { it.item == entry.item }
            if (index < 0) return
            val current = entries[index]
            when {
                current.count == -1 -> Unit
                current.count > 1 -> entries[index] = current.copy(count = current.count - 1)
                current.count == 1 -> entries.removeAt(index)
                else -> Unit
            }
        }

        override fun add(dept: Dept, entry: PlanEntry) {
            data.getOrPut(dept) { mutableListOf() }.add(entry)
        }

        override fun remove(dept: Dept, item: String) {
            data[dept]?.removeAll { it.item == item }
        }

        override fun snapshot(): Plan = Plan()

        override suspend fun persist() {
            persistCalls++
        }
    }

    private fun status(dept: Dept, state: DeptState, remainingSec: Int? = null) =
        DeptStatus(dept = dept, state = state, remainingSec = remainingSec)

    private fun observation(page: Page, vararg statuses: DeptStatus) =
        Observation(page = page, statuses = statuses.associateBy { it.dept })

    private fun flow(
        observer: Observer,
        crafter: Crafter,
        plan: PlanRepository,
        // 多数用例验证「只按识别到的状态动作」，轮巡收取单独用显式用例覆盖。
        safety: SafetyConfig = SafetyConfig(sweepDepartments = false),
        timing: TimingConfig = TimingConfig(cycleBufferSec = 30),
    ): HarvestFlow = HarvestFlow(
        observer = observer,
        crafter = crafter,
        plan = plan,
        profile = UiProfile(),
        timing = timing,
        safety = safety,
        logger = NoopLogger,
    )

    @Test
    fun `收菜后补产，成功后消费计划并计算下次唤醒`() = runTest {
        val observer = FakeObserver(observation(Page.DASHBOARD, status(Dept.TECH, DeptState.DONE)))
        val plan = FakePlan(
            mapOf(Dept.TECH to listOf(PlanEntry("3/7可调倍率狙击镜", 1))),
        )
        val crafter = FakeCrafter().apply {
            onCollect = {
                observer.current = observation(Page.DASHBOARD, status(Dept.TECH, DeptState.IDLE))
            }
            onCraft = { _, _ ->
                observer.current = observation(
                    Page.DASHBOARD,
                    status(Dept.TECH, DeptState.CRAFTING, 120),
                )
            }
        }

        val result = flow(observer, crafter, plan).runCycle()

        assertEquals(listOf(Dept.TECH), crafter.collectCalls)
        assertEquals(1, crafter.craftCalls.size)
        assertEquals(Dept.TECH, crafter.craftCalls.first().first)
        assertEquals(listOf(Dept.TECH to PlanEntry("3/7可调倍率狙击镜", 1)), plan.consumeCalls)
        assertEquals(1, plan.persistCalls)
        assertEquals(150L, result.nextWakeSec)
        assertEquals(
            listOf(ActionKind.COLLECT, ActionKind.CRAFT),
            result.actions.map { it.kind },
        )
    }

    @Test
    fun `单部门补产失败不影响其他部门`() = runTest {
        val observer = FakeObserver(
            observation(
                Page.DASHBOARD,
                status(Dept.TECH, DeptState.IDLE),
                status(Dept.WORK, DeptState.IDLE),
            ),
        )
        val techItem = PlanEntry("gun", 1)
        val workItem = PlanEntry("ammo", 1)
        val plan = FakePlan(
            mapOf(
                Dept.TECH to listOf(techItem),
                Dept.WORK to listOf(workItem),
            ),
        )
        val crafter = FakeCrafter().apply {
            outcomes["gun"] = CraftOutcome.ITEM_NOT_FOUND
            outcomes["ammo"] = CraftOutcome.SUCCESS
        }

        val result = flow(observer, crafter, plan).runCycle()

        assertEquals(
            listOf(Dept.TECH to techItem, Dept.WORK to workItem),
            crafter.craftCalls,
        )
        // 只有成功的 WORK 被消费。
        assertEquals(listOf(Dept.WORK to workItem), plan.consumeCalls)
        assertEquals(1, plan.persistCalls)
        assertFalse(result.stopped)
        assertNull(result.error)
    }

    @Test
    fun `未知页面恢复失败后停止`() = runTest {
        val observer = FakeObserver(Observation(page = Page.UNKNOWN))
        val crafter = FakeCrafter().apply {
            recoverResults.addLast(false)
            recoverResults.addLast(false)
        }
        val plan = FakePlan()

        val result = flow(observer, crafter, plan).runCycle()

        assertTrue(result.stopped)
        assertTrue(result.error != null)
        assertEquals(2, crafter.recoverCalls)
        assertEquals(2, result.actions.size)
        assertTrue(result.actions.all { it.kind == ActionKind.BACK })
        assertTrue(crafter.collectCalls.isEmpty())
        assertTrue(crafter.craftCalls.isEmpty())
    }

    @Test
    fun `恢复成功后继续本轮`() = runTest {
        val observer = FakeObserver(Observation(page = Page.UNKNOWN))
        val crafter = FakeCrafter().apply {
            recoverResults.addLast(true)
            onRecover = {
                observer.current = observation(
                    Page.DASHBOARD,
                    status(Dept.TECH, DeptState.IDLE),
                )
            }
        }

        val result = flow(observer, crafter, FakePlan()).runCycle()

        assertFalse(result.stopped)
        assertEquals(1, crafter.recoverCalls)
        assertEquals(listOf(ActionKind.BACK), result.actions.map { it.kind })
        assertEquals(60L, result.nextWakeSec)
    }

    @Test
    fun `超过每轮动作上限抛 SafetyLimitReached`() = runTest {
        val observer = FakeObserver(
            observation(
                Page.DASHBOARD,
                status(Dept.TECH, DeptState.DONE),
                status(Dept.WORK, DeptState.DONE),
            ),
        )
        val crafter = FakeCrafter().apply {
            // 收菜后观察结果仍是 DONE，避免进入补产分支。
            collectResult = true
        }
        val flow = flow(
            observer,
            crafter,
            FakePlan(),
            SafetyConfig(maxActionsPerCycle = 1),
        )

        var thrown: Throwable? = null
        try {
            flow.runCycle()
        } catch (t: Throwable) {
            thrown = t
        }

        assertTrue(thrown is SjzException.SafetyLimitReached)
        // 第二个部门的动作在记录时就被拦截，未真正执行点击。
        assertEquals(1, crafter.collectCalls.size)
    }

    @Test
    fun `补产失败不消费计划`() = runTest {
        val item = PlanEntry("medkit", 3)
        val observer = FakeObserver(
            observation(Page.DASHBOARD, status(Dept.MEDICAL, DeptState.IDLE)),
        )
        val plan = FakePlan(mapOf(Dept.MEDICAL to listOf(item)))
        val crafter = FakeCrafter().apply {
            outcomes["medkit"] = CraftOutcome.MISSING_MATERIALS
        }

        val result = flow(observer, crafter, plan).runCycle()

        assertTrue(plan.consumeCalls.isEmpty())
        assertEquals(0, plan.persistCalls)
        assertEquals(3, plan.next(Dept.MEDICAL)?.count)
        assertEquals(listOf(ActionKind.CRAFT), result.actions.map { it.kind })
    }

    @Test
    fun `补产失败后同一部门在冷却期跳过`() = runTest {
        val item = PlanEntry("medkit", 3)
        val observer = FakeObserver(
            observation(Page.DASHBOARD, status(Dept.MEDICAL, DeptState.IDLE)),
        )
        val plan = FakePlan(mapOf(Dept.MEDICAL to listOf(item)))
        val crafter = FakeCrafter().apply {
            outcomes["medkit"] = CraftOutcome.MISSING_MATERIALS
        }
        val flow = flow(
            observer,
            crafter,
            plan,
            timing = TimingConfig(cycleBufferSec = 30, failureBackoffSec = 300),
        )

        val first = flow.runCycle()
        val second = flow.runCycle()

        assertEquals(1, crafter.craftCalls.size)
        assertEquals(listOf(ActionKind.CRAFT), first.actions.map { it.kind })
        assertTrue(second.actions.isEmpty())
        // 唯一可执行部门在冷却中，下一次唤醒不能是 0，否则会空转。
        assertTrue(second.nextWakeSec >= 290L)
        assertTrue(plan.consumeCalls.isEmpty())
    }

    @Test
    fun `连续未知周期达到上限后停止`() = runTest {
        val observer = FakeObserver(
            observation(
                Page.DASHBOARD,
                status(Dept.TECH, DeptState.UNKNOWN),
                status(Dept.WORK, DeptState.UNKNOWN),
            ),
        )
        val flow = flow(
            observer,
            FakeCrafter(),
            FakePlan(),
            safety = SafetyConfig(maxConsecutiveUnknownCycles = 2),
        )

        val first = flow.runCycle()
        val second = flow.runCycle()

        assertFalse(first.stopped)
        assertTrue(second.stopped)
        assertTrue(second.error?.contains("无法识别") == true)
    }

    @Test
    fun `游戏切到后台时中止本轮但不终止会话`() = runTest {
        val flow = flow(
            ThrowingObserver(SjzException.GameNotForeground("com.android.launcher")),
            FakeCrafter(),
            FakePlan(),
        )

        val result = flow.runCycle()

        assertFalse(result.stopped)
        assertEquals(0L, result.nextWakeSec)
        assertTrue(result.error?.contains("游戏不在前台") == true)
        assertTrue(result.actions.isEmpty())
    }

    @Test
    fun `收菜后未变为空闲则不补产`() = runTest {
        val observer = FakeObserver(observation(Page.DASHBOARD, status(Dept.TECH, DeptState.DONE)))
        val plan = FakePlan(mapOf(Dept.TECH to listOf(PlanEntry("gun", 1))))
        val crafter = FakeCrafter() // 收菜后 observer 仍返回 DONE

        val result = flow(observer, crafter, plan).runCycle()

        assertEquals(listOf(Dept.TECH), crafter.collectCalls)
        assertTrue(crafter.craftCalls.isEmpty())
        assertTrue(plan.consumeCalls.isEmpty())
        assertEquals(listOf(ActionKind.COLLECT), result.actions.map { it.kind })
    }

    /**
     * 实机四个部门位置固定，用户流程是「依次点进去看，有没有可领取的」。
     * 轮巡模式不再依赖状态识别：除制造中的部门外，每个部门都点进去找一次
     * 「收获/收取」，收不到不算失败、不进入冷却。
     */
    @Test
    fun `轮巡模式按配置顺序依次探查各部门且跳过制造中`() = runTest {
        val observer = FakeObserver(
            observation(
                Page.DASHBOARD,
                status(Dept.TECH, DeptState.IDLE),
                status(Dept.WORK, DeptState.CRAFTING, 120),
                // 制药台已完成但 OCR 漏掉了状态文字 —— 轮巡正是为这种情况兜底。
                status(Dept.MEDICAL, DeptState.UNKNOWN),
                status(Dept.ARMOR, DeptState.DONE),
            ),
        )
        val crafter = FakeCrafter().apply { collectResult = false }

        val result = flow(
            observer,
            crafter,
            FakePlan(),
            safety = SafetyConfig(sweepDepartments = true),
        ).runCycle()

        // 默认顺序 tech → armor → work → medical；工作台制造中跳过。
        assertEquals(
            listOf(Dept.TECH, Dept.ARMOR, Dept.MEDICAL),
            crafter.collectCalls,
        )
        assertEquals(
            List(3) { ActionKind.COLLECT },
            result.actions.map { it.kind },
        )
        assertTrue(crafter.craftCalls.isEmpty())
    }

    @Test
    fun `轮巡收不到物品不进入失败冷却`() = runTest {
        val observer = FakeObserver(
            observation(
                Page.DASHBOARD,
                status(Dept.TECH, DeptState.UNKNOWN),
                status(Dept.WORK, DeptState.UNKNOWN),
                status(Dept.MEDICAL, DeptState.UNKNOWN),
                status(Dept.ARMOR, DeptState.UNKNOWN),
            ),
        )
        val crafter = FakeCrafter().apply { collectResult = false }
        val flow = flow(
            observer,
            crafter,
            FakePlan(),
            safety = SafetyConfig(sweepDepartments = true),
            timing = TimingConfig(cycleBufferSec = 30, failureBackoffSec = 300),
        )

        flow.runCycle()
        flow.runCycle()

        // 两轮都探查了四个部门：收不到不是失败，下一轮照常再点。
        assertEquals(
            listOf(
                Dept.TECH, Dept.ARMOR, Dept.WORK, Dept.MEDICAL,
                Dept.TECH, Dept.ARMOR, Dept.WORK, Dept.MEDICAL,
            ),
            crafter.collectCalls,
        )
    }
}
