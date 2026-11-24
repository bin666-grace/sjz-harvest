package com.sjz.harvest.vision

import com.sjz.harvest.core.config.FloatPoint
import com.sjz.harvest.core.config.UiProfile
import com.sjz.harvest.core.model.Dept
import com.sjz.harvest.core.model.DeptState
import com.sjz.harvest.core.model.DeptStatus
import com.sjz.harvest.core.model.Geometry
import com.sjz.harvest.core.model.OcrResult
import com.sjz.harvest.core.model.PointI
import com.sjz.harvest.core.model.TextElement

/**
 * 特勤处总览的四部门状态解析（纯 Kotlin，可在 JVM 单测中运行）。
 *
 * 设计要点：
 * 1. 锚点优先用 OCR 识别到的四个部门标题中心；
 * 2. 某个标题漏识别时，先用 2×2 卡片网格从同一行/列的已知标题推断位置，
 *    不依赖分辨率或宽高比；仍推不出才用配置里的归一化兜底点；
 * 3. 每段文字只归给最近的部门锚点，避免卡片之间串状态；
 * 4. 「无状态文字」默认不当作 DONE（见 UiProfile.dashboard.doneWhenNoStatus），
 *    宁可返回 UNKNOWN 让上层停止，也不误判收菜。
 */
object DashboardStatusResolver {

    data class Anchor(val point: PointI, val fromLabel: Boolean)

    data class Resolution(
        val statuses: Map<Dept, DeptStatus>,
        val anchors: Map<Dept, Anchor>,
    ) {
        /** 未直接识别到标题、靠网格/配置推断出来的部门。 */
        val inferredDepts: Set<Dept>
            get() = anchors.filterValues { !it.fromLabel }.keys
    }

    fun resolve(
        ocr: OcrResult,
        profile: UiProfile,
        screenWidth: Int,
        screenHeight: Int,
        /** 总览卡片「可收取」黄色角标中心（实际屏幕像素）。 */
        alertBadges: List<PointI> = emptyList(),
    ): Resolution {
        val labels = PageDetector.findDepartmentLabels(ocr, profile)
        val anchors = inferAnchors(labels, profile, screenWidth, screenHeight)
        val radiusPx = statusRadius(profile, screenWidth)
        val labelElements = labels.values.toList()
        val doneByBadge = assignBadges(alertBadges, anchors, profile, screenWidth)

        // 每段文字只归属一个部门；标题本身不参与状态判断。
        val assigned = ocr.elements.mapNotNull { element ->
            if (labelElements.any { PageDetector.sameElement(it, element) }) {
                null
            } else {
                deptForElement(element, anchors)?.let { nearest -> element to nearest }
            }
        }

        val statuses = LinkedHashMap<Dept, DeptStatus>()
        for (dept in Dept.entries) {
            val anchor = anchors[dept] ?: continue
            // 黄色角标是「制造完成」的确定性信号，优先于所有文字判断。
            if (dept in doneByBadge) {
                statuses[dept] = DeptStatus(
                    dept = dept,
                    state = DeptState.DONE,
                    confidence = 0.95f,
                    detail = "卡片黄色角标",
                )
                continue
            }
            val nearby = assigned
                .asSequence()
                .filter { (_, nearest) -> nearest == dept }
                .filter { (element, _) ->
                    PageDetector.pointRectDistance(anchor.point, element.rect) <= radiusPx
                }
                .sortedBy { (element, _) ->
                    PageDetector.pointRectDistance(anchor.point, element.rect)
                }
                .map { (element, _) -> element }
                .toList()
            statuses[dept] = statusFromTexts(dept, nearby, profile)
        }
        return Resolution(statuses, anchors)
    }

    /** 便捷入口：内部重新做一次标题识别。 */
    fun inferAnchors(
        ocr: OcrResult,
        profile: UiProfile,
        screenWidth: Int,
        screenHeight: Int,
    ): Map<Dept, Anchor> =
        inferAnchors(
            labels = PageDetector.findDepartmentLabels(ocr, profile),
            profile = profile,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )

    /**
     * 构建四个部门的锚点。已识别标题优先；缺失项按 2×2 网格推断：
     * 技术中心/工作台 在上排，制药台/防具台 在下排；
     * 技术中心/制药台 在左列，工作台/防具台 在右列。
     */
    fun inferAnchors(
        labels: Map<Dept, TextElement>,
        profile: UiProfile,
        screenWidth: Int,
        screenHeight: Int,
    ): Map<Dept, Anchor> {
        val anchors = LinkedHashMap<Dept, Anchor>()
        for ((dept, element) in labels) {
            anchors[dept] = Anchor(element.rect.center, fromLabel = true)
        }

        // 网格推断：同一行 + 同一列各有一个已知锚点，才能确定缺失点。
        for (dept in Dept.entries) {
            if (dept in anchors) continue
            val sameRow = rowPartner(dept, anchors)?.point
            val sameColumn = columnPartner(dept, anchors)?.point
            if (sameRow != null && sameColumn != null) {
                anchors[dept] = Anchor(PointI(sameColumn.x, sameRow.y), fromLabel = false)
            }
        }

        // 兜底：配置中的归一化位置（与宽高比无关）。
        val width = screenWidth.coerceAtLeast(1)
        val height = screenHeight.coerceAtLeast(1)
        for (dept in Dept.entries) {
            if (dept in anchors) continue
            val fallback = profile.dashboard.departmentAnchorPoints[dept.key]
                ?: defaultAnchorPoint(dept)
            anchors[dept] = Anchor(
                PointI((fallback.x * width).toInt(), (fallback.y * height).toInt()),
                fromLabel = false,
            )
        }
        return anchors
    }

    /**
     * 把一段文字归属到部门。
     *
     * 卡片标题在卡片顶部、状态文字在卡片中部；当上下两排标题的间距小于
     * 卡片高度时，上排卡片的「空闲中/计时器」在垂直方向上可能比自己的
     * 标题更靠近下排标题，单纯取最近锚点会串到下一排（2026-11-20 实机复现）。
     *
     * 因此优先只在「位于文字上方」的锚点中找；右侧设施列表等文字上方
     * 没有合适锚点时，再退回全局最近。
     */
    private fun deptForElement(element: TextElement, anchors: Map<Dept, Anchor>): Dept? {
        val centerY = element.rect.center.y
        val above = anchors.filterValues { it.point.y <= centerY }
        return nearestDept(element, above) ?: nearestDept(element, anchors)
    }

    /** 返回距离该文字最近的部门锚点；锚点为空时返回 null。 */
    fun nearestDept(
        element: TextElement,
        anchors: Map<Dept, Anchor>,
    ): Dept? {
        var best: Dept? = null
        var bestDistance = Int.MAX_VALUE
        for ((dept, anchor) in anchors) {
            val distance = PageDetector.pointRectDistance(anchor.point, element.rect)
            if (distance < bestDistance) {
                bestDistance = distance
                best = dept
            }
        }
        return best
    }

    /**
     * 把黄色角标归属到部门。
     *
     * 角标出现在每个卡片头部的右上角：相对标题中心在右侧、略偏下。
     * 只接受「右侧 + 有限垂直偏移」窗口，避免：
     * - 左侧「升级制造」栏的提醒角标（dx < 0）；
     * - 右侧设施列表里「潜水中心/洗消舱」的角标（dx 过大）。
     */
    private fun assignBadges(
        badges: List<PointI>,
        anchors: Map<Dept, Anchor>,
        profile: UiProfile,
        screenWidth: Int,
    ): Set<Dept> {
        val cfg = profile.dashboard.doneBadge
        if (!cfg.enabled || badges.isEmpty() || anchors.isEmpty()) return emptySet()
        val scale = Geometry.widthFactor(profile, screenWidth)
        val minDx = cfg.minDxPx * scale
        val maxDx = cfg.maxDxPx * scale
        val maxDy = cfg.maxDyPx * scale

        val result = HashSet<Dept>()
        for (badge in badges) {
            // 不能先找「最近锚点」：角标在卡片右上角，往往比右下部门标题
            // 更靠近「相邻卡片」的标题。改为在所有锚点中找落入右上角窗口的，
            // 多个命中时取距离最近的一个。
            var best: Dept? = null
            var bestDistance = Long.MAX_VALUE
            for ((dept, anchor) in anchors) {
                val dx = badge.x - anchor.point.x
                val dy = kotlin.math.abs(badge.y - anchor.point.y)
                if (dx < minDx || dx > maxDx || dy > maxDy) continue
                val distance = dx.toLong() * dx + dy.toLong() * dy
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = dept
                }
            }
            best?.let { result.add(it) }
        }
        return result
    }

    fun statusRadius(profile: UiProfile, screenWidth: Int): Int {
        val scale = Geometry.widthFactor(profile, screenWidth)
        return (profile.dashboard.statusSearchRadiusPx * scale).toInt().coerceAtLeast(1)
    }

    // ------------------------------------------------------------------
    // 状态判定
    // ------------------------------------------------------------------

    private fun statusFromTexts(
        dept: Dept,
        nearby: List<TextElement>,
        profile: UiProfile,
    ): DeptStatus {
        val dashboard = profile.dashboard

        // 1. 空闲
        val idleElement = nearby.firstOrNull {
            TextParser.isIdle(it.text, dashboard.idleKeywords)
        }
        if (idleElement != null) {
            return DeptStatus(
                dept = dept,
                state = DeptState.IDLE,
                confidence = 1f,
                detail = idleElement.text,
            )
        }

        // 2. 计时器：有计时器一定是制造中，优先于其他文字。
        val timer = nearby.firstNotNullOfOrNull { element ->
            TextParser.parseTimer(element.text, profile.text)?.let { element to it }
        }
        if (timer != null) {
            return DeptStatus(
                dept = dept,
                state = DeptState.CRAFTING,
                remainingSec = timer.second,
                confidence = 0.9f,
                detail = timer.first.text,
            )
        }

        // 3. 明确显示「制造中」但没有计时器：不能按“无状态”回退成 DONE。
        val craftingElement = nearby.firstOrNull {
            TextParser.containsAny(it.text, dashboard.craftingKeywords)
        }
        if (craftingElement != null) {
            return DeptStatus(
                dept = dept,
                state = DeptState.CRAFTING,
                confidence = 0.8f,
                detail = craftingElement.text,
            )
        }

        // 4. 完成
        val doneElement = nearby.firstOrNull {
            TextParser.isDone(it.text, dashboard.doneKeywords)
        }
        if (doneElement != null) {
            return DeptStatus(
                dept = dept,
                state = DeptState.DONE,
                confidence = 1f,
                detail = doneElement.text,
            )
        }

        // 5. 没有可识别的状态文字：默认 UNKNOWN，绝不当作 DONE 去点击。
        return if (dashboard.doneWhenNoStatus) {
            DeptStatus(
                dept = dept,
                state = DeptState.DONE,
                confidence = 0.6f,
                detail = "无状态文字，按配置视为已完成",
            )
        } else {
            DeptStatus(
                dept = dept,
                state = DeptState.UNKNOWN,
                confidence = 0f,
                detail = "无状态文字",
            )
        }
    }

    // ------------------------------------------------------------------
    // 2×2 网格关系
    // ------------------------------------------------------------------

    private fun rowPartner(dept: Dept, anchors: Map<Dept, Anchor>): Anchor? = when (dept) {
        Dept.TECH -> anchors[Dept.WORK]
        Dept.WORK -> anchors[Dept.TECH]
        Dept.MEDICAL -> anchors[Dept.ARMOR]
        Dept.ARMOR -> anchors[Dept.MEDICAL]
    }

    private fun columnPartner(dept: Dept, anchors: Map<Dept, Anchor>): Anchor? = when (dept) {
        Dept.TECH -> anchors[Dept.MEDICAL]
        Dept.MEDICAL -> anchors[Dept.TECH]
        Dept.WORK -> anchors[Dept.ARMOR]
        Dept.ARMOR -> anchors[Dept.WORK]
    }

    /**
     * 实机 2712×1220 截图标定的卡片标题中心（归一化）。
     * 与 DashboardProfile.departmentAnchorPoints 的默认值保持一致。
     */
    private fun defaultAnchorPoint(dept: Dept): FloatPoint = when (dept) {
        Dept.TECH -> FloatPoint(0.195f, 0.186f)
        Dept.WORK -> FloatPoint(0.338f, 0.186f)
        Dept.MEDICAL -> FloatPoint(0.195f, 0.607f)
        Dept.ARMOR -> FloatPoint(0.338f, 0.607f)
    }
}
