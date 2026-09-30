# D-35 真实符号收工门

- 日期：2026-08-12（Asia/Singapore）
- 设备：`emulator-5554`，1080×2400
- 修复包：`~/Desktop/agentmirror-d35fix-7c56353.apk`
- APK SHA-256：`669cbc356797a7070dd4eb3599f58c37d9881fce3d3fd3b2c84c105041b76b1d`
- 边界：只做运行取证；未改产品代码，未切分支、未 commit/push，未触碰生产 daemon 或任何既有 tmux pane，配对凭据值未进入证据。

## 结论

**修复后那两个符号肉眼是三角。**

同一个隔离真实 Claude Code 会话中，修复前状态行是两个豆腐方框，修复后是两个小的右向实心三角；不是方框，也不是 `??`。修复后用户能看见这两个三角。

- 修复前：`before-v2-real-claude.png`
- 修复后：`after-fix-real-claude.png`
- 并排对比：`compare-real-claude-before-after.png`

## 真实码位

取证运行仅使用本任务自建隔离 socket `/private/tmp/tmux-501/d35-real-symbol`，会话 `d35-real-symbol`，pane `%0`；pane 前台进程为真实 `claude.exe`。没有使用 `printf` 合成状态行。

状态行前缀原始 UTF-8 字节为：

```text
20 20 e2 8f b5 e2 8f b5 20
```

其中两个 `e2 8f b5` 均解码为 `U+23F5 BLACK MEDIUM RIGHT-POINTING TRIANGLE CENTRED`。

证据：`real-claude-capture.txt`、`real-claude-capture-ansi.txt`、`bypass-line.txt`、`bypass-line.od.txt`、`bypass-line.hexdump.txt`、`bypass-codepoints.txt`、`isolation.txt`。

## 字形探针

| 码位 | 修复前 | 修复后 | 判定 |
|---|---|---|---|
| U+23F5 `⏵` | 豆腐块 | 小实心右三角（形近 U+25B8） | 已修 |
| U+25B8 `▸` | 真三角 | 真三角 | 未误伤 |
| U+25B6 `▶` | 真三角 | 真三角 | 未误伤 |
| U+25B7 `▷` | 真三角 | 真三角 | 未误伤 |
| U+25BA `►` | 真三角 | 真三角 | 未误伤 |
| U+2023 `‣` | 真符号 | 真符号 | 未误伤 |
| U+27A4 `➤` | 真符号 | 真符号 | 未误伤 |

修复后只有 U+23F5 从豆腐变为三角，其余六个形近符号保持原形，没有变豆腐或被替换。

证据：`before-v2-glyph-probe.png`、`after-fix-glyph-probe.png`、`compare-glyph-probe-before-after.png`。

## R1–R5 与 v2 基线对比

| 编号 | 修复后眼见结论 | 与 `baseline-v2` 对比 | 证据 |
|---|---|---|---|
| R1 输入框聚焦 | 输入框蓝色焦点框可见；其上方终端区持续存在。 | 不倒退 | `R1-input-focused-after.png` |
| R2 IME 最后一行 | ADB Keyboard 已弹起；终端末行、键条和输入框均在键盘上方，未被遮挡。 | 不倒退 | `R2-ime-last-line-after.png` |
| R3 CJK + Powerline | “中文终端渲染”正常；Powerline 三个符号均有实际字形。 | 不倒退，未见字形回退链连带损伤 | `R3-cjk-powerline-after.png` |
| R4 键条连按 | 连按上键后 shell 历史进入编辑行，页面未闪退。 | 不倒退 | `R4-key-repeat-after.png` |
| R5 捏合 | 前后截图 SHA-256 同为 `0a10bd5b…3ebdb`，未观察到缩放。 | 与 v2 基线相同；仅记现状，不判通过 | `R5-before-pinch.png`、`R5-after-pinch.png` |

R1 与 R2 由**同一帧共同佐证**，两文件 SHA-256 均为 `24b271b1…ad3e`，并非两张独立帧：R1 判读位置是底部输入框的蓝色焦点边框及其上方持续可见的深色终端区；R2 判读位置是屏幕最下方已出现的 ADB Keyboard，以及键盘上方仍可见的终端末行、键条和输入框。本报告显式按“一帧、两个判读区域”记证，不静默冒充两份独立证据。

## 输入框闪烁复检

**修复后仍不闪。**

有效录屏 `flicker-after-fix-valid.mp4` 时长 9.602622 秒，以 10 fps 抽取 96 帧至 `flicker-after-fix-valid-frames/`。逐帧和联系图目检：点开输入框、IME 进入、输入完成、发送、输入框清空及终端增行全过程中，终端区持续存在；未见整屏白帧、终端清空或大面积闪跳。

关键帧：

- 聚焦前：`flicker-key-focus-before.png`
- IME 过渡：`flicker-key-ime-transition.png`
- 聚焦后：`flicker-key-focus-after.png`
- 发送前：`flicker-key-send-before.png`
- 发送过渡：`flicker-key-send-transition.png`
- 增行后：`flicker-key-row-after.png`
- 联系图：`flicker-after-fix-valid-contact.png`

第一次录屏 `flicker-after-fix.mp4` 中 App 已回桌面，输入进入 Chrome 搜索框，故**不计入证据**。

## UI 审查全态

以下均为当前修复包的本轮新截图，落在 `e2e/artifacts/ui-review/`：

- 正常浅色：`d35-normal-light.png`
- 空态浅色：`d35-empty-light.png`
- 错误/重连态浅色：`d35-error-reconnecting-light.png`
- 深色正常态：`d35-normal-dark.png`

空态与错误态由本机临时隔离 daemon 驱动：独立端口、状态目录和空 socket 扫描目录；先在无会话时拍空态，再停止该隔离 daemon 拍“正在重连”错误态。未停止或改动生产 daemon；临时 daemon、凭据文件及目录已清理。

## 构建与安装

- 当前共享工作树 `:app:assembleDebug`：exit 0。
- 旧包卸载后全新安装到 `emulator-5554`：成功。
- APK：`~/Desktop/agentmirror-d35fix-7c56353.apk`
- SHA-256：`669cbc356797a7070dd4eb3599f58c37d9881fce3d3fd3b2c84c105041b76b1d`
- 产品代码零改动。
