package com.sjz.harvest.vision

import com.sjz.harvest.core.config.UiProfile
import com.sjz.harvest.core.model.Dept
import com.sjz.harvest.core.model.OcrResult
import com.sjz.harvest.core.model.Page
import com.sjz.harvest.core.model.RectI
import com.sjz.harvest.core.model.TextElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageDetectorTest {

    private val profile = UiProfile()

    private fun ocr(vararg texts: String): OcrResult {
        val elements = texts.mapIndexed { index, text ->
            TextElement(text, RectI(100, 100 + index * 50, 400, 40))
        }
        return OcrResult(elements, texts.joinToString("\n"))
    }

    private fun ocr(vararg elements: TextElement): OcrResult =
        OcrResult(elements.toList(), elements.joinToString("\n") { it.text })

    @Test
    fun `开始制造优先判为制造弹窗`() {
        // 即使同时存在部门标题，也先按更强的制造弹窗锚点判定。
        assertEquals(Page.CRAFT_DIALOG, PageDetector.detect(ocr("开始制造", "技术中心"), profile))
    }

    @Test
    fun `所需材料判为制造弹窗`() {
        assertEquals(Page.CRAFT_DIALOG, PageDetector.detect(ocr("所需材料", "数量 3"), profile))
    }

    @Test
    fun `材料二字也按弹窗处理（契约优先级）`() {
        assertEquals(Page.CRAFT_DIALOG, PageDetector.detect(ocr("材料"), profile))
    }

    @Test
    fun `交易行判为拍卖页`() {
        assertEquals(Page.AUCTION, PageDetector.detect(ocr("交易行", "最低价"), profile))
    }

    @Test
    fun `主界面底部交易行入口不判为拍卖页`() {
        // 手游主界面底部导航有「交易行」，但真正的交易行页才有「最低价」。
        val lobby = ocr("仓库", "部门", "交易行", "特勤处", "改枪台")
        assertFalse(PageDetector.detect(lobby, profile) == Page.AUCTION)
    }

    @Test
    fun `至少两个部门标题判为总览`() {
        // 单个标题可能是部门详情页，不能直接判总览。
        assertEquals(Page.UNKNOWN, PageDetector.detect(ocr("技术中心"), profile))
        assertEquals(
            Page.DASHBOARD,
            PageDetector.detect(ocr("技术中心", "工作台", "制药台", "防具台"), profile),
        )
    }

    @Test
    fun `单个部门标题加状态文字判为总览`() {
        assertEquals(
            Page.DASHBOARD,
            PageDetector.detect(ocr("技术中心", "空闲中"), profile),
        )
    }

    @Test
    fun `轻微 OCR 错字仍能识别部门标题`() {
        val result = ocr("技术中芯")
        // 单标题仍需状态文字才能判总览，但标题匹配本身必须能纠错。
        assertEquals(Page.UNKNOWN, PageDetector.detect(result, profile))
        val labels = PageDetector.findDepartmentLabels(result, profile)
        assertEquals(Dept.TECH, labels.keys.first())
    }

    @Test
    fun `无锚点但文字足够判为部门列表`() {
        assertEquals(
            Page.DEPARTMENT,
            PageDetector.detect(
                ocr("M4A1突击步枪", "可制造", "数量 3", "重量", "等级 4", "详情"),
                profile,
            ),
        )
    }

    @Test
    fun `文字不足且无锚点判为未知`() {
        assertEquals(Page.UNKNOWN, PageDetector.detect(ocr("随机文字"), profile))
        assertEquals(Page.UNKNOWN, PageDetector.detect(OcrResult.EMPTY, profile))
    }

    @Test
    fun `单独制造二字不当作制造弹窗`() {
        // 「制造」常见于列表页签，必须有更具体锚点才判弹窗。
        assertEquals(Page.UNKNOWN, PageDetector.detect(ocr("制造"), profile))
    }

    @Test
    fun `findDepartmentLabels 返回四个部门`() {
        val result = ocr("技术中心", "工作台", "制药台", "防具台")
        val labels = PageDetector.findDepartmentLabels(result, profile)
        assertEquals(4, labels.size)
        assertTrue(labels.containsKey(Dept.TECH))
        assertTrue(labels.containsKey(Dept.WORK))
        assertTrue(labels.containsKey(Dept.MEDICAL))
        assertTrue(labels.containsKey(Dept.ARMOR))
    }

    @Test
    fun `isDepartment 支持模糊匹配`() {
        assertTrue(PageDetector.isDepartment("技术中心", profile))
        assertTrue(PageDetector.isDepartment("技术中芯", profile))
        assertFalse(PageDetector.isDepartment("完全无关", profile))
        assertEquals(Dept.TECH, PageDetector.departmentOf("技术中心", profile))
        assertNull(PageDetector.departmentOf("完全无关", profile))
    }

    @Test
    fun `findText 查找按钮文本`() {
        val result = ocr("收取", "开始制造")
        val element = PageDetector.findText(result, "开始制造")
        assertNotNull(element)
        assertEquals("开始制造", element!!.text)
    }

    @Test
    fun `findNearby 只返回半径内元素并按距离排序`() {
        val label = TextElement("技术中心", RectI(100, 100, 200, 40))
        val near = TextElement("空闲中", RectI(100, 160, 100, 30))
        val far = TextElement("防具台", RectI(1000, 900, 100, 30))
        val result = ocr(label, near, far)

        val found = PageDetector.findNearby(result, label, 200)
        assertEquals(listOf("空闲中"), found.map { it.text })
        assertEquals(20, PageDetector.rectDistance(label.rect, near.rect))
        assertTrue(PageDetector.rectDistance(label.rect, far.rect) > 200)
    }
}
