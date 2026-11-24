# AGENT.md — 项目决策与维护入口

> **这是本项目的长期记忆。** 后续任何人（包括 AI）接手时，先读本文件，再按需读
> `docs/`。不要为了理解架构而通读源码。
>
> 维护规则：每次做出影响架构、交互、配置格式、兼容性的决定，必须在这里追加
> 一条决策记录（ADR），并更新「模块地图」或「赛季维护清单」。日期用绝对日期。
>
> **每次做完一件事（改代码/改配置/跑通测试/做完设备验证）都要更新本文件**：
> 至少更新「最后更新」、第 11 节变更记录和对应 ADR；不要等下次接手再补。

- 项目：**三角洲行动手游 · 特勤处自动收菜/补产（Android App）**
- 代号：`sjz`
- 工作目录：`C:\app\soft\youxi\sjz`
- 主交付：`android/` 下的 Kotlin Android App（APK），运行在安卓模拟器/真机
- 次交付：`pc/` 下的 Python + ADB 版本（可选，第二优先级）
- 当前版本：0.1.0（2026-11-22 有未发布改动，见第 11 节）
- 最后更新：2026-11-24
- 公开仓库：<https://github.com/bin666-grace/sjz-harvest>（GPL-3.0，main 分支）
- 架构文档：`docs/02-architecture.md`（契约源）、`docs/05-architecture.html`（单文件离线可读版，同步副本 `dist/architecture.html`）

---

## 1. 一句话定位

在**不读写游戏内存、不修改网络数据、不注入游戏进程**的前提下，通过
「无障碍截屏 → ML Kit OCR 识别 → 无障碍/root 模拟触摸」复刻玩家在手游特勤处的操作：

**收取已完成制造 → 按生产计划补充制造 → 计算下一次检查时间 → 循环**。

它是 UI 层的「高级连点器/宏」，不是内存挂。**仍然违反游戏用户协议，存在封号风险**。
详见 `docs/01-research.md`。不实现任何反检测、反作弊规避。

## 2. 目标环境（已确认）

| 项 | 值 |
|----|----|
| 游戏 | 三角洲行动手游（国服） |
| 包名 | `com.tencent.tmgp.dfm` |
| 设备（实机验证） | Redmi 2312DRA50C / HyperOS V816，横屏 **2712×1220** |
| 设备（目标兼容） | Android 模拟器（MuMu / 雷电 / 夜神等），横屏 |
| 推荐分辨率 | profile 基准 **1920×1080**；实机 2712×1220 按宽度缩放 |
| 后台保活（MIUI） | 把包名加入 `settings put system MILLET_NO_RESTRICT_APP "com.tencent.mm,com.sjz.harvest"`；否则 Game Turbo / PowerKeeper 会把 uid 10360 冻结（`IM Game FZ`），前台服务也不能豁免 |
| 最低 Android | API 30（Android 11），无障碍截屏要求 |
| 构建 | JDK 17+（本机 21）、Gradle 8.11.1、AGP 8.7.3、Kotlin 2.0.21、compileSdk 35 |
| SDK | `C:\app\soft\android-sdk`（platform-tools 37.0.1、android-35、build-tools 34.0.0/35.0.0） |
| Gradle 缓存 | 项目独立 `C:\app\soft\youxi\sjz\.gradle-home`（避开用户全局 init.gradle） |

## 3. 业务闭环（手游版）

```
确认游戏在前台（包名 com.tencent.tmgp.dfm）
  └─ 截屏 + 全屏 OCR，确认在「特勤处总览」页
       ├─ 找到四个部门标题：技术中心 / 工作台 / 制药台 / 防具台
       ├─ 完成判定：卡片头部右侧黄色「!」角标（像素检测）→ DONE
       ├─ 在每个标题附近找状态文字（只归属给位于文字上方的标题，防串行）
       │    ├─ 空闲中 → IDLE
       │    ├─ HH:MM:SS → CRAFTING（记录剩余秒数）
       │    ├─ 收取/领取/完成 → DONE
       │    └─ 无状态文字 → UNKNOWN（不当作 DONE）
       ├─ 轮巡收菜（sweepDepartments，默认开）：
       │    ├─ 按固定顺序 技术中心→防具台→工作台→制药台 逐个点进去
       │    ├─ 当前页有 收取/领取/收获 → 直接点
       │    ├─ 是「XX升级」页 → 点「前往生产」→ 生产页找「收获」→ 点
       │    └─ 没有再返回总览；收不到不算失败、不进入冷却
       ├─ IDLE 且有生产计划 → 制造：
       │    ├─ 点部门标题进入「XX升级」页 → 点「前往生产」→「左列表 + 右详情」
       │    ├─ 第一行模式（默认）：直接点程序设定的第一行固定点
       │    │    （玩家自己把目标物品置顶；不扫描、不滚动、不依赖物品名 OCR）
       │    ├─ 旧扫描模式（firstRowSelect=false）：在左侧 viewport 内按名称找物品行
       │    ├─ OCR 预审右侧详情标题：读到且不是目标 → 中止；读不到 → 告警但继续
       │    ├─ 底部出现「一键补齐」= 材料不足：默认放弃；开启 autoBuyMaterials 后
       │    │   点补齐 → 确认页必须同时有价格与确认按钮 → 价格 ≤ maxUnitPrice 才确认
       │    └─ 找到「开始制造」→ 点击 → 验证按钮消失 → 返回总览
       └─ 重新识别状态，取最小剩余时间 + 30s 缓冲后休眠
```

主界面（大厅）底部导航含「特勤处」；恢复流程用归一化固定点点击它进入特勤处总览，
而不是盲目按返回退出游戏。OCR 只预审「当前在主界面」，不参与决定点击坐标——
否则 OCR 包围盒中心偏移会把点击落到紧邻的「特战干员」并进入干员选择界面。

与 PC 参考实现的关键差异：

- PC 用 **Space 收集**；手游没有键盘，必须点「收取/领取」按钮。
- PC 用固定坐标 + 列表行切分；手游改为 **OCR 全屏文本 + 模糊匹配**，对分辨率/刘海更鲁棒。
- PC 依赖 `UnrealWindow` 前台；Android 用**前台包名**判断游戏是否在前台。
- 手游交易行/材料流程可能与 PC 不同，因此 **v0.1 默认不自动买材料**，先保证收菜+补产安全可跑。

## 4. 架构（Android，依赖只能向下）

```
MainActivity / PlanActivity / CalibrationActivity / SettingsActivity
  └─ AutomationController（单例状态机、生命周期、暂停/停止）
       └─ HarvestFlow（一个业务周期：收菜 → 补产 → 计算睡眠）
            ├─ Observer（OCR 判页 + 四部门状态）
            ├─ CraftingController（点卡片/找物品/买材料/开始制造）
            ├─ Scheduler（下次唤醒时间）
            └─ PlanStore（生产计划持久化）
  └─ service/SjzAccessibilityService（截屏 + 手势 + 前台包名）
       ├─ AccessibilityScreenSource
       ├─ AccessibilityGestureInput / RootShellInput / DryRunInput
       └─ ControlReceiver（通知栏 暂停/继续/停止）
  └─ vision/（ML Kit OCR、Bitmap 预处理、文本解析/相似度）
  └─ core/（模型、几何缩放、配置模型、配置仓库、异常、日志）
```

依赖规则：

- `core/model` **不 import 任何 Android API**，保证可在 JVM 单测。
- `core/config` 只用 kotlinx.serialization，不依赖 Android（`ConfigRepository` 的实现除外）。
- `vision`、`capture`、`input` 只依赖 `core` 和 Android 平台 API。
- `game`、`plan` 只依赖接口（`Observer`/`Crafter`/`PlanRepository`/`InputController`），
  不直接 new 具体实现；由 `AutomationController` 注入。
- 业务层禁止直接调用 `AccessibilityService`；必须走接口，便于测试和切换 root/无障碍后端。

## 5. 重要决策记录（ADR）

| ID | 日期 | 决策 | 理由 / 影响 |
|----|------|------|-------------|
| D001 | 2026-11-18 | 采用**外部 UI 自动化**，不读内存、不改包、不注入进程 | 与市面主流方案一致；技术风险低；仍受行为检测/ToS 约束 |
| D002 | 2026-11-18 | 主交付改为 **Android 原生 App（Kotlin）** | 用户明确要求手机 App；运行在模拟器内，不需要电脑常开 |
| D003 | 2026-11-18 | 截屏用 **AccessibilityService.takeScreenshot**（API 30+） | 无需 MediaProjection 每次授权；`canTakeScreenshot=true`；有 ~333ms 频率限制 |
| D004 | 2026-11-18 | 输入提供 **root shell / 无障碍手势 / dry-run** 三种后端，auto 优先 root | 游戏常用 OpenGL/Vulkan，无障碍手势可能被忽略；模拟器通常可开 root，`su -c input tap` 更可靠 |
| D005 | 2026-11-18 | 识别以 **全屏 OCR + 文本锚点/模糊匹配** 为主，固定坐标为兜底 | 手游 UI 分辨率差异大；ML Kit 返回文字包围盒，可直接得到点击点；比固定像素更鲁棒 |
| D006 | 2026-11-18 | OCR 用 **ML Kit 中文 bundled 模型**（`text-recognition-chinese`） | 离线、不依赖 Google Play 服务、中文识别好；模型随 APK 打包 |
| D007 | 2026-11-18 | v0.1 **不引入 OpenCV / 模板匹配** | OCR + 像素比例已能覆盖按钮/状态；少一个原生依赖，构建更稳；后续需要再加 |
| D008 | 2026-11-18 | 配置用 **JSON assets + 首次启动复制到内部存储** | Android 原生、kotlinx.serialization 直接反序列化；用户可在 App 内改计划，配置文件可导出 |
| D009 | 2026-11-18 | 生产计划 `plan.json` 按部门 FIFO，`count=-1` 无限，成功后消费 | 断点续跑；失败不扣次数；比参考实现的「开造前扣次数」更安全 |
| D010 | 2026-11-18 | 安全默认：前台包名校验、未知页停止、每轮动作上限、v0.1 关闭自动买材料 | 宁可停不可误点；材料购买涉及真实交易行，确认手游流程后再开 |
| D011 | 2026-11-18 | 通知栏作为常驻控制面（暂停/继续/停止），不做悬浮窗 | 避免遮挡游戏截图/误触；不需要悬浮窗权限 |
| D012 | 2026-11-18 | 项目许可证 **GPL-3.0**，NOTICE 注明参考 DeltaForceSS | 参考了其公开业务逻辑/物品数据；保持兼容最稳妥；代码为独立实现 |
| D013 | 2026-11-18 | 测试：纯 Kotlin 单测（解析/几何/队列/状态机）+ 假感知集成测试 | 本机没有游戏/模拟器，无法跑真机 UI 测试；用接口假实现覆盖分支 |
| D014 | 2026-11-18 | v0.1 不做：自动登录、自动出售、自动跑刀、多开、反检测规避、iOS | 控制范围与风险 |
| D015 | 2026-11-18 | 校准不是必需：默认全屏 OCR；仅列表视口/滚动点/兜底点可校准 | 降低使用门槛；赛季大改时再用校准页重标 |
| D016 | 2026-11-18 | 保留 `pc/` Python+ADB 版本作为第二交付 | 用户要求两者都要；PC 版目前只有接口骨架，主交付先保证 Android 可用 |
| D017 | 2026-11-18 | 总览判定要求 **≥2 个部门标题**，或 1 个标题且旁边有空闲/完成/计时器 | 详情页也有部门标题；单标题判总览会让补产停在详情页。测试已按新契约更新 |
| D018 | 2026-11-18 | 「收取/领取」与完成状态关键词分离：新增 `collectButtonTexts` | 原实现把「完成」当按钮文字，OCR 可能点到状态文本。doneKeywords 只用于状态 |
| D019 | 2026-11-18 | 补产必须**验证制造弹窗已关闭**才返回 SUCCESS；失败不扣计划 | 原实现点击后固定返回成功，点击未命中会错误扣计划。dry-run 不验证也不扣 |
| D020 | 2026-11-18 | 部门失败进入 `failureBackoffSec`（默认 300s）冷却；连续未知达上限停止 | 缺材料/物品名不稳时，原实现每 5s 重扫 40 屏，形成空转和多余点击 |
| D021 | 2026-11-18 | OCR 仅对宽 <1600 的截图 2× 放大；`rotateIfPortrait` 默认 false | 大图 2× 内存/耗时高、收益低；旋转截图可能与手势坐标不一致 |
| D022 | 2026-11-18 | 自动买材料新增 `auction.maxUnitPrice`（0=不限）；计划分类必须匹配 catalog | 交易行价格波动，防止开启自动购买后高价成交；防止同名物品串分类 |
| D023 | 2026-11-18 | 按实机截图适配：手游制造是**左列表+右详情同屏**，没有独立弹窗 | 真实 UI 无「开始制造」弹窗；材料不足时按钮为「一键补齐」。新增 `itemList.viewport` 限定列表区、`craft.detailRegion` 校验右侧详情，防止点了列表但详情没切换时误造上一件物品 |
| D024 | 2026-11-18 | v0.1 **绝不点击「一键补齐」** | 该按钮会走补料/交易行，涉及真实资产，具体流程未验证；检测到即返回 MISSING_MATERIALS |
| D025 | 2026-11-18 | 交易行判定要求至少 2 个锚点；主界面底部「交易行」不再误判 | 主界面导航含「交易行」三字，单锚点会让恢复流程把大厅当交易行 |
| D026 | 2026-11-18 | 恢复总览先识别页面：主界面点底部「特勤处」；无部门标题且非弹窗/交易行时**不按返回** | 盲目 back 可能直接退出游戏；主界面截图已确认底部导航 |
| D027 | 2026-11-18 | 新增 `dashboard.craftingKeywords`（制造中/生产中） | 有“制造中”但 OCR 没读到计时器时，不能按“无状态”回退成 DONE 并误收菜 |
| D028 | 2026-11-19 | 状态解析拆为纯 Kotlin `DashboardStatusResolver`：标题中心为锚点，漏标题按 2×2 网格/归一化兜底推断；`doneWhenNoStatus` 默认 **false** | 实机出现工作台标题漏识别、技术中心空闲却判 DONE。固定坐标对 2712×1220 不准；网格推断只需两个标题即可定位其余卡片。无状态文字一律 UNKNOWN，宁可停不可误收 |
| D029 | 2026-11-19 | 新增 `ForegroundGuard`：每次截屏与点击前校验前台包名；周期中切走则**中止本轮并等待最多 10 分钟**，不终止会话 | 曾出现 App 被切到前台后流程仍截屏，把 App 自己的界面当游戏并继续操作。守卫只检查、不盲点；包名偶发为 null 时给 1.5s 确认窗口；等待超时才停止 |
| D030 | 2026-11-19 | 自动化运行在 `specialUse` 前台服务，并在启动时引导关闭电池优化 | MIUI 把后台进程冻结约 3 分钟，恢复后截到错误画面。仅发通知不是前台服务，进程仍会被冻结 |
| D031 | 2026-11-19 | 「左列表+右详情」页只要左侧视口有 ≥3 行文字就按列表页处理；开始制造后不再盲目按返回 | 右侧默认选中材料充足物品时底部直接显示「开始制造」，旧判据会把列表页当弹窗导致补产失败；开始后立即返回可能取消确认框 |
| D032 | 2026-11-20 | `ObserverImpl`/`CraftingController` 的截屏+OCR 封装为 `ScreenOcr` 接口（生产实现 `ScreenOcrSource`），`CraftingController` 另注入 `nanoTime` 时钟；新增 26 个 JVM 单测 | 原实现直接持有 `ScreenSource`+`OcrEngine`，依赖 `Bitmap`，导致感知与 850 行点击流程无法单测。抽出后用脚本化假画面覆盖观察/收菜/补产/恢复分支；生产构造签名保留 `ScreenSource`/`OcrEngine`，行为不变 |
| D033 | 2026-11-20 | 状态文字只归属给**位于文字上方**的部门标题；上方无锚点时才退回全局最近锚点 | 实机 2712×1220 上排卡片的「空闲中」在垂直方向上比下排标题更远，最近锚点会把上排状态串给下排（技术中心空闲被判成制药台）。该修复在实机 dry-run 验证通过 |
| D034 | 2026-11-20 | 完成卡片用**黄色「!」角标**判定 DONE；`AlertBadgeDetector` 纯 Kotlin 像素采样 + 连通域，按「标题右侧 dx/dy 窗口」归属部门；收取导航为 部门标题→「XX升级」→「前往生产」→「收获」 | 实机完成卡片只显示物品图 + 角标，**没有「完成/收取」文字**；旧逻辑只能判 UNKNOWN。角标不能取最近锚点（角标常更靠近相邻卡片标题），必须用右上角窗口筛选；右侧设施列表的角标 dx 过大，左侧升级栏 dx<0，均被排除 |
| D035 | 2026-11-20 | 新增**固定分区轮巡**收取（`safety.sweepDepartments`，默认开）：按 `dashboard.sweepOrder` 依次点四个部门，制造中的部门跳过；收不到不算失败、不入冷却；总览页标题漏识别时用 `departmentAnchorPoints` 固定点兜底（仅 DASHBOARD 页） | 用户明确要求「四个区位置固定，依次点进去，有领取画面就领，再点一下回特勤处」。只依赖状态文字/角标会被 OCR 漏字卡住；轮巡是有界的（每轮最多 4 次），且点击的是实机标定的卡片位置 |
| D036 | 2026-11-20 | **允许移植 DeltaForceSS 的代码**（GPL-3.0）；移植时保留版权头、在 NOTICE.md 注明来源，派生文件继续 GPL-3.0 | 用户明确授权「可以搬 deltaforcess 的代码」。此前 D012 的「代码为独立实现」约束作废；移植代码必须可审查、必须同时服务端游/手游（平台层适配） |
| D037 | 2026-11-20 | 首批移植 DeltaForceSS 的两个逻辑：①「一键补齐」补料确认流程（`autoBuyMaterials=true` 时点击，必须识别到价格 + 确认按钮且价格 ≤ `maxUnitPrice`，补料后仍缺则放弃）；②列表到底判定改为「滚动后首行物品名不变」（阈值取该部门 `itemSimilarity`）并保留整屏相似度兜底 | 参考实现 `main.py` 的 `initalize_preparation/buy_material` 与 `list_page_operation`。D024 的「永不点击一键补齐」仅对默认配置（`autoBuyMaterials=false`）继续有效；补齐流程涉及真实资产，默认仍关闭。列表首行判定比整屏相似度更稳 |
| D038 | 2026-11-21 | 「开始制造」兜底点从 SharedPreferences 迁到 `profile.craft.startPoint` 并由业务使用；只有「已确认制造面板（强锚点）+ 右侧详情为目标物品 + OCR 找不到开始制造文字」三者同时成立才点击，点击后必须看到 `isCraftPanel==false` 才算成功，否则按失败返回 | 校准页此前只把兜底点存本地扩展配置、业务从不用，导致 OCR 漏字时永远不点「开始制造」。盲点风险由三重前置条件和「面板消失」验证控制；没校准就保持原行为（判失败、不点击）。同时主界面在 `safety.dryRun=true` 时开始前弹窗确认，避免测试残留的 dry-run 被误当成「自动化不点击」 |
| D039 | 2026-11-21 | 部门页/生产页恢复总览改用页面内左上角返回箭头 `dashboard.departmentBackPoint`（归一化 0.0376/0.0951）；生产页需连点两次（生产页→升级页→总览）；每次点击后重新判页，两次仍未回总览就停止；`null` 时退回安卓返回键 | 实机「前往生产」后的页面完全不响应 `GLOBAL_ACTION_BACK`，旧恢复逻辑两次 back 后报「无法恢复总览」并中止整轮。页面内箭头已在实机验证；不要求先识别到部门标题——OCR 会把「工作台」识成「人H作台」，按标题门控会导致又退不出去 |
| D040 | 2026-11-21 | 收取后先等 500ms 截图识别拒绝提示（`dashboard.collectBlockedTexts`），命中即返回 false 并写日志；未命中再补足 `collectWaitMs` | 实机仓库满时点「收获」只弹「仓库空间不足」toast，按钮仍在、物品不入账。toast 寿命短，等满 `collectWaitMs`（1.5s）再截图会漏掉，导致误报「已收取」。这是账号状态，不是点击失败，App 只如实报告 |
| D041 | 2026-11-22 | 恢复主界面进入特勤处改为**归一化固定点** `dashboard.lobbyEntryPoint`（默认 0.37/0.882）；OCR 只做预审：底部导航 5 个字至少命中 4 个才点。未配置固定点时退回 **完全相等** 的「特勤处」文字中心，不再接受包含/模糊匹配 | 实机底部导航「特战干员」与「特勤处」紧挨着。ML Kit 可能把相邻标签合并成一个包围盒，或把「特勤处」中心算偏，点击后进入**干员选择界面**。把「识别页面」和「点击坐标」解耦，固定点不随 OCR 包围盒漂移；预审更严可避免把仓库/邮件页当成主界面 |
| D042 | 2026-11-22 | 补产默认改为**第一行模式**：`itemList.firstRowSelect=true`，直接点归一化固定点 `itemList.firstRowPoint`（0.17/0.239），不按物品名扫描/滚动；计划物品名只用于右侧详情 OCR 预审（`firstRowVerify`：能读到且不一致→中止，读不到→告警但继续）。`itemList.viewport` 上边界 220→140 | 用户明确要求「要生产的东西只要是列表第一行就行，我自己来置顶，这里不需要 OCR，程序设定就行，OCR 可以作预审」。旧扫描模式依赖物品名 OCR，且 2712×1220 下 viewport 上边界缩放后落在第一行文字下方，置顶物品反而被排除、报 ITEM_NOT_FOUND。固定点**按实际宽高归一化**（`Geometry.scaleNormalized`），避免非 16:9 设备 y 方向偏大 25% |

## 6. 模块地图（读代码前先看这里）

| 路径（`android/app/src/main/java/com/sjz/harvest/`） | 职责 | 关键入口 |
|---|---|---|
| `core/model/Models.kt` | Dept/Page/RectI/TextElement/DeptStatus/Observation/CycleResult | `Dept`, `Page`, `RectI` |
| `core/model/Errors.kt` | 可预期异常层级 | `SjzException.PageNotRecognized` |
| `core/model/Geometry.kt` | 基准坐标 → 实际屏幕缩放 | `Geometry.scaleRect` |
| `core/config/ConfigModels.kt` | 四个 JSON 的 schema（全字段带默认值） | `AppConfig`, `UiProfile`, `Catalog`, `Plan` |
| `core/config/ConfigRepository.kt` | 配置加载接口 | `loadProfile()` |
| `core/config/CatalogIndex.kt` | 物品词典查询 | `resolve`, `candidates` |
| `core/config/AssetConfigRepository.kt` | assets→内部存储复制、原子写、恢复默认 | `loadAppConfig`, `savePlan` |
| `core/config/ServiceLocator.kt` | 配置入口（不缓存，设置保存后立即生效） | `appConfig()`, `profile()` |
| `core/logging/LogBus.kt` | Logcat + UI 环形日志缓冲（StateFlow） | `LogBus.lines` |
| `capture/ScreenSource.kt` | 截屏接口 + Frame | `ScreenSource.capture` |
| `input/InputController.kt` | 点击/滑动/返回接口 | `tap`, `swipe`, `back` |
| `vision/OcrEngine.kt` | OCR 接口 | `recognize` |
| `vision/MlKitOcrEngine.kt` | ML Kit 中文 OCR + ROI 放大 | `MlKitOcrEngine` |
| `vision/BitmapOps.kt` | 裁剪/放大/灰度/白像素比例 | `whiteRatio`, `upscale` |
| `vision/TextParser.kt` | 计时器/价格/空闲/物品名相似度 | `parseTimer`, `bestMatch` |
| `vision/PageDetector.kt` | 页面判定 + 锚点查找 | `detect`, `findText` |
| `vision/DashboardStatusResolver.kt` | 四部门锚点推断 + 状态/黄色角标解析（纯 Kotlin） | `resolve`, `inferAnchors` |
| `vision/AlertBadgeDetector.kt` | 总览卡片黄色「!」角标检测（像素采样 + 连通域，纯 Kotlin） | `detect` |
| `game/Contracts.kt` | Observer/Crafter/CraftOutcome/ForegroundGuard | `Crafter.craft` |
| `game/ObserverImpl.kt` | 截屏/OCR/判页 + 调用状态解析 | `observe()` |
| `game/CraftingController.kt` | 收菜/轮巡导航（升级页→前往生产）/找物品/买材料/开始制造 | `collect`, `craft` |
| `game/ScreenOcr.kt` | 截屏+OCR 封装，返回不含 Bitmap 的 `OcrSnapshot`（便于单测） | `ScreenOcrSource` |
| `game/HarvestFlow.kt` | 单周期编排（轮巡收菜/补产/冷却/恢复） | `runCycle` |
| `plan/PlanStore.kt` | 计划持久化/消费 | `next`, `consume` |
| `plan/Scheduler.kt` | 下次唤醒时间 | `nextWakeSec` |
| `service/SjzAccessibilityService.kt` | 无障碍服务、截屏、手势、前台包名 | `instance` |
| `service/AutomationController.kt` | 运行状态机、协程、通知、热键动作 | `start/pause/resume/stop` |
| `service/AutomationForegroundService.kt` | 会话前台服务（防进程冻结），通知与 Controller 共用 ID | `start`, `stop` |
| `service/ControlReceiver.kt` | 通知按钮广播 | `ACTION_PAUSE` 等 |
| `ui/MainActivity.kt` | 主界面、状态、日志、入口 | `MainActivity` |
| `ui/PlanActivity.kt` | 生产计划编辑 | `PlanActivity` |
| `ui/CalibrationActivity.kt` | 截图后拖框校准 | `CalibrationActivity` |
| `ui/SettingsActivity.kt` | 输入后端/dry-run/时序 | `SettingsActivity` |
| `tools/Diagnostics.kt` | 自检：无障碍/截屏/OCR/输入/前台 | `runDiagnostics` |

## 7. 配置文件速查

| 文件（`android/app/src/main/assets/config/`） | 作用 | 什么时候改 |
|---|---|---|
| `app_config.json` | 包名、输入后端、截屏间隔、时序、安全、日志 | 换模拟器/调速度/开 dry-run/开自动买材料 |
| `profile.json` | 基准分辨率、OCR 锚点、列表视口/滚动点、按钮文字、阈值 | 赛季 UI 大改 / OCR 误判 / 换分辨率 |
| `catalog.json` | 部门→分类→物品名 + OCR 别名 | 新赛季新增物品 |
| `plan.json` | 每个部门造什么、造几个 | 玩家生产计划（也可在 App 内编辑） |

首次启动时 assets 复制到 `filesDir/config/`；之后读内部存储。恢复默认在设置页。
坐标基准 1920×1080，按宽度等比缩放；模拟器建议直接设 1920×1080 横屏。

v0.1 审查后新增字段（缺省即兼容旧配置）：

| 字段 | 默认 | 作用 |
|---|---|---|
| `timing.failureBackoffSec` | 300 | 单部门失败冷却秒数；0=不冷却 |
| `safety.maxConsecutiveUnknownCycles` | 3 | 连续全未知周期上限；0=不限制 |
| `dashboard.collectButtonTexts` | `["收取","领取","收获"]` | 仅用于点击的收取按钮文字（实机生产页是「收获」） |
| `dashboard.collectBlockedTexts` | 仓库空间不足/仓库已满/背包已满/无法收取 | 点击收取后 500ms 截图识别游戏拒绝；命中返回失败，不误报成功 |
| `dashboard.departmentBackPoint` | `{"x":0.0376,"y":0.0951}` | 部门页/生产页左上角页面内返回箭头（归一化）；安卓返回键在这些页面无效；null=退回安卓返回键 |
| `dashboard.lobbyEntryPoint` | `{"x":0.37,"y":0.882}` | 主界面底部「特勤处」固定点击点（归一化）；OCR 只预审在主界面；null=退回完全相等的文字中心 |
| `itemList.firstRowSelect` | true | 生产直接点列表第一行固定点，不按物品名扫描/滚动（玩家自行置顶目标） |
| `itemList.firstRowPoint` | `{"x":0.17,"y":0.239}` | 第一行归一化点击点；按实机 2712×1220 标定 |
| `itemList.firstRowVerify` | true | 点第一行后 OCR 预审右侧详情：读到且不匹配→中止；读不到→告警但继续 |
| `dashboard.doneBadge` | 启用，RGB(244,207,103)，容差 28 | 完成卡片右上角黄色「!」角标检测与部门归属 |
| `dashboard.sweepOrder` | tech→armor→work→medical | 轮巡点开四个部门的顺序；缺项自动补在末尾 |
| `safety.sweepDepartments` | true | 固定分区轮巡收取；制造中的部门跳过，收不到不冷却 |
| `craft.productionButtonTexts` | `["前往生产"]` | 「XX升级」页进入生产页的按钮 |
| `dashboard.craftingKeywords` | `["制造中","生产中"]` | 有“制造中”但无计时器时仍判 CRAFTING |
| `dashboard.doneWhenNoStatus` | false | 无状态文字按完成处理；默认关闭，宁可 UNKNOWN |
| `dashboard.departmentAnchorPoints` | 四卡片标题归一化中心 | 标题漏识别且网格推断失败时的兜底 |
| `logging.dumpOcrText` | false | 调参：每次观察输出 OCR 原文+包围盒（设置页可开） |
| `dashboard.lobbyNavTexts` | 仓库/部门/交易行/特勤处/改枪台 | 主界面底部导航识别 |
| `dashboard.lobbyEntryText` | 特勤处 | 从主界面进入特勤处的点击文字 |
| `craft.fillMaterialsTexts` | `["一键补齐"]` | 材料不足标志；`autoBuyMaterials=false` 时永不点击 |
| `craft.fillConfirmTexts` | 确认购买/确认补齐/购买/确认/确定 | 补齐确认页按钮；必须同时识别到价格才点击 |
| `craft.detailRegion` | 基准坐标矩形 | 右侧详情标题区域，用于校验选中目标 |
| `craft.startPoint` | null | 「开始制造」校准兜底点；OCR 漏字且确认面板时使用，点击后须见面板消失 |
| `itemList.viewport` | `{0,140,600,940}` | 限制物品匹配范围，避开右侧详情；上边界 140 才包含第一行 |
| `auction.maxUnitPrice` | 0 | 自动买材料单价上限；0=不限 |
| `capture.rotateIfPortrait` | false | 竖屏截图旋转兜底（默认关） |

## 8. 运行 / 构建

```powershell
# 构建 debug APK（必须用项目独立 GRADLE_USER_HOME，避开全局 init.gradle 冲突）
cd C:\app\soft\youxi\sjz\android
$env:GRADLE_USER_HOME = "C:\app\soft\youxi\sjz\.gradle-home"
C:\app\soft\gradle-8.11.1\bin\gradle.bat :app:assembleDebug :app:testDebugUnitTest

# 安装到已连接的模拟器（先确认 adb devices）
C:\app\soft\android-sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk

# 启动
"C:\app\soft\android-sdk\platform-tools\adb.exe" shell am start -n com.sjz.harvest/.ui.MainActivity
```

构建产物：`android/app/build/outputs/apk/debug/app-debug.apk`（v0.1.0 约 61MB）。
最新交付：`dist/sjz-harvest-0.1.0-debug-20261122.apk`（附同名 `.sha256`；
sha256 = `9db05df07345fa77c01e55c44084d93a55f93a797773dbb755a59314d115d89e`）。
`dist/README.txt` 已更新安装命令与实机说明；旧的 20261121 包保留备查。
架构设计 HTML：`docs/05-architecture.html`，同步到 `dist/architecture.html`（单文件、无 CDN）。
验证状态（2026-11-18）：assembleDebug 成功、67 个 JVM 单测全过、lint 0 error。
验证状态（2026-11-19）：assembleDebug 成功、76 个 JVM 单测全过（新增
`DashboardStatusResolverTest` 8 个 + 前台守卫 1 个）、lint 0 error；
尚未做模拟器端到端验收。
验证状态（2026-11-20）：assembleDebug 成功、102 个 JVM 单测全过（新增
`CraftingControllerTest` 21 个 + `ObserverImplTest` 5 个，覆盖观察/收菜/补产/
列表扫描/缺材料/恢复总览）、lint 0 error（38 warning）；仍未做模拟器端到端验收。
验证状态（2026-11-20 晚）：assembleDebug 成功、**119 个 JVM 单测全过**
（新增黄色角标检测 5 个、角标归属 3 个、轮巡 2 个、前往生产 2 个、
补齐流程 2 个、首行到底判定 1 个）。
验证状态（2026-11-22）：`:app:testDebugUnitTest` **137/137 通过**、
`:app:assembleDebug` 成功（61,284,984 字节）。新增用例覆盖：主界面固定点/
特勤处文字识别失败仍按固定点/合并导航行不点/导航不足 4 字不点（4 个）、
主界面固定点/文字识别失败仍按固定点/合并导航行不点/导航不足 4 字不点/无固定点退回精确中心（5 个）、
置顶第一行选择/详情不匹配中止/详情漏读放行/关闭预审/非列表页不点/dry-run（6 个）、
`Geometry.scaleNormalized`（1 个），共新增 12 个。
设备：Redmi 真机已安装；dry-run 曾因 MIUI 无障碍服务未连接失败，设备重启后未复测。
2026-11-22 改动**尚未做设备验证**（当前 adb 无设备）。

首次使用顺序：

1. 模拟器设置：横屏 1920×1080；开启 root（推荐，输入更可靠）；安装游戏并登录。
   停在主界面即可，脚本会点底部「特勤处」进入总览（也可手动进入特勤处总览）；
2. 安装 App → 打开无障碍服务（系统设置里授权）→ 通知权限；
3. App 内「自检」：截屏/OCR/输入后端/前台包名；
4. 编辑生产计划（物品从 catalog 选择，或导入 plan.json）；
5. 先开 **dry-run** 跑一轮，看日志识别是否正确；补产验证需手动进入目标部门列表
   （dry-run 不点击，无法自动导航）；
6. 关闭 dry-run，点开始；游戏切到前台，脚本会按周期运行。

## 9. 赛季更新 / 故障排查清单

赛季 UI 更新后：

1. 打开 App → 自检 → 截一张总览图（保存到 `filesDir/debug/`）；
2. 若 OCR 找不到部门/按钮：进「校准」，重截当前页，检查/调整 profile 中的文字锚点；
3. 新物品：更新 `catalog.json`（参考 DeltaForceSS 的物品数据手工补录；PC 导入工具尚未实现）；
4. 列表/第一行点错物品：默认第一行模式先确认目标已置顶，按实机截图调
   `itemList.firstRowPoint`；旧扫描模式才需要校准视口、滚动点或 `bottomSimilarity`；
   恢复时点进「干员选择」：调 `dashboard.lobbyEntryPoint`（或设 null 退回精确文字中心）；
5. 点击无反应：自检里切换输入后端（root ↔ accessibility），确认模拟器 root 开关；
6. 交易行流程变化：保持 `autoBuyMaterials=false`，先手动备材料；
7. MIUI/HyperOS 进程被冻结：把包名加入 `MILLET_NO_RESTRICT_APP`（见第 2 节）；
8. MIUI 显示「无障碍已开启（未连接）」：`am force-stop` 后 MIUI 会把服务从
   `enabled_accessibility_services` 移除；重新 `settings put` 后仍不连接时，
   到系统设置→无障碍→已下载的应用 里把「特勤处收菜 · 自动化」开关关一次再打开，
   必要时重启设备。以 App 内显示「已连接」为准，不能只看系统设置里的开关。

**未知页面、前台不是游戏、OCR 找不到关键按钮**时脚本会停止并在通知/日志说明，
不会盲点。恢复：人工回到特勤处总览 → App 内继续。

## 10. 已知限制与风险

- 无障碍手势在部分游戏/模拟器上会被忽略；root 模式更可靠，但需要模拟器开启 root。
- 游戏可能启用 `FLAG_SECURE` 导致截屏全黑；Android 不提供合法绕过，App 会报错停止。
- 点击注入可能被游戏行为检测识别；本项目不做任何规避。使用风险自负。
- 手游 UI 与 PC 不同，v0.1 的默认文字锚点（技术中心/收取/开始制造等）需要按实际客户端验证；
  校准页用于修正。
- 自动买材料默认关闭。开启后已实现「一键补齐」确认流程（价格 + 确认按钮 +
  `maxUnitPrice` 三重校验），但手游补料确认页尚未实机验证；**默认配置下永不点击**。
  交易行买入逻辑保留并同样受 `maxUnitPrice` 限制。
- 实机已确认：主界面底部导航、特勤处总览四卡片、完成卡片只有物品图 + 右上角
  黄色「!」角标（无「完成/收取」文字）、部门标题 → 「XX升级」页 → 「前往生产」→
  生产页「收获」、材料不足时的「一键补齐」。真实点击、页面恢复（页面内返回箭头）、
  四部门轮巡已在 2026-11-21 实机跑通。材料充足时的「开始制造」仍未实机验证
  （当前三个计划物品都不在对应部门列表中，全部 ITEM_NOT_FOUND）。
- 实机 2026-11-21：点击「收获」时游戏提示 **「仓库空间不足」**，物品不会入账。
  App 已在收取后 500ms 读取该 toast 并返回失败（D040）；清理仓库后才能完成真正的
  收取验收。仓库满也可能阻止后续制造产出，属于账号状态而非点击故障。
- 2026-11-22 起补产默认走**第一行模式**：需要玩家把目标物品在游戏里置顶/收藏为
  列表第一行。固定点 `itemList.firstRowPoint`（0.17/0.239）与主界面
  `dashboard.lobbyEntryPoint`（0.37/0.882）均按实机 2712×1220 截图标定，
  换分辨率/UI 后要重新标定（配置为归一化坐标，宽高比不同的设备会自动按宽高换算）。
  这两条路径尚未在设备上验证。
- 第一行模式的 OCR 预审是「读到标题才拦」：详情标题完全读不出来时会告警并继续开始制造，
  因此务必确认目标确实置顶在第一行；要更严可先关闭 `firstRowSelect` 用旧的名称扫描模式。
- 旧扫描模式（`firstRowSelect=false`）下生产计划必须与当前部门列表里真实存在的物品一致。实机当前列表：
  防具台 `DAR突击手胸挂/黑鹿野战胸挂/飓风战术胸挂/GN重型夜视头盔/DICH-1战术头盔`，
  工作台 `5.7x28mm HT/45-70 Govt RN/破纤柳叶箭矢/9x39mm SP5/9x19mm AP6.3`，
  制药台 `体能激活针/OE2战斗兴奋剂/感知激活针/M1肌肉内强化针/去甲肾上腺素`。
  plan.json 里的「精英防弹背心 / 9x39mm BP / 战地医疗箱」均不在列表中。
- 截图分辨率为 **2712×1220**，不是 16:9；`itemList.viewport` 与
  `craft.detailRegion` 已按该布局标定（基准 1920×1080 按宽度缩放）。
  换分辨率/UI 后需要重新校准。
- 点击部门标题进入的是「XX升级」页，需要再点「前往生产」才到列表；已按实机实现。
  右侧详情是否即时刷新仍需真机验证；详情不匹配时代码选择停止而不是继续制造。
- MIUI/HyperOS 的 Game Turbo 会冻结后台进程，且 `am force-stop` 后无障碍服务会被
  移除；换机/重启后要按第 9 节第 7、8 条恢复，否则自动化会在启动后 2 秒内报
  「无障碍服务未连接」。
- 截图全黑检测为 32×32 采样，极暗画面存在误报可能。
- 「开始制造」兜底点已接入业务（D038），但尚未在实机确认坐标；需要先在
  校准页选点并观察一次「开始制造」是否真正生效。未校准时不点，判失败。
  2026-11-21 实机没有走到该分支（计划物品均未找到，OCR 也未出现制造面板）。
- `safety.dryRun` 会持久化在 `filesDir/config/app_config.json`，升级 APK 不会覆盖；
  测试残留的 dry-run 会让自动化「在跑但什么都不点」。主界面现在开始前会弹窗确认，
  输入后端文案也会显示 `dry-run（不点击）`。
- `pc/` 目前只有接口骨架，未实现 ADB 截屏/点击与 OCR 主循环。

## 11. 变更记录

### 2026-11-24 / 开源发布
- **创建公开仓库**：<https://github.com/bin666-grace/sjz-harvest>（main，GPL-3.0）。
  首次提交 113 个文件，19,402 行；提交作者邮箱使用 GitHub 匿名地址
  `300913100+bin666-grace@users.noreply.github.com`。
- `.gitignore` 新增 `.debug/`、`pi/`、`devscreen*.png`：调试截图含设备信息与
  游戏 UID，不入库（`.venv/`、`.gradle-home/`、`dist/`、`*.apk`、`*.log` 原本已忽略）。
- README 顶部新增 GPL/Android/Kotlin/ML Kit 徽章与英文简介，方便仓库首页展示。
- 仓库描述与 topics：android、kotlin、automation、ocr、ml-kit、
  accessibility-service、game-automation、delta-force、macro。
- 注意：**不要**把 `pi/` 里的游戏截图原样上传（底部含游戏 UID 与资产信息）。

### 2026-11-22 / 未发布（程序固定点：主界面特勤处 + 生产第一行，OCR 仅预审）
- **修复误入干员选择界面**：主界面底部「特战干员 | 特勤处」相邻，原实现点击
  OCR 匹配到的「特勤处」包围盒中心；相邻标签被合并或中心算偏时会落到「特战干员」。
  新增 `dashboard.lobbyEntryPoint`（归一化 0.37/0.882）作为固定点击点；
  OCR 只预审主界面（5 个导航字至少命中 4 个）。未配置固定点时退回**完全相等**
  的「特勤处」文字中心，合并行不再命中（D041）。
- **生产改为第一行模式**：新增 `itemList.firstRowSelect`（默认 true）、
  `itemList.firstRowPoint`（0.17/0.239）、`itemList.firstRowVerify`（默认 true）。
  进入部门列表后直接点第一行固定点，不扫描、不滚动、不按物品名 OCR；
  计划物品名只用于右侧详情预审：读到且不匹配→中止，读不到→告警但继续（D042）。
- `itemList.viewport` 上边界 220→140：旧值按 2712 宽缩放后落在第一行文字下方，
  置顶物品会被排除在匹配区外（ITEM_NOT_FOUND 的原因之一）。
- `Geometry.scaleNormalized(x, y, width, height)`：固定点按实际宽高分别换算，
  避免非 16:9 设备按宽度等比缩放导致 y 偏大 25%。
- 验证：`:app:testDebugUnitTest` **137/137 通过**；`:app:assembleDebug` 成功，
  APK 61,284,984 字节。新增 12 个 JVM 用例（见第 8 节）。
- 文档：`docs/02-architecture.md` 配置表、`docs/04-operations.md` 实机 UI 要点与排障。
- 未做设备验证（当前 adb 无设备）。

### 2026-11-21 / 未发布（实机真实点击闭环验证）
- 实机修复：无障碍手势坐标系与游戏横屏不一致。`SjzAccessibilityService.screenSize()`
  改为优先读取默认显示器 `getRealMetrics`（旋转感知），`AccessibilityGestureInput`
  每次手势前重新取实时尺寸并裁剪，不再沿用创建时的竖屏 1220×2712。
  修复前「前往生产 (1586,941)」被截成 (1219,941)；修复后日志为 `bounds=2712x1220`。
- 恢复总览：生产页不响应安卓返回键（D039），改用页面内左上角返回箭头
  `dashboard.departmentBackPoint`；生产页需连点两次（生产页→升级页→总览）；
  部门标题 OCR 失败（如「人H作台」）时也照常返回。
- 收取结果校验：点击后 500ms 读取拒绝 toast（D040），识别「仓库空间不足」等提示，
  不再把「点击了但没入账」误报成收取成功。
- 实机验证：真实点击四部门轮巡一轮完成（actions=14~15），期间 7 次以上恢复总览全部成功；
  收取被游戏拒绝的原因是**仓库已满**；三个计划物品在当前部门列表中不存在（ITEM_NOT_FOUND）。
- 新增 4 个 JVM 用例（页面内返回 2、标题识别失败 1、收取拒绝 1），共 **126 通过**。
- 文档：ADR D039/D040；`docs/02-architecture.md` 配置表与 6.4 流程；HTML 同步。
- 交付：`dist/` APK 更新为 61,284,984 字节，
  SHA-256 `95ae69d683c1171d03037ecee12509cd91dcc09090adb811200bf6f87c293835`。

### 2026-11-21 / 未发布（真实点击闭环：dry-run 拦截 + 开始制造兜底点）
- 修复「自动化在跑但什么都不点」的高频原因：`safety.dryRun` 会持久化在
  `filesDir/config/app_config.json`，重装 APK 不会覆盖，测试残留的 dry-run 一直生效。
  - 主界面输入后端文案在 dry-run 时显示「· dry-run（不点击）」；
  - 点「开始」时若 dry-run 开启，弹窗要求显式选择「关闭 dry-run 并开始」或「继续 dry-run」；
  - 选择关闭时写回 `app_config.json` 并立即启动，确保真实点击路径被执行。
- 实现校准页「开始制造兜底点」：
  - `CraftProfile.startPoint`（基准坐标，默认 null），校准页保存到 `profile.json`
    （旧的 `calibration_extras` SharedPreferences 值作为初始值兼容迁移）；
  - `CraftingController.startCraft`：OCR 找不到「开始制造」时，若
    `isCraftPanel` 成立且调用方已校验右侧详情为目标物品，则点击兜底点；
    成功后必须观察到制造面板消失才返回 SUCCESS，否则按失败处理并返回；
  - 新增 3 个 JVM 用例：兜底点成功、点击后面板仍在判失败、未校准时不点击。
- 文档：`docs/02-architecture.md` 配置表增加 `craft.startPoint`；ADR D038。
- 验证：`:app:testDebugUnitTest` **122/122 通过**；`:app:assembleDebug` 成功
  （`app-debug.apk` 61,284,984 字节）。实机真实点击验收仍待设备连接。

### 2026-11-20 / 未发布（APK 整理 + 架构设计 HTML）
- 重新执行 `:app:assembleDebug`，确认 APK 的 `assets/config/profile.json` 已含
  `fillConfirmTexts`、`app_config.json` 已含 `sweepDepartments=true`。
- 交付物整理到 `dist/`：
  - `sjz-harvest-0.1.0-debug-20261120.apk`（61,281,756 字节）
  - `sjz-harvest-0.1.0-debug-20261120.apk.sha256`
  - `architecture.html`（`docs/05-architecture.html` 的副本）
  旧的未带日期 APK 与其 `.sha256` 已删除，避免误装旧版本。
- 新增 `docs/05-architecture.html`：单文件、离线、无外部 CDN/字体/图片，
  中文完整架构文档，覆盖定位与边界、业务闭环、系统上下文、分层依赖、运行时、
  四类状态机、感知管线、轮巡/补产/补料、配置、错误与安全、性能、119 单测、
  构建发布、D001–D037 ADR、限制与路线图。与 `docs/02-architecture.md` 同源。

### 2026-11-20 / 未发布（角标完成判定 + 固定分区轮巡 + 首批 DeltaForceSS 逻辑移植，119 单测）
- 完成判定：新增 `DoneBadgeProfile` + `vision/AlertBadgeDetector`，在总览截图里
  检测卡片右上角黄色「!」；`ScreenOcrSource` 把角标中心放进 `OcrSnapshot`，
  `DashboardStatusResolver.resolve(..., alertBadges)` 优先按角标判 DONE（置信度 0.95）。
  角标归属用「标题右侧 dx/dy 窗口」而不是最近锚点，避免串到相邻卡片。
- 收菜路径：`collectButtonTexts` 增加「收获」；`craft.productionButtonTexts=["前往生产"]`。
  `CraftingController.collect/craft` 在「XX升级」页自动点「前往生产」再操作生产页；
  总览页部门标题漏识别时用 `dashboardAnchorPoints` 固定点进入（仅 DASHBOARD 页）。
- 状态解析：状态文字只归属给位于文字上方的标题锚点，修复上排「空闲中」串到下排
  （实机 dry-run 已验证 `labels=4, statuses=tech/work/medical/armor=IDLE`）。
- 轮巡收取：`safety.sweepDepartments` 默认开；`dashboard.sweepOrder` 默认
  技术中心→防具台→工作台→制药台；`HarvestFlow` 逐个部门调用 `collect`，
  制造中的部门跳过，收不到不进入失败冷却。单测用 `sweepDepartments=false`
  保留原「按状态动作」语义，另加 2 个轮巡用例。
- 清理：移除此前临时的「UNKNOWN 单部门探查」分支，统一由轮巡处理。
- 移植 DeltaForceSS（`C:\app\soft\ref\DeltaForceSS-src\main.py`，GPL-3.0）：
  - 「一键补齐」补料流程：点击补齐 → 等待确认页 → 必须识别到价格与
    `craft.fillConfirmTexts` 中的确认按钮 → 价格 ≤ `auction.maxUnitPrice` 才点击 →
    回到面板确认材料已齐 → 直接开始制造。默认 `autoBuyMaterials=false` 不触发。
  - 列表到底判定：滚动后视口首行物品名与上一屏相同（相似度 ≥ 该部门
    `itemSimilarity`）即停止；整屏相似度判断保留为兜底。
- 验证：`:app:testDebugUnitTest` 119/119 通过；`:app:assembleDebug` 成功。
- 设备：MIUI 冻结问题通过 `MILLET_NO_RESTRICT_APP` 缓解；`am force-stop` 后
  无障碍需要重新授权/重启才能连接（详见第 9 节）。

### 2026-11-20 / 未发布（感知与点击流程单测）
- 新增 `game/ScreenOcr.kt`：`OcrSnapshot` + `ScreenOcr` 接口 + `ScreenOcrSource`
  生产实现（截屏前校验前台、OCR 后立即回收 Bitmap）。
- `ObserverImpl`/`CraftingController` 改为依赖 `ScreenOcr`；`CraftingController`
  另注入 `nanoTime`。两者保留接收 `ScreenSource`/`OcrEngine` 的生产构造，行为不变。
- 新增 `CraftingControllerTest` 21 个用例：收取按钮定位/进入详情兜底/未知页
  不点击、列表扫描与到底判定、视口外文字排除、右侧详情校验、缺材料不点
  「一键补齐」、默认不购买、开始制造生效验证、dry-run、恢复总览各分支。
- 新增 `ObserverImplTest` 5 个用例：空 OCR、四部门状态解析、单标题总览、
  无标题非总览页、OCR 原文日志。
- 验证：assembleDebug 成功、102/102 单测。

### 2026-11-19 / 未发布修复（待模拟器验收）
- 实机首轮 dry-run 暴露：技术中心空闲被判 DONE、工作台标题漏识别（labels=3）、
  计时器未解析；随后 MIUI 冻结进程约 3 分钟，恢复后把 App 界面当游戏。
- 新增 `vision/DashboardStatusResolver`：标题中心锚点 + 2×2 网格/归一化兜底 +
  最近锚点归属；`doneWhenNoStatus` 默认改为 false；补 8 个单测。
- 实机卡片标题中心按 2712×1220 截图标定为 `(0.195/0.338, 0.186/0.607)`，
  同步 profile 与代码默认值。
- 新增 `ForegroundGuard`，Observer/CraftingController 每次截屏、点击、滑动、
  返回前校验 `com.tencent.tmgp.dfm` 在前台；`foregroundPackage()` 增加
  「有焦点交互窗口」兜底。
- 新增 `AutomationForegroundService`（`specialUse`）+ 电池优化引导，防止后台冻结。
- 修复材料充足时「左列表+右详情」被判成制造弹窗而无法补产；开始制造后不再
  立即按返回（避免取消确认框）。
- 设置页新增「记录 OCR 原文与坐标」开关（`logging.dumpOcrText`）。
- 验证：assembleDebug 成功、76/76 单测、lint 0 error（38 warning）。

### 2026-11-18 / v0.1.0
- 调研 PC 参考实现 DeltaForceSS（截图→OCR→鼠标宏）与手游自动化可行性。
- 按用户要求从 PC 方案转为 **Android 原生 App**，保留 `pc/` 作为第二交付。
- 确定 OCR 驱动、无障碍截屏 + root/无障碍双输入、JSON 配置、安全默认值。
- 三个子智能体并行实现：平台/服务层、视觉/业务层、UI/工具/配置层。
- 修复构建阻断：KDoc 中 `/*` 造成嵌套注释（ConfigModels、AssetConfigRepository）、
  ML Kit `process(Bitmap)` 改为 `InputImage.fromBitmap`。
- 第一轮审查修复 12 项：总览判据、列表页判据、收取/完成关键词分离、制造成功验证、
  失败冷却、连续未知停止、OCR 自适应放大、竖屏旋转默认关闭、短子串匹配、
  计划分类校验、交易行单价上限、无障碍 canRetrieveWindowContent。
- 构建验证：`:app:assembleDebug` 成功，APK 60,373,364 B；
  `:app:testDebugUnitTest` 67 tests / 0 failures；`:app:lintDebug` 0 error。
- 文档：`docs/03-implementation.md`（实现细节）、`docs/04-operations.md`（安装/校准/排障）、
  `docs/reviews/round-1.md`、`round-2.md`、`round-3.md`。
- 第三轮（实机截图驱动）重构补产：同屏「左列表+右详情」、`detailRegion` 校验、
  `viewport` 限定、一键补齐永不点击、交易行双锚点、主界面恢复不盲退、
  制造中关键词、详情页全页找收取。
- 仍待真机/模拟器验收：制造完成/收取按钮形态、「开始制造」按钮是否出现、
  点击部门标题是否稳定进入列表、root vs 无障碍手势。

---

## 12. 给下一次接手的 AI 的最短路径

1. 读本文件第 2、3、4、5、9 节；
2. 只改你要动的层，遵守第 4 节依赖规则；
3. 改配置字段：同步 `ConfigModels.kt` 默认值、`assets/config/*.json`、`docs/02-architecture.md`；
4. 改业务分支：补 `android/app/src/test/`（用假 Observer/Crafter/PlanRepository，不需要游戏）；
5. 任何影响默认行为/安全边界/配置格式/兼容性的改动，在第 5 节追加 ADR；
6. 可以移植 DeltaForceSS 的代码（见 D036）：保留版权头、在 NOTICE.md 注明来源，
   派生文件继续 GPL-3.0；移植时把平台相关部分（截屏/输入/窗口）替换成 Android 实现，
   保证 PC/手游都能复现同一套业务逻辑。物品名/坐标等游戏事实同样注明来源。
