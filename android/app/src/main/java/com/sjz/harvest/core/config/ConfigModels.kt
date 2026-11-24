package com.sjz.harvest.core.config

import com.sjz.harvest.core.model.PointI
import com.sjz.harvest.core.model.RectI
import kotlinx.serialization.Serializable

/**
 * 四个配置文件的 schema。
 * 所有字段都有默认值：旧版本配置文件缺字段时仍可加载。
 * 修改字段时必须同步更新：
 * - android/app/src/main/assets/config/ 下的 *.json
 * - docs/02-architecture.md 的配置表
 * - AGENT.md 的决策记录
 */

/** 归一化坐标（0..1），不随分辨率/宽高比变化。 */
@Serializable
data class FloatPoint(val x: Float, val y: Float)

@Serializable
data class AppConfig(
    val version: Int = 1,
    val gamePackage: String = "com.tencent.tmgp.dfm",
    val profileFile: String = "profile.json",
    val catalogFile: String = "catalog.json",
    val planFile: String = "plan.json",
    val input: InputConfig = InputConfig(),
    val capture: CaptureConfig = CaptureConfig(),
    val timing: TimingConfig = TimingConfig(),
    val safety: SafetyConfig = SafetyConfig(),
    val logging: LoggingConfig = LoggingConfig(),
)

@Serializable
data class InputConfig(
    /** auto | root | accessibility | dry_run */
    val backend: String = "auto",
    val tapHoldMs: Long = 60,
    val swipeDurationMs: Long = 500,
    val jitterPx: Int = 3,
    val actionDelayMinMs: Long = 120,
    val actionDelayMaxMs: Long = 350,
)

@Serializable
data class CaptureConfig(
    val minIntervalMs: Long = 400,
    val retries: Int = 3,
    /**
     * 默认关闭：横屏游戏必须保持截图坐标与手势坐标一致。
     * 开启旋转兜底后，部分设备的输入坐标系仍是未旋转的，点击会整体错位。
     */
    val rotateIfPortrait: Boolean = false,
)

@Serializable
data class TimingConfig(
    val pageTimeoutMs: Long = 8_000,
    val pollIntervalMs: Long = 250,
    val collectWaitMs: Long = 1_500,
    val craftWaitMs: Long = 2_000,
    val auctionWaitMs: Long = 1_800,
    val scrollSettleMs: Long = 800,
    val cycleBufferSec: Int = 30,
    val maxScrolls: Int = 40,
    val ocrRetries: Int = 2,
    /** 单部门业务失败后的冷却时间，避免对同一部门立即重试形成空转。 */
    val failureBackoffSec: Int = 300,
)

@Serializable
data class SafetyConfig(
    val dryRun: Boolean = false,
    /** v0.1 默认关闭自动买材料，确认手游交易行流程后再开启 */
    val autoBuyMaterials: Boolean = false,
    val requireGameForeground: Boolean = true,
    val stopOnUnknownPage: Boolean = true,
    /**
     * 轮巡收取：每轮按 [DashboardProfile.sweepOrder] 依次点进四个部门看有没有
     * 完成物品，而不是只依赖状态文字/角标识别。
     *
     * 实机四个部门在总览上的位置固定；OCR 漏掉「完成」文字或角标时，
     * 逐区点进去看一眼是最可靠的收菜方式。制造中的部门不轮巡（一定没有
     * 可收物品），收不到也不算失败、不进入冷却。
     */
    val sweepDepartments: Boolean = true,
    /** 0 = 不限 */
    val maxCycles: Int = 0,
    /** 0 = 不限 */
    val maxRuntimeMinutes: Int = 0,
    val maxActionsPerCycle: Int = 80,
    /**
     * 连续多少个周期四个部门全部 UNKNOWN 后停止。
     * 防止 OCR/UI 失效时无限空转点击；0 = 不限制。
     */
    val maxConsecutiveUnknownCycles: Int = 3,
)

@Serializable
data class LoggingConfig(
    val maxLines: Int = 300,
    val saveFailureScreenshots: Boolean = true,
    /** 实机调参用：把每次观察的 OCR 原文与坐标写进日志。正式使用可关闭。 */
    val dumpOcrText: Boolean = false,
)

@Serializable
data class UiProfile(
    val version: Int = 1,
    val name: String = "default-1080p",
    val baseWidth: Int = 1920,
    val baseHeight: Int = 1080,
    val supportedWidths: List<Int> = listOf(1920, 2560, 3840),
    val dashboard: DashboardProfile = DashboardProfile(),
    val itemList: ItemListProfile = ItemListProfile(),
    val craft: CraftProfile = CraftProfile(),
    val auction: AuctionProfile = AuctionProfile(),
    val text: TextProfile = TextProfile(),
    val thresholds: ThresholdProfile = ThresholdProfile(),
)

/**
 * 总览卡片「可收取」黄色角标的识别参数。
 *
 * 实机 2712×1220 截图：制造完成的卡片右上角会出现约 22×22 的黄色「!」角标，
 * 卡片内只显示物品名和图片，**没有任何「完成/收取」文字**。
 * 因此仅靠 OCR 无法判断 DONE，需要按颜色找角标。
 *
 * 角标必须位于标题锚点的右侧（dx/minDx..maxDx）且垂直偏移不大（maxDy），
 * 这样既能排除左侧「升级制造」栏的提醒角标，也能排除右侧设施列表的角标。
 */
@Serializable
data class DoneBadgeProfile(
    val enabled: Boolean = true,
    /** 角标填充色（实机取样 244,207,103）。卡片金色描边约 (200,168,80)，不应命中。 */
    val r: Int = 244,
    val g: Int = 207,
    val b: Int = 103,
    val tolerance: Int = 28,
    /** 采样步长（像素）；2 在 2712 宽下约 400 次/行，足够且省时。 */
    val sampleStepPx: Int = 2,
    /** 单个色块的最少采样命中数（2px 采样下实机约 100+）。 */
    val minSampleHits: Int = 16,
    /** 色块外接框最大边长（基准 1920），过滤大片的金色图片/描边。 */
    val maxClusterSizePx: Int = 80,
    /** 角标相对标题锚点的水平窗口（基准 1920，按宽度缩放）。 */
    val minDxPx: Int = 90,
    val maxDxPx: Int = 320,
    /** 角标相对标题锚点的垂直窗口（基准 1920，按宽度缩放）。 */
    val maxDyPx: Int = 180,
)

@Serializable
data class DashboardProfile(
    val anchorTexts: List<String> = listOf("技术中心", "工作台", "制药台", "防具台"),
    val departmentLabels: Map<String, String> = mapOf(
        "tech" to "技术中心",
        "work" to "工作台",
        "medical" to "制药台",
        "armor" to "防具台",
    ),
    val statusSearchRadiusPx: Int = 360,
    val idleKeywords: List<String> = listOf("空闲中", "空闲"),
    /** 有「制造中」但没有计时器时，仍应判 CRAFTING，不能回退成 DONE。 */
    val craftingKeywords: List<String> = listOf("制造中", "生产中"),
    /** 仅用于判断「制造已完成」的状态文字，不直接点击。 */
    val doneKeywords: List<String> = listOf("收取", "领取", "已完成", "完成"),
    /** 真正可点击的收取按钮文字；故意不含「完成」，避免误点状态文本。 */
    val collectButtonTexts: List<String> = listOf("收取", "领取", "收获"),
    /**
     * 收取被游戏拒绝时弹出的提示（toast）。
     *
     * 实机：仓库满时点「收获」只弹「仓库空间不足」，物品不会入账，
     * 但按钮仍在页面上。若不识别这条提示，会把「点了没收到」误报成收取成功。
     */
    val collectBlockedTexts: List<String> = listOf(
        "仓库空间不足",
        "仓库已满",
        "背包已满",
        "无法收取",
    ),
    /**
     * 无状态文字时是否按「已完成」处理。
     *
     * 默认关闭：OCR 漏字/卡片渲染延迟都会导致「没有状态文字」，
     * 旧默认 true 会把空闲卡片误判成 DONE，进而点击进入部门。
     * 宁可停在 UNKNOWN，也不误收菜。
     */
    val doneWhenNoStatus: Boolean = false,
    /**
     * 四个部门卡片标题的期望位置（归一化 0..1）。仅在 OCR 漏掉标题、
     * 且无法从其余标题按 2×2 网格推断时兜底使用。
     */
    val departmentAnchorPoints: Map<String, FloatPoint> = mapOf(
        "tech" to FloatPoint(0.195f, 0.186f),
        "work" to FloatPoint(0.338f, 0.186f),
        "medical" to FloatPoint(0.195f, 0.607f),
        "armor" to FloatPoint(0.338f, 0.607f),
    ),
    /**
     * 轮巡收取时依次点开的部门顺序（Dept.key）。实机位置固定，
     * 顺序按用户习惯：技术中心 → 防具台 → 工作台 → 制药台。
     * 缺项会自动补在末尾。
     */
    val sweepOrder: List<String> = listOf("tech", "armor", "work", "medical"),
    /** ocr_button | tap_card_then_ocr | fixed_point */
    val collectStrategy: String = "ocr_button",
    val collectFixedPoint: PointI? = null,
    /**
     * 游戏主界面底部导航文字。用于恢复总览时识别「我们在主界面」，
     * 点击 [lobbyEntryText] 进入特勤处，而不是盲目按返回键退出游戏。
     */
    val lobbyNavTexts: List<String> = listOf("仓库", "部门", "交易行", "特勤处", "改枪台"),
    val lobbyEntryText: String = "特勤处",
    /**
     * 主界面底部「特勤处」的归一化点击位置（0..1，相对实际截图宽度/高度）。
     *
     * 实机底部导航从左到右是：仓库 / 部门 / 交易行 / **特战干员** / 特勤处 / 改枪台，
     * 两个入口紧挨着。OCR 有时会把相邻标签合并成一行，或把「特勤处」的包围盒
     * 算偏，点击中心就会落到「特战干员」并进入干员选择界面。
     *
     * 因此主界面的识别（OCR 预审）与点击位置分开：只有底部导航文字命中足够多时
     * 才认为在主界面，点击则用这个程序设定的固定点，不依赖 OCR 包围盒中心。
     * null = 退回 OCR 匹配到的「特勤处」文字中心（要求文字完全相等）。
     */
    val lobbyEntryPoint: FloatPoint? = FloatPoint(0.37f, 0.882f),
    /**
     * 部门页/生产页左上角的页面内返回箭头（归一化 0..1）。
     *
     * 实机 2712×1220 验证：安卓返回键在「前往生产」后的页面上无效，
     * 必须点页面内箭头 (102,116) 才能退回；生产页需要连点两次
     * （生产页 → 部门升级页 → 特勤处总览）。
     *
     * 只在识别为 DEPARTMENT 且确实看到部门标题时使用，每次点击后
     * 都重新判页；两次仍未回到总览就停止，不继续盲点。
     * null = 不使用页面内返回，退回安卓返回键（旧行为）。
     */
    val departmentBackPoint: FloatPoint? = FloatPoint(0.0376f, 0.0951f),
    /**
     * 制造完成角标识别。实机总览的 DONE 卡片没有状态文字，
     * 只有右上角黄色「!」角标（见 [DoneBadgeProfile]）。
     */
    val doneBadge: DoneBadgeProfile = DoneBadgeProfile(),
)

@Serializable
data class ItemListProfile(
    /**
     * 物品列表匹配区域（基准 1920×1080）。实机为「左列表 + 右详情」，
     * 默认只扫描左侧，避免把右侧详情标题当成物品行。
     * 上边界从 220 调到 140：旧值按 2712 宽缩放后落在第一行文字下方，
     * 第一行会被排除在匹配区外（这就是置顶物品常被报 ITEM_NOT_FOUND 的原因之一）。
     * null = 全屏 OCR（兼容没有独立详情栏的 UI）。
     */
    val viewport: RectI? = RectI(0, 140, 600, 940),
    val scrollFrom: PointI? = null,
    val scrollTo: PointI? = null,
    val scrollDurationMs: Long = 500,
    val maxScrolls: Int = 40,
    /** 连续两次滚动后文字相似度 >= 此值，认为已到底 */
    val bottomSimilarity: Int = 97,
    /**
     * 生产时直接点击列表第一行，不再按计划物品名扫描/滚动列表。
     *
     * 玩家把要制造的物品置顶（游戏内收藏/置顶）后，位置是固定的；
     * 用固定点比依赖物品名 OCR 稳定得多（物品名容易漏字、阈值难调，
     * 且旧版视口会漏掉第一行）。计划里的物品名仍用于右侧详情 OCR 预审。
     */
    val firstRowSelect: Boolean = true,
    /** 第一行的归一化点击位置（0..1，相对实际截图）；按实机 2712×1220 标定。 */
    val firstRowPoint: FloatPoint = FloatPoint(0.17f, 0.239f),
    /**
     * 点击第一行后的 OCR 预审（默认开）：
     * - 右侧详情能读到标题且与计划物品不一致 → 中止，绝不开始制造；
     * - 右侧详情读不到标题 → 记录告警但继续（位置由程序设定，OCR 只作预审）。
     * false = 完全不做 OCR 校验（仅建议在详情区域校准不准时临时关闭）。
     */
    val firstRowVerify: Boolean = true,
)

@Serializable
data class CraftProfile(
    val startButtonTexts: List<String> = listOf("开始制造", "制造"),
    /**
     * 手游的「一键补齐」按钮：出现即表示材料不足。
     * 仅在 `safety.autoBuyMaterials=true` 时才点击（会走补料/交易行，
     * 涉及真实资产）；默认关闭时只返回 MISSING_MATERIALS。
     */
    val fillMaterialsTexts: List<String> = listOf("一键补齐"),
    /**
     * 点击「一键补齐」后，补料确认页上的购买/确认按钮文字。
     * 只有页面同时识别到价格且价格不超过 `auction.maxUnitPrice` 时才会点击。
     */
    val fillConfirmTexts: List<String> = listOf("确认购买", "确认补齐", "购买", "确认", "确定"),
    val buyButtonTexts: List<String> = listOf("购买"),
    /** 弹窗/奖励面板的关闭/确认文字，恢复总览时可安全点击。 */
    val backTexts: List<String> = listOf("取消", "关闭", "返回", "确定", "确认"),
    val materialAnchorTexts: List<String> = listOf("材料", "所需材料"),
    val maxBuyAttempts: Int = 6,
    /**
     * 部门升级页 → 生产列表页的入口按钮。
     *
     * 实机点击总览部门卡片标题进入的不是物品列表，而是「XX升级」页；
     * 需要再点「前往生产」才到「左列表 + 右详情」。
     */
    val productionButtonTexts: List<String> = listOf("前往生产"),
    /**
     * 物品列表右侧详情标题区域（基准 1920×1080）。用于点击物品行后确认
     * 详情确实切换到目标物品，防止点击未命中时误造上一件物品。
     * null = 不校验（兼容没有独立详情栏的 UI）。
     */
    val detailRegion: RectI? = RectI(600, 90, 700, 220),
    /**
     * 「开始制造」按钮的兜底点击点（基准 1920×1080，校准页设置）。
     *
     * 只在已经确认是制造面板（识别到「所需材料」等强锚点）且右侧详情为目标物品、
     * 但 OCR 没识别出「开始制造」文字时使用；null = 不兜底，直接判失败。
     * 点击后必须看到面板消失才算成功，避免盲点造成误操作。
     */
    val startPoint: PointI? = null,
)

@Serializable
data class AuctionProfile(
    val anchorTexts: List<String> = listOf("交易行", "最低价"),
    val priceRegion: RectI? = null,
    val buyButtonTexts: List<String> = listOf("购买", "买入"),
    val priceRegex: String = "\\d[\\d,]{2,}",
    val maxAttempts: Int = 11,
    /** 自动买材料的单价上限；0 = 不限制。超过则放弃购买并保留材料不足状态。 */
    val maxUnitPrice: Int = 0,
)

@Serializable
data class TextProfile(
    val itemNoiseWords: List<String> = listOf(
        "制造", "可制造", "已解锁", "未解锁", "数量", "等级", "详情", "材料",
    ),
    val timerRegex: String = "(\\d{1,3}):(\\d{2}):(\\d{2})",
    val shortTimerRegex: String = "(\\d{1,3}):(\\d{2})",
    val ocrReplacements: Map<String, String> = mapOf("番" to "盔"),
)

@Serializable
data class ThresholdProfile(
    val itemSimilarity: Map<String, Int> = mapOf(
        "tech" to 71,
        "work" to 96,
        "medical" to 80,
        "armor" to 80,
    ),
    val defaultItemSimilarity: Int = 80,
    val minPageAnchorScore: Int = 60,
)

@Serializable
data class Catalog(
    val version: Int = 1,
    val source: String = "",
    val departments: Map<String, DepartmentCatalog> = emptyMap(),
    /** OCR 常见错字归一化，例如 "番" -> "盔" */
    val aliases: Map<String, List<String>> = emptyMap(),
)

@Serializable
data class DepartmentCatalog(
    val categories: Map<String, List<String>> = emptyMap(),
)

@Serializable
data class Plan(
    val version: Int = 1,
    val departments: Map<String, List<PlanEntry>> = emptyMap(),
)

@Serializable
data class PlanEntry(
    val item: String,
    /** -1 = 无限循环；正整数 = 剩余次数 */
    val count: Int = -1,
    val enabled: Boolean = true,
    /** 可选：限定 OCR 匹配词典的分类，降低同名/近似物品误匹配 */
    val category: String? = null,
)
