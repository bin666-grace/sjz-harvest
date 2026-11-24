package com.sjz.harvest.vision

import com.sjz.harvest.core.config.DoneBadgeProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 黄色「!」角标的纯 JVM 测试：直接构造 ARGB 像素数组，不需要 Bitmap。
 *
 * 宽度取 1920，与 profile 基准宽度一致，角标尺寸按实机比例（约 20px）。
 */
class AlertBadgeDetectorTest {

    private val profile = DoneBadgeProfile()

    private val background = 0xFF101010.toInt()
    private val badgeColor = (0xFF shl 24) or (244 shl 16) or (207 shl 8) or 103

    private fun pixels(
        width: Int = 1920,
        height: Int = 300,
        blocks: List<Triple<IntRange, IntRange, Int>> = emptyList(),
    ): IntArray {
        val data = IntArray(width * height) { background }
        for ((xs, ys, color) in blocks) {
            for (y in ys) {
                for (x in xs) {
                    if (x in 0 until width && y in 0 until height) {
                        data[y * width + x] = color
                    }
                }
            }
        }
        return data
    }

    @Test
    fun `找到黄色角标中心`() {
        val data = pixels(
            blocks = listOf(Triple(200..219, 40..59, badgeColor)),
        )

        val badges = AlertBadgeDetector.detect(profile, data, 1920, 300)

        assertEquals(1, badges.size)
        assertEquals(209, badges[0].x)
        assertEquals(49, badges[0].y)
    }

    @Test
    fun `颜色偏差过大时不误报`() {
        val otherColor = (0xFF shl 24) or (244 shl 16) or (207 shl 8) or 200
        val data = pixels(
            blocks = listOf(Triple(200..219, 40..59, otherColor)),
        )

        assertTrue(AlertBadgeDetector.detect(profile, data, 1920, 300).isEmpty())
    }

    @Test
    fun `过小的色点视为噪点`() {
        val data = pixels(
            blocks = listOf(Triple(100..103, 100..103, badgeColor)),
        )

        assertTrue(AlertBadgeDetector.detect(profile, data, 1920, 300).isEmpty())
    }

    @Test
    fun `过大的金色区域不会当成角标`() {
        val data = pixels(
            blocks = listOf(Triple(300..499, 100..219, badgeColor)),
        )

        assertTrue(AlertBadgeDetector.detect(profile, data, 1920, 300).isEmpty())
    }

    @Test
    fun `禁用时直接返回空`() {
        val data = pixels(
            blocks = listOf(Triple(200..219, 40..59, badgeColor)),
        )

        assertTrue(
            AlertBadgeDetector.detect(profile.copy(enabled = false), data, 1920, 300).isEmpty(),
        )
    }
}
