# A/B/C 真机回归模拟器对照

重绘(尺寸变化): **A=有 B=有 C=有**

侧滑第三级落点: **A=桌面（App 退出前台） B=桌面（App 退出前台） C=第二级会话列表**

## 包与共同条件

- A：`agentmirror-v2baseline-7c56353.apk`，SHA-256 `9719857efd8b4cb4bd5848e4bb2953c10c2ad3a20632075c90d355e6e003ebce`
- B：`agentmirror-d35fix-7c56353.apk`，SHA-256 `669cbc356797a7070dd4eb3599f58c37d9881fce3d3fd3b2c84c105041b76b1d`
- C：`agentmirror-debug-20260812-v4.apk`，SHA-256 `250b5b30195d9bf0abfea19cd9aa621b74d5e8217efa9de9e524dcda09081581`
- 设备：同一 `emulator-5554`，1080×2400。
- 会话：同一任务自建隔离 tmux socket `/tmp/agentmirror-ab-redraw/socket-dir/ab-redraw`，session `ab-redraw`，pane `%0`，真实 `claude.exe`。
- 每组先卸载再全新安装，配对同一隔离 daemon；录屏前后 `mCurrentFocus` 均为 `dev.agentmirror.app/.MainActivity`。
- 录屏参数相同：1080×2400、12 Mbps、25 秒；A/B/C 分别 25.461478/25.480644/25.447000 秒，全量 10 fps 抽帧 255/255/254 张。

## 场景一：输入框变高与终端重绘

三组数字完全相同：

| 状态 | 终端 View bounds | 输入框 bounds |
|---|---|---|
| 未聚焦 | `[0,254][1080,2010]` | `[158,2174][896,2321]` |
| 聚焦、IME 已起 | `[0,254][1080,1896]` | `[158,2060][896,2207]` |
| 一行内容 | `[0,254][1080,1896]` | `[158,2060][896,2207]` |
| 两行内容 | `[0,254][1080,1833]` | `[158,1997][896,2207]` |
| 三行内容 | `[0,254][1080,1770]` | `[158,1934][896,2207]` |

事实判定：A/B/C 都在首次聚焦时将终端下边界上移 114 px；输入框由一行变两行、三行时，终端下边界又各上移 63 px。全量抽帧眼见终端底部已有状态行随每次尺寸变化整体向上跳，终端有效画布重新布局/重画；三组表现同形，没有看到 B 独有的额外重绘形态。

证据：

- A：`A-git-v2/redraw.mp4`、`A-git-v2/frames/`、`A-git-v2/contact.png`、`A-git-v2/bounds.txt`
- B：`B-d35fix/redraw.mp4`、`B-d35fix/frames/`、`B-d35fix/contact.png`、`B-d35fix/bounds.txt`
- C：`C-v4/redraw.mp4`、`C-v4/frames/`、`C-v4/contact.png`、`C-v4/bounds.txt`
- 同阶段帧：`keyframes/`

因此只就模拟器事实：输入框变高导致终端尺寸变化/整体重绘，并非 B(D-35) 独有；A、B、C 都有。用户所说“v2 已解决”的包与本次 A 组 git 基线包行为不一致。

## 场景二：左右边缘侧滑

每次都从第三级会话页重新进入，滑动前 UI 含 `claude.exe` 顶栏和输入框，且 `mCurrentFocus` 为 App。动作参数：左侧 `5,1200 → 500,1200`，右侧 `1075,1200 → 580,1200`，300 ms。

| 组 | 左缘右滑 | 右缘左滑 |
|---|---|---|
| A git v2 | `mCurrentFocus` 变 NexusLauncher；落桌面 | `mCurrentFocus` 变 NexusLauncher；落桌面 |
| B d35fix | `mCurrentFocus` 变 NexusLauncher；落桌面 | `mCurrentFocus` 变 NexusLauncher；落桌面 |
| C v4 | App 仍在前台；落第二级会话列表 | App 仍在前台；落第二级会话列表 |

C 组落点 UI 明确包含 `‹ 工作区`、工作区名和 `claude.exe` 会话行，是第二级，不是第一级。A/B 表现一致，C 明显不同。

证据：各组的 `left-before.xml`、`left-after.xml`、`left-*-focus.txt`、`right-before.xml`、`right-after.xml`、`right-*-focus.txt`。

## 无效尝试说明

早先一轮 A 组侧滑前焦点误在 Photos/Gmail，以及一轮过长 `adb input text` 导致 App 离开前台，均已作废并由上述有效实验重跑覆盖；不进入结论。上一轮 D-35 `flicker-after-fix-contact.png` 也不属于本报告证据链。

本席未修改产品代码。
