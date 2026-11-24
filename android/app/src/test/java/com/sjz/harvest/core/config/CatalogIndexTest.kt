package com.sjz.harvest.core.config

import com.sjz.harvest.core.model.Dept
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogIndexTest {

    private val catalog = Catalog(
        departments = mapOf(
            "tech" to DepartmentCatalog(
                categories = mapOf(
                    "枪" to listOf("M4A1突击步枪", "AKM突击步枪"),
                    "瞄具" to listOf("3/7可调倍率狙击镜"),
                ),
            ),
            "work" to DepartmentCatalog(
                categories = mapOf("level_6" to listOf("9x39mm BP")),
            ),
            "未知部门" to DepartmentCatalog(
                categories = mapOf("x" to listOf("不应出现")),
            ),
        ),
    )

    private val index = CatalogIndex(catalog)

    @Test
    fun `candidates 返回部门全部物品`() {
        assertEquals(
            listOf("M4A1突击步枪", "AKM突击步枪", "3/7可调倍率狙击镜"),
            index.candidates(Dept.TECH),
        )
        assertEquals(emptyList<String>(), index.candidates(Dept.ARMOR))
    }

    @Test
    fun `resolve 精确查找`() {
        val entry = index.resolve(Dept.TECH, "M4A1突击步枪")
        assertEquals(Dept.TECH, entry?.dept)
        assertEquals("枪", entry?.category)
        assertEquals("M4A1突击步枪", entry?.name)
        assertNull(index.resolve(Dept.TECH, "不存在的枪"))
    }

    @Test
    fun `categoryOf 与 categories 去重`() {
        assertEquals("瞄具", index.categoryOf(Dept.TECH, "3/7可调倍率狙击镜"))
        assertEquals(listOf("枪", "瞄具"), index.categories(Dept.TECH))
    }

    @Test
    fun `itemsByCategory 分组`() {
        val grouped = index.itemsByCategory(Dept.TECH)
        assertEquals(listOf("M4A1突击步枪", "AKM突击步枪"), grouped["枪"])
        assertEquals(listOf("3/7可调倍率狙击镜"), grouped["瞄具"])
    }

    @Test
    fun `未知部门 key 被忽略`() {
        assertTrue(index.entries(Dept.MEDICAL).isEmpty())
        assertTrue(index.entries(Dept.ARMOR).isEmpty())
    }
}
