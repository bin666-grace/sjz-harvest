package com.sjz.harvest.vision

import com.sjz.harvest.core.config.UiProfile
import com.sjz.harvest.core.model.Dept
import com.sjz.harvest.core.model.DeptState
import com.sjz.harvest.core.model.OcrResult
import com.sjz.harvest.core.model.PointI
import com.sjz.harvest.core.model.RectI
import com.sjz.harvest.core.model.TextElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 总览状态解析的纯 JVM 测试。
 *
 * 坐标按实机 2712×1220 截图（baseWidth=1920）标定：
 * 四张卡片标题中心约为 tech(529,227) work(917,227)
 * medical(529,741) armor(917,741)，状态文字在卡片中部。
 */
class DashboardStatusResolverTest {

    private val profile = UiProfile()
    private val screenWidth = 2712
    private val screenHeight = 1220

    private fun element(text: String, cx: Int, cy: Int, w: Int = 160, h: Int = 40) =
        TextElement(text, RectI(cx - w / 2, cy - h / 2, w, h))

    private fun ocr(vararg elements: TextElement) =
        OcrResult(elements.toList(), elements.joinToString("\n") { it.text })

    private fun labels() = arrayOf(
        element("技术中心", 529, 227),
        element("工作台", 917, 227),
        element("制药台", 529, 741),
        element("防具台", 917, 741),
    )

    @Test
    fun `四部门状态按卡片就近归属`() {
        val result = DashboardStatusResolver.resolve(
            ocr(
                *labels(),
                element("空闲中", 529, 449),
                element("07:00:01", 917, 449),
                element("空闲中", 529, 979),
                element("已完成", 917, 979),
            ),
            profile,
            screenWidth,
            screenHeight,
        )

        assertEquals(DeptState.IDLE, result.statuses.getValue(Dept.TECH).state)
        assertEquals(DeptState.CRAFTING, result.statuses.getValue(Dept.WORK).state)
        assertEquals(25201, result.statuses.getValue(Dept.WORK).remainingSec)
        assertEquals(DeptState.IDLE, result.statuses.getValue(Dept.MEDICAL).state)
        assertEquals(DeptState.DONE, result.statuses.getValue(Dept.ARMOR).state)
        assertTrue(result.inferredDepts.isEmpty())
    }

    /**
     * 实机 2712×1220 的真实包围盒：标题在卡片顶部，状态文字在卡片中部，
     * 上排卡片的“空闲中”在垂直方向上离下排标题更近。
     * 单纯取“最近锚点”会把上排状态串给下排部门（2026-11-20 实机复现）。
     */
    @Test
    fun `上排卡片状态文字不会被下排标题抢走`() {
        val result = DashboardStatusResolver.resolve(
            OcrResult(
                elements = listOf(
                    TextElement("技术中心", RectI(440, 232, 142, 39)),
                    TextElement("工作台", RectI(693, 227, 291, 67)),
                    TextElement("制药台", RectI(440, 690, 106, 39)),
                    TextElement("防具台", RectI(877, 693, 105, 35)),
                    TextElement("空闲中", RectI(500, 471, 74, 28)),
                    TextElement("空闲中", RectI(921, 470, 99, 32)),
                    TextElement("空闲中", RectI(501, 931, 76, 25)),
                    TextElement("空闲中", RectI(936, 931, 76, 25)),
                ),
                fullText = "",
            ),
            profile,
            screenWidth,
            screenHeight,
        )

        Dept.entries.forEach { dept ->
            assertEquals(
                "部门 ${dept.key} 的空闲状态归属错误",
                DeptState.IDLE,
                result.statuses.getValue(dept).state,
            )
        }
    }

    @Test
    fun `工作台标题被 OCR 识花时仍能归属上排状态`() {
        val result = DashboardStatusResolver.resolve(
            OcrResult(
                elements = listOf(
                    TextElement("技术中心", RectI(440, 232, 142, 39)),
                    // ML Kit 实机识别结果：工作台图标干扰导致文字残缺。
                    TextElement("a父I作台", RectI(693, 227, 291, 67)),
                    TextElement("制药台", RectI(440, 690, 106, 39)),
                    TextElement("防具台", RectI(877, 693, 105, 35)),
                    TextElement("空闲中", RectI(500, 471, 74, 28)),
                    TextElement("空闲中", RectI(921, 470, 99, 32)),
                    TextElement("空闲中", RectI(501, 931, 76, 25)),
                    TextElement("空闲中", RectI(936, 931, 76, 25)),
                ),
                fullText = "",
            ),
            profile,
            screenWidth,
            screenHeight,
        )

        assertTrue(result.inferredDepts.contains(Dept.WORK))
        assertEquals(DeptState.IDLE, result.statuses.getValue(Dept.TECH).state)
        assertEquals(DeptState.IDLE, result.statuses.getValue(Dept.WORK).state)
        assertEquals(DeptState.IDLE, result.statuses.getValue(Dept.MEDICAL).state)
        assertEquals(DeptState.IDLE, result.statuses.getValue(Dept.ARMOR).state)
    }

    @Test
    fun `工作台标题漏识别时按 2x2 网格推断锚点`() {
        val result = DashboardStatusResolver.resolve(
            ocr(
                element("技术中心", 529, 227),
                element("制药台", 529, 741),
                element("防具台", 917, 741),
                element("空闲中", 529, 449),
                // 工作台卡片处于制造中，但标题没被 OCR 识别到。
                element("9x39mm BP", 917, 380),
                element("07:00:01", 917, 440),
            ),
            profile,
            screenWidth,
            screenHeight,
        )

        assertTrue(result.inferredDepts.contains(Dept.WORK))
        // 网格推断：列取防具台 x=917，行取技术中心 y=227。
        assertEquals(917, result.anchors.getValue(Dept.WORK).point.x)
        assertEquals(227, result.anchors.getValue(Dept.WORK).point.y)
        assertEquals(DeptState.CRAFTING, result.statuses.getValue(Dept.WORK).state)
        assertEquals(25201, result.statuses.getValue(Dept.WORK).remainingSec)
        assertEquals(DeptState.IDLE, result.statuses.getValue(Dept.TECH).state)
    }

    @Test
    fun `无状态文字默认 UNKNOWN，不误判为待收取`() {
        val result = DashboardStatusResolver.resolve(
            ocr(*labels()),
            profile,
            screenWidth,
            screenHeight,
        )

        Dept.entries.forEach { dept ->
            assertEquals(
                "部门 ${dept.key} 不应在缺乏状态文字时判为 DONE",
                DeptState.UNKNOWN,
                result.statuses.getValue(dept).state,
            )
        }
    }

    @Test
    fun `配置允许时无状态文字可按完成处理`() {
        val legacy = profile.copy(
            dashboard = profile.dashboard.copy(doneWhenNoStatus = true),
        )
        val result = DashboardStatusResolver.resolve(
            ocr(*labels()),
            legacy,
            screenWidth,
            screenHeight,
        )

        Dept.entries.forEach { dept ->
            assertEquals(DeptState.DONE, result.statuses.getValue(dept).state)
            assertEquals(0.6f, result.statuses.getValue(dept).confidence, 0.0001f)
        }
    }

    @Test
    fun `右侧面板文字不会串到部门状态`() {
        val result = DashboardStatusResolver.resolve(
            ocr(
                *labels(),
                element("空闲中", 529, 449),
                // 右栏列表远离卡片，不能被当作任何部门的状态。
                element("收取", 2300, 320),
            ),
            profile,
            screenWidth,
            screenHeight,
        )

        assertEquals(DeptState.IDLE, result.statuses.getValue(Dept.TECH).state)
        assertFalse(result.statuses.values.any { it.state == DeptState.DONE })
    }

    @Test
    fun `制造中关键词无计时器时仍判制造中`() {
        val result = DashboardStatusResolver.resolve(
            ocr(
                *labels(),
                element("制造中", 529, 449),
            ),
            profile,
            screenWidth,
            screenHeight,
        )

        val tech = result.statuses.getValue(Dept.TECH)
        assertEquals(DeptState.CRAFTING, tech.state)
        assertNull(tech.remainingSec)
        assertEquals(0.8f, tech.confidence, 0.0001f)
    }

    @Test
    fun `全角计时器可解析`() {
        val result = DashboardStatusResolver.resolve(
            ocr(
                *labels(),
                element("０７：００：０１", 917, 449),
            ),
            profile,
            screenWidth,
            screenHeight,
        )

        assertEquals(DeptState.CRAFTING, result.statuses.getValue(Dept.WORK).state)
        assertEquals(25201, result.statuses.getValue(Dept.WORK).remainingSec)
    }

    @Test
    fun `一个标题都没有时使用归一化兜底锚点`() {
        val anchors = DashboardStatusResolver.inferAnchors(
            labels = emptyMap(),
            profile = profile,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )

        assertEquals(4, anchors.size)
        assertEquals((0.195 * screenWidth).toInt(), anchors.getValue(Dept.TECH).point.x)
        assertEquals((0.186 * screenHeight).toInt(), anchors.getValue(Dept.TECH).point.y)
        assertEquals((0.338 * screenWidth).toInt(), anchors.getValue(Dept.WORK).point.x)
        assertTrue(anchors.values.none { it.fromLabel })
    }

    /**
     * 实机总览的制造完成卡片没有「完成/收取」文字，只有卡片右上角黄色「!」。
     * 角标相对标题中心约为 (+209, +95)（2712×1220 实测）。
     */
    @Test
    fun `卡片右上角黄色角标判定为已完成`() {
        val result = DashboardStatusResolver.resolve(
            ocr(*labels()),
            profile,
            screenWidth,
            screenHeight,
            alertBadges = listOf(PointI(738, 322)),
        )

        assertEquals(DeptState.DONE, result.statuses.getValue(Dept.TECH).state)
        assertEquals(DeptState.UNKNOWN, result.statuses.getValue(Dept.WORK).state)
        assertEquals(DeptState.UNKNOWN, result.statuses.getValue(Dept.MEDICAL).state)
        assertEquals(DeptState.UNKNOWN, result.statuses.getValue(Dept.ARMOR).state)
    }

    @Test
    fun `右侧设施列表的角标不会串到部门`() {
        val result = DashboardStatusResolver.resolve(
            ocr(*labels()),
            profile,
            screenWidth,
            screenHeight,
            // 实机右侧「潜水中心/洗消舱」的黄色角标位置。
            alertBadges = listOf(PointI(1595, 741), PointI(1595, 1002)),
        )

        Dept.entries.forEach { dept ->
            assertEquals(
                "部门 ${dept.key} 不应被右侧设施角标判为已完成",
                DeptState.UNKNOWN,
                result.statuses.getValue(dept).state,
            )
        }
    }

    @Test
    fun `左侧升级栏目角标不会判成技术中心已完成`() {
        val result = DashboardStatusResolver.resolve(
            ocr(*labels()),
            profile,
            screenWidth,
            screenHeight,
            // 实机左侧「升级制造」的黄色「!」在标题左边。
            alertBadges = listOf(PointI(248, 126)),
        )

        assertEquals(DeptState.UNKNOWN, result.statuses.getValue(Dept.TECH).state)
    }
}
