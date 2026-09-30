# v2 基线门禁报告

- 日期：2026-08-12（Asia/Singapore）
- 设备：`emulator-5554`，1080×2400
- 源版本：`7c5635364012eb2a6659ada725bad6fe93167c60`，精确 tag `v2-baseline`
- APK：`~/Desktop/agentmirror-v2baseline-7c56353.apk`
- APK SHA-256：`9719857efd8b4cb4bd5848e4bb2953c10c2ad3a20632075c90d355e6e003ebce`
- 边界：未改产品代码；本报告所有写盘均在 `e2e/artifacts/baseline-v2/`。

## 核心结论

**v2 基线不闪。**

本结论只依据模拟器连续录屏抽帧目检：

1. 点开输入框、IME 弹起：`flicker-input-send.mp4`（20.2679 秒）以 10 fps 抽出 203 帧。逐帧与联系图目检，终端区在输入框聚焦、IME 进入期间持续存在，未见整屏白帧、终端清空或大面积明暗闪跳。
   - 原始录屏：`flicker-input-send.mp4`
   - 全量抽帧：`flicker-input-send-frames/frame-*.png`
   - 时序联系图：`contact-input.png`
   - 关键帧：`flicker-input-before.png`、`flicker-input-ime-transition.png`、`flicker-input-after.png`
2. 发消息增行：在任务专属 shell 会话发送文本，`flicker-send-line.mp4`（12.5541 秒）以 10 fps 抽出 126 帧。眼见输入框清空、终端新增命令及错误输出行；增行前后及过渡帧中未见整屏白帧、终端清空或大面积闪跳。
   - 原始录屏：`flicker-send-line.mp4`
   - 全量抽帧：`flicker-send-line-frames/frame-*.png`
   - 时序联系图：`contact-send-line.png`
   - 关键帧：`flicker-send-before.png`、`flicker-send-transition.png`、`flicker-send-after.png`

第一次发送录屏中，发送坐标因 IME 上移而未命中，故该段只用于“点输入框/IME”判定，未冒充增行证据；增行结论来自第二段重新录制的有效动作。

## R1–R5 回归基准

| 编号 | 实测结论 | 证据 |
|---|---|---|
| R1 输入框点开后终端区 | 输入框获得焦点且 IME 弹起后，终端区仍完整可见；连续录屏未见闪烁。 | `R1-input-focused-terminal.png`、`flicker-input-send.mp4`、`contact-input.png` |
| R2 IME 时最后一行 | IME 弹起时终端最后一行和输入框均可见，未被键盘遮挡。 | `R2-ime-last-line-visible.png` |
| R3 CJK + Powerline | CJK“中文终端渲染”和 Powerline 三个符号均有实际渲染；其中前两个 Powerline 三角可见，第三个特殊字形形态按截图留作基线。 | `R3-cjk-powerline.png` |
| R4 键条连按 | 连续点三次上键后，shell 历史文本进入编辑行；键条仍可见、页面无闪退。此项是现状截图基线，不作延迟量化。 | `R4-key-strip-repeat.png` |
| R5 捏合后画面 | 向 `/dev/input/event1` 注入真实双指捏合后，前后 PNG SHA-256 完全相同（`7638c3…c7d`），且未生成 `cell_size.xml`；本轮未观察到缩放效果。按现场基要求仅记录现状，不作通过判定。 | `R5-before-pinch.png`、`R5-after-pinch.png`、`R5-cell-size-before.xml`、`R5-cell-size-after.xml` |

## D-35 / D-36 复现

### D-35：复现

在任务专属 `baseline-v2-e2e` pane 输出与 Claude Code 状态栏相同的 Unicode 符号序列及 `bypass permissions on · 1 shell` 文本。App 实际渲染为两个空方框，符号没有正常字形。证据：`D35-bypass-actual-rendering.png`。

本报告采用需求原始口径“符号显示为空”；不采用冲突文档中的“透明红框”说法。这里复现的是相同字形序列的渲染回退，不声称模拟了完整 Claude Code UI。

### D-36：复现

在任务专属 pane 生成 `D36-HISTORY-001` 至 `D36-HISTORY-160`。App 底部画面显示 075–160 后，连续两次执行上滑（`540,1700 → 540,450`，1200 ms），动作前、第一次后、第二次后三张截图 SHA-256 均为：

`db3196818ffbeb385a83dbf4cfa5c3639504fb0678de06db3c57c98b1ba42ee7`

眼见画面完全不动，无法查看 001–074 的更早历史，D-36 复现成立。证据：`D36-before-up-swipe.png`、`D36-after-up-swipe.png`、`D36-after-second-up-swipe.png`。

## 构建与纪律核对

- `:app:assembleDebug`：exit 0；构建缓存与产物重定向到本目录。
- `adb install -r`：Success。
- 9 张主要截图均经 `sips` 核对为 1080×2400。
- 取证期间发现 `app/app/src/test/kotlin/dev/agentmirror/app/termview/TermSurfaceSessionBindingRegressionTest.kt` 于 18:43 成为未跟踪文件；它不属于本席改动，且 `assembleDebug` 不编译测试源。本席未触碰该文件。
- 未切分支、未 commit、未 push；未输出或截取配对凭据值。
