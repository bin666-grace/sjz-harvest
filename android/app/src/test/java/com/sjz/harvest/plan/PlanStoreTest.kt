package com.sjz.harvest.plan

import com.sjz.harvest.core.config.Plan
import com.sjz.harvest.core.config.PlanEntry
import com.sjz.harvest.core.logging.NoopLogger
import com.sjz.harvest.core.model.Dept
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlanStoreTest {

    private fun store(plan: Plan, save: suspend (Plan) -> Unit = {}): PlanStore =
        PlanStore(plan, save, NoopLogger)

    private fun entry(item: String, count: Int, enabled: Boolean = true) =
        PlanEntry(item = item, count = count, enabled = enabled)

    @Test
    fun `next 取第一个启用且 count 非零的条目`() {
        val store = store(
            Plan(
                departments = mapOf(
                    "tech" to listOf(
                        entry("disabled", 3, enabled = false),
                        entry("zero", 0),
                        entry("first", 5),
                        entry("second", 1),
                    ),
                ),
            ),
        )
        assertEquals("first", store.next(Dept.TECH)?.item)
        assertNull(store.next(Dept.WORK))
    }

    @Test
    fun `consume count=-1 保持不变`() {
        val store = store(
            Plan(departments = mapOf("tech" to listOf(entry("infinite", -1)))),
        )
        val e = store.next(Dept.TECH)!!
        store.consume(Dept.TECH, e)
        assertEquals(-1, store.next(Dept.TECH)?.count)
    }

    @Test
    fun `consume count 大于 1 递减`() {
        val store = store(
            Plan(departments = mapOf("work" to listOf(entry("ammo", 3)))),
        )
        store.consume(Dept.WORK, store.next(Dept.WORK)!!)
        assertEquals(2, store.next(Dept.WORK)?.count)
        store.consume(Dept.WORK, store.next(Dept.WORK)!!)
        assertEquals(1, store.next(Dept.WORK)?.count)
    }

    @Test
    fun `consume count 等于 1 时删除`() {
        val store = store(
            Plan(departments = mapOf("medical" to listOf(entry("medkit", 1)))),
        )
        store.consume(Dept.MEDICAL, store.next(Dept.MEDICAL)!!)
        assertNull(store.next(Dept.MEDICAL))
        assertTrue(store.snapshot().departments["medical"].isNullOrEmpty())
    }

    @Test
    fun `consume count=0 不消费`() {
        val store = store(
            Plan(departments = mapOf("armor" to listOf(entry("vest", 0)))),
        )
        store.consume(Dept.ARMOR, entry("vest", 0))
        assertEquals(1, store.snapshot().departments["armor"]?.size)
        assertEquals(0, store.snapshot().departments["armor"]?.first()?.count)
    }

    @Test
    fun `consume 未找到条目时无副作用`() {
        val store = store(
            Plan(departments = mapOf("tech" to listOf(entry("known", 2)))),
        )
        store.consume(Dept.TECH, entry("unknown", 2))
        assertEquals(2, store.next(Dept.TECH)?.count)
    }

    @Test
    fun `add 与 remove 生效`() {
        val store = store(Plan())
        store.add(Dept.TECH, entry("new-gun", 2))
        assertEquals("new-gun", store.next(Dept.TECH)?.item)
        store.remove(Dept.TECH, "new-gun")
        assertNull(store.next(Dept.TECH))
    }

    @Test
    fun `snapshot 是深拷贝`() {
        val store = store(Plan(departments = mapOf("tech" to listOf(entry("gun", 2)))))
        val snap = store.snapshot()
        store.consume(Dept.TECH, store.next(Dept.TECH)!!)
        assertEquals(2, snap.departments["tech"]?.first()?.count)
        assertEquals(1, store.snapshot().departments["tech"]?.first()?.count)
    }

    @Test
    fun `persist 把最新快照写到临时文件`() = runTest {
        val file = File.createTempFile("sjz-plan-", ".json")
        try {
            val store = store(
                Plan(
                    departments = mapOf(
                        "tech" to listOf(entry("gun", 2)),
                        "unknownDept" to listOf(entry("keep", 1)),
                    ),
                ),
                save = { plan -> file.writeText(serialize(plan)) },
            )
            store.consume(Dept.TECH, store.next(Dept.TECH)!!)
            store.persist()

            val text = file.readText()
            assertTrue(text.contains("tech=gunx1"))
            // 未知部门 key 不应丢失。
            assertTrue(text.contains("unknownDept=keepx1"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `persist 回调抛出时向调用方传播`() = runTest {
        val store = store(
            Plan(departments = mapOf("tech" to listOf(entry("gun", 1)))),
            save = { throw IllegalStateException("disk full") },
        )
        var thrown: Throwable? = null
        try {
            store.persist()
        } catch (t: Throwable) {
            thrown = t
        }
        assertNotNull(thrown)
        assertTrue(thrown is IllegalStateException)
    }

    private fun serialize(plan: Plan): String =
        plan.departments.entries.joinToString(";") { (dept, entries) ->
            "$dept=" + entries.joinToString(",") { "${it.item}x${it.count}" }
        }
}
