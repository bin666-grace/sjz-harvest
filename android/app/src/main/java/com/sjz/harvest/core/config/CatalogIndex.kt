package com.sjz.harvest.core.config

import com.sjz.harvest.core.model.Dept

data class CatalogEntry(
    val dept: Dept,
    val category: String,
    val name: String,
)

/**
 * catalog 的查询索引。纯 Kotlin，可在 JVM 单测中直接使用。
 */
class CatalogIndex(catalog: Catalog) {

    private val byDept: Map<Dept, List<CatalogEntry>> = buildMap {
        for ((deptKey, department) in catalog.departments) {
            val dept = Dept.fromKey(deptKey) ?: continue
            val entries = buildList {
                for ((category, items) in department.categories) {
                    for (item in items) add(CatalogEntry(dept, category, item))
                }
            }
            put(dept, entries)
        }
    }

    fun entries(dept: Dept): List<CatalogEntry> = byDept[dept].orEmpty()

    fun candidates(dept: Dept): List<String> = entries(dept).map { it.name }

    fun resolve(dept: Dept, item: String): CatalogEntry? =
        entries(dept).firstOrNull { it.name == item }

    fun categoryOf(dept: Dept, item: String): String? = resolve(dept, item)?.category

    fun categories(dept: Dept): List<String> =
        entries(dept).map { it.category }.distinct()

    fun itemsByCategory(dept: Dept): Map<String, List<String>> =
        entries(dept).groupBy({ it.category }, { it.name })
}
