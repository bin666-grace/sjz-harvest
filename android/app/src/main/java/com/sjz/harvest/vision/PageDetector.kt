package com.sjz.harvest.vision

import com.sjz.harvest.core.config.UiProfile
import com.sjz.harvest.core.model.Dept
import com.sjz.harvest.core.model.OcrResult
import com.sjz.harvest.core.model.Page
import com.sjz.harvest.core.model.PointI
import com.sjz.harvest.core.model.RectI
import com.sjz.harvest.core.model.TextElement
import kotlin.math.hypot
import kotlin.math.max

/**
 * 页面判定与文本锚点查找。纯 Kotlin，只依赖 core.model / core.config。
 *
 * 判页优先级（见 docs/02-architecture.md 5.2）：
 * 1. 「开始制造」或材料锚点 → CRAFT_DIALOG；
 * 2. 「交易行 / 最低价」→ AUCTION；
 * 3. 至少两个部门标题（或一个标题且旁边有状态文字）→ DASHBOARD；
 * 4. 没有总览特征、但文字数量足够 → DEPARTMENT；
 * 5. 否则 UNKNOWN。
 *
 * 第 3 条要求两个标题，是为了避免部门详情页只有自身标题时被误判成总览；
 * 误判会让补产流程停在详情页而不是进入部门列表。
 *
 * 注意：制造弹窗里也可能出现「制造」二字，因此这里只把更具体的
 * 「开始制造」当作强锚点；单独的「制造」不作为弹窗依据。
 */
object PageDetector {

    private const val MATERIAL_ANCHOR_SCORE = 90

    // ---------------------------------------------------------------------
    // 页面判定
    // ---------------------------------------------------------------------

    fun detect(ocr: OcrResult, profile: UiProfile): Page {
        if (ocr.elements.isEmpty()) return Page.UNKNOWN

        if (hasCraftDialogAnchor(ocr, profile)) return Page.CRAFT_DIALOG

        val auction = profile.auction.anchorTexts
        if (auction.isNotEmpty() &&
            hasEnoughAuctionAnchors(ocr, auction, profile)
        ) {
            return Page.AUCTION
        }

        val labels = findDepartmentLabels(ocr, profile)
        if (labels.size >= 2) return Page.DASHBOARD
        if (labels.size == 1 && hasStatusNearLabel(ocr, labels.values.first(), profile)) {
            return Page.DASHBOARD
        }

        // 没有总览特征：文字足够多时认为在部门物品列表/详情页。
        if (ocr.elements.size >= 6) return Page.DEPARTMENT

        return Page.UNKNOWN
    }

    /**
     * 单个部门标题不足以判总览（详情页也有标题），
     * 但标题旁出现空闲/计时器/完成文字时，基本可以确认是总览卡片。
     */
    private fun hasStatusNearLabel(
        ocr: OcrResult,
        label: TextElement,
        profile: UiProfile,
    ): Boolean {
        val dashboard = profile.dashboard
        return findNearby(
            ocr,
            label,
            dashboard.statusSearchRadiusPx,
        ) { element ->
            TextParser.isIdle(element.text, dashboard.idleKeywords) ||
                TextParser.isDone(element.text, dashboard.doneKeywords) ||
                TextParser.parseTimer(element.text, profile.text) != null
        }.isNotEmpty()
    }

    /**
     * 交易行锚点至少命中 min(2, anchor 总数) 个。
     * 主界面底部导航也有「交易行」三个字，单个锚点会造成误判；
     * 真正的交易行页同时有「交易行」和「最低价」。
     */
    private fun hasEnoughAuctionAnchors(
        ocr: OcrResult,
        anchors: List<String>,
        profile: UiProfile,
    ): Boolean {
        val threshold = profile.thresholds.minPageAnchorScore
        val matched = anchors.distinct().count { anchor ->
            findText(ocr, listOf(anchor), profile, threshold) != null
        }
        val required = minOf(2, anchors.distinct().size)
        return matched >= required
    }

    private fun hasCraftDialogAnchor(ocr: OcrResult, profile: UiProfile): Boolean {
        // 强按钮：长度 >= 3，即「开始制造」；排除泛化的「制造」。
        val strongStarts = profile.craft.startButtonTexts.filter {
            TextParser.normalize(it, profile.text).length >= 3
        }
        if (strongStarts.isNotEmpty() &&
            findText(ocr, strongStarts, profile, profile.thresholds.minPageAnchorScore) != null
        ) {
            return true
        }
        // 材料锚点（默认「材料 / 所需材料」）。要求近似精确，避免列表里普通文字误判。
        return findText(
            ocr,
            profile.craft.materialAnchorTexts,
            profile,
            MATERIAL_ANCHOR_SCORE,
        ) != null
    }

    // ---------------------------------------------------------------------
    // 部门标题
    // ---------------------------------------------------------------------

    /**
     * 查找四个部门的标题元素。一个元素只归属分数最高的一个部门；
     * 匹配分数低于 minPageAnchorScore 的忽略。
     */
    fun findDepartmentLabels(ocr: OcrResult, profile: UiProfile): Map<Dept, TextElement> {
        val minScore = profile.thresholds.minPageAnchorScore
        data class Candidate(val dept: Dept, val index: Int, val element: TextElement, val score: Int)

        val candidates = ArrayList<Candidate>()
        for (dept in Dept.entries) {
            val label = departmentLabel(dept, profile)
            if (label.isBlank()) continue
            ocr.elements.forEachIndexed { index, element ->
                val score = anchorScore(element.text, label, profile)
                if (score >= minScore) {
                    candidates.add(Candidate(dept, index, element, score))
                }
            }
        }
        if (candidates.isEmpty()) return emptyMap()

        candidates.sortWith(
            compareByDescending<Candidate> { it.score }.thenBy { it.dept.ordinal },
        )

        val usedIndexes = HashSet<Int>()
        val usedDepts = HashSet<Dept>()
        val result = LinkedHashMap<Dept, TextElement>()
        for (candidate in candidates) {
            if (candidate.dept in usedDepts || candidate.index in usedIndexes) continue
            usedDepts.add(candidate.dept)
            usedIndexes.add(candidate.index)
            result[candidate.dept] = candidate.element
        }
        return result
    }

    fun departmentLabel(dept: Dept, profile: UiProfile): String =
        profile.dashboard.departmentLabels[dept.key] ?: dept.label

    /** 判断一段文字是否是某个部门标题。 */
    fun isDepartment(text: String, profile: UiProfile): Boolean =
        departmentOf(text, profile) != null

    fun isDepartment(element: TextElement, profile: UiProfile): Boolean =
        isDepartment(element.text, profile)

    /** 返回文字对应的部门（支持轻微 OCR 错字），不是部门标题时返回 null。 */
    fun departmentOf(text: String, profile: UiProfile): Dept? {
        var best: Dept? = null
        var bestScore = 0
        val minScore = profile.thresholds.minPageAnchorScore
        for (dept in Dept.entries) {
            val label = departmentLabel(dept, profile)
            if (label.isBlank()) continue
            val score = anchorScore(text, label, profile)
            if (score > bestScore) {
                bestScore = score
                best = dept
            }
        }
        return best?.takeIf { bestScore >= minScore }
    }

    // ---------------------------------------------------------------------
    // 文本查找
    // ---------------------------------------------------------------------

    /** 查找单个目标文本（模糊匹配）。 */
    fun findText(ocr: OcrResult, target: String, threshold: Int = 80): TextElement? =
        findText(ocr, listOf(target), UiProfile(), threshold)

    /** 查找单个目标文本（使用 profile 的 OCR 纠错表与归一化规则）。 */
    fun findText(
        ocr: OcrResult,
        target: String,
        profile: UiProfile,
        threshold: Int = 80,
    ): TextElement? = findText(ocr, listOf(target), profile, threshold)

    /** 在一组目标文本中找与 OCR 文字最相似的一个元素。 */
    fun findText(
        ocr: OcrResult,
        targets: List<String>,
        profile: UiProfile,
        threshold: Int = 80,
    ): TextElement? {
        if (targets.isEmpty() || ocr.elements.isEmpty()) return null
        var bestElement: TextElement? = null
        var bestScore = 0
        for (element in ocr.elements) {
            for (target in targets) {
                val score = anchorScore(element.text, target, profile)
                if (score > bestScore) {
                    bestScore = score
                    bestElement = element
                }
            }
        }
        return bestElement?.takeIf { bestScore >= threshold }
    }

    fun findTextAny(
        ocr: OcrResult,
        targets: List<String>,
        profile: UiProfile,
        threshold: Int = 80,
    ): TextElement? = findText(ocr, targets, profile, threshold)

    /** 查找以 anchor 为中心、半径 radiusPx 内的文字元素，按距离由近到远排序。 */
    fun findNearby(
        ocr: OcrResult,
        anchor: TextElement,
        radiusPx: Int,
        predicate: (TextElement) -> Boolean = { true },
    ): List<TextElement> =
        ocr.elements
            .asSequence()
            .filter { it !== anchor }
            .map { it to rectDistance(anchor.rect, it.rect) }
            .filter { (element, distance) -> distance <= radiusPx && predicate(element) }
            .sortedBy { (_, distance) -> distance }
            .map { (element, _) -> element }
            .toList()

    /** 查找以某个点为中心、半径 radiusPx 内的文字元素。 */
    fun findNearby(
        ocr: OcrResult,
        center: PointI,
        radiusPx: Int,
        predicate: (TextElement) -> Boolean = { true },
    ): List<TextElement> =
        ocr.elements
            .asSequence()
            .map { it to pointRectDistance(center, it.rect) }
            .filter { (element, distance) -> distance <= radiusPx && predicate(element) }
            .sortedBy { (_, distance) -> distance }
            .map { (element, _) -> element }
            .toList()

    // ---------------------------------------------------------------------
    // 几何辅助
    // ---------------------------------------------------------------------

    /** 两个矩形之间的最短距离（重叠为 0）。 */
    fun rectDistance(a: RectI, b: RectI): Int {
        val dx = max(0, max(a.x - b.right, b.x - a.right))
        val dy = max(0, max(a.y - b.bottom, b.y - a.bottom))
        return hypot(dx.toDouble(), dy.toDouble()).toInt()
    }

    fun pointRectDistance(point: PointI, rect: RectI): Int {
        val dx = when {
            point.x < rect.x -> rect.x - point.x
            point.x > rect.right -> point.x - rect.right
            else -> 0
        }
        val dy = when {
            point.y < rect.y -> rect.y - point.y
            point.y > rect.bottom -> point.y - rect.bottom
            else -> 0
        }
        return hypot(dx.toDouble(), dy.toDouble()).toInt()
    }

    // ---------------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------------

    /**
     * 单个文字与锚点的匹配分：
     * 完全相等 100、包含锚点 95、其余按 Levenshtein 相似度。
     *
     * 刻意不做「锚点包含文字」的加分：否则 OCR 只识别出「制造」时
     * 会匹配到锚点「开始制造」，造成列表页误判成制造弹窗。
     */
    fun anchorScore(text: String, anchor: String, profile: UiProfile): Int {
        val normalizedText = TextParser.normalize(text, profile.text)
        val normalizedAnchor = TextParser.normalize(anchor, profile.text)
        if (normalizedText.isEmpty() || normalizedAnchor.isEmpty()) return 0
        if (normalizedText == normalizedAnchor) return 100
        if (normalizedText.contains(normalizedAnchor)) return 95
        return TextParser.similarity(normalizedText, normalizedAnchor)
    }

    /** 判断两个元素是否指向同一处文字（OCR 结果里可能有重复行）。 */
    fun sameElement(a: TextElement?, b: TextElement?): Boolean {
        if (a == null || b == null) return false
        return a === b || (a.text == b.text && a.rect == b.rect)
    }
}
