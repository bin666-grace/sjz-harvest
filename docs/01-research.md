# 01 · 市场调研与业务逻辑拆解

> 调研日期：2026-11-18
> 目标：搞清楚市面「三角洲行动 · 特勤处收菜脚本」在做什么、怎么做、哪些能复用、
> 手游版与 PC 版的差异，以及 Android 端可用的技术边界。

---

## 1. 结论先行

1. 市面脚本的本质高度一致：**外部截屏 → OCR/模板识别 → 模拟键鼠/触摸**，
   不读内存、不改包、不注入进程。它复刻的是玩家在特勤处的手动点击流程。
2. 最成熟的公开参考是 **yi-zelin/DeltaForceSS**（PC，GPL-3.0，约 280 star）：
   `dxcam 截屏 → Tesseract OCR → pyautogui 鼠标宏`，四个部门自动收菜、补产、买材料。
3. **手游版没有现成的同等级开源实现**；手游 UI、触摸交互、包名、前台判断都不同，
   不能直接套 PC 坐标。需要重新做 Android 端感知与输入。
4. Android 端可行路径：
   - 截屏：`AccessibilityService.takeScreenshot()`（API 30+，无需每次授权）；
   - 识别：ML Kit 中文离线 OCR（返回文字包围盒，可直接得到点击点）；
   - 输入：无障碍 `dispatchGesture`（免 root，但部分游戏忽略）或 root `input tap`
     （模拟器通常可开 root，最可靠）；
   - 备选：Shizuku（shell 权限代理）、MediaProjection + 悬浮窗（本项目 v1 不用）。
5. 最大风险不是技术，而是**游戏 ToS / 行为检测 / 封号**，以及**截屏被 FLAG_SECURE 屏蔽**。
   本项目不做任何反检测规避。

---

## 2. 市面方案盘点

### 2.1 PC 端：DeltaForceSS（重点参考）

- 仓库：<https://github.com/yi-zelin/DeltaForceSS>（GPL-3.0）
- 技术栈：`dxcam` + `opencv` + `pytesseract` + `pyautogui` + `pywin32` + `rapidfuzz` + `ruamel.yaml`
- 作者自述：**截图 → OCR 文字识别 → 模拟鼠标、键盘输入**；不读取/更改游戏数据；
  收益约 270 万哈弗币/天。
- 关键实现（阅读公开源码后总结，非复制）：
  - 找窗口：`FindWindow('UnrealWindow', '三角洲行动  ')`；
  - 分辨率只支持 16:9：1920×1080 / 2560×1440 / 3840×2160，坐标按宽度缩放；
  - 总览页四个部门卡片各有一组 ROI：
    - `free` 区域 OCR「空闲中」；
    - `timmer` 区域 OCR `HH:MM:SS`；
    - 两者都没有 → 视为「已完成」；
  - 已完成：点卡片 → `Space` 收取；
  - 空闲且有队列：点卡片 → 进入物品列表 → 滚动 → 每行 OCR 物品名 →
    `rapidfuzz` 与目标词典比对（阈值：tech 71 / work 96.5 / medical 80 / armor 80）→ 点中目标；
  - 制造弹窗：用两个探针点的白色像素比例判断 3 材料/4 材料 →
    点购买 → 交易行 OCR 价格 → 点价格购买 → `Esc` 返回；
  - 材料齐 → 点「开始制造」；
  - 重读四部门状态 → 取最小剩余时间 + 30s 缓冲 → 睡眠；
  - 物品列表到底的判断：滚动后**首行物品名与上次相同**；
  - 已知限制：只支持 16:9；`.300 BLK` 三种子弹同名需手动置顶；OCR 失败 1 分钟后重启；
    后台太久会断网；赛季 UI 改动需要更新 `config.yaml`。
- 对我们的价值：业务闭环、状态判定、列表扫描、材料购买、下次唤醒算法都可以复用**思路**；
  但它是 PC 固定坐标 + 键盘，手游必须重做感知/输入层。

### 2.2 PC 端：Gitee `womeiid/script`

- 地址：<https://gitee.com/womeiid/script>（MulanPSL-2.0）
- 同样定位「特勤处自动制造/收菜」，S5 赛季；自称屏幕识别 + 鼠标键盘模拟，
  后台运行、免手动登录。
- 说明这个需求有持续市场，但实现路线与 DeltaForceSS 同类。

### 2.3 手游端：自动跑刀 / ADB 脚本

- 公开教程多为「Python + ADB + OpenCV 固定点位」：
  `adb exec-out screencap` → 模板匹配 → `adb shell input tap/swipe`；
  思路是每张地图/出生点写死动作序列。
- 这类方案与「特勤处收菜」不同：跑刀需要地图导航/环境理解，收菜只需要稳定的 UI 流程。
- 对本项目：ADB 方案可作为 PC 第二交付；但用户要求**手机 App**，
  所以主方案必须能在手机内完成截屏与点击。

### 2.4 通用 Android 自动化工具

- 自动点击器：Accessibility `dispatchGesture`，免 root；对普通 App 有效，
  对 OpenGL/Vulkan 游戏可能被忽略或检测。
- Shizuku：通过 ADB/root 启动 shell 服务，普通 App 可借 shell 权限执行
  `input tap/swipe`，是免 root 的可靠路径，但需要一次性启动。
- root：模拟器通常自带 root 开关，`su -c input tap` 最直接；真机需 root。
- MediaProjection：可连续截屏，但每次运行要用户授权，且需要前台服务；
  无障碍截屏更轻量，v1 采用后者。

---

## 3. 业务逻辑（手游版）

### 3.1 游戏内对象

- 入口：游戏主界面下方「特勤处」。
- 四个制造部门：技术中心（枪械）、工作台（弹药）、制药台（医疗）、防具台（护甲）。
- 每个部门一个制造位；制造需要材料与时间；完成后回部门收取。
- 交易行可购买部分材料（价格浮动），部分材料只能靠搜刮/兑换。
- 手游为横屏；不同模拟器分辨率不同，UI 位置会缩放/偏移。

### 3.2 手工流程（脚本要复刻的）

1. 进入特勤处总览；
2. 看四个部门：是否空闲、剩余时间、是否可收取；
3. 有完成的 → 进入部门 → 点「收取/领取」；
4. 空闲且想补产 → 进入部门 → 找到目标物品 → 确认材料 → 点「开始制造」；
5. 材料缺 → 去交易行买（可选）；
6. 回到总览，等下一个完成时间。

### 3.3 状态机

```
DASHBOARD
  ├─ DONE    → COLLECT → DASHBOARD
  ├─ IDLE + 有计划 → OPEN_LIST → FIND_ITEM → CRAFT_DIALOG → (BUY_MATERIAL)* → START_CRAFT → DASHBOARD
  └─ CRAFTING → 记录剩余时间
UNKNOWN → 停止/等待人工，禁止盲点
```

页面判定优先级：

1. 出现「开始制造/制造」按钮 → `CRAFT_DIALOG`；
2. 出现「交易行/最低价」→ `AUCTION`；
3. 出现四个部门标题（至少 1 个）且没有列表特征 → `DASHBOARD`；
4. 有物品列表文字/「制造」页签 → `DEPARTMENT`；
5. 否则 `UNKNOWN`。

### 3.4 时间调度

- 每轮结束后重读状态；
- 若存在 DONE，或 IDLE 且有计划 → 立即下一轮（0s）；
- 否则取所有 CRAFTING 的最小剩余秒数 + 30s 缓冲；
- 无 CRAFTING 且无事可做 → 60s 后重试；
- 最大循环/最长运行时间用于安全退出。

---

## 4. Android 技术边界（实测资料 + 官方 API）

| 能力 | 方案 | 限制 |
|------|------|------|
| 截屏 | `AccessibilityService.takeScreenshot()` | API 30+；需 `canTakeScreenshot="true"`；约 333ms 频率限制；`FLAG_SECURE` 黑屏/报错 |
| 截屏（备选） | MediaProjection | 每次运行需授权；需前台服务；系统投屏图标 |
| 点击 | `dispatchGesture()` | API 24+；游戏可能忽略；需主线程；有最小间隔 |
| 点击（可靠） | root `su -c input tap` | 需 root；模拟器可开；每次进程开销 ~50ms |
| 点击（免 root 可靠） | Shizuku shell | 需安装 Shizuku 并用 ADB/无线调试启动；v1 未集成 |
| 前台判断 | Accessibility `windows` / `rootInActiveWindow.packageName` | 游戏窗口可能返回 null；用包名 + 页面锚点双重校验 |
| 中文 OCR | ML Kit `text-recognition-chinese`（bundled） | 离线、随 APK 打包；模型约 16MB；识别速度 ~100-500ms/帧 |
| 模板匹配 | OpenCV Android | 需额外原生库；v1 不用，避免构建复杂度 |
| 持续运行 | AccessibilityService + 通知栏 | 无障碍服务常驻；通知栏提供暂停/停止；无需悬浮窗 |

**关键结论**：v1 用「无障碍截屏 + ML Kit OCR + root/无障碍双输入」。root 模式在模拟器上
最可靠；无障碍模式作为免 root 兜底。若两者都不可用，App 会明确报错，不静默失败。

---

## 5. 与参考实现的差异 / 我们的改进

| 维度 | DeltaForceSS（PC） | 本项目（Android） |
|------|-------------------|-------------------|
| 平台 | Windows + 游戏 PC 客户端 | Android App + 手游 |
| 截屏 | dxcam（DXGI） | AccessibilityService.takeScreenshot |
| 识别 | Tesseract + 固定 ROI | ML Kit 中文 OCR + 全屏文字锚点 + 模糊匹配 |
| 定位 | 固定坐标 + 列表行切分 | OCR 包围盒直接给点击点；固定坐标仅兜底 |
| 输入 | pyautogui 键鼠 | root shell / 无障碍手势 / dry-run |
| 收集 | Space | OCR 找「收取/领取」按钮 |
| 配置 | YAML + 赛季替换 | JSON assets + App 内编辑 + 校准页 |
| 买材料 | 默认开启 | v0.1 默认关闭（手游流程未验证） |
| 测试 | 无公开测试 | JVM 单测 + 假感知集成测试 |
| 风险控制 | 页面异常停止 | 前台包名 + 未知页停止 + 动作上限 + dry-run |

---

## 6. 风险与合规

1. **违反游戏 ToS**：任何自动化/宏都可能被判定为第三方软件，导致封号。
   公开项目声称「不碰内存就不封号」是营销话术，不成立。
2. **行为检测**：点击注入带有系统标志/异常时序，游戏可能识别；本项目不实现规避。
3. **截屏黑屏**：若游戏启用 FLAG_SECURE，Android 不提供合法绕过；App 会停止。
4. **交易行误购**：自动买材料涉及真实游戏资产；v0.1 默认关闭，开启前需人工验证流程。
5. **第三方 APK 风险**：本项目从源码构建；不要安装来源不明的「成品脚本」。
6. **模拟器 root**：root 会降低设备安全性；仅在专用模拟器中使用。

---

## 7. 参考资料

- DeltaForceSS（PC 参考实现，GPL-3.0）：<https://github.com/yi-zelin/DeltaForceSS>
- Gitee 同类项目：<https://gitee.com/womeiid/script>
- 手游包名/版本信息：<https://delta-force-hawk-ops-cn.en.uptodown.com/android/download>
- 三角洲行动官网：<https://df.qq.com/>
- 特勤处制造攻略（手游）：<http://orzice.com>
- Android 无障碍截屏限制：<https://stackoverflow.com/questions/70902879/>
- 无障碍手势在游戏中的限制：<https://shyu216.github.io/knownoevil/life/androidautoclicker.html>
- ML Kit 文字识别（中文）：<https://developers.google.com/ml-kit/vision/text-recognition/v2/android>
- Android `takeScreenshot` 文档：<https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshot(int,%20java.util.concurrent.Executor,%20android.accessibilityservice.AccessibilityService.TakeScreenshotCallback)>

> 免责声明：以上仅为技术调研，不构成对使用自动化脚本的鼓励。使用即表示自行承担
> 账号封禁、资产损失等风险。
