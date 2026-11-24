# 审查轮次 2 · 构建、测试与第二遍代码审查

- 日期：2026-11-18
- 范围：第一轮修复后的完整工程；真实执行 Gradle 编译、JVM 单测、Android Lint
- 环境：Windows 11、JDK 21、Gradle 8.11.1（`GRADLE_USER_HOME=.gradle-home`）、
  Android SDK `C:\app\soft\android-sdk`（android-35、build-tools 34.0.0/35.0.0）
- 结论：`assembleDebug`、`testDebugUnitTest`、`lintDebug` 均成功；APK 60.17MB；
  66 个 JVM 单测全过。第二次通读又修复 3 个问题。**仍未在真实游戏/模拟器上验收**。

## 一、构建阶段暴露的问题（已修复）

### R2-01（阻断）KDoc 中的 `/*` 造成嵌套注释，吞掉整个配置文件

- 位置：`core/config/ConfigModels.kt:11`、`core/config/AssetConfigRepository.kt:18`
- 现象：`ConfigModels.kt` 本身没有直接报错，但后续所有使用 `AuctionProfile`、
  `TextProfile`、`UiProfile` 的文件报 `Unresolved reference`；`AssetConfigRepository`
  报 `Syntax error: Unclosed comment`。
- 原因：Kotlin 块注释**可以嵌套**。KDoc 里的 `assets/config/*.json` 中 `/*` 开启
  内层注释，本行末尾的 `*/` 关闭了内层，外层注释继续吞掉后面的声明，直到遇到下一个
  `*/`。于是 schema 类被当成注释，编译单元“看起来”没有这些类。
- 修复：改写为 `assets/config/ 下的 *.json`，不再出现 `/*` 序列。
- 教训：注释中引用通配符路径必须写成 `config/ *.json` 或转义；已写入 AGENT.md
  交接清单。

### R2-02（阻断）ML Kit `process()` 不接受 Bitmap

- 位置：`vision/MlKitOcrEngine.kt`
- 现象：`None of the following candidates is applicable: process(MlImage), process(InputImage)`。
- 修复：`InputImage.fromBitmap(input, 0)` 后调用 `process()`；增加
  `com.google.mlkit.vision.common.InputImage` import。

### R2-03（中）自适应缩放后的位图生命周期

- 位置：`vision/MlKitOcrEngine.kt`
- 问题：加入“大图不放大”后，`upscaled` 可能指向调用方位图；原 `finally` 无条件
  `recycle()` 会回收调用方正在使用的截图。
- 修复：只有发生真实放大时才把副本赋给 `upscaled`；原图路径 `upscaled=null`，
  `finally` 不回收。

## 二、测试阶段暴露的问题（已修复）

### R2-04（中）测试仍按旧判页契约断言

- 现象：4 个用例失败——单标题判总览、轻微错字判总览、3 条文字判列表、
  `bestMatch` 包含关系。
- 处理：前三个按新契约更新（单标题 → `UNKNOWN`；列表需要 ≥6 条文字）；
  新增「单标题 + 空闲中 → 总览」。`bestMatch` 的问题不是测试错，而是 R1-09 的修复
  过度：OCR 行「9x39mm BP 可制造」包含完整候选「9x39mm BP」是合法匹配，不能删。
  最终规则：包含方向不限，但**被包含字符串长度必须 ≥3**。
- 结果：66 tests / 0 failures。

### R2-05（低）`isListPage()` 仍保留单标题总览分支

- 位置：`game/CraftingController.kt`
- 问题：R1-01 收紧后，单标题 + 状态文字只会在“真总览”成立；此时 `labels.size<=1`
  仍被当列表页，会把总览当物品列表扫描。
- 修复：删除该分支，列表页只认 `DEPARTMENT` 或弱制造弹窗。

## 三、第二遍通读的其余结论

以下问题**未修改**，保留为真机验证项，避免在没有游戏画面的情况下过度设计：

1. **恢复动作可能盲退**：`recoverToDashboard()` 先发 `back` 再判页。若页面被误判，
   理论上可能从总览退回大厅。已通过“总览需 ≥2 标题或标题旁有状态”降低概率；
   真机应验证首次启动、加载页、结算弹窗三种场景。
2. **全黑检测采样**：32×32 网格、RGB≤8。极暗场景可能误报；若真机出现
   “截屏连续全黑”，先关闭游戏内 HDR/滤镜验证，再考虑提高采样密度。
3. **收取点击未二次验证**：点「收取/领取」后未确认按钮消失。奖励弹窗会干扰判断，
   当前依赖下一轮状态刷新兜底；失败会进入 300s 冷却，不会高频连点。
4. **物品匹配可被描述文本干扰**：列表若把物品描述拆成独立文本框且包含完整物品名，
   可能点到描述行。多数情况下仍在同一卡片内，风险可接受；后续可用
   `itemList.viewport` 或分类约束进一步收紧。
5. **校准兜底点未接线**：`CalibrationActivity` 的「开始制造兜底点」保存在
   SharedPreferences `calibration_extras`，`CraftProfile` 无对应字段。当前 OCR
   可找到按钮，不构成阻塞；后续加 `craft.startPoint` 时一并迁移。
6. **PC 交付仍是骨架**：`pc/src/sjz` 只有接口与基础类型，未实现 ADB 截屏/点击与
   OCR 主循环。主交付 Android App 已可用；PC 版列为后续工作。

## 四、验证记录

```text
:app:assembleDebug       BUILD SUCCESSFUL
:app:testDebugUnitTest   BUILD SUCCESSFUL, 66 tests, 0 failures, 0 errors
:app:lintDebug           BUILD SUCCESSFUL, 0 errors, 40 warnings
APK                      android/app/build/outputs/apk/debug/app-debug.apk (60,173,616 B)
```

Lint 警告均为非阻断：固定竖屏 Activity（有意，配置页竖屏）、`notifyDataSetChanged`、
资源未使用、`SetTextI18n`、布局硬编码箭头等。无 `NewApi`、无权限错误。

## 五、尚未完成的验收（必须真机/模拟器）

- 用真实手游画面确认：四部门标题、空闲/计时器/完成状态、收取按钮、物品列表、
  制造弹窗、交易行锚点是否与默认 profile 一致。
- 确认 root `input tap` 与无障碍 `dispatchGesture` 在目标模拟器上哪一个真正生效。
- 确认 1920×1080 横屏下 OCR 包围盒与手势坐标一致。
- 用 dry-run 跑完整一轮，核对日志中的部门状态与计划选择。
- 真实计划补产一次，确认“点击开始制造 → 弹窗关闭 → 状态变为计时器 → 计划扣减 1”。
