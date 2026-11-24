package com.sjz.harvest.game

import com.sjz.harvest.core.config.Catalog
import com.sjz.harvest.core.config.CatalogIndex
import com.sjz.harvest.core.config.CraftProfile
import com.sjz.harvest.core.config.DashboardProfile
import com.sjz.harvest.core.config.DepartmentCatalog
import com.sjz.harvest.core.config.ItemListProfile
import com.sjz.harvest.core.config.PlanEntry
import com.sjz.harvest.core.config.SafetyConfig
import com.sjz.harvest.core.config.TimingConfig
import com.sjz.harvest.core.config.UiProfile
import com.sjz.harvest.core.logging.NoopLogger
import com.sjz.harvest.core.model.Dept
import com.sjz.harvest.core.model.OcrResult
import com.sjz.harvest.core.model.PointI
import com.sjz.harvest.core.model.RectI
import com.sjz.harvest.core.model.TextElement
import com.sjz.harvest.input.InputController
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CraftingController] 的 JVM 单测。
 *
 * 画面通过 [FakeScreenOcr] 脚本化返回：每个用例按调用顺序提供
 * 「截屏 + OCR」结果，因此不需要模拟器，也不依赖 Bitmap/Robolectric。
 * 点击由 [FakeInput] 记录，用于断言「该点的点了、不该点的没点」。
 */
class CraftingControllerTest {

    // ------------------------------------------------------------------
    // 假实现
    // ------------------------------------------------------------------

    private class FakeScreenOcr(
        responses: List<OcrSnapshot>,
        private val clockStepNanos: Long = 10_000_000_000L,
    ) : ScreenOcr {
        private val queue = ArrayDeque(responses)
        val hints = mutableListOf<String>()
        var nowNanos = 0L
            private set

        override suspend fun capture(hint: String): OcrSnapshot {
            hints.add(hint)
            nowNanos += clockStepNanos
            assertTrue("第 ${hints.size} 次截屏 ($hint) 没有预置画面", queue.isNotEmpty())
            return queue.removeFirst()
        }
    }

    private class FakeInput : InputController {
        val taps = mutableListOf<PointI>()
        val swipes = mutableListOf<Triple<PointI, PointI, Long>>()
        var backs = 0

        override suspend fun tap(point: PointI) {
            taps.add(point)
        }

        override suspend fun swipe(from: PointI, to: PointI, durationMs: Long) {
            swipes.add(Triple(from, to, durationMs))
        }

        override suspend fun back() {
            backs++
        }

        override fun isAvailable(): Boolean = true

        override fun backendName(): String = "fake"

        override fun close() = Unit
    }

    // ------------------------------------------------------------------
    // 构造工具
    // ------------------------------------------------------------------

    private fun el(text: String, x: Int, y: Int, w: Int = 120, h: Int = 40): TextElement =
        TextElement(text, RectI(x, y, w, h))

    private fun snap(vararg elements: TextElement): OcrSnapshot = OcrSnapshot(
        result = OcrResult(elements.toList(), elements.joinToString(" ") { it.text }),
        width = 1920,
        height = 1080,
    )

    /** 四个部门标题齐全的总览页。 */
    private fun dashboard(vararg extra: TextElement): OcrSnapshot = snap(
        el("技术中心", 250, 180),
        el("工作台", 650, 180),
        el("制药台", 250, 650),
        el("防具台", 650, 650),
        *extra,
    )

    /** 「左列表 + 右详情」的物品列表页（无部门标题、无制造锚点）。 */
    private fun itemListPage(
        target: String? = "红点瞄准镜",
        targetX: Int = 100,
        targetY: Int = 400,
    ): OcrSnapshot {
        val elements = buildList {
            if (target != null) add(el(target, targetX, targetY, 160, 40))
            add(el("可制造", 300, 400))
            add(el("等级", 100, 460))
            add(el("详情", 100, 520))
            add(el("数量", 100, 580))
            add(el("已解锁", 100, 640))
            // 保证没有目标时也有 >=6 段文字，被判为部门列表而不是 UNKNOWN。
            add(el("物品一", 100, 700))
        }
        return snap(*elements.toTypedArray())
    }

    /** 右侧详情显示目标、底部显示「开始制造」的可操作面板。 */
    private fun craftPanel(
        detailTitle: String = "红点瞄准镜",
        startText: String = "开始制造",
    ): OcrSnapshot = snap(
        el(detailTitle, 700, 140, 200, 40),
        el(startText, 900, 900, 200, 50),
    )

    private fun controller(
        ocr: FakeScreenOcr,
        input: FakeInput = FakeInput(),
        // 旧用例验证「按名称扫描列表」；第一行模式有单独的用例。
        profile: UiProfile = UiProfile(itemList = ItemListProfile(firstRowSelect = false)),
        catalog: CatalogIndex = CatalogIndex(Catalog()),
        safety: SafetyConfig = SafetyConfig(),
        timing: TimingConfig = TimingConfig(
            pageTimeoutMs = 8_000,
            pollIntervalMs = 250,
            collectWaitMs = 0,
            craftWaitMs = 0,
            scrollSettleMs = 0,
        ),
    ): CraftingController = CraftingController(
        snapshotter = ocr,
        input = input,
        profile = profile,
        catalog = catalog,
        timing = timing,
        safety = safety,
        logger = NoopLogger,
        nanoTime = { ocr.nowNanos },
    )

    private fun catalogWith(dept: Dept, category: String, vararg items: String): CatalogIndex =
        CatalogIndex(
            Catalog(
                departments = mapOf(
                    dept.key to DepartmentCatalog(
                        categories = mapOf(category to items.toList()),
                    ),
                ),
            ),
        )

    // ------------------------------------------------------------------
    // 收菜
    // ------------------------------------------------------------------

    @Test
    fun `总览页在部门标题附近找到收取按钮并点击`() = runTest {
        val button = el("收取", 300, 260, 100, 40)
        val ocr = FakeScreenOcr(listOf(dashboard(button), dashboard()))
        val input = FakeInput()

        val collected = controller(ocr, input).collect(Dept.TECH)

        assertTrue(collected)
        assertEquals(listOf(button.rect.center), input.taps)
        assertEquals(0, input.backs)
        assertEquals(listOf("collect:tech", "collect-verify:tech"), ocr.hints)
    }

    @Test
    fun `收取后出现仓库空间不足时不报成功`() = runTest {
        val button = el("收取", 300, 260, 100, 40)
        val toast = snap(el("仓库空间不足", 800, 1000, 300, 40))
        val ocr = FakeScreenOcr(listOf(dashboard(button), toast))
        val input = FakeInput()

        val collected = controller(ocr, input).collect(Dept.TECH)

        assertFalse(collected)
        assertEquals(listOf(button.rect.center), input.taps)
        assertEquals(listOf("collect:tech", "collect-verify:tech"), ocr.hints)
    }

    @Test
    fun `总览页没有收取按钮时进入部门详情再找`() = runTest {
        val detailButton = el("领取", 800, 900, 100, 40)
        val detail = snap(
            detailButton,
            el("物品一", 100, 300),
            el("物品二", 100, 360),
            el("物品三", 100, 420),
            el("物品四", 100, 480),
            el("物品五", 100, 540),
        )
        val ocr = FakeScreenOcr(listOf(dashboard(), detail, detail))
        val input = FakeInput()

        val collected = controller(ocr, input).collect(Dept.TECH)

        assertTrue(collected)
        assertEquals(2, input.taps.size)
        // 先点部门标题进入详情，再点详情页的领取按钮。
        assertEquals(PointI(310, 200), input.taps[0])
        assertEquals(detailButton.rect.center, input.taps[1])
    }

    @Test
    fun `页面无法识别时不点击直接返回失败`() = runTest {
        val ocr = FakeScreenOcr(listOf(snap(el("公告", 100, 100))))
        val input = FakeInput()

        val collected = controller(ocr, input).collect(Dept.TECH)

        assertFalse(collected)
        assertTrue(input.taps.isEmpty())
        assertEquals(0, input.backs)
    }

    @Test
    fun `部门页找不到收取按钮且无标题时不盲目点击`() = runTest {
        val ocr = FakeScreenOcr(listOf(itemListPage(target = null)))
        val input = FakeInput()

        val collected = controller(ocr, input).collect(Dept.TECH)

        assertFalse(collected)
        assertTrue(input.taps.isEmpty())
    }

    @Test
    fun `部门标题进入升级页后点前往生产再收获`() = runTest {
        val upgrade = snap(
            el("升级条件", 100, 300),
            el("升级消耗", 100, 360),
            el("消耗", 100, 420),
            el("上交2个ASOS电脑主板", 100, 480),
            el("上交2个HIFI声卡", 100, 540),
            el("前往生产", 900, 900, 200, 50),
        )
        val production = snap(
            el("腾龙突击步枪", 100, 300),
            el("参考价格", 100, 360),
            el("参考价格税后价", 100, 420),
            el("腾龙突击步枪", 300, 480),
            el("品质", 100, 540),
            el("收获", 900, 900, 200, 50),
        )
        val ocr = FakeScreenOcr(listOf(dashboard(), upgrade, production, production))
        val input = FakeInput()

        val collected = controller(ocr, input).collect(Dept.TECH)

        assertTrue(collected)
        assertEquals(
            listOf(
                PointI(310, 200), // 技术中心标题 → 升级页
                PointI(1000, 925), // 前往生产
                PointI(1000, 925), // 收获
            ),
            input.taps,
        )
        assertEquals(
            listOf(
                "collect:tech",
                "collect-detail:tech",
                "collect-detail:tech",
                "collect-verify:tech",
            ),
            ocr.hints,
        )
    }

    // ------------------------------------------------------------------
    // 补产
    // ------------------------------------------------------------------

    @Test
    fun `页面未识别时补产不做任何点击`() = runTest {
        val ocr = FakeScreenOcr(listOf(snap(el("公告", 100, 100))))
        val input = FakeInput()

        val outcome = controller(ocr, input).craft(Dept.TECH, PlanEntry("红点瞄准镜", 1))

        assertEquals(CraftOutcome.PAGE_ERROR, outcome)
        assertTrue(input.taps.isEmpty())
    }

    @Test
    fun `计划分类与 catalog 不一致时拒绝执行`() = runTest {
        val ocr = FakeScreenOcr(emptyList())
        val input = FakeInput()
        val catalog = catalogWith(Dept.TECH, "配件", "红点瞄准镜")

        val outcome = controller(ocr, input, catalog = catalog)
            .craft(Dept.TECH, PlanEntry("红点瞄准镜", 1, category = "弹药"))

        assertEquals(CraftOutcome.PAGE_ERROR, outcome)
        assertTrue(input.taps.isEmpty())
        // 分类校验在任何截屏之前完成。
        assertTrue(ocr.hints.isEmpty())
    }

    @Test
    fun `dry-run 在总览页不模拟进入部门列表`() = runTest {
        val ocr = FakeScreenOcr(listOf(dashboard()))
        val input = FakeInput()

        val outcome = controller(ocr, input, safety = SafetyConfig(dryRun = true))
            .craft(Dept.TECH, PlanEntry("红点瞄准镜", 1))

        assertEquals(CraftOutcome.PAGE_ERROR, outcome)
        assertTrue(input.taps.isEmpty())
    }

    @Test
    fun `从总览进入列表选中目标并开始制造`() = runTest {
        val target = el("红点瞄准镜", 100, 400, 160, 40)
        val ocr = FakeScreenOcr(
            listOf(
                dashboard(),
                itemListPage(),
                craftPanel(),
                itemListPage(),
            ),
        )
        val input = FakeInput()

        val outcome = controller(ocr, input).craft(Dept.TECH, PlanEntry("红点瞄准镜", 1))

        assertEquals(CraftOutcome.SUCCESS, outcome)
        assertEquals(
            listOf(
                PointI(310, 200), // 技术中心标题
                target.rect.center, // 列表中的目标物品
                PointI(1000, 925), // 开始制造
            ),
            input.taps,
        )
        assertEquals(
            listOf("craft-open:tech", "craft-list:tech", "craft-panel:tech", "craft-start-verify"),
            ocr.hints,
        )
        // 开始后不盲目按返回（避免取消确认框）。
        assertEquals(0, input.backs)
    }

    @Test
    fun `从总览先经升级页前往生产再选中目标`() = runTest {
        val target = el("红点瞄准镜", 100, 400, 160, 40)
        val upgrade = snap(
            el("升级条件", 100, 300),
            el("升级消耗", 100, 360),
            el("消耗", 100, 420),
            el("上交2个ASOS电脑主板", 100, 480),
            el("上交2个HIFI声卡", 100, 540),
            el("前往生产", 900, 900, 200, 50),
        )
        val ocr = FakeScreenOcr(
            listOf(
                dashboard(),
                upgrade, // craft-list 第一次：升级页
                itemListPage(), // 点「前往生产」后的物品列表
                craftPanel(),
                itemListPage(),
            ),
        )
        val input = FakeInput()

        val outcome = controller(ocr, input).craft(Dept.TECH, PlanEntry("红点瞄准镜", 1))

        assertEquals(CraftOutcome.SUCCESS, outcome)
        assertEquals(
            listOf(
                PointI(310, 200), // 技术中心标题
                PointI(1000, 925), // 前往生产
                target.rect.center, // 目标物品
                PointI(1000, 925), // 开始制造
            ),
            input.taps,
        )
        assertEquals(
            listOf(
                "craft-open:tech",
                "craft-list:tech",
                "craft-list:tech",
                "craft-panel:tech",
                "craft-start-verify",
            ),
            ocr.hints,
        )
    }

    @Test
    fun `右侧详情未切换到目标物品时拒绝开始制造`() = runTest {
        val target = el("红点瞄准镜", 100, 400, 160, 40)
        val wrongPanel = craftPanel(detailTitle = "其他物品")
        val ocr = FakeScreenOcr(listOf(itemListPage(), wrongPanel, wrongPanel))
        val input = FakeInput()

        val outcome = controller(ocr, input).craft(Dept.TECH, PlanEntry("红点瞄准镜", 1))

        assertEquals(CraftOutcome.PAGE_ERROR, outcome)
        // 只点击了列表行，从未点击「开始制造」。
        assertEquals(listOf(target.rect.center), input.taps)
    }

    // ------------------------------------------------------------------
    // 补产：置顶第一行模式（程序固定点 + OCR 预审）
    // ------------------------------------------------------------------

    @Test
    fun `置顶第一行模式点击固定点并开始制造`() = runTest {
        val ocr = FakeScreenOcr(
            listOf(
                itemListPage(),
                craftPanel(),
                itemListPage(), // 开始制造后按钮消失
            ),
        )
        val input = FakeInput()

        val outcome = controller(ocr, input, profile = UiProfile())
            .craft(Dept.TECH, PlanEntry("红点瞄准镜", 1))

        assertEquals(CraftOutcome.SUCCESS, outcome)
        // 1920×1080 下 (0.17, 0.239) → (326, 258)，不是物品名文字的中心。
        assertEquals(
            listOf(PointI(326, 258), PointI(1000, 925)),
            input.taps,
        )
        // 不再滚动列表、不按名称找物品。
        assertTrue(input.swipes.isEmpty())
        assertEquals(
            listOf("craft-open:tech", "craft-panel:tech", "craft-start-verify"),
            ocr.hints,
        )
    }

    @Test
    fun `第一行右侧详情不是计划物品时拒绝开始制造`() = runTest {
        val wrongPanel = craftPanel(detailTitle = "其他物品")
        val ocr = FakeScreenOcr(listOf(itemListPage(), wrongPanel))
        val input = FakeInput()

        val outcome = controller(ocr, input, profile = UiProfile())
            .craft(Dept.TECH, PlanEntry("红点瞄准镜", 1))

        assertEquals(CraftOutcome.PAGE_ERROR, outcome)
        // 只点了第一行固定点，从未点击「开始制造」。
        assertEquals(listOf(PointI(326, 258)), input.taps)
    }

    @Test
    fun `第一行右侧详情OCR读不到时预审放行并继续制造`() = runTest {
        // 详情区只有制造操作文字，读不到物品标题。
        val panel = snap(
            el("开始制造", 900, 900, 200, 50),
            el("所需材料", 300, 500),
        )
        val ocr = FakeScreenOcr(listOf(itemListPage(), panel, itemListPage()))
        val input = FakeInput()

        val outcome = controller(ocr, input, profile = UiProfile())
            .craft(Dept.TECH, PlanEntry("红点瞄准镜", 1))

        assertEquals(CraftOutcome.SUCCESS, outcome)
        assertEquals(listOf(PointI(326, 258), PointI(1000, 925)), input.taps)
    }

    @Test
    fun `关闭第一行预审后右侧详情不匹配也继续`() = runTest {
        val wrongPanel = craftPanel(detailTitle = "其他物品")
        val ocr = FakeScreenOcr(listOf(itemListPage(), wrongPanel, itemListPage()))
        val input = FakeInput()
        val profile = UiProfile(itemList = ItemListProfile(firstRowVerify = false))

        val outcome = controller(ocr, input, profile = profile)
            .craft(Dept.TECH, PlanEntry("红点瞄准镜", 1))

        assertEquals(CraftOutcome.SUCCESS, outcome)
        assertEquals(listOf(PointI(326, 258), PointI(1000, 925)), input.taps)
    }

    @Test
    fun `第一行模式在非列表页面不点击固定点`() = runTest {
        // 强制造面板但右侧详情不是目标、也不是左列表：不能拿第一行固定点乱点。
        val wrongPanel = craftPanel(detailTitle = "其他物品")
        val ocr = FakeScreenOcr(listOf(wrongPanel))
        val input = FakeInput()

        val outcome = controller(ocr, input, profile = UiProfile())
            .craft(Dept.TECH, PlanEntry("红点瞄准镜", 1))

        assertEquals(CraftOutcome.PAGE_ERROR, outcome)
        assertTrue(input.taps.isEmpty())
    }

    @Test
    fun `第一行模式 dry-run 只记录不点击`() = runTest {
        val ocr = FakeScreenOcr(listOf(itemListPage()))
        val input = FakeInput()

        val outcome = controller(
            ocr,
            input,
            profile = UiProfile(),
            safety = SafetyConfig(dryRun = true),
        ).craft(Dept.TECH, PlanEntry("红点瞄准镜", 1))

        assertEquals(CraftOutcome.SUCCESS, outcome)
        assertTrue(input.taps.isEmpty())
        assertTrue(input.swipes.isEmpty())
    }

    @Test
    fun `目标已在右侧详情选中时直接开始制造`() = runTest {
        val ocr = FakeScreenOcr(listOf(craftPanel(), itemListPage()))
        val input = FakeInput()

        val outcome = controller(ocr, input).craft(Dept.TECH, PlanEntry("红点瞄准镜", 1))

        assertEquals(CraftOutcome.SUCCESS, outcome)
        assertEquals(listOf(PointI(1000, 925)), input.taps)
        assertEquals(listOf("craft-open:tech", "craft-start-verify"), ocr.hints)
    }

    @Test
    fun `OCR 漏掉开始制造时使用校准兜底点并验证面板消失`() = runTest {
        // 面板只有「所需材料」强锚点，没有「开始制造」文字，也没有「一键补齐」。
        val panel = snap(
            el("红点瞄准镜", 700, 140, 200, 40),
            el("所需材料", 300, 500),
        )
        val ocr = FakeScreenOcr(listOf(panel, itemListPage()))
        val input = FakeInput()
        val profile = UiProfile(
            craft = CraftProfile(startPoint = PointI(1000, 925)),
        )

        val outcome = controller(ocr, input, profile = profile)
            .craft(Dept.TECH, PlanEntry("红点瞄准镜", 1))

        assertEquals(CraftOutcome.SUCCESS, outcome)
        assertEquals(listOf(PointI(1000, 925)), input.taps)
        assertEquals(listOf("craft-open:tech", "craft-start-verify"), ocr.hints)
        // 兜底点生效后也不盲目按返回，避免取消确认框。
        assertEquals(0, input.backs)
    }

    @Test
    fun `兜底点点击后面板仍在时判失败并返回`() = runTest {
        val panel = snap(
            el("红点瞄准镜", 700, 140, 200, 40),
            el("所需材料", 300, 500),
        )
        // 两次截图都是同一个面板：兜底点击没有生效。
        val ocr = FakeScreenOcr(listOf(panel, panel))
        val input = FakeInput()
        val profile = UiProfile(
            craft = CraftProfile(startPoint = PointI(1000, 925)),
        )
        // 超时设 0：只做一次验证截图，不必为轮询再预置画面。
        val timing = TimingConfig(
            pageTimeoutMs = 0,
            pollIntervalMs = 0,
            collectWaitMs = 0,
            craftWaitMs = 0,
            auctionWaitMs = 0,
            scrollSettleMs = 0,
        )

        val outcome = controller(ocr, input, profile = profile, timing = timing)
            .craft(Dept.TECH, PlanEntry("红点瞄准镜", 1))

        assertEquals(CraftOutcome.PAGE_ERROR, outcome)
        assertEquals(listOf(PointI(1000, 925)), input.taps)
        assertEquals(1, input.backs)
    }

    @Test
    fun `没有校准兜底点且找不到开始制造时不点击`() = runTest {
        val panel = snap(
            el("红点瞄准镜", 700, 140, 200, 40),
            el("所需材料", 300, 500),
        )
        val ocr = FakeScreenOcr(listOf(panel))
        val input = FakeInput()

        val outcome = controller(ocr, input)
            .craft(Dept.TECH, PlanEntry("红点瞄准镜", 1))

        assertEquals(CraftOutcome.PAGE_ERROR, outcome)
        assertTrue(input.taps.isEmpty())
        assertEquals(1, input.backs)
    }

    @Test
    fun `出现一键补齐时返回缺材料且绝不点击补齐`() = runTest {
        val target = el("红点瞄准镜", 100, 400, 160, 40)
        val fill = el("一键补齐", 900, 900, 200, 50)
        val list = itemListPage()
        val panel = snap(el("红点瞄准镜", 700, 140, 200, 40), fill)
        val ocr = FakeScreenOcr(listOf(list, panel))
        val input = FakeInput()

        val outcome = controller(ocr, input).craft(Dept.TECH, PlanEntry("红点瞄准镜", 1))

        assertEquals(CraftOutcome.MISSING_MATERIALS, outcome)
        assertEquals(listOf(target.rect.center), input.taps)
        assertFalse(fill.rect.center in input.taps)
    }

    @Test
    fun `缺材料弹窗出现购买按钮时默认不购买`() = runTest {
        val buy = el("购买", 800, 700, 120, 50)
        val panel = snap(
            el("红点瞄准镜", 700, 140, 200, 40),
            el("所需材料", 300, 500),
            buy,
        )
        val ocr = FakeScreenOcr(listOf(itemListPage(), panel))
        val input = FakeInput()

        val outcome = controller(ocr, input).craft(Dept.TECH, PlanEntry("红点瞄准镜", 1))

        assertEquals(CraftOutcome.MISSING_MATERIALS, outcome)
        assertFalse(buy.rect.center in input.taps)
        assertEquals(1, input.backs)
    }

    @Test
    fun `列表视口外的相似文字不会被当作目标物品`() = runTest {
        // 目标文字出现在视口 (0..600, 220..1080) 之外。
        val page = itemListPage(targetX = 1400, targetY = 900)
        val ocr = FakeScreenOcr(listOf(page, page))
        val input = FakeInput()

        val outcome = controller(ocr, input).craft(Dept.TECH, PlanEntry("红点瞄准镜", 1))

        assertEquals(CraftOutcome.ITEM_NOT_FOUND, outcome)
        assertTrue(input.taps.isEmpty())
    }

    @Test
    fun `滚动后画面不变判定列表到底`() = runTest {
        val page = itemListPage(target = null)
        val ocr = FakeScreenOcr(listOf(page, page))
        val input = FakeInput()

        val outcome = controller(ocr, input).craft(Dept.TECH, PlanEntry("红点瞄准镜", 1))

        assertEquals(CraftOutcome.ITEM_NOT_FOUND, outcome)
        assertEquals(1, input.swipes.size)
        assertTrue(input.taps.isEmpty())
    }

    @Test
    fun `点击开始制造后按钮仍在时按失败处理并退出`() = runTest {
        val ocr = FakeScreenOcr(
            listOf(
                itemListPage(),
                craftPanel(),
                craftPanel(), // 8 秒后「开始制造」仍未消失
            ),
        )
        val input = FakeInput()

        // 超时设为 0：点击后只再截一屏就判定「按钮仍在」，避免依赖真实时钟。
        val timing = TimingConfig(
            pageTimeoutMs = 0,
            pollIntervalMs = 250,
            craftWaitMs = 0,
            scrollSettleMs = 0,
        )
        val outcome = controller(ocr, input, timing = timing)
            .craft(Dept.TECH, PlanEntry("红点瞄准镜", 1))

        assertEquals(CraftOutcome.PAGE_ERROR, outcome)
        assertEquals(2, input.taps.size) // 列表行 + 开始制造
        assertEquals(1, input.backs)
    }

    @Test
    fun `dry-run 匹配到目标后不验证弹窗也不点击`() = runTest {
        val ocr = FakeScreenOcr(listOf(itemListPage(), craftPanel()))
        val input = FakeInput()

        val outcome = controller(ocr, input, safety = SafetyConfig(dryRun = true))
            .craft(Dept.TECH, PlanEntry("红点瞄准镜", 1))

        assertEquals(CraftOutcome.SUCCESS, outcome)
        assertTrue(input.taps.isEmpty())
        assertEquals(listOf("craft-open:tech", "craft-dry-run:tech"), ocr.hints)
    }

    // ------------------------------------------------------------------
    // 恢复总览
    // ------------------------------------------------------------------

    @Test
    fun `已在总览时不产生任何输入`() = runTest {
        val ocr = FakeScreenOcr(listOf(dashboard()))
        val input = FakeInput()

        assertTrue(controller(ocr, input).recoverToDashboard())
        assertTrue(input.taps.isEmpty())
        assertEquals(0, input.backs)
    }

    @Test
    fun `主界面使用固定点点击底部特勤处进入总览`() = runTest {
        val lobby = snap(
            el("仓库", 100, 1000),
            el("部门", 300, 1000),
            el("交易行", 500, 1000),
            el("特战干员", 700, 1000),
            el("特勤处", 900, 1000),
            el("改枪台", 1100, 1000),
        )
        val ocr = FakeScreenOcr(listOf(lobby, dashboard()))
        val input = FakeInput()

        assertTrue(controller(ocr, input).recoverToDashboard())
        // (0.37, 0.882) × (1920, 1080)；不是「特勤处」OCR 包围盒中心。
        assertEquals(listOf(PointI(710, 953)), input.taps)
        assertEquals(0, input.backs)
    }

    @Test
    fun `主界面特勤处文字识别失败时仍用固定点进入`() = runTest {
        val lobby = snap(
            el("仓库", 100, 1000),
            el("部门", 300, 1000),
            el("交易行", 500, 1000),
            el("特战干员", 700, 1000),
            el("特勒处", 900, 1000),
            el("改枪台", 1100, 1000),
        )
        val ocr = FakeScreenOcr(listOf(lobby, dashboard()))
        val input = FakeInput()

        assertTrue(controller(ocr, input).recoverToDashboard())
        assertEquals(listOf(PointI(710, 953)), input.taps)
    }

    @Test
    fun `未配置主界面固定点时退回精确匹配的特勤处中心`() = runTest {
        val entry = el("特勤处", 700, 1000)
        val lobby = snap(
            el("仓库", 100, 1000),
            el("部门", 300, 1000),
            el("交易行", 500, 1000),
            entry,
            el("改枪台", 900, 1000),
        )
        val ocr = FakeScreenOcr(listOf(lobby, dashboard()))
        val input = FakeInput()
        val profile = UiProfile(
            dashboard = DashboardProfile(lobbyEntryPoint = null),
        )

        assertTrue(controller(ocr, input, profile = profile).recoverToDashboard())
        assertEquals(listOf(entry.rect.center), input.taps)
        assertEquals(0, input.backs)
    }

    @Test
    fun `底部导航被OCR合并成一行时不使用错误中心点`() = runTest {
        // 合并行包含「特勤处」，但中心落在「特战干员」附近；
        // 没有固定点时宁可不点，也不能点进干员选择界面。
        val merged = el("仓库部门交易行特战干员特勤处改枪台", 80, 1000, 760, 40)
        val ocr = FakeScreenOcr(listOf(snap(merged)))
        val input = FakeInput()
        val profile = UiProfile(
            dashboard = DashboardProfile(lobbyEntryPoint = null),
        )

        assertFalse(controller(ocr, input, profile = profile).recoverToDashboard())
        assertTrue(input.taps.isEmpty())
        assertEquals(0, input.backs)
    }

    @Test
    fun `导航文字不足四个时不使用主界面固定点`() = runTest {
        // 非主界面页面也可能出现零星导航词；固定点预审要求至少 4 个。
        val page = snap(
            el("仓库", 100, 1000),
            el("交易行", 500, 1000),
            el("改枪台", 900, 1000),
            el("公告详情", 300, 300),
            el("其他内容", 300, 360),
        )
        val ocr = FakeScreenOcr(listOf(page))
        val input = FakeInput()

        assertFalse(controller(ocr, input).recoverToDashboard())
        assertTrue(input.taps.isEmpty())
        assertEquals(0, input.backs)
    }

    @Test
    fun `无法识别页面且没有安全按钮时不按返回`() = runTest {
        val ocr = FakeScreenOcr(listOf(snap(el("游戏公告", 100, 100))))
        val input = FakeInput()

        assertFalse(controller(ocr, input).recoverToDashboard())
        assertTrue(input.taps.isEmpty())
        assertEquals(0, input.backs)
    }

    @Test
    fun `识别到关闭按钮时点击关闭并回到总览`() = runTest {
        val close = el("关闭", 900, 500, 100, 40)
        val ocr = FakeScreenOcr(listOf(snap(close), dashboard()))
        val input = FakeInput()

        assertTrue(controller(ocr, input).recoverToDashboard())
        assertEquals(listOf(close.rect.center), input.taps)
        assertEquals(0, input.backs)
    }

    /**
     * 移植自 DeltaForceSS 的补料流程：材料不足时点「一键补齐」→
     * 补料确认页同时有价格与确认按钮才点击 → 回到面板后开始制造。
     */
    @Test
    fun `自动补料开启时点一键补齐并在价格可见时确认购买`() = runTest {
        val label = el("技术中心", 250, 180)
        val target = el("红点瞄准镜", 100, 400, 160, 40)
        val fill = el("一键补齐", 900, 900, 200, 50)
        val price = el("4,364", 900, 700, 160, 40)
        val confirm = el("确认", 900, 950, 160, 50)
        val panel = snap(el("红点瞄准镜", 700, 140, 200, 40), fill)
        val confirmPage = snap(price, confirm)
        val filledPanel = snap(
            el("红点瞄准镜", 700, 140, 200, 40),
            el("开始制造", 900, 900, 200, 50),
        )
        val verify = snap(el("红点瞄准镜", 700, 140, 200, 40))
        val ocr = FakeScreenOcr(
            listOf(dashboard(), itemListPage(), panel, confirmPage, filledPanel, verify),
        )
        val input = FakeInput()

        val outcome = controller(ocr, input, safety = SafetyConfig(autoBuyMaterials = true))
            .craft(Dept.TECH, PlanEntry("红点瞄准镜", 1))

        assertEquals(CraftOutcome.SUCCESS, outcome)
        assertEquals(
            listOf(
                label.rect.center,
                target.rect.center,
                fill.rect.center,
                confirm.rect.center,
                filledPanel.result.elements.first { it.text == "开始制造" }.rect.center,
            ),
            input.taps,
        )
    }

    @Test
    fun `补料价格超过上限时不点击确认`() = runTest {
        val fill = el("一键补齐", 900, 900, 200, 50)
        val price = el("4,364", 900, 700, 160, 40)
        val confirm = el("确认", 900, 950, 160, 50)
        val panel = snap(el("红点瞄准镜", 700, 140, 200, 40), fill)
        val confirmPage = snap(price, confirm)
        val ocr = FakeScreenOcr(listOf(dashboard(), itemListPage(), panel, confirmPage))
        val input = FakeInput()
        val base = UiProfile()
        val profile = base.copy(
            auction = base.auction.copy(maxUnitPrice = 1000),
        )

        val outcome = controller(
            ocr,
            input,
            profile = profile,
            safety = SafetyConfig(autoBuyMaterials = true),
        ).craft(Dept.TECH, PlanEntry("红点瞄准镜", 1))

        assertEquals(CraftOutcome.MISSING_MATERIALS, outcome)
        assertFalse(confirm.rect.center in input.taps)
        assertEquals(1, input.backs)
    }

    /**
     * 移植自 DeltaForceSS 的「列表到底」判定：滚动后首行物品名不变即到底。
     * 这里两屏只有首行相同，整屏文字相似度很低，旧逻辑会继续往下滚。
     */
    @Test
    fun `滚动后首行不变时判定列表已到底`() = runTest {
        fun listed(vararg rows: TextElement) = snap(
            *rows,
            el("等级", 100, 600),
            el("详情", 100, 660),
            el("数量", 100, 720),
            el("已解锁", 100, 780),
            el("可制造", 100, 840),
        )
        val first = listed(
            el("物品一", 100, 240),
            el("物品二", 100, 300),
            el("物品三", 100, 360),
            el("物品四", 100, 420),
            el("物品五", 100, 480),
        )
        val second = listed(
            el("物品一", 100, 240),
            el("甲甲甲", 100, 300),
            el("乙乙乙", 100, 360),
            el("丙丙丙", 100, 420),
            el("丁丁丁", 100, 480),
        )
        val ocr = FakeScreenOcr(listOf(dashboard(), first, second))
        val input = FakeInput()

        val outcome = controller(ocr, input).craft(Dept.TECH, PlanEntry("不存在的物品", 1))

        assertEquals(CraftOutcome.ITEM_NOT_FOUND, outcome)
        assertEquals(1, input.swipes.size)
    }

    @Test
    fun `部门页点击页面内返回箭头回到总览`() = runTest {
        val department = snap(
            el("技术中心", 250, 180),
            el("物品一", 100, 300),
            el("物品二", 100, 360),
            el("物品三", 100, 420),
            el("物品四", 100, 480),
            el("物品五", 100, 540),
        )
        val ocr = FakeScreenOcr(listOf(department, dashboard()))
        val input = FakeInput()

        assertTrue(controller(ocr, input).recoverToDashboard())
        // 归一化 (0.0376, 0.0951) → 1920×1080 的 (72,102)，不是安卓返回键。
        assertEquals(listOf(PointI(72, 102)), input.taps)
        assertEquals(0, input.backs)
    }

    @Test
    fun `生产页连续两次页面内返回才回到总览`() = runTest {
        // 实机：生产页 → 部门升级页 → 特勤处总览，需要点到两次箭头。
        val production = snap(
            el("技术中心", 250, 180),
            el("腾龙突击步枪", 100, 300),
            el("参考价格", 100, 360),
            el("品质", 100, 420),
            el("数量", 100, 480),
            el("收获", 900, 900, 200, 50),
        )
        val upgrade = snap(
            el("技术中心", 250, 180),
            el("升级条件", 100, 300),
            el("升级消耗", 100, 360),
            el("上交2个ASOS电脑主板", 100, 480),
            el("上交2个HIFI声卡", 100, 540),
            el("前往生产", 900, 900, 200, 50),
        )
        val ocr = FakeScreenOcr(listOf(production, upgrade, dashboard()))
        val input = FakeInput()
        val controller = controller(ocr, input)

        assertTrue(controller.recoverToDashboard())
        assertEquals(listOf(PointI(72, 102), PointI(72, 102)), input.taps)
        assertEquals(0, input.backs)
    }

    @Test
    fun `部门标题识别失败时仍用页面内返回箭头恢复`() = runTest {
        // 实机 OCR 把「工作台」识成「人H作台」，部门标题找不到，
        // 但页面仍是 DEPARTMENT，必须能退回而不是中断整轮。
        val unreadable = snap(
            el("人H作台", 112, 30, 212, 47),
            el("5.7x28mm HT", 286, 336),
            el("45-70 Govt RN", 297, 504),
            el("破纤柳叶箭失", 284, 680),
            el("9x39mm SP5", 285, 855),
            el("9x19mm AP6.3", 285, 1027),
        )
        val ocr = FakeScreenOcr(listOf(unreadable, dashboard()))
        val input = FakeInput()

        assertTrue(controller(ocr, input).recoverToDashboard())
        assertEquals(listOf(PointI(72, 102)), input.taps)
        assertEquals(0, input.backs)
    }

    @Test
    fun `未配置页面内返回点时退回安卓返回键`() = runTest {
        val department = snap(
            el("技术中心", 250, 180),
            el("物品一", 100, 300),
            el("物品二", 100, 360),
            el("物品三", 100, 420),
            el("物品四", 100, 480),
            el("物品五", 100, 540),
        )
        val ocr = FakeScreenOcr(listOf(department, dashboard()))
        val input = FakeInput()
        val base = UiProfile()
        val profile = base.copy(
            dashboard = base.dashboard.copy(departmentBackPoint = null),
        )

        assertTrue(controller(ocr, input, profile = profile).recoverToDashboard())
        assertEquals(1, input.backs)
        assertTrue(input.taps.isEmpty())
    }
}
