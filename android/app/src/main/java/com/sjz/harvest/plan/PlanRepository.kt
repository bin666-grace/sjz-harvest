package com.sjz.harvest.plan

import com.sjz.harvest.core.config.Plan
import com.sjz.harvest.core.config.PlanEntry
import com.sjz.harvest.core.model.Dept

interface PlanRepository {
    /** 取该部门下一个启用的计划；跳过 count == 0 的条目。 */
    fun next(dept: Dept): PlanEntry?

    /** 制造成功后消费一次：-1 不变，1 删除，>1 减一。 */
    fun consume(dept: Dept, entry: PlanEntry)

    fun add(dept: Dept, entry: PlanEntry)

    fun remove(dept: Dept, item: String)

    fun snapshot(): Plan

    suspend fun persist()
}
