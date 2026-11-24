# 特勤处收菜（sjz）

[![License: GPL-3.0](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%2011%2B-3ddc84.svg)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7f52ff.svg)](#)
[![ML Kit](https://img.shields.io/badge/OCR-ML%20Kit%20on--device-4285f4.svg)](#)

《三角洲行动》手游 · 特勤处自动收菜 / 补产的 Android 原生 App。

> **English** — An Android automation app for Delta Force Mobile's 特勤处: take
> screenshots via AccessibilityService, read the screen with on-device ML Kit OCR,
> and replay taps with gestures or root to collect finished crafts and refill
> production plans. No memory reading, no traffic modification, no anti-cheat
> evasion. It still violates the game's ToS — use at your own risk.

通过 **无障碍截屏（AccessibilityService.takeScreenshot）→ ML Kit 中文离线 OCR →
root / 无障碍手势模拟触摸**，复刻玩家在特勤处的操作：

1. 识别「技术中心 / 工作台 / 制药台 / 防具台」四个部门的状态；
2. 收取已完成的制造；
3. 按生产计划补充制造（可选自动买材料，默认关闭）；
4. 计算下一次检查时间，循环运行。

> 本项目不读写游戏内存、不修改网络数据、不注入游戏进程，只是一个「看得懂屏幕的
> 高级连点器/宏」。**它仍然违反游戏用户协议，存在封号风险。使用风险自负。**

- 包名：`com.sjz.harvest`
- 游戏包名：`com.tencent.tmgp.dfm`
- 版本：0.1.0
- 最低 Android：API 30（Android 11）
- 许可证：GPL-3.0

---

## ⚠️ 风险与免责声明（务必阅读）

1. **违反游戏 ToS**：任何自动化/宏都可能被判定为第三方软件，导致警告、封禁或
   资产损失。公开项目声称「不碰内存就不封号」是营销话术，不成立。
2. **行为检测**：模拟点击带有系统标志与固定时序，游戏可能识别。本项目
   **不实现任何反检测、反作弊规避**。
3. **截屏黑屏（FLAG_SECURE）**：若游戏启用了 `FLAG_SECURE`，Android 不提供
   合法绕过方式；自检会报告「采样像素全黑」，自动化会停止。
4. **交易行误购**：自动买材料会真实消耗游戏内资产（哈弗币/材料）。v0.1 默认关闭，
   开启前请自行验证手游交易行流程，并在设置页二次确认。
5. **root 风险**：root 会降低设备安全性。请仅在专用模拟器/测试设备上使用。
6. **第三方 APK**：请从本仓库源码构建；不要安装来源不明的「成品脚本」。
7. 本项目与腾讯及《三角洲行动》官方无关，未获得授权。游戏名称、物品名称、
   商标归相应权利人所有。

---

## 功能概览

- **主界面**：无障碍状态、游戏前台状态、输入后端、运行阶段、下一轮倒计时、
  已运行轮数；四部门状态卡片；开始/暂停/停止、自检、生产计划、校准、设置；
  等宽字体运行日志（自动滚动）。
- **生产计划**：四个部门分页；条目启用开关、上移/下移、删除；从 `catalog.json`
  搜索物品并设置数量（`-1` 表示无限）；保存到 `plan.json`。
- **校准**：3 秒倒计时后通过无障碍服务截屏；矩形拖框 / 点选；自动换算为
  1920×1080 基准坐标；保存到 `profile.json`（或本地扩展配置）。
- **设置**：输入后端 `auto/root/accessibility/dry_run`、dry-run、自动买材料
  （二次确认）、最大轮数/最长运行、截屏间隔、动作延迟、下一轮缓冲；恢复默认。
- **自检**：无障碍开关/连接、截屏非黑、ML Kit OCR、root/无障碍/dry-run 输入、
  前台包名、profile/catalog/plan 加载、屏幕分辨率。

---

## 目录结构

```
sjz/
├─ android/                      Android App（主交付）
│  └─ app/src/main/
│     ├─ java/com/sjz/harvest/
│     │  ├─ ui/                  主界面、计划、校准、设置、RoiEditorView
│     │  ├─ tools/Diagnostics.kt 自检
│     │  ├─ core/                模型、配置、日志
│     │  ├─ capture/ input/ vision/ game/ plan/ service/
│     ├─ res/                    layout / values / drawable / xml
│     └─ assets/config/          app_config、profile、catalog、plan
├─ docs/                         调研与架构文档
├─ pc/                           Python + ADB 参考版本（第二交付）
├─ scripts/                      build_apk.bat / install_apk.bat
├─ README.md
├─ LICENSE                       GPL-3.0
└─ NOTICE.md                     参考项目与数据来源
```

---

## 构建

环境（本机已确认）：

| 项 | 值 |
|----|----|
| JDK | 17+（本机 21） |
| Gradle | 8.11.1 |
| AGP | 8.7.3 |
| Kotlin | 2.0.21 |
| compileSdk / targetSdk | 35 |
| minSdk | 30 |
| Android SDK | `C:\app\soft\android-sdk` |

在 PowerShell / cmd 中：

```bat
cd C:\app\soft\youxi\sjz\android
set GRADLE_USER_HOME=C:\app\soft\youxi\sjz\.gradle-home
C:\app\soft\gradle-8.11.1\bin\gradle.bat assembleDebug
```

> 必须使用项目独立的 `GRADLE_USER_HOME`：本机全局 `init.gradle` 与本项目的
> 仓库设置冲突。该目录内的 `init.gradle` 已配置腾讯云镜像，首次构建需联网。

或直接运行封装脚本（带路径与错误检查）：

```bat
C:\app\soft\youxi\sjz\scripts\build_apk.bat
```

产物：

```
android\app\build\outputs\apk\debug\app-debug.apk
```

---

## 安装与启动

```bat
C:\app\soft\youxi\sjz\scripts\install_apk.bat
```

手动方式：

```bat
C:\app\soft\android-sdk\platform-tools\adb.exe install -r android\app\build\outputs\apk\debug\app-debug.apk
C:\app\soft\android-sdk\platform-tools\adb.exe shell am start -n com.sjz.harvest/.ui.MainActivity
```

如遇 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，先卸载旧包：

```bat
adb uninstall com.sjz.harvest
```

---

## 首次使用步骤

1. **模拟器设置**
   - 分辨率建议 **1920×1080 横屏**（与 profile 基准一致，无需缩放）；
   - 开启 **root**（推荐；`su -c input tap` 比无障碍手势可靠）；
   - 安装并登录《三角洲行动》，手动进入「特勤处总览」。
2. **安装 App**，打开后进入「设置」，确认输入后端（模拟器建议 `root` 或 `auto`）。
3. **开启无障碍服务**
   - 主界面点「开启无障碍」，在系统设置 → 无障碍中找到
     「特勤处收菜 · 自动化」并开启；
   - 允许通知权限（Android 13+ 会弹窗），通知栏提供暂停/继续/停止。
4. **自检**
   - 主界面点「自检」；
   - 确认无障碍已连接、截屏非全黑、OCR 能返回文字、输入后端可用、
     profile/catalog/plan 能加载；
   - 分辨率项若显示不在 `supportedWidths`，请把模拟器改为 1920×1080 横屏。
5. **编辑生产计划**
   - 「生产计划」→ 选择部门 → 「添加物品」→ 搜索并选择 → 输入数量
     （`-1` = 无限）→ 保存。
6. **先跑 dry-run**
   - 「设置」中打开 `dry-run`（只识别、不点击）；
   - 回主界面点「开始」，把游戏切到前台，观察日志中的页面/部门状态识别；
   - 确认四部门状态与物品匹配无误后停止，关闭 dry-run。
7. **正式运行**
   - 点「开始」，切回游戏；
   - 脚本按周期收菜、补产，并在通知栏显示状态；
   - 主界面退出/后台不影响运行。

---

## 配置说明

四个 JSON 位于 `android/app/src/main/assets/config/`，首次启动复制到 App 私有目录
`filesDir/config/`；之后读取内部存储。修改配置后需重新启动自动化生效。

| 文件 | 作用 | 常见修改 |
|------|------|----------|
| `app_config.json` | 包名、输入后端、截屏间隔、时序、安全、日志 | 换模拟器、调速度、开 dry-run / 自动买材料 |
| `profile.json` | 基准分辨率、OCR 锚点、列表视口/滚动点、按钮文字、阈值 | 赛季 UI 大改、OCR 误判、换分辨率 |
| `catalog.json` | 部门 → 分类 → 物品名 + OCR 别名 | 新赛季新增物品（**请勿随意改物品数据**） |
| `plan.json` | 每个部门造什么、造几个 | 玩家生产计划（也可在 App 内编辑） |

关键字段：

- `input.backend`：`auto`（优先 root，其次无障碍）/ `root` / `accessibility` / `dry_run`；
- `capture.minIntervalMs`：截屏最小间隔，默认 400ms（系统限制约 333ms）；
- `timing.actionDelayMinMs/MaxMs`：动作间随机延迟；
- `timing.cycleBufferSec`：下一轮唤醒缓冲，默认 30s；
- `safety.maxCycles` / `maxRuntimeMinutes`：`0` 表示不限；
- `safety.autoBuyMaterials`：默认 `false`；开启会真实消耗游戏资产，需在设置页二次确认。

App 内「设置 → 恢复默认」会把四个配置全部覆盖回 assets 默认值。

---

## 赛季校准

默认策略是**全屏 OCR + 文字锚点 + 模糊匹配**，大多数情况下无需校准。出现以下情况时
再使用「校准」：

- OCR 找不到部门/按钮，或列表点错物品；
- 列表滚动不到底 / 滚动起点不合适；
- 赛季 UI 大改，需要重新标记列表视口或兜底点。

操作：

1. 在游戏里手动切到要校准的页面（总览 / 部门物品列表）；
2. 回到 App →「校准」→ 选择步骤；
3. 点「截屏」，在 3 秒倒计时内切回游戏（App 在后台也能截屏）；
4. 回到 App，在截图上**拖框**（矩形）或**单击**（点）；
5. 点「保存坐标」，映射为 1920×1080 基准后写入 `profile.json`；
6. 「清除该项」可恢复为默认策略。

可校准项：

| 步骤 | 写入字段 |
|------|----------|
| 列表视口（矩形） | `itemList.viewport` |
| 滚动起点（点） | `itemList.scrollFrom` |
| 滚动终点（点） | `itemList.scrollTo` |
| 收取兜底点（点） | `dashboard.collectFixedPoint` |
| 开始制造兜底点（点） | 本地扩展配置（当前 profile schema 无此字段，业务层使用 OCR 查找「开始制造」） |

> 坐标系以 profile 的 `baseWidth`/`baseHeight`（默认 1920×1080）为基准，按宽度比例
> 换算。模拟器建议直接使用 1920×1080 横屏，避免非 16:9 的垂直偏差。

---

## 故障排查

| 现象 | 排查 |
|------|------|
| 自检提示「无障碍未开启」 | 系统设置 → 无障碍中开启「特勤处收菜 · 自动化」；部分模拟器需在设置里允许后台运行 |
| 自检提示「服务实例为空」 | 开关一次无障碍服务；等待几秒再点自检；确认没有被系统省电策略杀掉 |
| 截屏全黑 | 游戏可能启用 `FLAG_SECURE`，或截到了受保护窗口；本项目不提供绕过；换模拟器/关闭安全窗口 |
| OCR 没有文字 | 确认游戏画面不是纯色/加载页；提高模拟器分辨率；检查 ML Kit bundled 模型是否随 APK 安装 |
| 点击无效 | 设置里切换 `root ↔ accessibility`；确认模拟器 root 开关；部分游戏忽略无障碍手势 |
| 前台包名不对 | 点开始前把游戏切到前台；自检在 App 内显示本 App 包名属于正常现象 |
| 坐标偏移 | 分辨率不是 1920×1080；改为横屏 1920×1080，或按宽度比例校准 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 先 `adb uninstall com.sjz.harvest` 再安装 |
| 构建失败 | 查看 Gradle 第一个错误；确认 `android/local.properties` 的 `sdk.dir` 与 JDK 17+ |

自动化遇到未知页面、游戏不在前台、OCR 连续失败时会**停止并记录日志**，不会盲点。
恢复：人工回到特勤处总览，再点「开始/继续」。

---

## 架构与文档

- [AGENT.md](AGENT.md)：项目决策、模块地图、赛季维护清单（长期记忆）；
- [docs/01-research.md](docs/01-research.md)：市场调研、业务拆解、Android 技术边界；
- [docs/02-architecture.md](docs/02-architecture.md)：分层、接口、配置 schema、错误策略；
- [docs/03-implementation.md](docs/03-implementation.md)：实际模块、算法、构建与测试结果；
- [docs/04-operations.md](docs/04-operations.md)：模拟器配置、安装、校准、dry-run、排障；
- [docs/reviews/round-1.md](docs/reviews/round-1.md)：第一轮静态审查与 12 项修复；
- [docs/reviews/round-2.md](docs/reviews/round-2.md)：构建/测试/第二遍审查与验证记录；
- [docs/reviews/round-3.md](docs/reviews/round-3.md)：实机截图驱动的制造流程/恢复逻辑适配；
- [NOTICE.md](NOTICE.md)：参考项目 DeltaForceSS 与数据来源说明。

分层依赖只能向下：

```
UI（Main/Plan/Calibration/Settings）
  └─ AutomationController / HarvestFlow
       └─ Observer / Crafter / Scheduler / PlanStore
  └─ SjzAccessibilityService（截屏 + 手势）→ root / 无障碍 / dry-run
  └─ vision（ML Kit OCR）/ core（模型、配置、日志）
```

---

## 测试

```bat
cd C:\app\soft\youxi\sjz\android
C:\app\soft\gradle-8.11.1\bin\gradle.bat testDebugUnitTest
```

纯 JVM 单测覆盖文本解析、几何缩放、物品词典、计划队列、调度与业务分支；
真实游戏 UI 需在模拟器上通过「自检 + dry-run + 校准」验证。

---

## 许可证

本项目采用 **GNU General Public License v3.0**，全文见 [LICENSE](LICENSE)。
物品名/业务事实参考了 GPL-3.0 的公开项目，详见 [NOTICE.md](NOTICE.md)。

```
This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.
```

GPL-3.0 官方文本：<https://www.gnu.org/licenses/gpl-3.0.txt>
