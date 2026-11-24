# 03 · 实现说明（v0.1.0）

本文记录**代码实际怎么实现**，与 `docs/02-architecture.md` 的架构契约对应。
赛季校准、安装操作见 `docs/04-operations.md`。

## 1. 交付物

| 内容 | 路径 |
|---|---|
| Android 源码 | `android/` |
| Debug APK | `android/app/build/outputs/apk/debug/app-debug.apk` |
| 默认配置 | `android/app/src/main/assets/config/{app_config,profile,catalog,plan}.json` |
| JVM 单测 | `android/app/src/test/java/com/sjz/harvest/` |
| 构建/安装脚本 | `scripts/build_apk.bat`、`scripts/install_apk.bat` |
| PC 骨架（未完成） | `pc/src/sjz/` |

- 包名：`com.sjz.harvest`
- versionName `0.1.0`，minSdk 30（Android 11），target/compileSdk 35
- Kotlin 2.0.21 / AGP 8.7.3 / Gradle 8.11.1 / JDK 21
- 依赖：AppCompat、Material3、RecyclerView、Lifecycle、kotlinx-coroutines、
  kotlinx-serialization-json、ML Kit `text-recognition-chinese:16.0.1`（内置中文模型）
- Debug APK 约 **60MB**，主要来自随包中文 OCR 模型；release 未配置签名。

## 2. 代码结构

```
com.sjz.harvest
├── core/
│   ├── model/        Models.kt（Dept/Page/TextElement/RectI/Observation…）
│   │                 Errors.kt（SjzException 密封层级）
│   │                 Geometry.kt（基准坐标按宽度缩放）
│   ├── config/       ConfigModels.kt（4 份 JSON 的 @Serializable schema）
│   │                 ConfigRepository.kt / AssetConfigRepository.kt
│   │                 CatalogIndex.kt / ServiceLocator.kt
│   └── logging/      AppLogger / NoopLogger / LogBus（Logcat + 环形缓冲）
├── capture/          ScreenSource / AccessibilityScreenSource
├── input/            InputController / InputFactory
│                     RootShellInput / AccessibilityGestureInput / DryRunInput
├── vision/           OcrEngine / MlKitOcrEngine / BitmapOps
│                     TextParser / PageDetector
├── game/             Contracts（Observer/Crafter/CraftOutcome）
│                     ObserverImpl / CraftingController / HarvestFlow
├── plan/             PlanRepository / PlanStore / Scheduler
├── service/          SjzAccessibilityService / AutomationController
│                     ControlReceiver / NotificationHelper
├── ui/               MainActivity / PlanActivity / SettingsActivity
│                     CalibrationActivity / RoiEditorView / 三个 Adapter
├── tools/            Diagnostics
└── SjzApplication.kt
```

依赖方向严格向下：`ui/service → game/plan → vision/capture/input → core`。
`game` 与 `plan` 只依赖接口，因此 JVM 单测不需要 Android 或游戏。

## 3. 关键实现

### 3.1 截屏与坐标

1. `SjzAccessibilityService.captureBitmap()` 在主线程调用
   `takeScreenshot(DEFAULT_DISPLAY, executor, callback)`。
2. 回调在单线程 executor：`HardwareBuffer` → `Bitmap.wrapHardwareBuffer` →
   `copy(ARGB_8888)` → 回收包装位图 → `hardwareBuffer.close()`。
3. 服务层 Mutex + 400ms 最小间隔；8s 超时；错误码 1（间隔过短）由
   `AccessibilityScreenSource` 固定 450ms 后重试，其余按 `capture.retries`。
4. 采样检测全黑（32×32，RGB≤8），连续 3 次报 `CaptureFailed`（提示 FLAG_SECURE）。
5. OCR 返回的包围盒直接是截图像素坐标，`RectI.center` 即点击点。
   profile 坐标以 1920×1080 为基准，按 `screenWidth/baseWidth` 等比缩放。

`capture.rotateIfPortrait` 默认 **false**：旋转截图与手势坐标系可能不一致，
游戏强制横屏时没有必要。开启仅在极端情况下兜底。

### 3.2 OCR

- `MlKitOcrEngine` 使用中文 bundled recognizer，Mutex 串行（ML Kit recognizer 非线程安全）。
- 宽度 <1600 时 2× 放大，否则原图；坐标按实际缩放比除回。
- 每个识别请求最多 `1+timing.ocrRetries` 次；协程取消单独重抛；最终失败返回
  `OcrResult.EMPTY`（不抛异常），由判页层转为 `UNKNOWN`。
- `TextParser` 做 NFKC → 去空白/标点 → OCR 错字替换；相似度为归一化 Levenshtein。
- 计时器支持 `HH:MM:SS` / `MM:SS`、全角数字/冒号。

### 3.3 页面判定（`PageDetector.detect`）

优先级：

1. 「开始制造」（长度≥3）或材料锚点（近似精确 ≥90）→ `CRAFT_DIALOG`
2. 「交易行 / 最低价」至少命中 2 个 → `AUCTION`（主界面底部导航只有一个「交易行」）
3. ≥2 个部门标题 → `DASHBOARD`；仅 1 个标题时，标题半径内有空闲/完成/计时器
   → `DASHBOARD`
4. 文字数 ≥6 → `DEPARTMENT`
5. 否则 `UNKNOWN`

单标题不直接判总览，避免部门详情页误判；`CraftingController.isListPage` 也不再
接受单标题总览。实机「左列表 + 右详情」页即使在右侧显示「开始制造」/「一键补齐」，
只要 `itemList.viewport` 内有多行物品文字（≥3）就仍按列表页处理，否则材料充足时
会把列表页误判成弹窗而无法补产。开始制造后不回按返回键（可能取消确认框），
导航恢复统一交给 `recoverToDashboard`。

### 3.4 部门状态（`ObserverImpl` + `DashboardStatusResolver`）

- 解析逻辑在纯 Kotlin 的 `DashboardStatusResolver`，`ObserverImpl` 只负责
  截屏/OCR/判页/日志，便于 JVM 单测覆盖。
- 以四个部门标题中心为锚点；标题漏识别时按 2×2 网格（同行+同列标题）
  推断，仍失败才用 `departmentAnchorPoints` 归一化兜底。
- 每个 OCR 文本元素只归属**最近**的部门锚点，半径
  `dashboard.statusSearchRadiusPx × widthFactor`（默认 360，基准 1080p）。
- 状态优先级：空闲关键词 → 计时器 → 制造中关键词 → 完成关键词 → 回退。
- 默认 `doneWhenNoStatus=false`：没有状态文字返回 `UNKNOWN` 并跳过该部门。
  旧默认 true 会把 OCR 漏字的空闲卡片误判成 DONE 并去点收取。

### 3.5 收菜

1. 截图判页（`UNKNOWN`/`AUCTION` 直接失败）。
2. 在部门标题半径内找 `collectButtonTexts`（默认「收取/领取」）。
3. 找不到时：`fixed_point` 策略用校准点；否则点部门标题进入详情，再 OCR 找一次。
4. 点击后等待 `collectWaitMs`。业务层随后重新观察，不沿用旧状态。

### 3.6 补产

实机截图确认：手游制造**没有独立弹窗**，而是「左侧物品列表 + 右侧详情 +
底部操作按钮」同屏。因此流程按同屏面板实现：

1. 总览页点部门标题 → `awaitState(isListPage)`。
2. **第一行模式（默认 `itemList.firstRowSelect=true`）**：玩家把目标物品置顶，
   直接点归一化固定点 `itemList.firstRowPoint`；不按物品名匹配、不滚动。
   点完后等 `isCraftPanel`，再做 OCR 预审（`firstRowVerify`）：
   `craft.detailRegion` 能读到标题且不是计划物品 → `PAGE_ERROR`；
   读不到 → 告警但继续。
3. 旧扫描模式（`firstRowSelect=false`）：每屏全屏 OCR，只在
   `itemList.viewport`（左侧列表）内按名称匹配，过滤 UI 文案/纯数字/噪声词；
   显式分类的计划项会先与 catalog 分类校验。找到目标行后点击，
   等待 `isCraftPanel` 且右侧标题确认为目标物品；不匹配则 `PAGE_ERROR`。
4. 面板处理：
   - 出现「一键补齐」→ 材料不足，`MISSING_MATERIALS`；**v0.1 永不点击**；
   - 出现旧式「购买」弹窗时，沿用旧逻辑（默认关闭自动买材料）；
   - 找「开始制造」→ 点击 → **验证按钮消失** → `SUCCESS`；超时 `PAGE_ERROR`，
     不扣计划。
5. 列表滚动：最多 `min(timing.maxScrolls, itemList.maxScrolls)` 屏；连续两屏
   归一化文本相似度 ≥ `bottomSimilarity`（默认 97）判到底。

> dry-run 无法真正切换右侧详情：如果起始在总览，会记录“请手动进入部门物品页”
> 并返回失败；需要验证补产时请手动进入目标部门列表后再启动 dry-run。

### 3.7 周期与调度（`HarvestFlow` / `Scheduler`）

- 起始页非总览：最多两次 `recoverToDashboard`；失败按
  `stopOnUnknownPage` 停止。
- 部门顺序固定：tech → work → medical → armor。
- 收菜成功后必须重新观察；只有 `craft==SUCCESS` 才 `plan.consume()+persist()`。
- 单部门业务失败 → 记录并进入 `failureBackoffSec`（默认 300s）冷却，继续其他部门；
  页面/前台/截屏/输入异常 → 整轮停止。
- 每轮动作数超过 `maxActionsPerCycle` 抛 `SafetyLimitReached`。
- `Scheduler`：
  - 有 DONE → 0；
  - 有 IDLE 且有计划 → 0；
  - CRAFTING → `max(15, 最小剩余秒 + cycleBufferSec)`；
  - 全 UNKNOWN → 30；其他 → 60。
- 若 `nextWake=0` 的唯一原因是冷却中的部门，本轮唤醒推迟到冷却结束。
- 连续 `maxConsecutiveUnknownCycles`（默认 3）轮全部 UNKNOWN → 停止。

### 3.8 运行状态机（`AutomationController`）

- 单例 `object`，`SupervisorJob + Dispatchers.Default`，`StateFlow<AutomationUiState>`。
- start：加载配置 → 等无障碍 ≤3s → 构建 source/OCR/input/observer/crafter/
  PlanStore/HarvestFlow → 常驻通知 → 循环。
- 每轮开始：`awaitResume` → maxCycles/maxRuntime 检查 → 前台包名校验
  （首轮宽限 15s；包名为 null 时依赖页面锚点）→ `runCycle` → 发布状态 → 可中断
  delay（500ms 粒度，暂停不消耗等待时间）。
- `nextWake=0` 且本轮无动作时至少退避 5s，防止空转。
- stop：取消 Job；`finally` 关闭 OCR/input/source，发布终态；用户停止时取消通知。
- 无障碍服务断开由 watcher 监听 `instance==null` 立即停止。
- 异常统一映射为中文提示；未知异常保存失败截图到 `filesDir/debug/`。

### 3.9 输入后端

| 后端 | 实现 | 适用 |
|---|---|---|
| `root` | `su -c "input tap/swipe/keyevent 4"`，3s 超时，点击 ±jitter 并 clamp | 已 root 的模拟器，最可靠 |
| `accessibility` | `dispatchGesture` / `performGlobalAction(BACK)`，10s 超时 | 未 root 真机；部分游戏忽略 |
| `dry_run` | 只写日志 | 校准/验证识别 |

`auto` 创建时只选择一次：root 可用 → root，否则无障碍；未知后端报错。
安全开关 `safety.dryRun` 优先级最高，即使 `backend=root` 也强制 DryRunInput。

### 3.10 配置持久化

- 首次访问把 `assets/config/*.json` 复制到 `filesDir/config/`，不覆盖已存在文件。
- `kotlinx.serialization`：`ignoreUnknownKeys=true`、`encodeDefaults=true`、prettyPrint。
- 写入使用 `.tmp` + `fd.sync()` + `ATOMIC_MOVE`（失败回退替换/rename）。
- 读取/解析失败包装为 `ConfigError`；设置页可恢复默认（全量覆盖）。
- 配置文件在 App 运行中不热更新：修改在下次 start 生效。

## 4. 构建与测试

```powershell
cd C:\app\soft\youxi\sjz\android
$env:GRADLE_USER_HOME = "C:\app\soft\youxi\sjz\.gradle-home"
C:\app\soft\gradle-8.11.1\bin\gradle.bat :app:assembleDebug :app:testDebugUnitTest
```

> 本机 `C:\Users\admin\.gradle\init.gradle` 与项目的 `FAIL_ON_PROJECT_REPOS` 冲突，
> 且存在 Groovy 变量遮蔽 bug。项目使用独立 `GRADLE_USER_HOME`，其中 `init.gradle`
> 配置腾讯云镜像 + Google Maven；没有修改用户的全局文件。

验证结果（2026-11-18）：

| 任务 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | 成功 |
| `:app:assembleDebug` | 成功；APK 60,373,364 B |
| `:app:testDebugUnitTest` | 67 tests / 0 failures / 0 errors |
| `:app:lintDebug` | 成功；0 errors，40 warnings |

单测覆盖：文本解析（计时器/价格/归一化/相似度）、几何缩放、catalog 索引、
PlanStore FIFO/消费/持久化、Scheduler 分支、页面判定、HarvestFlow 收菜→补产、
失败隔离、冷却、未知页停止、动作上限、失败不扣计划。

验证结果（2026-11-20）：

| 任务 | 结果 |
|---|---|
| `:app:assembleDebug` | 成功 |
| `:app:testDebugUnitTest` | 102 tests / 0 failures / 0 errors |

新增 `CraftingControllerTest` 21 个用例：通过 `ScreenOcr` 假实现按顺序提供
「截屏+OCR」结果，用记录型 `InputController` 断言点击序列；覆盖收菜按钮定位、
进入部门详情兜底、未知页不点击、列表扫描到底、视口外文字排除、右侧详情校验、
缺材料不点「一键补齐」、默认不购买、开始制造生效验证、dry-run、恢复总览。

新增 `ObserverImplTest` 5 个用例：空 OCR、四部门状态解析、单标题总览、
无标题非总览页、开启 `dumpOcrText` 时的日志输出。

## 5. 性能与资源

- 一次全屏 OCR（1920×1080，ML Kit Chinese）在模拟器上通常数百 ms；
  截屏受系统 400ms 下限约束，因此一轮四部门的感知开销约数秒。
- 每个 `Frame` 在 OCR 完成/失败截图保存后立即 `recycle()`；
  OCR 内部放大副本单独回收。
- 日志环形缓冲默认 300 行（`logging.maxLines`），UI 只展示最近 200 行。
- APK 体积主要是 OCR 模型；无需联网下载模型。

## 6. 与架构文档的差异

| 项 | 架构文档 | 实现 |
|---|---|---|
| 启动时前台校验 | 严格包名 | 首轮 15s 宽限；包名为 null 时接受并依赖页面锚点 |
| 自动买材料 | 可选 | 已实现，但默认关闭；新增单价上限 |
| 校准「开始制造」点 | 预留 | UI 已保存到 SharedPreferences，业务尚未读取 |
| PC 版 | 第二交付 | 仅接口骨架，未实现主循环 |
| 竖屏 | 建议拦截 | 提供旋转兜底但默认关闭 |

## 7. 已知未验证项

见 `docs/reviews/round-2.md` 第五节。核心是：**默认文字锚点/阈值尚未用真实
手游截图验证**，首次使用必须先 dry-run + 校准。
