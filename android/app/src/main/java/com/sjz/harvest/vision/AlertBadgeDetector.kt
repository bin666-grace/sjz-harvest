package com.sjz.harvest.vision

import com.sjz.harvest.core.config.DoneBadgeProfile
import com.sjz.harvest.core.model.PointI
import kotlin.math.abs

/**
 * 按颜色寻找总览卡片右上角的「!」角标（纯 Kotlin，可在 JVM 单测中运行）。
 *
 * 输入是 ARGB 像素数组（[android.graphics.Bitmap.getPixels] 的结果），
 * 不依赖 Android API。算法：
 * 1. 按 [DoneBadgeProfile.sampleStepPx] 采样，颜色落在角标色 ±tolerance 内记为命中；
 * 2. 对命中点做 8 邻域连通分量（邻居距离 = 采样步长）；
 * 3. 丢弃外接框过大的色块（金色图片/描边）和命中点过少的噪点；
 * 4. 返回每个角标色块的中心（实际屏幕像素）。
 *
 * 角标归属到哪个部门由 [DashboardStatusResolver] 按标题锚点位置判断，
 * 本类只负责「屏幕上哪里有角标」。
 */
object AlertBadgeDetector {

    private const val BASE_WIDTH = 1920f

    fun detect(
        profile: DoneBadgeProfile,
        pixels: IntArray,
        width: Int,
        height: Int,
    ): List<PointI> {
        if (!profile.enabled) return emptyList()
        if (width <= 0 || height <= 0) return emptyList()
        require(pixels.size >= width * height) {
            "pixels 长度 ${pixels.size} 小于 ${width}x$height"
        }

        val step = profile.sampleStepPx.coerceAtLeast(1)
        val scale = width / BASE_WIDTH
        val maxClusterSize = (profile.maxClusterSizePx * scale)
            .toInt()
            .coerceAtLeast(step * 4)

        val hits = HashSet<Int>()
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                if (matches(pixels[y * width + x], profile)) {
                    hits.add(y * width + x)
                }
                x += step
            }
            y += step
        }
        if (hits.isEmpty()) return emptyList()

        val visited = HashSet<Int>()
        val centers = ArrayList<PointI>()
        val neighbors = intArrayOf(-step, 0, step)

        for (start in hits) {
            if (!visited.add(start)) continue
            val stack = ArrayDeque<Int>()
            stack.addLast(start)
            var count = 0
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE

            while (stack.isNotEmpty()) {
                val key = stack.removeLast()
                val x = key % width
                val yIndex = key / width
                count++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (yIndex < minY) minY = yIndex
                if (yIndex > maxY) maxY = yIndex
                for (dy in neighbors) {
                    for (dx in neighbors) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = yIndex + dy
                        if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                        val next = ny * width + nx
                        if (next in hits && visited.add(next)) {
                            stack.addLast(next)
                        }
                    }
                }
            }

            val clusterWidth = maxX - minX + step
            val clusterHeight = maxY - minY + step
            if (count < profile.minSampleHits) continue
            if (clusterWidth > maxClusterSize || clusterHeight > maxClusterSize) continue

            centers.add(PointI((minX + maxX) / 2, (minY + maxY) / 2))
        }
        return centers.sortedWith(compareBy({ it.y }, { it.x }))
    }

    private fun matches(color: Int, profile: DoneBadgeProfile): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return abs(r - profile.r) <= profile.tolerance &&
            abs(g - profile.g) <= profile.tolerance &&
            abs(b - profile.b) <= profile.tolerance
    }
}
