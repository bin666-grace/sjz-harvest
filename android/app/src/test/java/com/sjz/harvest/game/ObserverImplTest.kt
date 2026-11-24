package com.sjz.harvest.game

import com.sjz.harvest.core.config.UiProfile
import com.sjz.harvest.core.logging.AppLogger
import com.sjz.harvest.core.model.Dept
import com.sjz.harvest.core.model.DeptState
import com.sjz.harvest.core.model.OcrResult
import com.sjz.harvest.core.model.Page
import com.sjz.harvest.core.model.RectI
import com.sjz.harvest.core.model.TextElement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ObserverImpl] 的 JVM 单测：用脚本化 [ScreenOcr] 驱动判页与状态解析。
 */
class ObserverImplTest {

    private class OneShotScreenOcr(private val snapshot: OcrSnapshot) : ScreenOcr {
        var calls = 0

        override suspend fun capture(hint: String): OcrSnapshot {
            calls++
            return snapshot
        }
    }

    private class RecordingLogger : AppLogger {
        val lines = mutableListOf<String>()

        override fun d(tag: String, message: String) {
            lines.add(message)
        }

        override fun i(tag: String, message: String) {
            lines.add(message)
        }

        override fun w(tag: String, message: String) {
            lines.add(message)
        }

        override fun e(tag: String, message: String, throwable: Throwable?) {
            lines.add(message)
        }
    }

    private fun el(text: String, x: Int, y: Int, w: Int = 120, h: Int = 40): TextElement =
        TextElement(text, RectI(x, y, w, h))

    private fun snap(vararg elements: TextElement): OcrSnapshot = OcrSnapshot(
        result = OcrResult(elements.toList(), elements.joinToString(" ") { it.text }),
        width = 1920,
        height = 1080,
    )

    @Test
    fun `OCR 无文字时返回未知页面和四个未知状态`() = runTest {
        val ocr = OneShotScreenOcr(snap())

        val observation = ObserverImpl(ocr, UiProfile(), RecordingLogger()).observe()

        assertEquals(1, ocr.calls)
        assertEquals(Page.UNKNOWN, observation.page)
        assertEquals(4, observation.statuses.size)
        assertTrue(observation.statuses.values.all { it.state == DeptState.UNKNOWN })
    }

    @Test
    fun `总览页解析四个部门的空闲计时与完成状态`() = runTest {
        val ocr = OneShotScreenOcr(
            snap(
                el("技术中心", 250, 180),
                el("工作台", 650, 180),
                el("制药台", 250, 650),
                el("防具台", 650, 650),
                el("空闲中", 250, 240),
                el("01:02:03", 650, 240),
                el("领取", 250, 710),
            ),
        )

        val observation = ObserverImpl(ocr, UiProfile(), RecordingLogger()).observe()

        assertEquals(Page.DASHBOARD, observation.page)
        assertEquals(4, observation.statuses.size)
        assertEquals(DeptState.IDLE, observation.statuses[Dept.TECH]?.state)
        assertEquals(DeptState.CRAFTING, observation.statuses[Dept.WORK]?.state)
        assertEquals(3723, observation.statuses[Dept.WORK]?.remainingSec)
        assertEquals(DeptState.DONE, observation.statuses[Dept.MEDICAL]?.state)
        // 没有状态文字时绝不当成 DONE。
        assertEquals(DeptState.UNKNOWN, observation.statuses[Dept.ARMOR]?.state)
    }

    @Test
    fun `只有一个部门标题且旁边有状态时仍判为总览`() = runTest {
        val ocr = OneShotScreenOcr(
            snap(
                el("技术中心", 250, 180),
                el("空闲中", 250, 240),
            ),
        )

        val observation = ObserverImpl(ocr, UiProfile(), RecordingLogger()).observe()

        assertEquals(Page.DASHBOARD, observation.page)
        // 其余三个部门靠网格/配置锚点推断，但仍输出 UNKNOWN 而不是缺失。
        assertEquals(4, observation.statuses.size)
        assertEquals(DeptState.IDLE, observation.statuses[Dept.TECH]?.state)
    }

    @Test
    fun `没有部门标题的非总览页不解析状态`() = runTest {
        val ocr = OneShotScreenOcr(
            snap(
                el("物品一", 100, 300),
                el("物品二", 100, 360),
                el("物品三", 100, 420),
                el("物品四", 100, 480),
                el("物品五", 100, 540),
                el("物品六", 100, 600),
            ),
        )

        val observation = ObserverImpl(ocr, UiProfile(), RecordingLogger()).observe()

        assertEquals(Page.DEPARTMENT, observation.page)
        assertEquals(4, observation.statuses.size)
        assertTrue(observation.statuses.values.all { it.state == DeptState.UNKNOWN })
        assertTrue(observation.elements.size == 6)
    }

    @Test
    fun `开启 dumpOcrText 时把识别文字写入日志`() = runTest {
        val logger = RecordingLogger()
        val ocr = OneShotScreenOcr(
            snap(
                el("技术中心", 250, 180),
                el("工作台", 650, 180),
                el("制药台", 250, 650),
                el("防具台", 650, 650),
            ),
        )

        ObserverImpl(ocr, UiProfile(), logger, dumpOcrText = true).observe()

        assertTrue(logger.lines.any { it.startsWith("OCR[0/") && it.contains("技术中心") })
    }
}
