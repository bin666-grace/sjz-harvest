# 02 · 架构设计

> 本文是实现的唯一架构契约。模块职责、接口、配置 schema、错误策略以此为准。
> 修改架构时必须同步 `AGENT.md` 的 ADR 和本文。
>
> 可读版：`docs/05-architecture.html`（单文件、离线、含图示与 D001–D037 ADR）。

---

## 1. 目标与非目标

### 目标

- 在 Android 模拟器/真机上，自动完成三角洲行动手游特勤处的
  **收菜 → 补产 → 等待下一轮** 闭环。
- 对分辨率、刘海、模拟器差异尽量鲁棒：**OCR 文字锚点优先，固定坐标为兜底**。
- 任何不确定状态都停止，不盲点；所有操作可审计（日志 + 通知栏状态）。
- 可离线构建、可单元测试、可在没有真实游戏的情况下验证业务分支。

### 非目标（v0.1）

- 自动登录、自动出售、自动跑刀、自动对局、多开。
- 内存读写、封包修改、注入进程、反检测/反作弊规避。
- iOS、PC 客户端（PC 版另见 `pc/`，第二优先级）。
- 自动买材料默认关闭；手游交易行流程验证后再开。

---

## 2. 系统上下文

```
┌──────────────────────── Android 模拟器 / 真机 ────────────────────────┐
│                                                                      │
│  ┌────────────────┐   截屏/手势/前台包名    ┌──────────────────────┐  │
│  │ 三角洲行动手游  │ ◄──────────────────────► │  sjz App (本项目)     │  │
│  │ com.tencent... │                          │                      │  │
│  └────────────────┘                          │  AccessibilityService│  │
│                                              │  AutomationController│  │
│                                              │  ML Kit OCR          │  │
│                                              │  Notification        │  │
│                                              └──────────────────────┘  │
└──────────────────────────────────────────────────────────────────────┘
```

App 不联网、不上传截图。配置和日志都在应用私有目录。

---

## 3. 分层与依赖

```
┌─────────────────────────────────────────────────────────────┐
│ UI 层：MainActivity / PlanActivity / Calibration / Settings  │
├─────────────────────────────────────────────────────────────┤
│ 编排层：AutomationController / HarvestFlow / Scheduler       │
├─────────────────────────────────────────────────────────────┤
│ 业务层：ObserverImpl / CraftingController / PlanStore        │
├─────────────────────────────────────────────────────────────┤
│ 能力层：OcrEngine / ScreenSource / InputController           │
├─────────────────────────────────────────────────────────────┤
│ 基础层：core.model / core.config / Geometry / Errors / Log   │
└─────────────────────────────────────────────────────────────┘
                            Android Framework
```

硬性规则：

1. `core.model`、`core.config`（模型与 `CatalogIndex`）、`vision/TextParser`、
   `plan/Scheduler`、`game/HarvestFlow` **不得 import Android API**，保证 JVM 单测。
2. 业务层只依赖接口：
   - `Observer`、`Crafter`、`PlanRepository`、`InputController`、`OcrEngine`、`ScreenSource`；
   - 具体实现由 `AutomationController`/`ServiceLocator` 注入。
3. 只有 `service`/`ui`/`capture`/`input`/`vision/MlKitOcrEngine` 可以碰 Android API。
4. 不允许在业务层直接调用 `AccessibilityService` 或 `Runtime.exec("su")`。

---

## 4. 运行时模型

### 4.1 进程与服务

- `SjzAccessibilityService`：系统绑定的无障碍服务，常驻。
  - 持有 `ScreenSource`、`InputController` 工厂能力；
  - 暴露 `instance: StateFlow<SjzAccessibilityService?>`；
  - 提供 `foregroundPackage()`、`takeScreenshot()`、`dispatchGesture()`。
- `AutomationController`：单例，持有 `CoroutineScope(SupervisorJob + Dispatchers.Default)`；
  - `start()` / `pause()` / `resume()` / `stop()`；
  - 状态 `StateFlow<AutomationUiState>` 给 UI/通知；
  - 每轮调用 `HarvestFlow.runCycle()`，结束后 `delay(nextWakeSec)`；
  - 暂停用 `CompletableDeferred`/`Mutex` 阻塞在轮次之间；停止用 `Job.cancel()` + 标志。
- `ControlReceiver`：通知栏按钮广播，调用 Controller。
- `MainActivity`：前台可见时绑定状态流；退到后台不影响自动化。

### 4.2 线程/协程

- 所有 I/O（截屏、OCR、root 命令）用 `Dispatchers.IO`；
- `dispatchGesture`、`takeScreenshot` 的回调在主线程，包装为 `suspendCancellableCoroutine`；
- OCR 串行执行（ML Kit recognizer 非线程安全），用 `Mutex` 保护；
- 截屏串行 + 最小间隔 400ms，避免 `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`；
- 业务循环单线程顺序执行，不做并发点击。

### 4.3 生命周期

```
用户点开始
  → 检查无障碍服务已连接（否则提示去设置）
  → 加载配置（app/profile/catalog/plan）
  → 选择输入后端（auto: root → accessibility → 报错）
  → 通知栏显示“运行中”
  → loop:
       检查暂停/停止/安全限制
       flow.runCycle()
       记录下一个唤醒时间
       delay(nextWake)
  → 停止：取消协程、回收 OCR、更新通知
```

无障碍服务被系统关闭时：Controller 监听 `instance == null`，停止循环并提示。

---

## 5. 状态机

### 5.1 自动化状态

```
IDLE ──start──► RUNNING ──pause──► PAUSED ──resume──► RUNNING
                  │  ▲                  │
                  │  └──────────────────┘
                  ├──stop──► STOPPED
                  └──error─► ERROR（等待用户处理）
```

状态字段：`running`, `paused`, `phase`, `lastMessage`, `nextWakeAtMs`,
`cycleCount`, `lastObservation`。

### 5.2 页面判定

输入：`OcrResult`（全屏文字 + 包围盒）。
按顺序：

1. 找到长度 ≥3 的 `craft.startButtonTexts`（即「开始制造」，不含泛化「制造」）
   或材料锚点（近似精确 ≥90）→ `CRAFT_DIALOG`；
2. `auction.anchorTexts` 至少命中 2 个（真交易行同时有「交易行」「最低价」；
   主界面底部导航只有「交易行」，不能判拍卖页）→ `AUCTION`；
3. 找到 **≥2 个**部门标题 → `DASHBOARD`；只有 1 个标题时，标题半径内必须出现
   空闲/完成关键词或计时器，才判 `DASHBOARD`；
4. 文字元素数 ≥6 → `DEPARTMENT`；
5. 否则 `UNKNOWN`。

> 顺序很重要：制造弹窗里也可能出现「制造」二字，因此先用更具体的
> 「开始制造」/材料锚点，再判总览。详情页通常只有自身一个标题，
> 单标题不能直接判总览，否则补产会停在详情页而不是进入部门列表。

### 5.3 部门状态

解析集中在 `vision/DashboardStatusResolver`（纯 Kotlin，可单测）：

1. 四个部门标题作为锚点；某个标题漏识别时，先按 2×2 卡片网格
   （同行/同列已知标题）推断，仍推不出才用 `departmentAnchorPoints` 归一化兜底；
2. 每个 OCR 文字元素只归属**最近**的部门锚点，再限制在
   `statusSearchRadiusPx × widthFactor` 半径内；
3. 状态优先级：
   - 含 `idleKeywords` → `IDLE`；
   - 匹配 timer 正则 → `CRAFTING(remainingSec)`；
   - 含 `craftingKeywords` 但无计时器 → `CRAFTING`；
   - 含 `doneKeywords` → `DONE`；
   - 无状态文字 → `UNKNOWN`（`doneWhenNoStatus` 默认 **false**，避免把空闲卡片误判成待收取）。

锚点使用标题中心而不是固定坐标，因此卡片状态文字（如「+ 空闲中」、
计时器）只要在卡片内就能归属到正确部门。

### 5.4 业务周期

```
runCycle():
  obs = observer.observe()   // GameNotForeground → 本轮中止（不终止会话）
  if obs.page != DASHBOARD:
      tryRecoverToDashboard()  // 最多 2 次 back，仍失败 → PageNotRecognized
  actions = []
  for dept in [TECH, WORK, MEDICAL, ARMOR]:
      st = obs.status(dept)
      if st.state == DONE:
          ok = crafter.collect(dept)
          actions += ...
      if st.state == IDLE or (collected and now idle):
          entry = plan.next(dept)
          if entry != null:
              outcome = crafter.craft(dept, entry)
              if outcome == SUCCESS: plan.consume(dept, entry); persist
  obs2 = observer.observe()
  next = Scheduler.nextWakeSec(obs2, plan, buffer)
  return CycleResult(...)
```

注意：

- 收菜和补产可能连续发生；收菜后需要重新判断该部门是否空闲，不能直接用旧状态。
- 一个部门失败不阻塞其他部门；记录后继续，但页面异常/前台异常立即中止整轮。
- 失败部门进入 `failureBackoffSec` 冷却；若 `nextWake=0` 的唯一原因是冷却部门，
  唤醒推迟到冷却结束。
- `craft` 必须确认制造弹窗已关闭才算 `SUCCESS`；只有 `SUCCESS` 消费计划。
- 整轮页面未知或四部门全 `UNKNOWN` 会累计；达到
  `maxConsecutiveUnknownCycles` 停止。
- 每轮动作数超过 `maxActionsPerCycle` → `SafetyLimitReached`。

---

## 6. 模块规格

### 6.1 capture

```kotlin
interface ScreenSource {
    suspend fun capture(region: RectI? = null): Frame
    fun screenSize(): Pair<Int, Int>
    fun close()
}
```

`AccessibilityScreenSource`：

- 调用 `service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)`；
- 将 `HardwareBuffer` 包装成 `Bitmap`，复制为软件 Bitmap（`ARGB_8888`）；
- 错误重试：`ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` → 等待 450ms 重试；
  其他错误 → `CaptureFailed`；
- 全黑检测：采样像素，若全黑且重试 2 次仍全黑 → `CaptureFailed("截屏全黑，可能被 FLAG_SECURE 保护")`；
- `region` 裁剪；返回的 Frame 坐标 = 屏幕坐标；
- 屏幕方向：默认不旋转；仅当 `rotateIfPortrait=true` 且 bitmap 高 > 宽时旋转 90°
  （截图坐标与手势坐标可能不一致，不推荐）。
  **注意**：旋转后手势坐标需与截图坐标一致；v1 要求模拟器横屏，
  旋转只做兜底，并在日志警告。

### 6.2 input

```kotlin
interface InputController {
    suspend fun tap(point: PointI)
    suspend fun swipe(from: PointI, to: PointI, durationMs: Long)
    suspend fun back()
    fun isAvailable(): Boolean
    fun backendName(): String
    fun close()
}
```

实现：

- `RootShellInput`：
  - 启动时执行 `su -c id`，输出含 `uid=0` 才可用；
  - `tap`：`su -c "input tap x y"`；`swipe`：`su -c "input swipe x1 y1 x2 y2 dur"`；
  - `back`：`su -c "input keyevent 4"`；
  - 每个命令 `waitFor(timeout=3000ms)`，退出码非 0 → `InputUnavailable`；
  - 随机抖动 ±`jitterPx`，但 clamp 到屏幕内。
- `AccessibilityGestureInput`：
  - `tap`：Path moveTo → `GestureDescription` duration=`tapHoldMs`；
  - `swipe`：Path moveTo + lineTo，duration 参数；
  - `back`：`performGlobalAction(GLOBAL_ACTION_BACK)`；
  - 等待 `onCompleted`；`onCancelled` → `InputUnavailable`；
  - 必须在主线程 dispatch。
- `DryRunInput`：只记录日志，不执行；`isAvailable=true`。
- 工厂 `InputFactory.create(config, service)`：
  - `auto`：root 可用 → root；否则 accessibility 可用 → accessibility；否则抛错；
  - `root`/`accessibility`/`dry_run` 强制指定。

### 6.3 vision

- `MlKitOcrEngine`：
  - `TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())`；
  - 宽度 <1600 时输入 bitmap 放大 2×（小字），否则原图；统一转 ARGB_8888；
  - 返回 `TextElement(text, boundingBox)`，坐标除以放大倍数；
  - 串行 `Mutex`；识别失败重试 `ocrRetries` 次；最终返回 `OcrResult.EMPTY` 并记录。
- `BitmapOps`：
  - `crop(bitmap, rect)`；`upscale(bitmap, factor)`；`toGray`；`whiteRatio(bitmap, threshold=200)`；
  - 全部纯 Android，不依赖 OpenCV。
- `TextParser`（纯 Kotlin）：
  - `parseTimer(text, profile): Int?`：匹配 `H:MM:SS` 或 `MM:SS`；
  - `parsePrice(text, profile): Int?`：取最长的数字/逗号串，去逗号；
  - `isIdle(text, profile)`、`isDone(text, profile)`；
  - `normalize(text)`：去空白/标点、应用 `ocrReplacements`；
  - `similarity(a,b)`：归一化 Levenshtein 比率 0..100；
  - `bestMatch(text, candidates, threshold): Match?`；
  - `groupNearby(elements, radiusPx)`：按 y 聚类（备用）。
- `PageDetector`：
  - `detect(ocr, profile): Page`；
  - `findDepartmentLabels(ocr, profile): Map<Dept, TextElement>`；
  - `findNearby(ocr, center, radius, predicate): List<TextElement>`；
  - `findButton(ocr, texts, profile): TextElement?`。

### 6.4 game

- `ObserverImpl(snapshotter, profile, logger)`（生产构造接收 `source, ocr, ...`）：
  - `observe()`：`ScreenOcr` 截屏+全屏 OCR → PageDetector → 状态计算。
  - 只读，不点击。
- `ScreenOcr` / `ScreenOcrSource`：把「截屏 → OCR → 回收 Bitmap」封装起来，
  返回不含 `Bitmap` 的 `OcrSnapshot`，使点击流程可在 JVM 单测中用假画面驱动。
- `CraftingController(snapshotter, input, profile, catalog, timing, safety, nanoTime)`：
  生产构造仍接收 `(source, ocr, ...)`，内部包装为 `ScreenOcrSource`。
  - `collect(dept)`：
    1. 在当前 OCR 结果中找该部门附近的「收取/领取」→ 点击；
    2. 若 `collectStrategy=ocr_button` 且没找到 → 点部门标题 → 等待 → 再 OCR 找收取按钮 → 点击；
    3. `fixed_point` → 点固定点；
    4. 点击收取后先等 500ms 截图，检查游戏拒绝提示（`collectBlockedTexts`，
       实机如「仓库空间不足」）；命中 → 返回 false 并记录原因，不把未入账的
       点击误报成成功；未命中 → 补足 `collectWaitMs` 后返回 true。
  - `recoverToDashboard()`：
    1. 已在总览 → 直接成功；
    2. 识别到主界面底部导航 → 点「特勤处」；
    3. `DEPARTMENT`（部门升级页/生产页）→ 点页面内左上角返回箭头
       （`dashboard.departmentBackPoint`）；实机安卓返回键在这些页面无效，
       生产页需要连点两次（生产页 → 升级页 → 总览）；每次点击后重新判页，
       两次仍未回总览即停止；
    4. 其他未知页面 → 只点完全匹配的「取消/关闭/确定」按钮，找不到就停止。
  - `craft(dept, entry)`：
    1. 点部门标题 → 等待 → 确认 `DEPARTMENT`；
    2. 循环最多 `maxScrolls`：
       - OCR 当前页；`bestMatch` 找目标物品；找到 → 点击，跳出；
       - 未找到 → 上滑（`swipe`），等待 `scrollSettleMs`，比较前后 OCR 文本相似度；>= bottomSimilarity → 未找到，返回 `ITEM_NOT_FOUND`；
    3. 等待制造弹窗；OCR；
    4. 材料检查/购买：
       - 找 `buyButtonTexts`；若 `autoBuyMaterials=false` 且有购买按钮 → 返回 `MISSING_MATERIALS`（不点击购买），按返回退出；
       - 若开启：逐个点购买 → 等待交易行 → OCR 价格区域 → 找购买按钮 → 点击 → 等待 → `back()`；
       - 重试 `maxBuyAttempts`；仍缺 → `BUY_FAILED` / `MISSING_MATERIALS`；
    5. 找「开始制造」并点击；找不到 → `PAGE_ERROR`；
    6. 点击后等待并验证制造弹窗关闭；超时未关闭 → `PAGE_ERROR`（不扣计划）；
    6. 等待 `craftWaitMs`；`back()` 回总览；返回 `SUCCESS`。
- `HarvestFlow(observer, crafter, plan, scheduler, safety, clock)`：
  - 纯 Kotlin，可用假实现测试；
  - 负责页面对齐、部门循环、失败隔离、动作计数、结果汇总。

### 6.5 plan

- `PlanStore(context, json)`：
  - 内存 `MutableMap<Dept, MutableList<PlanEntry>>`；
  - `next(dept)`：第一个 `enabled && count != 0`；
  - `consume`：`count == -1` 不变；`count > 1` 减一；`count == 1` 移除；
  - `persist()`：写临时文件 + `rename` 原子替换；
  - `snapshot()`：深拷贝。
- `Scheduler.nextWakeSec(obs, plan, bufferSec)`：
  - 有 DONE → 0；
  - 有 IDLE 且计划非空 → 0；
  - 有 CRAFTING → `max(15, minRemaining + buffer)`；
  - 全 UNKNOWN → 30（让循环再试一次，连续 3 次由 Flow 停止）；
  - 其他 → 60。

### 6.6 service / controller

- `SjzAccessibilityService`：
  - `onServiceConnected()`：设置 `instance`，初始化 `AccessibilityScreenSource`；
  - `onAccessibilityEvent()`：记录当前前台包名（`event.packageName`）；
  - `onDestroy()`：清空 `instance`，通知 Controller 停止；
  - 提供 `suspend fun screenshot()`、`suspend fun tap/swipe` 的底层方法。
- `AutomationController`：
  - 单例 `object` 或 `Application` 持有；
  - `start()`：读配置 → 构建依赖 → 启动循环；
  - 通知：channel `sjz_automation`，低优先级，常驻；动作 Pause/Resume/Stop；
  - 状态流：`StateFlow<AutomationUiState>`；
  - 停止条件：`StoppedByUser`、`SafetyLimitReached`、`PageNotRecognized`（可配置）、
    `GameNotForeground`、连续 OCR 失败。
- `ControlReceiver`：处理通知 action，调用 Controller。

### 6.7 ui

- `MainActivity`：
  - 顶部：无障碍状态、游戏前台状态、输入后端；
  - 中部：运行状态、下一轮倒计时、四部门状态卡片；
  - 按钮：开始/暂停/停止、自检、生产计划、校准、设置；
  - 底部：日志（最近 300 行，等宽字体，自动滚动）。
- `PlanActivity`：
  - 部门 Tab（4 个）；
  - 条目列表：物品名、count、启用开关、删除、上移/下移；
  - 添加：搜索框 + catalog 全量列表（RecyclerView + 过滤）；
  - 保存即持久化。
- `CalibrationActivity`：
  - 步骤列表：列表视口、滚动起点/终点、收取兜底点、开始制造兜底点；
  - 点「截屏」→ 通过无障碍服务截当前游戏画面（用户需先把游戏切到对应页面，
    可用通知栏或返回键切换；App 提供 3 秒倒计时）；
  - 自定义 `RoiEditorView`：拖动画矩形/点；按缩放比例映射回 1920×1080；
  - 保存到 profile.json（内部存储），显示 JSON diff。
- `SettingsActivity`：
  - 输入后端（auto/root/accessibility/dry_run）；
  - dry-run 开关；
  - 自动买材料开关（带警告）；
  - 最大循环/最长运行；
  - 截屏间隔、动作延迟；
  - 恢复默认配置；
  - 导出/导入 plan/profile（SAF）。

---

## 7. 配置 schema

### 7.1 `app_config.json`

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `gamePackage` | string | `com.tencent.tmgp.dfm` | 前台校验包名 |
| `profileFile` / `catalogFile` / `planFile` | string | `profile.json` 等 | 内部存储文件名 |
| `input.backend` | string | `auto` | auto/root/accessibility/dry_run |
| `input.tapHoldMs` | int | 60 | 点击按压时长 |
| `input.swipeDurationMs` | int | 500 | 滑动默认时长 |
| `input.jitterPx` | int | 3 | 点击随机抖动 |
| `input.actionDelayMinMs` / `MaxMs` | int | 120 / 350 | 动作间随机延迟 |
| `capture.minIntervalMs` | int | 400 | 截屏最小间隔（系统限制约 333ms） |
| `capture.retries` | int | 3 | 截屏重试 |
| `capture.rotateIfPortrait` | bool | false | 竖图旋转兜底（默认关，避免坐标错位） |
| `timing.pageTimeoutMs` | int | 8000 | 等页面超时 |
| `timing.pollIntervalMs` | int | 250 | 轮询间隔 |
| `timing.collectWaitMs` | int | 1500 | 收取后等待 |
| `timing.craftWaitMs` | int | 2000 | 开始制造后等待 |
| `timing.auctionWaitMs` | int | 1800 | 交易行等待 |
| `timing.scrollSettleMs` | int | 800 | 滚动后等待 |
| `timing.cycleBufferSec` | int | 30 | 下次唤醒缓冲 |
| `timing.maxScrolls` | int | 40 | 列表最大滚动次数 |
| `timing.ocrRetries` | int | 2 | OCR 重试 |
| `timing.failureBackoffSec` | int | 300 | 部门失败冷却秒数；0=不冷却 |
| `safety.dryRun` | bool | false | 只识别不点击 |
| `safety.autoBuyMaterials` | bool | false | 自动买材料（v0.1 关闭） |
| `safety.requireGameForeground` | bool | true | 前台包名校验 |
| `safety.stopOnUnknownPage` | bool | true | 未知页停止 |
| `safety.maxCycles` | int | 0 | 0=不限 |
| `safety.maxRuntimeMinutes` | int | 0 | 0=不限 |
| `safety.maxActionsPerCycle` | int | 80 | 每轮动作上限 |
| `safety.maxConsecutiveUnknownCycles` | int | 3 | 连续全未知周期上限；0=不限 |
| `logging.maxLines` | int | 300 | UI 日志行数 |
| `logging.saveFailureScreenshots` | bool | true | 失败截图落盘 |
| `logging.dumpOcrText` | bool | false | 调参用：每次观察输出 OCR 原文+包围盒（分块写日志） |

### 7.2 `profile.json`

| 字段 | 默认 | 说明 |
|------|------|------|
| `baseWidth` / `baseHeight` | 1920 / 1080 | 坐标基准 |
| `supportedWidths` | [1920,2560,3840] | 允许的屏幕宽度 |
| `dashboard.anchorTexts` | 四部门标题 | 总览页锚点 |
| `dashboard.departmentLabels` | key→中文名 | 部门标题 |
| `dashboard.statusSearchRadiusPx` | 360 | 状态文字搜索半径 |
| `dashboard.idleKeywords` | 空闲中/空闲 | 空闲 |
| `dashboard.craftingKeywords` | 制造中/生产中 | 无计时器时仍判 CRAFTING |
| `dashboard.doneKeywords` | 收取/领取/已完成/完成 | 完成状态判断（不直接点击） |
| `dashboard.collectButtonTexts` | 收取/领取/收获 | 仅用于点击的收取按钮文字 |
| `dashboard.collectBlockedTexts` | 仓库空间不足/仓库已满/背包已满/无法收取 | 收取后识别游戏拒绝提示；命中即返回失败，不误报收取成功 |
| `dashboard.lobbyNavTexts` | 仓库/部门/交易行/特勤处/改枪台 | 主界面底部导航 |
| `dashboard.lobbyEntryText` | 特勤处 | 主界面进入特勤处的文字（OCR 预审/兜底用） |
| `dashboard.lobbyEntryPoint` | (0.37, 0.882) | 主界面底部「特勤处」的归一化固定点击点；OCR 只预审「在主界面」，点击不依赖包围盒中心，避免误点相邻的「特战干员」；null=退回完全相等的 OCR 文字中心 |
| `dashboard.departmentBackPoint` | (0.0376, 0.0951) | 部门页/生产页左上角页面内返回箭头（归一化）；安卓返回键在这些页面无效；null=退回安卓返回键 |
| `dashboard.doneWhenNoStatus` | false | 无状态文字视为完成；默认关闭，宁可 UNKNOWN 也不误收 |
| `dashboard.departmentAnchorPoints` | 归一化四卡片标题中心 | OCR 漏标题且无法按网格推断时的兜底锚点 |
| `dashboard.collectStrategy` | ocr_button | ocr_button/tap_card_then_ocr/fixed_point |
| `dashboard.collectFixedPoint` | null | 兜底收取点 |
| `itemList.viewport` | {0,140,600,940} | 左侧列表区域（基准）；上边界 140 才包含第一行；null=全屏 OCR |
| `itemList.scrollFrom` / `scrollTo` | null | null=按屏幕比例推导 |
| `itemList.maxScrolls` | 40 | 列表滚动上限 |
| `itemList.bottomSimilarity` | 97 | 判断到底的相似度 |
| `itemList.firstRowSelect` | true | 生产时直接点列表第一行的固定点，不按物品名扫描/滚动；玩家自行把目标置顶 |
| `itemList.firstRowPoint` | (0.17, 0.239) | 第一行的归一化点击点（按实机 2712×1220 标定） |
| `itemList.firstRowVerify` | true | 点第一行后 OCR 预审右侧详情：读到标题且不匹配→中止；读不到→告警但继续 |
| `craft.startButtonTexts` | 开始制造/制造 | 开始制造按钮 |
| `craft.fillMaterialsTexts` | 一键补齐 | 材料不足标志；v0.1 永不点击 |
| `craft.buyButtonTexts` | 购买 | 材料购买（旧弹窗兜底） |
| `craft.materialAnchorTexts` | 材料/所需材料 | 面板/弹窗锚点 |
| `craft.maxBuyAttempts` | 6 | 购买重试 |
| `craft.detailRegion` | {600,90,700,220} | 右侧详情标题区域，校验选中目标 |
| `craft.startPoint` | null | 「开始制造」校准兜底点；仅 OCR 漏字且确认面板时使用，点击后须见面板消失 |
| `auction.anchorTexts` | 交易行/最低价 | 交易行锚点 |
| `auction.priceRegion` | null | null=全屏找价格 |
| `auction.buyButtonTexts` | 购买/买入 | 交易行购买 |
| `auction.priceRegex` | `\d[\d,]{2,}` | 价格提取 |
| `auction.maxUnitPrice` | 0 | 自动买材料单价上限；0=不限 |
| `text.timerRegex` | `(\d{1,3}):(\d{2}):(\d{2})` | 计时器 |
| `text.ocrReplacements` | `番→盔` | OCR 纠错 |
| `thresholds.itemSimilarity` | tech71/work96/med80/armor80 | 物品模糊匹配阈值 |
| `thresholds.minPageAnchorScore` | 60 | 页面锚点阈值 |

### 7.3 `catalog.json`

```json
{
  "version": 1,
  "source": "community data, see NOTICE.md",
  "departments": {
    "tech": { "categories": { "枪": ["M4A1突击步枪"], "瞄具": ["..."] } },
    "work": { "categories": { "level_6": ["..."] } },
    "medical": { "categories": { "level_6": ["..."] } },
    "armor": { "categories": { "level_6": ["..."] } }
  },
  "aliases": { "番": ["盔"] }
}
```

- 物品名来自公开社区配置，GPL-3.0，来源见 `NOTICE.md`。
- `catalog.json` 只用于「目标物品名 → 候选列表」，不参与页面判定。

### 7.4 `plan.json`

```json
{
  "version": 1,
  "departments": {
    "tech":  [ { "item": "3/7可调倍率狙击镜", "count": -1, "enabled": true } ],
    "work":  [ { "item": "9x39mm BP", "count": 3, "enabled": true } ],
    "medical": [],
    "armor": [ { "item": "精英防弹背心", "count": -1, "enabled": true } ]
  }
}
```

- `count=-1` 无限；`1` 造一次后删除；`>1` 递减。
- 计划只决定「造什么」，实际是否解锁/材料是否够由游戏决定。

---

## 8. 错误处理与重试

| 异常 | 处理 |
|------|------|
| `AccessibilityServiceUnavailable` | 通知用户去系统设置开启，不重试 |
| `CaptureFailed`（全黑） | 重试 2 次 → 停止，提示 FLAG_SECURE/窗口问题 |
| `CaptureFailed`（其他） | 重试 `capture.retries` → 停止 |
| `PageNotRecognized` | 先按返回 2 次尝试回总览；仍失败 → 停止并通知 |
| `GameNotForeground` | 停止，通知用户切回游戏 |
| `InputUnavailable` | auto 模式切换后端一次；仍失败 → 停止 |
| `ITEM_NOT_FOUND` | 记录日志，跳过该部门，继续其他部门 |
| `MISSING_MATERIALS` / `BUY_FAILED` | 记录日志，跳过该部门；不扣计划次数 |
| 连续 3 轮 OCR 全空 | 停止（可能游戏黑屏/被保护） |
| `SafetyLimitReached` | 正常停止，通知 |
| 未知异常 | 记录堆栈 + 失败截图，停止 |

重试原则：**只有幂等的读取/截屏/OCR 可以重试**；点击后不盲目重试，先重新观察页面。

---

## 9. 安全设计

- 前台包名校验：默认开启；若 Accessibility 拿不到包名，则要求页面锚点匹配总览。
- 页面确认：每次点击前都必须知道当前页；未知页禁止点击。
- 动作上限：每轮最多 80 次点击/滑动，防止死循环。
- 最大循环/最长运行：0 表示不限，可配置。
- 自动买材料默认关闭；开启需在设置页二次确认。
- 暂停/停止：通知栏常驻；停止会立即取消协程，不再发出新点击。
- dry-run：完整跑识别与决策，只记录不点击；用于首次校准。
- 日志脱敏：不记录账号、UID；截图只存在应用私有目录。

---

## 10. 性能预算

| 操作 | 目标 |
|------|------|
| 截屏 | 100–300ms（受系统限速 ~333ms） |
| 全屏 OCR | 200–600ms（模拟器 CPU） |
| 单轮收菜 | < 15s（4 部门） |
| 列表扫描 | 每屏 OCR + 1 次滚动，最多 40 屏；通常 < 30s |
| 内存 | 峰值 < 200MB；bitmap 及时 recycle |

优化：

- OCR 只在必要时全屏；状态读取可先对锚点区域裁剪 OCR（v1 先全屏，简单可靠）。
- 每轮结束释放 Frame/bitmap。
- 列表扫描时复用同一张截图直到滚动。

---

## 11. 测试策略

### JVM 单测（`android/app/src/test/`）

- `TextParserTest`：计时器/价格/OCR 纠错/相似度/最佳匹配；
- `GeometryTest`：缩放/裁剪/越界；
- `CatalogIndexTest`：词典查询/分类；
- `PlanStoreTest`：FIFO、count=-1、递减、持久化（用临时文件，不依赖 Android）；
- `SchedulerTest`：DONE/IDLE+计划/CRAFTING/UNKNOWN；
- `HarvestFlowTest`：假 Observer/Crafter/PlanRepository，覆盖：
  - 收菜后补产；
  - 某部门物品找不到不影响其他部门；
  - 未知页面停止；
  - 动作上限；
  - 计划消费只在成功后。
- `PageDetectorTest`：用构造的 OcrResult 验证页面判定优先级；
- `DashboardStatusResolverTest`：标题锚点/网格推断/状态优先级；
- `CraftingControllerTest`：用脚本化 `ScreenOcr` 假画面 + 记录型 `InputController`
  覆盖收菜、补产（列表扫描/详情校验/缺材料/开始制造验证）、恢复总览分支；
- `ObserverImplTest`：空 OCR、四部门状态、单标题总览、非总览页、OCR 原文日志。

### Android 仪器测试（可选，需要模拟器）

- 无障碍服务连接、截屏非空、OCR 返回非空；
- 输入自检（root/accessibility）不崩溃；
- 配置加载/保存。

### 手工验收

- 模拟器 1920×1080 横屏，游戏停在特勤处总览：
  - dry-run 一轮，日志识别出四部门状态；
  - 关闭 dry-run，点开始，完成收取；
  - 制造一个已解锁物品；
  - 通知栏暂停/停止生效。

---

## 12. 构建与发布

- `gradle assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`；
- 无签名 release 需自建 keystore；v0.1 只要求 debug；
- 依赖全部来自 Google Maven / Maven Central；
- 版本号：`versionCode 1`, `versionName "0.1.0"`；
- 开源许可证 GPL-3.0；NOTICE 注明 DeltaForceSS。

---

## 13. 扩展点

- **新增输入后端**：实现 `InputController`，在 `InputFactory` 注册（例如 Shizuku）。
- **新增部门/物品**：只改 `catalog.json` 和 profile 的 `departmentLabels`；`Dept` 枚举需加值（少见）。
- **新增页面流程**：扩展 `Page` 枚举 + `PageDetector` + `CraftingController`。
- **换 OCR**：实现 `OcrEngine`，在 `AutomationController` 注入。
- **PC 版**：`pc/` 复用同一套业务规则与配置 schema（YAML/JSON 可转换），
  截屏换 dxcam/adb，输入换 SendInput/adb。

---

## 14. 已知未知（需要在模拟器上验证）

1. 手游特勤处总览是否显示「收取/领取」文字，还是必须点卡片后出现按钮；
2. 制造完成时是否真的没有状态文字（`doneWhenNoStatus` 是否适用）；
3. 物品列表是否单列/双列、OCR 能否稳定识别拉丁物品名；
4. 材料弹窗与交易行的实际按钮文字/层级；
5. 游戏是否启用 FLAG_SECURE；
6. 无障碍手势在目标模拟器/游戏中是否生效；
7. 游戏 UI 对 1920×1080 的适配（是否有黑边/安全区）。

这些未知都通过「dry-run + 校准 + 日志截图」在用户环境里验证；代码不硬编码猜测。
