# 审查轮次 1 · 架构与实现静态审查

- 日期：2026-11-18
- 范围：三个子智能体交付的全部 Android 源码（foundation / platform / vision+business / UI），未运行真机
- 方法：按「安全边界 → 页面判定 → 点击链路 → 资源生命周期 → 可测试性」逐层读代码，
  对每个可疑点写出可复现的输入/状态与错误后果；只保留能落到具体代码行的问题。
- 结论：架构与分层符合 `AGENT.md` 第 4 节；发现 12 个需要修复的问题，全部在本轮修复。
  修复后 `:app:compileDebugKotlin` 通过，JVM 单测 66 个全过。

## 发现与修复

### R1-01（高）单部门标题被误判为总览

- 位置：`vision/PageDetector.kt` `detect()`
- 问题：只要识别到任意一个部门标题就返回 `DASHBOARD`。部门详情页、物品列表页通常也有
  自身标题，会被误判成总览；随后 `ObserverImpl` 把详情页文字当状态，`HarvestFlow`
  可能对错误区域点击，或把真正的列表页当成总览而不进入。
- 修复：`DASHBOARD` 要求 **≥2 个部门标题**；只有 1 个标题时，必须在该标题
  `statusSearchRadiusPx` 内找到空闲/完成关键词或计时器才判总览。其余情况文字数 ≥6 判
  `DEPARTMENT`，否则 `UNKNOWN`。
- 测试：`PageDetectorTest` 新增「单标题+空闲中」用例；原「单标题即总览」用例改为
  `UNKNOWN`。

### R1-02（高）列表页判定过宽，可能把总览当列表扫描

- 位置：`game/CraftingController.kt` `isListPage()`
- 问题：`DASHBOARD && labels.size <= 1` 被当作列表页。结合 R1-01，详情页/总览边界
  很容易互相误判，导致在总览上滚动、点错卡片。
- 修复：移除该分支。列表页只接受 `DEPARTMENT`，或「弱制造弹窗」（仅泛化「材料」、
  没有「开始制造」）。误判时返回 `PAGE_ERROR`，由上层恢复/停止，不盲点。

### R1-03（高）收取按钮文字与完成状态文字混用

- 位置：`core/config/ConfigModels.kt`、`game/CraftingController.kt`、`profile.json`
- 问题：`doneKeywords = ["收取","领取","已完成","完成"]` 同时用于状态判断和点击目标。
  OCR 把状态文本「完成」识别出来后会被当成按钮去点，可能点开无关面板。
- 修复：新增 `dashboard.collectButtonTexts = ["收取","领取"]`，仅用于点击；
  `doneKeywords` 保留给状态判断。`isCollectText` 只匹配 `collectButtonTexts`。

### R1-04（高）补产「成功」未验证就扣计划

- 位置：`game/CraftingController.kt` `handleCraftDialog()`
- 问题：点击「开始制造」后固定 `delay + back` 就返回 `SUCCESS`，`HarvestFlow` 随即
  消费计划并持久化。若点击未命中、材料校验失败、游戏弹了提示，计划会被错误扣减，
  且可能因为 `back` 关闭弹窗而看起来“流程正常”。
- 修复：点击后轮询至多 `pageTimeoutMs`，确认**制造弹窗与「开始制造」按钮都已消失**
  才返回 `SUCCESS`；否则返回 `PAGE_ERROR`，不消费计划。`dry-run` 不验证也不消费。
  弹窗关闭后若不在总览，返回一次；若已在总览则不再发 `back`，避免退出特勤处。

### R1-05（高）失败后立即重试形成空转

- 位置：`game/HarvestFlow.kt`
- 问题：补产失败只 `continue`，下一轮 `Scheduler` 看到 `IDLE + 有计划` 又返回 0，
  同一部门会立刻再扫 40 屏。缺材料/物品名不匹配时会形成高频 OCR 与点击循环。
- 修复：新增 `timing.failureBackoffSec`（默认 300s）。部门失败（收菜/补产/未知异常）
  进入冷却；冷却期内跳过该部门。若本轮 `nextWake=0` 的原因只剩被冷却部门，
  `nextWake` 至少取剩余冷却时间。

### R1-06（中）连续无法识别时无限运行

- 位置：`game/HarvestFlow.kt`、`core/config/ConfigModels.kt`
- 问题：`doneWhenNoStatus=false` 或 OCR 完全失效时，四部门都是 `UNKNOWN`，
  `Scheduler` 每 30s 重试且永不停止。
- 修复：新增 `safety.maxConsecutiveUnknownCycles`（默认 3）。整轮页面不在总览或
  四部门全 `UNKNOWN` 时累计，达到上限返回 `stopped=true`；任一状态识别成功即清零。

### R1-07（中）OCR 无条件 2× 放大

- 位置：`vision/MlKitOcrEngine.kt`
- 问题：1920×1080 截图放大成 3840×2160（约 33MB ARGB）后再交给 ML Kit，
  内存峰值高、耗时长，识别率提升有限；低配模拟器容易 OOM。
- 修复：宽度 ≥1600 时原图识别；小图才 2×。放大副本的回收改为
  `if (input !== bitmap) recycle(input)`，绝不回收调用方位图。

### R1-08（中）竖屏旋转兜底默认开启导致坐标错位

- 位置：`core/config/ConfigModels.kt`、`assets/config/app_config.json`
- 问题：`rotateIfPortrait=true` 时截图被旋转 90°，但手势坐标系可能仍是竖屏，
  点击整体错位。游戏本身强制横屏，旋转兜底弊大于利。
- 修复：默认改为 `false`；保留能力并在开启时记录警告。文档建议模拟器固定
  1920×1080 横屏。

### R1-09（中）短子串误匹配

- 位置：`vision/TextParser.kt` `bestMatch()`
- 问题：`normalized.contains(target) || target.contains(normalized)` 给 95 分。
  候选只有 1–3 个字时（如「弹」），作为长目标的子串也会得高分；物品名越短越危险。
- 修复：包含关系只在**被包含的一方长度 ≥3** 时给 95；否则退回 Levenshtein 相似度。

### R1-10（中）计划分类未参与校验

- 位置：`game/CraftingController.kt` `craft()`
- 问题：`PlanEntry.category` 已存在但只用于日志；不同分类下的同名物品可能串部门。
- 修复：计划项显式带 `category` 且 catalog 能解析该物品时，分类必须一致，
  否则返回 `PAGE_ERROR`，不点击。

### R1-11（中）自动买材料无价格上限

- 位置：`game/CraftingController.kt` `buyMaterials()`、`profile.auction`
- 问题：开启 `autoBuyMaterials` 后，任意价格都会点「买入」，交易行价格波动时
  可能高价成交。
- 修复：新增 `auction.maxUnitPrice`（默认 0=不限）。识别价格超过上限时
  `back` 回制造弹窗并返回失败，不点击买入。

### R1-12（低）无障碍配置缺 `canRetrieveWindowContent`

- 位置：`res/xml/accessibility_service_config.xml`、`AndroidManifest.xml`
- 问题：`rootInActiveWindow` 可能长期为 null，前台包名校验退化；服务声明
  `exported=false` 与官方示例不一致。
- 修复：补充 `android:canRetrieveWindowContent="true"`；服务改为
  `android:exported="true"`（仍受 `BIND_ACCESSIBILITY_SERVICE` 权限保护）。

## 未在本轮修改但已记录

- `recoverToDashboard()` 采用「先 back 再看页面」。如果页面被误判为
  `DEPARTMENT`，理论上可能从总览退回游戏大厅。当前通过收紧总览判据降低概率，
  真机验证时需重点观察。
- 截图全黑检测是 32×32 网格采样，极暗的真实游戏画面可能误报。阈值已很低
  （RGB≤8），仍建议真机观察。
- 校准页「开始制造兜底点」写入 SharedPreferences，尚未接入业务；当前业务只按
  OCR 找按钮。保留为后续 schema 扩展。
