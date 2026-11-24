package com.sjz.harvest.plan

import com.sjz.harvest.core.config.Plan
import com.sjz.harvest.core.config.PlanEntry
import com.sjz.harvest.core.logging.AppLogger
import com.sjz.harvest.core.model.Dept
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 生产计划的内存实现 + 持久化回调。
 *
 * 纯 JVM（只依赖 java.* / kotlinx.coroutines），不依赖 Android。
 * 所有读写方法线程安全；[persist] 只负责把当前快照交给上层保存函数，
 * 具体写文件由调用方（ConfigRepository）完成。
 *
 * consume 语义（对应 AGENT.md D009）：
 * - count == -1：无限，不变化；
 * - count > 1：减一；
 * - count == 1：删除；
 * - count == 0：不消费（也不应被 next 选中）。
 */
class PlanStore(
    initial: Plan,
    private val save: suspend (Plan) -> Unit,
    private val logger: AppLogger,
) : PlanRepository {

    private val lock = Any()

    /** 四个已知部门的有序计划。 */
    private val departments: MutableMap<Dept, MutableList<PlanEntry>> = LinkedHashMap()

    /** 配置里出现但 Dept 枚举不认识的部门，原样保留，避免 snapshot 丢数据。 */
    private val unknownDepartments: MutableMap<String, List<PlanEntry>> = LinkedHashMap()

    private val version = initial.version
    private val persistMutex = Mutex()

    @Volatile
    private var mutationCount: Long = 0

    init {
        for ((key, entries) in initial.departments) {
            val dept = Dept.fromKey(key)
            if (dept != null) {
                departments[dept] = entries.toMutableList()
            } else {
                unknownDepartments[key] = entries.toList()
            }
        }
        for (dept in Dept.entries) {
            departments.getOrPut(dept) { mutableListOf() }
        }
    }

    override fun next(dept: Dept): PlanEntry? = synchronized(lock) {
        departments[dept]?.firstOrNull { it.enabled && it.count != 0 }
    }

    override fun consume(dept: Dept, entry: PlanEntry) = synchronized(lock) {
        val entries = departments[dept] ?: return@synchronized
        val index = entries.indexOfFirst { it.item == entry.item }
        if (index < 0) {
            logger.w(TAG, "consume 未找到计划条目: ${dept.key}/${entry.item}")
            return@synchronized
        }
        val current = entries[index]
        when {
            current.count == -1 -> Unit // 无限计划，不消费
            current.count <= 0 -> Unit  // 0 或非法值，不消费
            current.count > 1 -> {
                entries[index] = current.copy(count = current.count - 1)
                mutationCount++
            }
            else -> {
                entries.removeAt(index)
                mutationCount++
            }
        }
    }

    override fun add(dept: Dept, entry: PlanEntry) {
        synchronized(lock) {
            departments.getOrPut(dept) { mutableListOf() }.add(entry)
            mutationCount++
        }
    }

    override fun remove(dept: Dept, item: String) = synchronized(lock) {
        val entries = departments[dept] ?: return@synchronized
        val removed = entries.removeAll { it.item == item }
        if (removed) mutationCount++
    }

    override fun snapshot(): Plan = synchronized(lock) {
        val map = LinkedHashMap<String, List<PlanEntry>>()
        for ((key, entries) in unknownDepartments) {
            map[key] = entries.toList()
        }
        for (dept in Dept.entries) {
            val entries = departments[dept].orEmpty()
            if (entries.isNotEmpty()) {
                map[dept.key] = entries.map { it.copy() }
            }
        }
        Plan(version = version, departments = map)
    }

    /**
     * 持久化当前快照。写操作在 [persistMutex] 内串行，避免两个协程同时
     * 写同一文件；保存的是调用时刻的快照。
     */
    override suspend fun persist() {
        val snap: Plan
        val countAtSnapshot: Long
        synchronized(lock) {
            snap = snapshot()
            countAtSnapshot = mutationCount
        }
        persistMutex.withLock {
            try {
                save(snap)
                logger.d(TAG, "plan 已持久化（mutation=$countAtSnapshot）")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logger.e(TAG, "plan 持久化失败", error)
                throw error
            }
        }
    }

    companion object {
        private const val TAG = "PlanStore"
    }
}
