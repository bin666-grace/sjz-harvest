# 04 · 安装与运行手册

适用版本：v0.1.0（Android App）。目标环境：Windows + Android 模拟器（推荐
MuMu / 雷电 / 夜神），游戏《三角洲行动》手游国服，包名 `com.tencent.tmgp.dfm`。

> 使用本工具违反游戏用户协议，存在封号风险。脚本只做截屏识别和模拟触摸，
> 不读写游戏内存、不修改网络数据、不注入进程，也不包含任何反检测逻辑。

## 1. 模拟器准备

1. 新建实例，建议 **Android 11+（API 30+）**，64 位。
2. 分辨率设为 **1920×1080，横屏**；DPI 默认即可。
   - 其他 16:9 分辨率（1280×720、2560×1440）可用，profile 会按宽度缩放。
   - 实机截图为 2712×1220（非 16:9），profile 的列表/详情区域已按它标定；
     该宽度也已加入 `supportedWidths`。
3. 关闭「自动旋转」，固定横屏。
4. 开启 **root**（设置 → 其他/高级 → root 权限）。本工具不强制 root，
   但模拟器上 `su -c input tap` 比无障碍手势可靠得多。
5. 安装并登录游戏。停在**主界面**即可：脚本会点底部「特勤处」进入总览；
   也可以手动进入**特勤处总览**，确认四张部门卡片可见。
6. 关闭游戏内可能的“高帧率/HDR/滤镜”叠加层（若截屏异常再排查）。

实机 UI 要点：

- 主界面底部导航：仓库 / 部门 / 交易行 / 特战干员 / 特勤处 / 改枪台；
  「特战干员」和「特勤处」紧挨着，App 用归一化固定点 `dashboard.lobbyEntryPoint`
  点击特勤处（OCR 只预审是否在主界面），避免点进干员选择界面。
- 特勤处总览卡片：技术中心 / 工作台 / 制药台 / 防具台，空闲时显示「空闲中」；
- 部门物品页是「左列表 + 右详情」同屏，没有独立制造弹窗；
- **生产前请把自己要造的物品置顶/收藏为列表第一行**：App 默认
  `itemList.firstRowSelect=true`，直接点第一行固定点，不再按物品名扫描滚动；
  右侧详情的 OCR 只作预审（读到的标题与计划物品不一致会中止）；
- 材料不足时底部按钮为「一键补齐」，**v0.1 不会点击它**，会直接放弃该部门；
- 材料充足时才会出现「开始制造」。该状态尚未在截图中确认，首次真机运行请重点观察。

确认 adb 能连上：

```powershell
C:\app\soft\android-sdk\platform-tools\adb.exe devices
# 应看到 emulator-5554  device
```

## 2. 构建与安装

```powershell
# 构建（项目自带独立 Gradle 缓存，避开全局 init.gradle 冲突）
cd C:\app\soft\youxi\sjz\android
$env:GRADLE_USER_HOME = "C:\app\soft\youxi\sjz\.gradle-home"
C:\app\soft\gradle-8.11.1\bin\gradle.bat :app:assembleDebug

# 安装
C:\app\soft\android-sdk\platform-tools\adb.exe install -r `
  C:\app\soft\youxi\sjz\android\app\build\outputs\apk\debug\app-debug.apk

# 启动
C:\app\soft\android-sdk\platform-tools\adb.exe shell am start `
  -n com.sjz.harvest/.ui.MainActivity
```

也可以直接双击 `scripts\build_apk.bat` 和 `scripts\install_apk.bat`
（脚本内是绝对路径，换机器需改路径）。

如果 adb 提示 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，先卸载旧包：

```powershell
adb uninstall com.sjz.harvest
```

## 3. 首次授权

1. 打开 App → 底部「无障碍设置」→ 系统设置中找到 **SjzHarvest / 特勤处助手** →
   开启。返回 App，状态卡应显示“无障碍已连接”。
2. Android 13+ 首次点「开始」会请求通知权限。拒绝也能运行，但通知栏的
   暂停/继续/停止按钮不可用，建议允许。
3. 点「自检」，逐项确认：
   - 无障碍开关/连接：✅
   - 截屏：能拿到非全黑画面；若提示 FLAG_SECURE，本工具无法绕过（见第 8 节）
   - OCR：能识别出屏幕上的中文
   - 输入后端：root 或 accessibility 至少一个可用
   - 前台包名：切到游戏后再点自检，应显示 `com.tencent.tmgp.dfm`
   - profile / catalog / plan：加载成功
   - 分辨率：1920 宽为最佳

## 4. dry-run 验证（必做）

1. 设置页 → 打开 **dry-run**（只识别、不点击）。
2. 手动切到游戏特勤处总览（或在主界面，让脚本自动点「特勤处」）。
3. 回 App → 开始 → 15 秒内切回游戏。
4. dry-run 下如果停在总览，补产会记录“请手动进入部门物品页”并跳过；
   要验证物品匹配，请在启动后手动进入目标部门列表。
5. 观察日志/主界面：
   - 是否识别到 4 个部门标题；
   - 每个部门状态是 `空闲 / 制造中 HH:MM:SS / 待收取 / 未知`；
   - 计划中的物品名是否被正确选中；
   - 是否出现“页面不符”“找不到按钮”等错误。
5. 若识别错误，先别关 dry-run，进入第 5 节校准。

## 5. 校准（识别不准时）

入口：主界面 →「校准」。

1. 选择校准项：
   - **物品列表视口**：一般不需要；全屏 OCR 默认即可。
   - **列表滚动起点/终点**：默认取屏幕 50%/75% → 50%/30%；若滚动无效再校准。
   - **收取兜底点**：仅当 OCR 找不到「收取/领取」且你知道固定按钮位置时使用。
   - **开始制造兜底点**：预留项，当前业务不读取，可忽略。
2. 点「截屏」，3 秒内切到目标游戏页面，截图会显示在编辑器里。
3. 按住拖框（矩形项）或点击（点项），保存。
4. 坐标自动换算到 1920×1080 基准，其他分辨率按宽度缩放。

赛季 UI 大改时优先改 `profile.json` 的文字锚点/阈值（见第 7 节），
而不是重标坐标。

## 6. 生产计划

- 主界面 →「生产计划」→ 选择部门 Tab → 添加物品。
- 物品从 `catalog.json` 搜索选择；数量 `-1` 表示无限，`0` 不允许。
- 每个部门按列表顺序取第一条启用且数量非 0 的计划。
- 制造**确认成功后**数量减 1；数量为 1 时移除。失败不扣次数。
- 运行中修改计划，需停止后重新开始才生效。
- 默认计划：技术中心「3/7可调倍率狙击镜」、工作台「9x39mm BP ×3」、
  医疗「战地医疗箱」、防具「精英防弹背心」。请按自己的需求修改。

## 7. 配置与恢复默认

配置文件首次启动复制到 App 内部存储：

```
/data/data/com.sjz.harvest/files/config/
├── app_config.json
├── profile.json
├── catalog.json
└── plan.json
```

- 设置页可改：输入后端、dry-run、自动买材料、最大轮次/运行时长、
  截屏间隔、动作间隔、周期缓冲。
- 需要改文字锚点/阈值/新增物品：`adb pull` 出配置，修改后 `adb push` 回去
  （或直接改 `assets/config/` 重新构建，仅对未生成过配置的新安装生效）。
- 设置页「恢复默认」会覆盖全部 4 份配置，请先备份 `plan.json`。

adb 路径示例：

```powershell
adb shell run-as com.sjz.harvest ls files/config
adb exec-out run-as com.sjz.harvest cat files/config/profile.json > profile.json
adb push profile.json /sdcard/profile.json
adb shell run-as com.sjz.harvest cp /sdcard/profile.json files/config/profile.json
```

## 8. 运行

1. 关闭 dry-run（确认第 4 节识别正确后）。
2. 保持游戏已登录并停在特勤处总览。
3. App →「开始」→ 15 秒内切回游戏。
4. 通知栏出现常驻通知，可暂停/继续/停止；App 主界面也能控制。
5. 周期示例：某部门剩余 2:30 → 下次唤醒约 3:00（+30s 缓冲）；
   有空闲部门且有未完成计划 → 立即补产；有待收取 → 立即收菜。

停止方式：通知栏「停止」、App「停止」、或强制停止 App。暂停只在当前轮次
结束后生效，不会在半途松手。

## 9. 故障排查

| 现象 | 可能原因 | 处理 |
|---|---|---|
| 启动即停：无障碍未连接 | 服务未开启/被系统杀 | 系统设置重新开启；关闭电池优化；App 内重试 |
| 启动即停：游戏不在前台 | 15s 内没切回游戏 | 重开，尽快切到游戏；或设置里关闭前台校验（不推荐） |
| 截屏全黑 / FLAG_SECURE | 游戏启用安全窗口 | 无法合法绕过；换模拟器/关闭相关安全渲染；本工具会停止 |
| OCR 识别不到文字 | 画面过暗、分辨率过低、模型未加载 | 自检 OCR；提高亮度；用 1080p；重启 App |
| 找不到部门标题 | 锚点与当前 UI 不符/语言不同 | 用校准截图核对，修改 `profile.json.dashboard.departmentLabels` |
| 状态总是“未知” | OCR 漏字 | 打开设置里的「记录 OCR 原文」看实际文字；调大 `statusSearchRadiusPx`；把实际状态词加入 idle/done 关键词 |
| 状态总是“待收取” | 手动开启了 `doneWhenNoStatus` 且没识别到状态 | 设为 false（默认），或把实际状态词加入 `doneKeywords` |
| 收取按钮点不到 | 按钮文字不同/按钮在详情页 | 把实际文字加入 `collectButtonTexts`；或校准兜底点 |
| 点击完全没反应 | 无障碍手势被游戏忽略 | 设置 `backend=root`，确认模拟器 root；用自检验证 |
| 点「特勤处」却进入干员选择 | 底部「特战干员」紧挨特勤处，OCR 包围盒中心偏移 | 检查 `dashboard.lobbyEntryPoint`（默认 0.37/0.882）；换分辨率后按截图重新标定，或设为 null 退回精确文字中心 |
| 置顶的物品仍未被选中 | 第一行固定点不准 / 未开启 first row 模式 | 检查 `itemList.firstRowPoint`（默认 0.17/0.239）与 `firstRowSelect=true`；确保目标确实被游戏置顶在第一行 |
| 补产找不到物品 | 物品名不在 catalog/阈值过严（仅 `firstRowSelect=false` 的旧扫描模式） | catalog 加别名；调低对应部门 `itemSimilarity` |
| 补产停在“未出现制造弹窗” | 点到了描述行/未选中物品 | 校准列表视口；检查物品行是否可点 |
| 补产失败后 5 分钟不重试 | 失败冷却 | 正常保护；可调小 `timing.failureBackoffSec` 或重启 |
| 连续 3 轮未知后停止 | `maxConsecutiveUnknownCycles` | 修正识别；或调大/设为 0 |
| 自动买材料不生效 | 默认关闭 / 交易行锚点不符 | 设置里开启（有风险）；先 dry-run 验证交易行识别 |
| 计划数量没减少 | 制造未确认成功 | 看日志是否 `PAGE_ERROR`；确认弹窗已关闭 |
| App 在后台被系统杀死 | 省电策略 | 自动化已运行在 specialUse 前台服务；仍需在系统里允许后台/关闭省电限制 |
| 自动化卡住几分钟后才恢复 | MIUI/EMUI 冻结后台进程 | 启动时按提示把本 App 设为「不限制电量」；确认通知栏常驻 |
| 周期中切到其它 App | 前台守卫（`requireGameForeground`） | 预期行为：绝不截屏/点击，本轮中止并在通知栏提示；切回游戏后自动继续，等待超过 10 分钟才停止 |

日志：App 主界面实时日志；Logcat 过滤：

```powershell
adb logcat -s SjzA11y SjzA11y Automation HarvestFlow Crafting Observer MlKitOcr ScreenSource
```

失败截图：`/data/data/com.sjz.harvest/files/debug/failure_*.png`

```powershell
adb exec-out run-as com.sjz.harvest ls files/debug
adb exec-out run-as com.sjz.harvest cat files/debug/failure_xxx.png > failure.png
```

## 10. 安全使用建议

- 首次使用务必 dry-run；确认四部门状态和计划选择正确后再真跑。
- 先只放 1 个部门、数量 1 的简单计划试一次。
- 保持 `autoBuyMaterials=false`，除非你已确认交易行流程并设置了
  `auction.maxUnitPrice`。
- 不要把 `maxCycles`/`maxRuntimeMinutes` 设为 0 长时间无人值守；建议先设
  `maxCycles=10` 观察。
- 游戏更新后先重新 dry-run；UI 变化会导致识别失败，本工具会停止而不是盲点。
- 本项目不提供任何规避检测的手段；账号风险由使用者承担。
