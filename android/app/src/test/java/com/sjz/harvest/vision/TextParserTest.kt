package com.sjz.harvest.vision

import com.sjz.harvest.core.config.AuctionProfile
import com.sjz.harvest.core.config.TextProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextParserTest {

    private val textProfile = TextProfile()

    @Test
    fun `parseTimer 支持时分秒`() {
        assertEquals(3723, TextParser.parseTimer("剩余 01:02:03", textProfile))
        assertEquals(359999, TextParser.parseTimer("99:59:59", textProfile))
    }

    @Test
    fun `parseTimer 支持分秒`() {
        assertEquals(754, TextParser.parseTimer("12:34", textProfile))
    }

    @Test
    fun `parseTimer 容忍全角数字和冒号`() {
        assertEquals(3723, TextParser.parseTimer("０１：０２：０３", textProfile))
    }

    @Test
    fun `parseTimer 无计时器返回 null`() {
        assertNull(TextParser.parseTimer("空闲中", textProfile))
        assertNull(TextParser.parseTimer("", textProfile))
    }

    @Test
    fun `parsePrice 去逗号并取最长数字串`() {
        assertEquals(12345, TextParser.parsePrice("单价 12,345 哈弗币", AuctionProfile()))
    }

    @Test
    fun `parsePrice 忽略短数字`() {
        assertNull(TextParser.parsePrice("共 9 个", AuctionProfile()))
    }

    @Test
    fun `parsePrice 支持自定义正则`() {
        val profile = AuctionProfile(priceRegex = "\\d+")
        assertEquals(8888, TextParser.parsePrice("最低价 8888", profile))
    }

    @Test
    fun `normalize 去空白标点并应用 OCR 纠错`() {
        val profile = TextProfile(ocrReplacements = mapOf("番" to "盔"))
        assertEquals("M4A1突击步枪", TextParser.normalize("M4A1 突击-步枪！", profile))
        assertEquals("头盔", TextParser.normalize("头 番", profile))
    }

    @Test
    fun `similarity 相同与完全不同`() {
        assertEquals(100, TextParser.similarity("技术中心", "技术中心"))
        assertEquals(0, TextParser.similarity("", "abc"))
        assertEquals(0, TextParser.similarity("abc", "xyz"))
    }

    @Test
    fun `similarity 使用 Levenshtein 比率`() {
        // kitten -> sitting，距离 3，最长 7，相似度 57。
        assertEquals(57, TextParser.similarity("kitten", "sitting"))
        // 技术中心 vs 技术中芯，距离 1，最长 4，相似度 75。
        assertEquals(75, TextParser.similarity("技术中心", "技术中芯"))
    }

    @Test
    fun `bestMatch 精确与阈值`() {
        val candidates = listOf("9x39mm BP", "9x39mm SP6", "7.62x39mm AP")
        val exact = TextParser.bestMatch("9x39mm BP", candidates, 96)
        assertNotNull(exact)
        assertEquals("9x39mm BP", exact!!.name)
        assertEquals(100, exact.score)

        val fuzzy = TextParser.bestMatch("技术中芯", listOf("技术中心"), 60)
        assertNotNull(fuzzy)
        assertEquals(75, fuzzy!!.score)

        assertNull(TextParser.bestMatch("技术中芯", listOf("技术中心"), 90))
    }

    @Test
    fun `bestMatch 包含关系得高分`() {
        val match = TextParser.bestMatch("9x39mm BP 可制造", listOf("9x39mm BP"), 95)
        assertNotNull(match)
        assertEquals(95, match!!.score)
        // work 的阈值 96 会拒绝「包含但不等」的匹配，避免 BT 命中 BT ST。
        assertNull(TextParser.bestMatch("9x39mm BP 可制造", listOf("9x39mm BP"), 96))
    }

    @Test
    fun `isIdle 与 isDone 支持自定义关键词`() {
        assertTrue(TextParser.isIdle("当前空闲中"))
        assertTrue(TextParser.isIdle("可以开工", listOf("开工")))
        assertFalse(TextParser.isIdle("制造中"))

        assertTrue(TextParser.isDone("点击收取"))
        assertTrue(TextParser.isDone("好了", listOf("好了")))
        assertFalse(TextParser.isDone("已完成", listOf("好了")))
        assertFalse(TextParser.isDone("制造中"))
    }
}
