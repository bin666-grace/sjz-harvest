package com.sjz.harvest.game

import com.sjz.harvest.capture.ScreenSource
import com.sjz.harvest.core.config.CatalogIndex
import com.sjz.harvest.core.config.FloatPoint
import com.sjz.harvest.core.config.PlanEntry
import com.sjz.harvest.core.config.SafetyConfig
import com.sjz.harvest.core.config.TimingConfig
import com.sjz.harvest.core.config.UiProfile
import com.sjz.harvest.core.logging.AppLogger
import com.sjz.harvest.core.model.Dept
import com.sjz.harvest.core.model.Geometry
import com.sjz.harvest.core.model.Page
import com.sjz.harvest.core.model.PointI
import com.sjz.harvest.core.model.TextElement
import com.sjz.harvest.input.InputController
import com.sjz.harvest.vision.OcrEngine
import com.sjz.harvest.vision.PageDetector
import com.sjz.harvest.vision.TextParser
import kotlinx.coroutines.delay

/**
 * 收菜与补产的具体点击流程。
 *
 * 安全原则：
 * - 每次点击前都基于当前 OCR 判页；页面不符返回 PAGE_ERROR，不盲点；
 * - 所有等待时间来自 [TimingConfig]；
 * - 自动买材料默认关闭（[SafetyConfig.autoBuyMaterials]）：
 *   关闭时出现「购买/一键补齐」直接返回 MISSING_MATERIALS；
 *   开启后才走补料流程，且必须识别到可见价格且不超过上限才确认；
 * - dry-run 时只记录日志，不真正调用输入后端。
 *
 * 本类只依赖接口（ScreenSource / OcrEngine / InputController），
 * 不直接触碰 AccessibilityService。
 */
class CraftingController(
    private val snapshotter: ScreenOcr,
    private val input: InputController,
    private val profile: UiProfile,
    private val catalog: CatalogIndex,
    private val timing: TimingConfig,
    private val safety: SafetyConfig,
    private val logger: AppLogger,
    private val foregroundGuard: ForegroundGuard = ForegroundGuard.ALWAYS_OK,
    /** 测试可注入的单调时钟；默认使用 [System.nanoTime]。 */
    private val nanoTime: () -> Long = System::nanoTime,
) : Crafter {

    /**
     * 生产用构造：把截屏 + OCR 包装成 [ScreenOcrSource]。
     * 业务代码不直接接触 Bitmap。
     */
    constructor(
        source: ScreenSource,
        ocr: OcrEngine,
        input: InputController,
        profile: UiProfile,
        catalog: CatalogIndex,
        timing: TimingConfig,
        safety: SafetyConfig,
        logger: AppLogger,
        foregroundGuard: ForegroundGuard = ForegroundGuard.ALWAYS_OK,
    ) : this(
        snapshotter = ScreenOcrSource(
            source = source,
            ocr = ocr,
            foregroundGuard = foregroundGuard,
            doneBadge = profile.dashboard.doneBadge,
        ),
        input = input,
        profile = profile,
        catalog = catalog,
        timing = timing,
        safety = safety,
        logger = logger,
        foregroundGuard = foregroundGuard,
    )

    /** 预归一化的界面文字，避免在物品匹配循环里反复构造。 */
    private val uiLabelSet: Set<String> = buildList {
        addAll(profile.craft.startButtonTexts)
        addAll(profile.craft.fillMaterialsTexts)
        addAll(profile.craft.buyButtonTexts)
        addAll(profile.craft.backTexts)
        addAll(profile.dashboard.idleKeywords)
        addAll(profile.dashboard.craftingKeywords)
        addAll(profile.dashboard.doneKeywords)
        addAll(profile.dashboard.collectButtonTexts)
        addAll(profile.dashboard.departmentLabels.values)
    }.map { TextParser.normalize(it, profile.text) }
        .filter { it.isNotEmpty() }
        .toSet()

    // ---------------------------------------------------------------------
    // 收菜
    // ---------------------------------------------------------------------

    override suspend fun collect(dept: Dept): Boolean {
        val snap = captureOcr("collect:${dept.key}")
        val page = PageDetector.detect(snap.result, profile)
        if (page == Page.UNKNOWN || page == Page.AUCTION) {
            logger.w(TAG, "收菜前页面不符: ${dept.label} page=$page，跳过")
            return false
        }

        val radius = statusRadius(snap.width)
        val labels = PageDetector.findDepartmentLabels(snap.result, profile)
        val label = labels[dept]

        // 1. 优先在当前页面直接找「收取/领取」按钮。
        val button = findCollectButton(snap, label, page, radius)
        if (button != null) {
            tap(button.rect.center, "收取 ${dept.label}")
            val blocked = verifyCollect(dept, timing.collectWaitMs)
            if (blocked != null) {
                // 实机仓库满时点「收获」只弹提示，物品不会入账。
                // 必须如实返回失败，不能把「点了没收到」报成收取成功。
                logger.w(
                    TAG,
                    "收取 ${dept.label} 被游戏拒绝：${blocked.text}（请先清理仓库）",
                )
                return false
            }
            logger.i(TAG, "已点击收取按钮: ${dept.label} (${button.text})")
            return true
        }

        // 2. 固定点兜底（校准页配置）。
        val dashboard = profile.dashboard
        val fixedPoint = dashboard.collectFixedPoint
        if (dashboard.collectStrategy == "fixed_point" && fixedPoint != null) {
            tap(
                Geometry.scalePoint(fixedPoint, profile, snap.width),
                "固定点收取 ${dept.label}",
            )
            delay(timing.collectWaitMs)
            return true
        }

        // 3. 按钮不在总览：点部门标题/卡片进入，再找一次。
        //    实机四个部门位置固定：总览页标题被 OCR 漏掉时用校准点兜底，
        //    避免因为一个字没识别出来就放弃这个部门。其它页面（物品列表、
        //    制造面板）上的固定点没有意义，绝不能盲点。
        val entry = label?.rect?.center ?: if (page == Page.DASHBOARD) {
            fixedDepartmentPoint(dept, snap)
        } else {
            null
        }
        if (entry == null) {
            logger.w(TAG, "找不到 ${dept.label} 标题，且无固定位置配置，无法进入收集")
            return false
        }
        tap(
            entry,
            if (label != null) "进入 ${dept.label} 收集" else "进入 ${dept.label}（固定位置）",
        )
        delay(timing.collectWaitMs)

        var detail = captureOcr("collect-detail:${dept.key}")
        var detailPage = PageDetector.detect(detail.result, profile)
        if (detailPage == Page.UNKNOWN || detailPage == Page.AUCTION) {
            logger.w(TAG, "进入 ${dept.label} 后页面不符: $detailPage")
            return false
        }

        // 实机部门卡片标题进入的是「XX升级」页，需要再点「前往生产」
        // 才能看到左列表 + 右详情（底部按钮为「收获」）。
        val productionEntry = findProductionEntry(detail)
        if (productionEntry != null) {
            tap(productionEntry.rect.center, "前往生产 ${dept.label}")
            delay(timing.collectWaitMs)
            detail = captureOcr("collect-detail:${dept.key}")
            detailPage = PageDetector.detect(detail.result, profile)
            if (detailPage == Page.UNKNOWN || detailPage == Page.AUCTION) {
                logger.w(TAG, "前往生产后页面不符: $detailPage")
                return false
            }
        }

        val detailLabels = PageDetector.findDepartmentLabels(detail.result, profile)
        val detailButton = findCollectButton(
            detail,
            detailLabels[dept],
            detailPage,
            statusRadius(detail.width),
        )
        if (detailButton == null) {
            logger.w(TAG, "进入 ${dept.label} 后仍未找到收取按钮")
            return false
        }
        tap(detailButton.rect.center, "收取 ${dept.label}")
        val blocked = verifyCollect(dept, timing.collectWaitMs)
        if (blocked != null) {
            logger.w(
                TAG,
                "收取 ${dept.label} 被游戏拒绝：${blocked.text}（请先清理仓库）",
            )
            return false
        }
        logger.i(TAG, "已在详情页点击收取: ${dept.label} (${detailButton.text})")
        return true
    }

    /**
     * 收取后尽快截图检查拒绝提示。
     *
     * 实机 toast（如「仓库空间不足」）只显示很短时间，若等满 collectWaitMs
     * 再截图会漏掉，从而把「被拒绝」误报成收取成功。因此先等一个很短的
     * 反应时间截图，未发现拒绝提示时再补足剩余等待。
     */
    private suspend fun verifyCollect(dept: Dept, waitMs: Long): TextElement? {
        val verifyDelay = minOf(waitMs, COLLECT_VERIFY_DELAY_MS)
        delay(verifyDelay)
        val blocked = collectBlocked(captureOcr("collect-verify:${dept.key}"))
        if (blocked == null) {
            delay((waitMs - verifyDelay).coerceAtLeast(0))
        }
        return blocked
    }

    /** 收取后画面上的拒绝提示（toast），如「仓库空间不足」。 */
    private fun collectBlocked(snap: OcrSnapshot): TextElement? {
        if (profile.dashboard.collectBlockedTexts.isEmpty()) return null
        return PageDetector.findText(
            snap.result,
            profile.dashboard.collectBlockedTexts,
            profile,
            threshold = 85,
        )
    }

    private fun findCollectButton(
        snap: OcrSnapshot,
        label: TextElement?,
        page: Page,
        radiusPx: Int,
    ): TextElement? {
        val buttonTexts = profile.dashboard.collectButtonTexts
        val candidates = when {
            // 总览：只在部门标题附近找，避免四个卡片的按钮串位。
            page == Page.DASHBOARD && label != null ->
                PageDetector.findNearby(snap.result, label, radiusPx) { element ->
                    isCollectText(element.text, buttonTexts)
                }
            // 部门详情/物品页：页面专属该部门，按钮可能在右下角，
            // 不能限制在标题半径内；全页查找。
            page != Page.DASHBOARD -> snap.result.elements.filter { element ->
                isCollectText(element.text, buttonTexts)
            }
            else -> emptyList()
        }
        return candidates.firstOrNull { element ->
            !PageDetector.isDepartment(element.text, profile)
        }
    }

    private fun isCollectText(text: String, keywords: List<String>): Boolean =
        TextParser.isDone(text, keywords)

    /**
     * 「XX升级」页底部/右上角的「前往生产」按钮。
     * 找不到返回 null（例如已经处于生产列表页）。
     */
    private fun findProductionEntry(snap: OcrSnapshot): TextElement? =
        PageDetector.findText(
            snap.result,
            profile.craft.productionButtonTexts,
            profile,
            threshold = 85,
        )

    /**
     * 部门卡片标题的固定位置（归一化坐标 → 当前屏幕像素）。
     * 只在 OCR 漏掉该部门标题时使用，位置本身来自实机标定。
     */
    private fun fixedDepartmentPoint(dept: Dept, snap: OcrSnapshot): PointI? {
        val base = profile.dashboard.departmentAnchorPoints[dept.key] ?: return null
        return PointI((base.x * snap.width).toInt(), (base.y * snap.height).toInt())
    }

    // ---------------------------------------------------------------------
    // 补产
    // ---------------------------------------------------------------------

    override suspend fun craft(dept: Dept, entry: PlanEntry): CraftOutcome {
        val threshold = profile.thresholds.itemSimilarity[dept.key]
            ?: profile.thresholds.defaultItemSimilarity
        val known = catalog.resolve(dept, entry.item)
        logger.d(
            TAG,
            "开始补产 ${dept.label} → ${entry.item} " +
                "(catalog=${known?.category ?: "unknown"}, threshold=$threshold)",
        )
        // 计划项如果显式指定了分类，必须与 catalog 的分类一致，防止同名物品串部门。
        if (entry.category != null && known != null &&
            !known.category.equals(entry.category.trim(), ignoreCase = true)
        ) {
            logger.w(
                TAG,
                "计划分类不一致: ${entry.item} 计划=${entry.category} " +
                    "catalog=${known.category}，拒绝执行",
            )
            return CraftOutcome.PAGE_ERROR
        }

        var snap = captureOcr("craft-open:${dept.key}")
        var page = PageDetector.detect(snap.result, profile)

        when (page) {
            Page.UNKNOWN, Page.AUCTION -> {
                logger.w(TAG, "补产前页面不符: ${dept.label} page=$page")
                return CraftOutcome.PAGE_ERROR
            }
            Page.CRAFT_DIALOG -> {
                // 手游的「左列表 + 右详情」也会因「材料」被判成 CRAFT_DIALOG。
                // 只有强面板且右侧详情就是目标物品时才直接操作；
                // 否则继续按列表页扫描，避免误造上一件已选物品。
                if (isStrongCraftDialog(snap) &&
                    panelShowsTarget(snap, entry.item, threshold)
                ) {
                    return handleCraftDialog(snap)
                }
            }
            Page.DASHBOARD -> {
                val label = PageDetector.findDepartmentLabels(snap.result, profile)[dept]
                if (label == null) {
                    logger.w(TAG, "总览页找不到 ${dept.label} 标题")
                    return CraftOutcome.PAGE_ERROR
                }
                if (safety.dryRun) {
                    logger.i(
                        TAG,
                        "[dry-run] 无法模拟进入 ${dept.label} 列表；" +
                            "请手动进入部门物品页后再开始 dry-run",
                    )
                    return CraftOutcome.PAGE_ERROR
                }
                tap(label.rect.center, "进入 ${dept.label} 列表")
                // 实机中间页是「XX升级」：先等列表或「前往生产」按钮出现。
                snap = awaitState("craft-list:${dept.key}", timing.pageTimeoutMs) {
                    isListPage(it) || findProductionEntry(it) != null
                }
                val productionEntry = findProductionEntry(snap)
                if (productionEntry != null) {
                    tap(productionEntry.rect.center, "前往生产 ${dept.label}")
                    snap = awaitState("craft-list:${dept.key}", timing.pageTimeoutMs) {
                        isListPage(it)
                    }
                }
                if (!isListPage(snap)) {
                    // 实机卡片标题可能不可点，但卡片中部（“+ / 空闲中”）可点。
                    val fallback = PointI(
                        label.rect.center.x,
                        label.rect.center.y + (statusRadius(snap.width) * 0.5).toInt(),
                    )
                    logger.w(TAG, "点击标题未进入列表，尝试点击卡片区域兜底")
                    tap(fallback, "进入 ${dept.label} 列表（卡片兜底）")
                    snap = awaitState("craft-list:${dept.key}", timing.pageTimeoutMs) {
                        isListPage(it)
                    }
                }
                if (!isListPage(snap)) {
                    logger.w(TAG, "点击 ${dept.label} 后未进入物品列表")
                    return CraftOutcome.PAGE_ERROR
                }
            }
            Page.DEPARTMENT -> Unit
        }

        // 玩家把目标物品置顶后，直接点列表第一行的程序固定点。
        // 不依赖物品名 OCR，也不滚动列表；OCR 只用于点完后的右侧详情预审。
        if (profile.itemList.firstRowSelect) {
            return craftFirstRow(snap, dept, entry, threshold)
        }

        // 扫描物品列表：最多 maxScrolls 屏，到底后返回 ITEM_NOT_FOUND。
        val maxScrolls = minPositive(timing.maxScrolls, profile.itemList.maxScrolls)
        // 移植自 DeltaForceSS：滚动后首行物品名与上一屏相同 → 已到底。
        // 比整屏文字相似度更稳（列表底部常只剩一两行变化）。
        val bottomThreshold = profile.thresholds.itemSimilarity[dept.key]
            ?: profile.thresholds.defaultItemSimilarity
        var lastTopItem: String? = null
        for (index in 0 until maxScrolls) {
            page = PageDetector.detect(snap.result, profile)
            if (page == Page.AUCTION || page == Page.UNKNOWN) {
                logger.w(TAG, "扫描列表时页面异常: page=$page")
                return CraftOutcome.PAGE_ERROR
            }
            if (page == Page.CRAFT_DIALOG && isStrongCraftDialog(snap) &&
                panelShowsTarget(snap, entry.item, threshold)
            ) {
                return handleCraftDialog(snap)
            }

            val currentTop = topItemText(snap)
            if (index > 0 && lastTopItem != null && currentTop != null) {
                val topScore = TextParser.similarity(lastTopItem, currentTop)
                if (topScore >= bottomThreshold) {
                    logger.i(
                        TAG,
                        "列表已到底（首行未变化，相似度 $topScore >= $bottomThreshold: " +
                            "\"$lastTopItem\")，未找到 ${entry.item}",
                    )
                    return CraftOutcome.ITEM_NOT_FOUND
                }
            }
            if (currentTop != null) lastTopItem = currentTop

            val target = findTargetItem(snap, entry.item, threshold)
            if (target != null) {
                logger.i(TAG, "匹配到物品: ${entry.item} ← \"${target.text}\"")
                tap(target.rect.center, "选择 ${entry.item}")

                if (safety.dryRun) {
                    // dry-run 不能真的切换右侧详情，无法验证目标/材料；
                    // 记录匹配结果后直接返回，避免 8 秒超时与错误冷却。
                    val panel = captureOcr("craft-dry-run:${dept.key}")
                    logger.i(
                        TAG,
                        "[dry-run] 目标匹配成功 ${entry.item}；" +
                            "panel=${isCraftPanel(panel)}, " +
                            "fill=${findFillButton(panel)?.text ?: "无"}",
                    )
                    return CraftOutcome.SUCCESS
                }

                // 手游是「左列表 + 右详情」同屏：点击后不出现弹窗，而是右侧
                // 详情和底部按钮（开始制造 / 一键补齐）更新。
                snap = awaitState("craft-panel:${dept.key}", timing.pageTimeoutMs) {
                    isCraftPanel(it) && panelShowsTarget(it, entry.item, threshold)
                }
                if (!isCraftPanel(snap)) {
                    logger.w(TAG, "点击物品后未出现制造操作区")
                    return CraftOutcome.PAGE_ERROR
                }
                if (!panelShowsTarget(snap, entry.item, threshold)) {
                    logger.w(TAG, "点击后右侧详情未切换到 ${entry.item}，拒绝继续")
                    return CraftOutcome.PAGE_ERROR
                }
                return handleCraftDialog(snap)
            }

            if (index == maxScrolls - 1) break

            val before = textKey(snap)
            swipeUp(snap, "查找 ${entry.item}")
            delay(timing.scrollSettleMs)
            val afterSnap = captureOcr("craft-scroll:${dept.key}")
            val after = textKey(afterSnap)
            val similarity = TextParser.similarity(before, after)
            if (similarity >= profile.itemList.bottomSimilarity) {
                logger.i(
                    TAG,
                    "列表已到底（相似度 $similarity >= ${profile.itemList.bottomSimilarity}），" +
                        "未找到 ${entry.item}",
                )
                return CraftOutcome.ITEM_NOT_FOUND
            }
            snap = afterSnap
        }

        logger.w(TAG, "扫描 $maxScrolls 屏后未找到 ${entry.item}")
        return CraftOutcome.ITEM_NOT_FOUND
    }

    /**
     * 第一行模式：点击列表第一行的程序固定点，再用右侧详情做 OCR 预审。
     *
     * 安全约束：
     * - 只有当前确实是物品列表页才点击（否则固定点可能落到别处）；
     * - 点击后必须出现制造操作区；
     * - 右侧详情能读到标题且不是计划物品 → 中止，绝不开始制造；
     * - 详情标题读不出来 → 记录告警但继续（位置是程序设定的，OCR 只作预审）。
     */
    private suspend fun craftFirstRow(
        snap: OcrSnapshot,
        dept: Dept,
        entry: PlanEntry,
        threshold: Int,
    ): CraftOutcome {
        if (!isListPage(snap)) {
            logger.w(
                TAG,
                "第一行模式：当前不是物品列表" +
                    "（page=${PageDetector.detect(snap.result, profile)}），不点击",
            )
            return CraftOutcome.PAGE_ERROR
        }

        val point = normalizedPoint(profile.itemList.firstRowPoint, snap)
        tap(point, "选择列表第一行 ${entry.item}")
        if (safety.dryRun) {
            logger.i(TAG, "[dry-run] 已模拟点击列表第一行（${point.x},${point.y}，未验证）")
            return CraftOutcome.SUCCESS
        }

        val after = awaitState("craft-panel:${dept.key}", timing.pageTimeoutMs) {
            isCraftPanel(it)
        }
        if (!isCraftPanel(after)) {
            logger.w(TAG, "点击列表第一行后未出现制造操作区")
            return CraftOutcome.PAGE_ERROR
        }

        if (profile.itemList.firstRowVerify) {
            when (detailCheck(after, entry.item, threshold)) {
                DetailCheck.MISMATCH -> {
                    logger.w(
                        TAG,
                        "OCR 预审：右侧详情不是 ${entry.item}，拒绝开始制造" +
                            "（确认目标物品已置顶为第一行）",
                    )
                    return CraftOutcome.PAGE_ERROR
                }
                DetailCheck.UNREADABLE -> logger.w(
                    TAG,
                    "OCR 预审：右侧详情标题未识别，按程序设定继续" +
                        "（请确认第一行确实是 ${entry.item}）",
                )
                DetailCheck.MATCH -> Unit
            }
        }

        return handleCraftDialog(after)
    }

    /**
     * 右侧详情标题的三态校验：
     * - MATCH：读到标题且匹配目标；
     * - MISMATCH：读到标题但明显不是目标（中止）；
     * - UNREADABLE：详情区没有可用文字（OCR 漏读，预审不拦截）。
     */
    private fun detailCheck(
        snap: OcrSnapshot,
        target: String,
        threshold: Int,
    ): DetailCheck {
        val baseRegion = profile.craft.detailRegion ?: return DetailCheck.MATCH
        val region = Geometry.scaleRect(baseRegion, profile, snap.width)
        val targetNorm = TextParser.normalize(target, profile.text)
        if (targetNorm.isEmpty()) return DetailCheck.MATCH

        // 详情区最靠上的文字就是标题；下方还会有「参考价格」等行，不能取任意文字。
        val title = snap.result.elements
            .asSequence()
            .filter { region.contains(it.rect.center) }
            .filter { TextParser.normalize(it.text, profile.text).length >= 2 }
            .minByOrNull { it.rect.y }
            ?: return DetailCheck.UNREADABLE

        val normalized = TextParser.normalize(title.text, profile.text)
        val matched = normalized == targetNorm ||
            (targetNorm.length >= 3 &&
                normalized.length >= targetNorm.length &&
                normalized.contains(targetNorm)) ||
            TextParser.similarity(normalized, targetNorm) >= threshold
        return if (matched) DetailCheck.MATCH else DetailCheck.MISMATCH
    }

    /**
     * 在 OCR 结果中找与目标物品最相似的文字。
     * 物品行可能带「可制造」等后缀，因此包含目标名的行给 100 分。
     */
    private fun findTargetItem(snap: OcrSnapshot, target: String, threshold: Int): TextElement? {
        val targetNorm = TextParser.normalize(target, profile.text)
        if (targetNorm.isEmpty()) return null

        val viewport = profile.itemList.viewport?.let {
            Geometry.scaleRect(it, profile, snap.width)
        }
        val noiseWords = profile.text.itemNoiseWords
            .map { TextParser.normalize(it, profile.text) }
            .filter { it.isNotEmpty() }
            .toSet()

        var best: TextElement? = null
        var bestScore = 0
        for (element in snap.result.elements) {
            val normalized = TextParser.normalize(element.text, profile.text)
            if (normalized.length < 2) continue
            if (normalized in noiseWords) continue
            if (normalized.all { it.isDigit() }) continue
            if (isUiLabel(normalized)) continue
            if (viewport != null && !viewport.contains(element.rect.center)) continue

            val score = when {
                normalized == targetNorm -> 100
                targetNorm.length >= 4 && normalized.contains(targetNorm) -> 100
                else -> TextParser.similarity(normalized, targetNorm)
            }
            if (score > bestScore) {
                bestScore = score
                best = element
            }
        }
        return best?.takeIf { bestScore >= threshold }
    }

    /** 排除按钮/部门标题等界面文字，避免把它们当作物品行。 */
    private fun isUiLabel(normalized: String): Boolean =
        normalized.isEmpty() || normalized in uiLabelSet

    /**
     * 制造弹窗：
     * 1. 有「购买」且未开启自动买材料 → MISSING_MATERIALS（不点击购买）；
     * 2. 开启后循环购买，最多 maxBuyAttempts；
     * 3. 有「一键补齐」且开启自动买材料 → 补料确认流程（价格校验）；
     * 4. 找「开始制造」点击，等待、返回，SUCCESS。
     */
    private suspend fun handleCraftDialog(initial: OcrSnapshot): CraftOutcome {
        var snap = initial

        val buyButton = findBuyButton(snap)
        if (buyButton != null) {
            if (!safety.autoBuyMaterials) {
                logger.w(
                    TAG,
                    "制造弹窗缺少材料，但 autoBuyMaterials=false；" +
                        "不点击购买，返回 MISSING_MATERIALS",
                )
                back("退出制造弹窗（材料不足）")
                delay(timing.pollIntervalMs)
                return CraftOutcome.MISSING_MATERIALS
            }
            if (!buyMaterials(snap)) {
                logger.w(TAG, "自动购买材料失败")
                back("退出制造弹窗（购买失败）")
                delay(timing.pollIntervalMs)
                return CraftOutcome.BUY_FAILED
            }
            snap = captureOcr("craft-dialog-after-buy")
            if (!isStrongCraftDialog(snap)) {
                logger.w(TAG, "购买流程结束后未回到制造弹窗")
                back("退出异常页面")
                delay(timing.pollIntervalMs)
                return CraftOutcome.PAGE_ERROR
            }
            if (findBuyButton(snap) != null) {
                logger.w(TAG, "购买次数用尽仍有缺失材料")
                back("退出制造弹窗（材料仍缺）")
                delay(timing.pollIntervalMs)
                return CraftOutcome.MISSING_MATERIALS
            }
        }

        // 手游材料不足时按钮是「一键补齐」。默认（autoBuyMaterials=false）
        // 永不点击，直接返回缺材料；显式开启后才走补料流程。
        val fill = findFillButton(snap)
        if (fill != null) {
            if (!safety.autoBuyMaterials) {
                logger.w(
                    TAG,
                    "材料不足（检测到「${fill.text}」）；" +
                        "autoBuyMaterials=false，不点击补齐，返回 MISSING_MATERIALS",
                )
                return CraftOutcome.MISSING_MATERIALS
            }
            logger.i(TAG, "材料不足，自动补料已开启：点击「${fill.text}」")
            return fillMaterials(fill)
        }

        return startCraft(snap)
    }

    /** 点击「开始制造」并验证按钮消失；成功才返回 SUCCESS。 */
    private suspend fun startCraft(snap: OcrSnapshot): CraftOutcome {
        val start = findStartButton(snap)
        var usedFallback = false
        if (start != null) {
            tap(start.rect.center, "开始制造")
        } else {
            // OCR 漏掉「开始制造」文字时，用校准页保存的兜底点。
            // 只在已经确认是制造面板时使用（调用方已通过强锚点+目标校验），
            // 并且点击后必须看到面板消失才算成功，绝不盲判成功。
            val fallback = profile.craft.startPoint
            if (fallback == null) {
                logger.w(TAG, "制造操作区中找不到「开始制造」，且未校准兜底点")
                back("退出无按钮的制造页面")
                delay(timing.pollIntervalMs)
                return CraftOutcome.PAGE_ERROR
            }
            if (!isCraftPanel(snap)) {
                logger.w(TAG, "找不到「开始制造」且当前不是制造面板，拒绝使用兜底点")
                return CraftOutcome.PAGE_ERROR
            }
            usedFallback = true
            val point = Geometry.scalePoint(fallback, profile, snap.width)
            logger.i(TAG, "找不到「开始制造」文字，使用校准兜底点 (${point.x},${point.y})")
            tap(point, "开始制造（校准兜底点）")
        }
        delay(timing.craftWaitMs)

        // dry-run 没有真实点击，无法验证，也不消费计划。
        if (safety.dryRun) {
            logger.i(TAG, "[dry-run] 已模拟点击开始制造（未验证）")
            return CraftOutcome.SUCCESS
        }

        // 验证点击真的生效：
        // - 有按钮文字时：按钮应消失（同屏面板可能仍在）；
        // - 用兜底点时：没有「按钮文字消失」可看，改为要求制造面板整体消失，
        //   否则不消费计划。
        // 若 8 秒后条件仍不成立，很可能是点击未命中/材料校验失败。
        fun started(snapshot: OcrSnapshot): Boolean = if (usedFallback) {
            !isCraftPanel(snapshot)
        } else {
            findStartButton(snapshot) == null
        }

        var after = captureOcr("craft-start-verify")
        val deadline = nanoTime() + timing.pageTimeoutMs.coerceAtLeast(0) * 1_000_000L
        while (!started(after) && nanoTime() < deadline) {
            delay(timing.pollIntervalMs)
            after = captureOcr("craft-start-verify")
        }
        if (!started(after)) {
            logger.w(TAG, "点击开始制造后按钮/面板仍未消失，按失败处理（不消费计划）")
            back("退出未响应的制造页面")
            delay(timing.pollIntervalMs)
            return CraftOutcome.PAGE_ERROR
        }

        // 开始按钮消失说明点击已生效。此时不要盲目按返回：
        // 部分客户端会弹确认框，按返回会取消制造。
        // 页面回归/关闭弹窗统一交给 HarvestFlow → recoverToDashboard：
        // 它只点击「确定/确认/关闭」这类已知按钮，找不到安全按钮就停止。
        logger.i(
            TAG,
            "已确认开始制造（${if (usedFallback) "兜底点生效，制造面板已消失" else "按钮已消失"}，" +
                "page=${PageDetector.detect(after.result, profile)}）",
        )
        return CraftOutcome.SUCCESS
    }

    /**
     * 「一键补齐」：移植自 DeltaForceSS 的补料流程（GPL-3.0）。
     *
     * 安全约束：
     * - 只在 `autoBuyMaterials=true` 时被调用；
     * - 补料确认页必须同时识别到价格与确认按钮才点击，绝不盲点；
     * - 价格超过 `auction.maxUnitPrice` 时退出；
     * - 补料后仍缺材料或没回到制造面板则放弃本次补产。
     */
    private suspend fun fillMaterials(fillButton: TextElement): CraftOutcome {
        tap(fillButton.rect.center, "一键补齐")
        delay(timing.auctionWaitMs)

        val snap = captureOcr("fill-after-tap")
        val page = PageDetector.detect(snap.result, profile)

        // 情况 1：直接进入交易行，复用已实现的价格校验/买入流程。
        if (page == Page.AUCTION) {
            if (!buyMaterials(snap)) {
                logger.w(TAG, "补料购买失败（交易行）")
                return CraftOutcome.BUY_FAILED
            }
            return finishAfterFill()
        }

        // 情况 2：补料确认页。价格与确认按钮缺一不可。
        val price = findAnyPrice(snap)
        val confirm = findFillConfirmButton(snap, fillButton)
        if (price == null || confirm == null) {
            logger.w(
                TAG,
                "补齐后未识别到价格或确认按钮（page=$page, price=$price），退出",
            )
            back("退出未知补齐页面")
            delay(timing.pollIntervalMs)
            return CraftOutcome.MISSING_MATERIALS
        }
        val maxPrice = profile.auction.maxUnitPrice
        if (maxPrice > 0 && price > maxPrice) {
            logger.w(TAG, "补齐价格 $price 超过上限 $maxPrice，放弃购买")
            back("价格超限，退出补齐")
            delay(timing.pollIntervalMs)
            return CraftOutcome.MISSING_MATERIALS
        }

        tap(confirm.rect.center, "确认补料 ${confirm.text}（价格 $price）")
        delay(timing.auctionWaitMs)
        return finishAfterFill()
    }

    /** 补料后检查制造面板：材料已齐则直接开始制造。 */
    private suspend fun finishAfterFill(): CraftOutcome {
        val snap = captureOcr("fill-done")
        if (!isCraftPanel(snap)) {
            logger.w(
                TAG,
                "补料后未回到制造面板（page=${PageDetector.detect(snap.result, profile)}）",
            )
            return CraftOutcome.MISSING_MATERIALS
        }
        if (findFillButton(snap) != null) {
            logger.w(TAG, "补料后仍缺材料，放弃本次补产")
            return CraftOutcome.MISSING_MATERIALS
        }
        return startCraft(snap)
    }

    /** 全页找第一个可解析的价格（补料确认页不一定有 auction.priceRegion）。 */
    private fun findAnyPrice(snap: OcrSnapshot): Int? =
        snap.result.elements
            .asSequence()
            .mapNotNull { element -> TextParser.parsePrice(element.text, profile.auction) }
            .firstOrNull()

    /**
     * 补料确认按钮；排除「一键补齐」本身，防止价格恰好在场时
     * 把同一个按钮当成确认反复点击。
     */
    private fun findFillConfirmButton(
        snap: OcrSnapshot,
        fillButton: TextElement,
    ): TextElement? {
        val fillTexts = profile.craft.fillMaterialsTexts
            .map { TextParser.normalize(it, profile.text) }
            .filter { it.isNotEmpty() }
            .toSet()
        return PageDetector.findText(
            snap.result,
            profile.craft.fillConfirmTexts,
            profile,
            threshold = 90,
        )?.takeIf { element ->
            val normalized = TextParser.normalize(element.text, profile.text)
            normalized !in fillTexts && element.rect.center != fillButton.rect.center
        }
    }

    /** 自动购买材料（仅在 safety.autoBuyMaterials=true 时调用）。 */
    private suspend fun buyMaterials(initial: OcrSnapshot): Boolean {
        var snap = initial
        val attempts = profile.craft.maxBuyAttempts.coerceAtLeast(1)
        repeat(attempts) {
            val buy = findBuyButton(snap) ?: return true
            tap(buy.rect.center, "购买缺失材料")
            delay(timing.auctionWaitMs)

            var auction = captureOcr("auction")
            if (PageDetector.detect(auction.result, profile) != Page.AUCTION) {
                logger.w(TAG, "点击购买后未进入交易行")
                return false
            }
            val price = findPrice(auction)
            if (price == null) {
                logger.w(TAG, "交易行未识别到价格")
                return false
            }
            val maxPrice = profile.auction.maxUnitPrice
            if (maxPrice > 0 && price > maxPrice) {
                logger.w(TAG, "交易行价格 $price 超过上限 $maxPrice，放弃购买")
                back("价格超限，返回制造弹窗")
                delay(timing.pollIntervalMs)
                return false
            }
            val auctionBuy = PageDetector.findText(
                auction.result,
                profile.auction.buyButtonTexts,
                profile,
                threshold = 85,
            )
            if (auctionBuy == null) {
                logger.w(TAG, "交易行找不到买入按钮")
                return false
            }
            tap(auctionBuy.rect.center, "交易行买入")
            delay(timing.auctionWaitMs)
            back("交易行返回制造弹窗")
            delay(timing.pollIntervalMs)

            auction = captureOcr("craft-dialog-after-buy")
            if (!isStrongCraftDialog(auction)) {
                logger.w(TAG, "交易行返回后不在制造弹窗")
                return false
            }
            snap = auction
        }
        return findBuyButton(snap) == null
    }

    private fun findBuyButton(snap: OcrSnapshot): TextElement? =
        PageDetector.findText(
            snap.result,
            profile.craft.buyButtonTexts,
            profile,
            threshold = 90,
        )

    /** 材料不足时手游显示的「一键补齐」。 */
    private fun findFillButton(snap: OcrSnapshot): TextElement? =
        PageDetector.findText(
            snap.result,
            profile.craft.fillMaterialsTexts,
            profile,
            threshold = 85,
        )

    /** 是否已经处于可操作的制造面板（有「开始制造」或「一键补齐」）。 */
    private fun isCraftPanel(snap: OcrSnapshot): Boolean =
        isStrongCraftDialog(snap) || findFillButton(snap) != null

    /**
     * 右侧详情是否确实显示目标物品。只检查 detailRegion（基准坐标），
     * 防止点击列表行未命中时把上一件已选物品当成目标开始制造。
     * detailRegion=null 时跳过校验（兼容没有独立详情栏的 UI）。
     */
    private fun panelShowsTarget(snap: OcrSnapshot, target: String, threshold: Int): Boolean {
        val baseRegion = profile.craft.detailRegion ?: return true
        val region = Geometry.scaleRect(baseRegion, profile, snap.width)
        val targetNorm = TextParser.normalize(target, profile.text)
        if (targetNorm.isEmpty()) return false
        return snap.result.elements.any { element ->
            if (!region.contains(element.rect.center)) {
                false
            } else {
                val normalized = TextParser.normalize(element.text, profile.text)
                when {
                    normalized == targetNorm -> true
                    targetNorm.length >= 3 &&
                        normalized.length >= targetNorm.length &&
                        normalized.contains(targetNorm) -> true
                    else -> TextParser.similarity(normalized, targetNorm) >= threshold
                }
            }
        }
    }

    /**
     * 「开始制造」优先；泛化的「制造」只接受完全相等，避免匹配到「可制造」。
     */
    private fun findStartButton(snap: OcrSnapshot): TextElement? {
        val normalizedStarts = profile.craft.startButtonTexts
            .associateBy { TextParser.normalize(it, profile.text) }
        val strongTargets = normalizedStarts.keys.filter { it.length >= 3 }
        if (strongTargets.isNotEmpty()) {
            PageDetector.findText(snap.result, strongTargets, profile, threshold = 80)
                ?.let { return it }
        }
        val weakTargets = normalizedStarts.keys.filter { it.length in 1..2 }.toSet()
        if (weakTargets.isEmpty()) return null
        return snap.result.elements.firstOrNull { element ->
            TextParser.normalize(element.text, profile.text) in weakTargets
        }
    }

    private fun findPrice(snap: OcrSnapshot): Int? {
        val region = profile.auction.priceRegion?.let {
            Geometry.scaleRect(it, profile, snap.width)
        }
        return snap.result.elements
            .asSequence()
            .filter { element -> region == null || region.contains(element.rect.center) }
            .mapNotNull { element -> TextParser.parsePrice(element.text, profile.auction) }
            .firstOrNull()
    }

    // ---------------------------------------------------------------------
    // 恢复总览
    // ---------------------------------------------------------------------

    override suspend fun recoverToDashboard(): Boolean {
        // 先看页面再决定动作：主界面底部导航可以进入特勤处；
        // 只有弹窗/部门页才按返回。盲目 back 可能直接退出游戏。
        // 先截一屏；之后复用每次动作后的截图判断，避免同一页面重复 OCR。
        var snap = captureOcr("recover")
        var page = PageDetector.detect(snap.result, profile)
        repeat(2) { attempt ->
            if (page == Page.DASHBOARD) {
                logger.i(TAG, "已在特勤处总览")
                return true
            }

            val lobbyEntry = findLobbyEntryPoint(snap)
            if (lobbyEntry != null && page != Page.CRAFT_DIALOG) {
                logger.i(
                    TAG,
                    "检测到游戏主界面，点击底部「${profile.dashboard.lobbyEntryText}」" +
                        " (${lobbyEntry.x},${lobbyEntry.y}) 进入特勤处",
                )
                tap(lobbyEntry, "进入特勤处")
                delay(timing.collectWaitMs)
                snap = captureOcr("recover-lobby")
                page = PageDetector.detect(snap.result, profile)
                if (page == Page.DASHBOARD) return true
                logger.w(
                    TAG,
                    "点击主界面「${profile.dashboard.lobbyEntryText}」后仍未到总览: $page",
                )
                return false
            }

            val labels = PageDetector.findDepartmentLabels(snap.result, profile)
            val backPoint = profile.dashboard.departmentBackPoint
            if (page == Page.DEPARTMENT && backPoint != null) {
                // 部门页/生产页（实机「前往生产」后的左列表+右详情）不响应
                // 安卓返回键，必须点页面内左上角的返回箭头。生产页需连点两次：
                // 生产页 → 部门升级页 → 特勤处总览。每次点击后重新判页，
                // 两次仍未回到总览就停止，不继续盲点。
                //
                // 不能要求先识别到部门标题：实机 OCR 会把「工作台」识成
                // 「人H作台」之类，标题缺失时仍必须能退回，否则整轮中断。
                val point = PointI(
                    (backPoint.x * snap.width).toInt(),
                    (backPoint.y * snap.height).toInt(),
                )
                logger.i(TAG, "部门页使用页面内返回箭头 (${point.x},${point.y})")
                tap(point, "页面内返回箭头（第 ${attempt + 1} 次）")
            } else if (page == Page.DEPARTMENT) {
                // 显式关闭页面内返回点：保持旧行为（安卓返回键）。
                back("恢复总览（第 ${attempt + 1} 次）")
            } else if (labels.isEmpty() && page != Page.CRAFT_DIALOG && page != Page.AUCTION) {
                // 既不是可识别的游戏页面（无部门标题、不是弹窗/交易行），
                // 也没有识别出主界面导航：先尝试关闭奖励/确认弹窗；
                // 找不到可安全点击的按钮就停止，避免直接退出游戏。
                // 只接受与按钮文字完全相等的元素，避免把包含「取消/确定」
                // 的说明文字当成按钮点掉。
                val dismissTexts = profile.craft.backTexts
                    .map { TextParser.normalize(it, profile.text) }
                    .filter { it.isNotEmpty() }
                    .toSet()
                val dismiss = snap.result.elements.firstOrNull { element ->
                    TextParser.normalize(element.text, profile.text) in dismissTexts
                }
                if (dismiss == null) {
                    logger.w(TAG, "无法确认当前页面（$page），停止恢复，不盲目按返回")
                    return false
                }
                logger.i(TAG, "关闭弹窗「${dismiss.text}」后重新判断页面")
                tap(dismiss.rect.center, "关闭弹窗 ${dismiss.text}")
                delay(timing.collectWaitMs)
                snap = captureOcr("recover-dismiss")
                page = PageDetector.detect(snap.result, profile)
                if (page == Page.DASHBOARD) return true
                return false
            } else {
                back("恢复总览（第 ${attempt + 1} 次）")
            }
            delay(timing.collectWaitMs)
            snap = captureOcr("recover")
            page = PageDetector.detect(snap.result, profile)
            if (page == Page.DASHBOARD) {
                logger.i(TAG, "已恢复特勤处总览")
                return true
            }
        }
        return false
    }

    /**
     * 识别游戏主界面并返回「特勤处」的点击点。
     *
     * 预审（OCR）：底部导航文字命中足够多（有固定点时至少 4/5，退回 OCR
     * 时至少 3/5），说明当前确实是主界面。
     * 点击：优先用 [DashboardProfile.lobbyEntryPoint] 程序固定点。
     *
     * 为什么不用 OCR 包围盒中心：实机底部导航是「… 特战干员 | 特勤处 …」，
     * 两个入口紧挨着。ML Kit 可能把相邻标签合并成一个包围盒，或把中心算偏；
     * 这时点「特勤处」文字中心会落到「特战干员」，进入干员选择界面。
     * 固定点让「识别」和「点击位置」解耦：OCR 只负责确认在主界面。
     *
     * 未配置固定点时退回 OCR：只接受归一化后**完全相等**的「特勤处」，
     * 取最靠下的一个；不再接受「包含」或模糊匹配（合并行会把中心点带偏）。
     */
    private fun findLobbyEntryPoint(snap: OcrSnapshot): PointI? {
        val navTexts = profile.dashboard.lobbyNavTexts
        if (navTexts.size < 3) return null
        val matched = navTexts.count { nav ->
            PageDetector.findText(
                snap.result,
                listOf(nav),
                profile,
                threshold = profile.thresholds.minPageAnchorScore,
            ) != null
        }
        if (matched < 3) return null

        profile.dashboard.lobbyEntryPoint?.let {
            // 固定点不再依赖「特勤处」文字，预审就要更严：主界面 5 个导航字
            // 至少命中 4 个才点，避免在库里/邮件等页面把固定点当成特勤处。
            val required = minOf(4, navTexts.size)
            return if (matched >= required) normalizedPoint(it, snap) else null
        }

        val entryNorm = TextParser.normalize(
            profile.dashboard.lobbyEntryText,
            profile.text,
        )
        if (entryNorm.isEmpty()) return null
        return snap.result.elements
            .asSequence()
            .filter { element ->
                TextParser.normalize(element.text, profile.text) == entryNorm
            }
            .maxByOrNull { it.rect.y }
            ?.rect
            ?.center
    }

    // ---------------------------------------------------------------------
    // 页面判断 / 输入 / OCR 工具
    // ---------------------------------------------------------------------

    /**
     * 是否在部门物品列表：
     * - 判定为 DEPARTMENT；或
     * - 被判成 CRAFT_DIALOG 但只有泛化「材料」文字（实际是列表页）。
     *
     * 不再把单标题 DASHBOARD 当列表：PageDetector 现在只有状态文字
     * 在标题旁时才判 DASHBOARD，那更可能是真正的总览卡片。
     */
    private fun isListPage(snap: OcrSnapshot): Boolean {
        val page = PageDetector.detect(snap.result, profile)
        return when {
            // 总览也是「左卡片 + 右面板」，绝不能当成物品列表。
            page == Page.DASHBOARD -> false
            page == Page.DEPARTMENT -> true
            page == Page.CRAFT_DIALOG && !isStrongCraftDialog(snap) -> true
            // 实机「左列表 + 右详情」：右侧详情默认选中第一件物品时，
            // 底部会直接显示「开始制造」（材料充足）或「一键补齐」。
            // 这仍然是部门列表页，不能因为强按钮就判定「没进入列表」。
            page == Page.CRAFT_DIALOG && hasItemRows(snap) -> true
            else -> false
        }
    }

    /**
     * 左列表区域是否有多行物品文字。
     *
     * 用于区分「部门列表 + 右详情」和真正的制造弹窗：
     * 列表页左侧视口内有数行物品；弹窗通常只有少量说明文字，
     * 且被大块遮罩覆盖。阈值取 3，避免把筛选栏/单个标题当成列表。
     */
    private fun hasItemRows(snap: OcrSnapshot): Boolean {
        val viewport = profile.itemList.viewport
            ?.let { Geometry.scaleRect(it, profile, snap.width) }
            ?: return false
        val rows = snap.result.elements.count { element ->
            val normalized = TextParser.normalize(element.text, profile.text)
            normalized.length >= 2 &&
                !normalized.all { it.isDigit() } &&
                !isUiLabel(normalized) &&
                viewport.contains(element.rect.center)
        }
        return rows >= MIN_LIST_ROWS
    }

    /**
     * 强制造弹窗锚点：「开始制造」或更具体的材料锚点（如「所需材料」）。
     * 不用泛化的「材料」，避免部门列表里的材料 Tab 被当成弹窗。
     */
    private fun isStrongCraftDialog(snap: OcrSnapshot): Boolean {
        val strongStarts = profile.craft.startButtonTexts.filter {
            TextParser.normalize(it, profile.text).length >= 3
        }
        if (strongStarts.isNotEmpty() &&
            PageDetector.findText(snap.result, strongStarts, profile, threshold = 80) != null
        ) {
            return true
        }
        val materialAnchors = profile.craft.materialAnchorTexts
        val specificMaterials = materialAnchors.filter {
            TextParser.normalize(it, profile.text).length >= 3
        }
        if (specificMaterials.isNotEmpty() &&
            PageDetector.findText(snap.result, specificMaterials, profile, threshold = 90) != null
        ) {
            return true
        }
        // 泛化「材料」+ 购买按钮也足够确认是缺料弹窗（列表页通常没有购买按钮）。
        val genericMaterials = materialAnchors.filter {
            TextParser.normalize(it, profile.text).length in 1..2
        }
        return genericMaterials.isNotEmpty() &&
            PageDetector.findText(snap.result, genericMaterials, profile, threshold = 90) != null &&
            findBuyButton(snap) != null
    }

    private suspend fun awaitState(
        hint: String,
        timeoutMs: Long,
        predicate: (OcrSnapshot) -> Boolean,
    ): OcrSnapshot {
        var snap = captureOcr(hint)
        if (predicate(snap)) return snap
        val deadline = nanoTime() + timeoutMs.coerceAtLeast(0) * 1_000_000L
        while (nanoTime() < deadline) {
            delay(timing.pollIntervalMs)
            snap = captureOcr(hint)
            if (predicate(snap)) return snap
        }
        return snap
    }

    private suspend fun captureOcr(hint: String): OcrSnapshot = snapshotter.capture(hint)

    private suspend fun tap(point: PointI, reason: String) {
        // 点击前再校验一次前台，避免从截图到点击之间用户切走。
        foregroundGuard.ensureGameForeground()
        if (safety.dryRun) {
            logger.i(TAG, "[dry-run] tap (${point.x},${point.y}) $reason")
            return
        }
        logger.d(TAG, "tap (${point.x},${point.y}) $reason")
        input.tap(point)
    }

    private suspend fun swipeUp(snap: OcrSnapshot, reason: String) {
        foregroundGuard.ensureGameForeground()
        val scale = Geometry.widthFactor(profile, snap.width)
        val baseFrom = profile.itemList.scrollFrom
        val baseTo = profile.itemList.scrollTo
        val from = if (baseFrom != null) {
            Geometry.scalePoint(baseFrom, profile, snap.width)
        } else {
            PointI((snap.width * 0.5).toInt(), (snap.height * 0.75).toInt())
        }
        val to = if (baseTo != null) {
            Geometry.scalePoint(baseTo, profile, snap.width)
        } else {
            PointI((snap.width * 0.5).toInt(), (snap.height * 0.30).toInt())
        }
        val duration = profile.itemList.scrollDurationMs
        if (safety.dryRun) {
            logger.i(TAG, "[dry-run] swipe (${from.x},${from.y})->(${to.x},${to.y}) $reason")
            return
        }
        logger.d(TAG, "swipe (${from.x},${from.y})->(${to.x},${to.y}) $reason")
        input.swipe(from, to, duration)
        // scale 仅用于日志排查非整数缩放。
        logger.d(TAG, "scroll scale=$scale")
    }

    private suspend fun back(reason: String) {
        foregroundGuard.ensureGameForeground()
        if (safety.dryRun) {
            logger.i(TAG, "[dry-run] back $reason")
            return
        }
        logger.d(TAG, "back $reason")
        input.back()
    }

    /** 归一化坐标 → 当前截图的实际像素；用于程序设定的固定点。 */
    private fun normalizedPoint(point: FloatPoint, snap: OcrSnapshot): PointI =
        Geometry.scaleNormalized(point.x, point.y, snap.width, snap.height)

    private fun statusRadius(screenWidth: Int): Int {
        val scale = Geometry.widthFactor(profile, screenWidth)
        return (profile.dashboard.statusSearchRadiusPx * scale).toInt().coerceAtLeast(1)
    }

    /** 列表视口内最靠上的一行物品文字（归一化后）；没有时返回 null。 */
    private fun topItemText(snap: OcrSnapshot): String? {
        val viewport = profile.itemList.viewport
            ?.let { Geometry.scaleRect(it, profile, snap.width) }
            ?: return null
        return snap.result.elements
            .asSequence()
            .filter { viewport.contains(it.rect.center) }
            .filter { element ->
                val normalized = TextParser.normalize(element.text, profile.text)
                normalized.length >= 2 &&
                    !normalized.all { it.isDigit() } &&
                    !isUiLabel(normalized)
            }
            .minByOrNull { it.rect.y }
            ?.let { TextParser.normalize(it.text, profile.text) }
    }

    private fun textKey(snap: OcrSnapshot): String =
        snap.result.elements
            .sortedWith(compareBy({ it.rect.y }, { it.rect.x }))
            .joinToString(separator = "|") {
                TextParser.normalize(it.text, profile.text)
            }

    private fun minPositive(vararg values: Int): Int =
        values.filter { it > 0 }.minOrNull() ?: 1

    private enum class DetailCheck { MATCH, MISMATCH, UNREADABLE }

    companion object {
        private const val TAG = "Crafting"

        /** 左列表至少几行文字才认为「已经进入部门物品列表」。 */
        private const val MIN_LIST_ROWS = 3

        /** 收取后多久截图检查拒绝 toast；toast 寿命很短，不能等满 collectWaitMs。 */
        private const val COLLECT_VERIFY_DELAY_MS = 500L
    }
}
