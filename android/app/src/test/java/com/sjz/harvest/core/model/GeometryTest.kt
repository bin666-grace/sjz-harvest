package com.sjz.harvest.core.model

import com.sjz.harvest.core.config.UiProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {

    private val profile = UiProfile(baseWidth = 1920, baseHeight = 1080)

    @Test
    fun `宽度因子按屏幕宽度等比缩放`() {
        assertEquals(1.0, Geometry.widthFactor(profile, 1920), 0.0001)
        assertEquals(4.0 / 3.0, Geometry.widthFactor(profile, 2560), 0.0001)
    }

    @Test
    fun `scalePoint 等比缩放`() {
        val point = Geometry.scalePoint(PointI(100, 200), profile, 2560)
        assertEquals(133, point.x)
        assertEquals(266, point.y)
    }

    @Test
    fun `scaleRect 等比缩放`() {
        val rect = Geometry.scaleRect(RectI(100, 200, 300, 400), profile, 3840)
        assertEquals(200, rect.x)
        assertEquals(400, rect.y)
        assertEquals(600, rect.w)
        assertEquals(800, rect.h)
    }

    @Test
    fun `scaleNormalized 按实际宽高分别缩放`() {
        // 2712×1220（非 16:9）不能按宽度等比缩放 y，否则 y 会偏大 25%。
        assertEquals(
            PointI(1003, 1076),
            Geometry.scaleNormalized(0.37f, 0.882f, 2712, 1220),
        )
        assertEquals(
            PointI(326, 258),
            Geometry.scaleNormalized(0.17f, 0.239f, 1920, 1080),
        )
    }

    @Test
    fun `resolveRect 空值返回全屏`() {
        assertEquals(RectI(0, 0, 2560, 1440), Geometry.resolveRect(null, profile, 2560, 1440))
    }

    @Test
    fun `resolveRect 非空按宽度缩放`() {
        assertEquals(
            RectI(200, 400, 600, 800),
            Geometry.resolveRect(RectI(100, 200, 300, 400), profile, 3840, 2160),
        )
    }

    @Test
    fun `clamp 处理负坐标与越界`() {
        assertEquals(
            RectI(0, 0, 1920, 1080),
            Geometry.clamp(RectI(-50, -50, 3000, 3000), 1920, 1080),
        )
        assertEquals(
            RectI(1900, 1000, 20, 80),
            Geometry.clamp(RectI(1900, 1000, 100, 100), 1920, 1080),
        )
    }

    @Test
    fun `isSupportedWidth 允许两像素误差`() {
        assertTrue(Geometry.isSupportedWidth(profile, 1920))
        assertTrue(Geometry.isSupportedWidth(profile, 1922))
        assertFalse(Geometry.isSupportedWidth(profile, 2000))
    }

    @Test
    fun `RectI center 与 contains`() {
        val rect = RectI(100, 200, 300, 400)
        assertEquals(PointI(250, 400), rect.center)
        assertTrue(rect.contains(PointI(100, 200)))
        assertFalse(rect.contains(PointI(400, 600)))
    }
}
