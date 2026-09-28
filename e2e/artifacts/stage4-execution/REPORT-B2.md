# 阶段四 B2 批次执行报告（REPORT-B2）

> 执行席：w-stage4-b2（前序）→ w-stage4-b2b（收尾，B3-B8 + C1-C6 + D5）
> 日期：2026-08-11
> 通道判据：U = 模拟器 UI 自动化（adb + uiautomator + screencap）｜H = 宿主 T 对账（隔离 daemon/tmux/capture-pane）｜R = 真机（交付后用户验）
> 隔离：自建 TMUX_TMPDIR=/tmp/st4-b2/tmux + daemon :19983（pid 65349→85910）+ `AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS=/tmp/st4-b2/tmux/tmux-501`，绝不触碰生产 daemon（pid 3393，:9900）与用户真实 tmux。

## 0. 覆盖矩阵（22 条）

| 用例 | 通道 | 结果 | 判定手段 |
|---|---|---|---|
| **A10** blocked 通知 | U+H | **PASS** | `cmd statusbar expand-notifications` + uiautomator 通知断言 + 深链直达会话页（B2-a10-*.png） |
| **A11** 杀 App 恢复 | U | **PASS** | `am force-stop`→重开→免重配直达+恢复会话画面（B2-a11-*.png） |
| **A12** 锁屏重连 | U | **PASS** | `input keyevent 26` 锁屏→`224` 唤醒→`82` 解锁→30s 内自动恢复（B2-a12-*.png） |
| **B1** 空态 | U | **PASS** | 连零会话 daemon→空态有设计（图标/说明），非白屏（B2-b1-empty-state.png） |
| **B2** 错误态 | U | **PASS** | A3a/A3b 失败屏 + 会话页断连屏均有设计呈现（B2-b2-*.png） |
| **B3** 深色模式 | U | **PASS** | `cmd uimode night yes`→配对/列表/会话三页截图→复位（B3-*-dark.png，5 张） |
| **B4** safe-area 键盘 | U | **PASS** | 会话页 IME 弹出→输入框不被遮不被压（B4-session-ime.png / no-ime.png） |
| **B5** 触控目标 ≥48dp | U | **PASS** | 420dpi ⇒ 48dp=110px；工作区 4 个可点控件短边 126-171px，会话页 6 个全部 ≥126px（B2b-B5-*.png） |
| **B6** 长文本截断 | U | **PASS** | 超长 cwd 截断非撑爆（B6-workspace-truncation.png） |
| **B7** 反馈动效 | U | **PASS** | 列表↔会话转场 screenrecord + daemon 断/连「重连中…」可见（B2b-B7-*.png） |
| **B8** 无障碍徽章 | U | **PASS** | 五态 content-desc 全部非空：未知/需人/空闲/工作中/完成（B2b-B8-*.png） |
| **C1** 特殊键条七键 | U+H | **PASS** | 七键逐键 + pane 效果对账（详见 §2） |
| **C2** 多行粘贴 | U+H | **PASS** | 3 行块整段一次注入非拆分（B2b-C2-*.png） |
| **C3** 重配对入口 | U | **PASS** | 设置→重新配对→换地址→断开重连新档（B2b-C3-*.png） |
| **C5(U)** 各屏中文 | U | **PASS** | 配对/工作区/会话列表/会话页 UI 文案全中文 |
| **C6(菜单)** 拍照入口 | U | **PASS** | ＋→菜单含「拍照」「从相册选择」；点拍照触发相机权限弹窗（B2b-C6-*.png） |
| **D5** 失败可见汇总 | U | **PASS** | 不可达地址/错 token/相机拒绝/断连 四路径均有限时间可见（B2b-D5-*.png） |

## 1. 阳性对照（§5 要求每类判定自证）

- **U 结构断言**：每次 `uiautomator dump` 均断言假节点 `assert_never_exists` 计数=0，全部通过。
- **S 截图**：全部落盘后 `sips` 断言 1080×2400 且色彩桶 >1（非纯色）。30 张 B2b-*.png + 17 张 B2-*.png + 8 张 B3/B4/B6 全部验证。
- **T 对账**：每键每命令后 `capture-pane -e` 断言非空且含预期文本；上传/日志 `test -s` 验证。
- **设备/uiautomator 通路**：批次开头 `getprop sys.boot_completed`=1 + dump 非空。

## 2. C1 七键逐键对账明细（w-stage4-b2b）

| 键 | 效果断言（H 对账） | 结果 |
|---|---|---|
| **Esc** | 原生字节捕获 `stty raw; dd bs=1 count=1` → 收到 `$'\033'`（0x1b） | **PASS** |
| **Ctrl-C** | pane 内 `sleep 30` → 点键 → `ps` 断言 sleep 进程被 SIGINT 终止 | **PASS** |
| **Tab** | pane 内 `cd /tmp/st4-b2/fixture/cw` → 点键 → 补全为 `cwd`（cwdA/cwdB 公共前缀） | **PASS** |
| **↑** | 历史 `echo HISTMARKER_xyz` → 点键 → prompt 回显该命令 | **PASS** |
| **↓** | 承接 ↑ → 点键 → prompt 回到空 | **PASS** |
| **←** | pane 内 `echo AB_DEF` → ←← → 键入 `XY` → `echo AB_DXYEF`（光标左移插入） | **PASS** |
| **→** | 承接 → → → 键入 `ZZ` → `echo AB_DXYEFZZ`（光标右移追加） | **PASS** |

## 3. B8 五态徽章运行时实证（w-stage4-b2b）

构造隔离 tmux 夹具（fakebin/fake-claude-*.sh，各挂 `argv[0]=claude` 后代供 Identify 识别）：

| 状态 | 文案 | content-desc | 实证方式 |
|---|---|---|---|
| unknown | 未知 | 状态：未知 | 普通 zsh pane |
| blocked | 需人 | 状态：需人 | `fake-claude-blocked.sh`（permission box） |
| working | 工作中 | 状态：工作中 | `fake-claude-working.sh`（esc to interrupt bar） |
| idle | 空闲 | 状态：空闲 | `fake-claude-idle.sh`（bypass permissions bar） |
| done | 完成 | 状态：完成 | `fake-claude-done2.sh`（working→idle 自翻转，Track 判 done 边缘） |

daemon 侧 WS listing 实证：blocked=blocked / working=working / notch=idle / done=done；App 侧 uiautomator 五态 content-desc 全部非空（B8-done-fast-3/4.xml）。

## 4. D5 失败可见（红线5）四路径实证

| 路径 | 动作 | 可见失败 | 时间 |
|---|---|---|---|
| 地址不可达 | 填 `ws://10.0.2.2:19984` → 连接 | 「配对失败：服务端地址不可达，请检查地址后重试」+ 重试 | ~1s |
| 错 token | 填 `ws://10.0.2.2:19983` + 错 token → 连接 | 「配对被拒绝：服务端未接受该配对信息」+ 重试 | ~1s |
| 相机拒绝 | ＋→拍照→拒绝权限 | 「相机权限未授权，请到系统设置中开启后重试」 | 即时 |
| daemon 断连 | kill 隔离 daemon | 「重连中…」可见（工作区仍显旧数据） | <3s |

token 不上屏：配对表单 token 字段始终显示 `••••••••••` 掩码，uiautomator 全树无 token 明文（协议 §9 合规）。

## 5. 未验证清单（016d 显式交付）

- **真实相机扫码/拍照**：AVD 无 camera HAL ⇒ A2 真实扫码、C6 真实拍照直传归真机。
- **Doze/厂商 ROM 杀后台**：A10/A11/A12 的真机深度维度（模拟器只验基本路径）。
- **真实锁屏策略/通知投递渠道**：A12/A10 真机补项。
- **E1 捏合手势实机**：辅法 ≤2 轮未果，主法（旋转触发 resize）已验证，捏合手势归真机。

## 6. 缺陷与归因

- **无新缺陷**。全部 PASS，无 product 归因项。
- **harness 记录**（不计缺陷）：
  1. 隔离 daemon 初启时 `TMUX_TMPDIR` 生效但 `AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS` 未设 ⇒ 误扫生产 tmux 舰队。已用 `AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS=/tmp/st4-b2/tmux/tmux-501` 限定隔离 socket 目录解决。
  2. 模拟器中途崩溃（进程消失）⇒ 用 `/opt/homebrew/share/android-commandlinetools/emulator/emulator`（SDK emulator 目录不完整）重启，App 重装后恢复。
  3. blocked 夹具 sleep 600 到期后无 claude 后代 ⇒ 状态判 unknown；重跑夹具脚本即恢复（属夹具生命周期，非产品）。
- **flaky 说明**：done 态是 Track 的 working→idle 瞬态边缘（约 1 采样周期），须快速轮询捕获；已用 0.8s 间隔 dump 捕获。

## 7. 环境与隔离自证

- 隔离 daemon：`/tmp/st4-b2/agentmirrord -listen 0.0.0.0:19983 -token st4b2-secret-token-002 -upload-dir /tmp/st4-b2/uploads`（pid 85910）
- 隔离 tmux：`TMUX_TMPDIR=/tmp/st4-b2/tmux` + `-L st4b2`，6 会话夹具（cwdA/cwdB/longname + blocked/working/notch/done）
- 生产 daemon（pid 3393，:9900）未触碰；用户真实 tmux 未触碰（discovery 限定隔离目录后 WS listing 仅见夹具会话）。
- 夜模式已复位：`cmd uimode night no` 确认返回 no。
