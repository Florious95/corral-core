# 阶段四执行方案（stage4-execution-plan）

> 状态：方案先行稿（plan-stage4-execution 交件）｜**只产出文档，不跑任何用例、不改任何代码。**
> 用例执行等阶段三收口后另派席位，本文件是后继执行席位的**逐条作业手册**。
> 输入：`e2e/artifacts/dogfood/TESTPLAN.md`（43 条用例）｜`docs/perf-scenarios.md`（A–F 六组）
> ｜`docs/next-round-plan-20260810.md` §3.5（三通道分工）｜`requirement-base/entries/016`（真机权威）
> ｜`requirement-base/entries/013`（五层体系 + 失败四归因）｜`docs/round-findings-20260811.md`（P-3 与 F1）。
> 红线：不跑用例、不改代码；自动化必要非充分、验收权在真机（016）；可自动化度不决定覆盖优先级（016c）。

---

## 0. 口径校正（执行席开工前必读）

### 0.1 用例总数是 **43 不是 41**

派单与 taskbook 写「41 条」，但 `TESTPLAN.md` §10 已有算术勘误（裁定席 msg_2e00d012b430）：
A 组表格实际 **17** 条（A1–A9a、A10、A10a、A11、A12），总计 = **17 + B8 + C6 + D5 + E7 = 43**。
本方案逐条覆盖全部 **43** 条，不留 2 条死角。执行席**以本文件 §1 总表为准**，
不要被「41」误导跳过 A9a/A10a 这类后补编号。

### 0.2 上一轮（dogfood）已执行与未执行的底账（防重复、防漏跑）

`e2e/artifacts/dogfood/REPORT.md` 三档闭合：**完整执行 26 ／ 局部执行 2（C1、D5）／
完全未执行 15**。未执行项即阶段四必须补跑的优先面（016(d)：未执行一律不得计入已验收）。
完全未执行 15 项 = A9a、A10、A10a、A11、A12、B2（会话页断连那一面）、B3、B7、B8、C2、E1、E3、E7
＋ C1/D5 的缺口部分（C1 只验 2/7 键，余 5 键要补；D5 缺 A9a 上传失败路径）。已修不复报清单
（TESTPLAN §11）只对「疑似回归」时报。在途案（feat-ts-wire）现象只记不计数（TESTPLAN §12）。

### 0.3 输入矛盾处置：§3.5「真机列」与用户「能测的全测」的取舍

`docs/next-round-plan-20260810.md` §3.5 把「锁屏重连、通知投递」列进真机通道；但用户裁定原文
（§1 与 taskbook ①）是「**模拟器能测的全测**」。取舍规则：**凡不依赖相机、不经 Extended Controls
GUI 窗口寻址的路径，模拟器一律可测**（用 adb shell 命令驱动，见 §2 不可用面）。故：

- A10 通知投递、A12 锁屏重连、A11 杀 App 恢复的**基本路径划模拟器**（`cmd statusbar` 展开通知栏 /
  `input keyevent 26` 锁屏 / `am force-stop`，均为纯 adb 命令，已实证可用）。
- §3.5 真机列在此处的含义收窄为**全保真维度**：厂商 ROM 杀后台、Doze 调度、真实设备锁屏策略、
  真实通知投递渠道——这些模拟器**无法代表**（REPORT U-5），列为真机补充项，与模拟器基本路径**并存**，
  不互为替代。

本取舍显式列出供 leader 复核；若 leader 意图是「A10/A12 整项只走真机」，改回即可，但那样会与
用户「能测的全测」冲突，需 leader 先与用户对齐。

---

## 1. 逐条用例通道归属总表（43 条）

通道记号：**U** = 模拟器 UI 自动化（adb + uiautomator + screencap）｜**H** = 宿主 T 对账
（隔离 daemon/tmux/文档，零设备依赖，随模拟器批次环境执行）｜**R** = 真机（交付后用户验）。
API/instrumentation 通道在本阶段**只承接 TS 网络**（§6 D1），43 条中**没有一条划进 API 通道**——
TESTPLAN 无 TS 专用用例，TS 已由 feat-ts-wire/headscale 脚本（API 通道）覆盖，不在本批重跑。

### A 组 · 016 首触九步（主干，最先最重）

| 用例 | 通道 | 判定手段（U 结构断言 / S 截哪屏 / T 对账） |
|---|---|---|
| **A1 首装冷启路由** | U | `pm clear dev.agentmirror.app`→冷启→`uiautomator dump`：断言**配对页特征节点非空**（标题「连接主机」类文本 / 手填表单 / 扫码钮任一）。**截**「首装冷启」屏。无白屏 = 结构树非空且含配对页节点，且 `screencap` 尺寸=1080×2400 非纯色。 |
| **A2 扫码配对（QR 载荷核对 + 入口）** | U+H + R(真实扫码) | H：读隔离 daemon 启动横幅候选 ws URL，断言**不含** 198.18/169.254/utun/awdl/bridge（R-003 缺陷 A 未回归）。U：点「扫码」→断言权限弹窗出现→允许后取景态（或拒绝后走 A2a 降级）。**截**「扫码入口」屏。**真实相机识别 = 真机**（TESTPLAN U-1 设计排除，模拟器无相机）。 |
| **A2a 拒绝相机权限降级** | U | `pm clear`→冷启→点「授予相机权限」→系统弹窗选拒绝→断言出现**手填降级引导文案节点**（D-01 已修：二次拒绝须有可见原因+去设置引导）。**截**「拒绝后降级」屏。 |
| **A3 手填配对成功** | U | 填 `ws://10.0.2.2:19983/ws`+正确 token→有限时间内断言**工作区列表节点出现**；过程有进度态；**token 不上屏**：uiautomator 全树 grep 无 token 明文（协议 §9）。**截**「配对成功进入列表」屏。 |
| **A3a 错 token** | U | 填错 token→断言**显式拒绝原因节点**（人话、不泄 token 值、可重试）。**截**「错误态」屏。 |
| **A3b 服务端未起/地址不可达** | U | 隔离 daemon 停→填合法地址→断言**有限时间**（≤配对时钟泵上限，fix-pairing-timeout-pump 后为 15s 级）显式失败**含地址**，非无限「连接中」。**截**「不可达错误态」屏。 |
| **A4 工作区列表两级分组** | U+H | 预置隔离 tmux：同一 cwd 2 session + 另一 cwd 1 session→U：断言一级分组节点数=2、各分组 session 数=2/1；H：`capture-pane`/daemon listing 对账。**截**「列表页」屏。 |
| **A5 聚合状态徽章** | U+H | 预置一个 pane 跑真实交互程序（如 `read x`）制造 blocked→断言该组徽章**非全灰 unknown**、为 blocked 态（色+文案）；H 对账 daemon 状态字段。**截**「徽章」屏。 |
| **A6 打开会话秒开** | U | 点会话→同步起 `screenrecord` 或逐帧 `screencap`，断言**体感 ≤1s 出首屏**（首个非空白帧时间戳 <1000ms）。**截**「首帧」屏。数值门限已由 e2e 层1 负责，本轮只做体感。 |
| **A7 CLI 画面一致** | U+H | 会话内跑夹具脚本（256 色 recap `48;5;254;38;5;16`、`47;30` 白底黑字、默认背景 CJK、框线、emoji）→**截图 vs 隔离 tmux `capture-pane -e` 逐项对照**：字形完整无豆腐块、等宽对齐、颜色/背景块一致、CJK 不重叠不黑块（018-7）。**截**「终端夹具」屏，**leader 逐图目检**。 |
| **A8 输入回显与回执** | U+H | 输入英文/中文/emoji 分次发送→H：`capture-pane` 断言 pane 内准确回显；U：断言发送回执可见。**截**「输入后」屏。 |
| **A9 发图注入** | U+H | `adb push` 一张图→MediaStore 可见→「+」相册选图→断言上传进度/结果提示→H：`-upload-dir` 出现该文件→断言主机路径被插入输入框→发送→pane 收到该路径（H 对账）。**截**「上传结果」屏。 |
| **A9a 上传失败可见** | U | daemon 停→选图上传→断言**明确失败文案 + 草稿保留**（红线5，D-02/D-03 修后复验该失败路径）。**截**「上传失败」屏。 |
| **A10 blocked 通知** | U+H + R(Doze 延递) | 前置 `pm grant ... POST_NOTIFICATIONS`（API35 运行时权限）→pane 制造 blocked→App 退后台→等待→`cmd statusbar expand-notifications`→uiautomator 断言**通知出现且文案含会话/blocked 信息**→点击→断言**深链直达该会话页**（会话页特征节点，MainActivity.handleDeepLink 路由）。**截**「通知栏」屏。真机补：真实设备 Doze 下通知延递。 |
| **A10a 通知全局开关** | U | 设置页→找通知开关→关闭→断言开关态翻转且后续 blocked 无通知（D-15 已修，运行时确认）。**截**「开关」屏。 |
| **A11 杀 App 恢复** | U + R(厂商 ROM) | 会话页→`am force-stop`→重开→断言**免重配直达**、恢复到被杀前会话画面与导航位置（uiautomator 会话页节点 + 截图对比杀前）。**截**「恢复后」屏。真机补：厂商 ROM 杀后台策略。 |
| **A12 锁屏重连** | U + R(Doze/ROM) | 会话页→`input keyevent 26`（power）锁屏 60s→`input keyevent 224`（wake）唤醒→`input keyevent 82`（menu）解除 keyguard（无 PIN 模拟器标准解锁序列，任一失败换 `wm dismiss-keyguard`）→断言 **30s 内自动恢复画面**、期间断连态可见、不无限重连（fix-reconnect-stale-config 复发才报）。**截**「解锁恢复」屏。真机补：Doze/厂商 ROM 深度。 |

### B 组 · 018 视觉标准专项（每屏按 §3 目检卡七条逐图执行）

| 用例 | 通道 | 判定手段 |
|---|---|---|
| **B1 空态** | U | 连零 tmux 会话的 daemon（或 `kill-session` 清空）→列表页**截**图→断言设计过的空态（图标/说明/下一步动作），非白屏非裸一行。 |
| **B2 错误态** | U | A3a/A3b 失败屏 + **会话页断连屏**（上一轮缺口）→**截**图→断言设计过的错误呈现，非裸英文异常串。 |
| **B3 深色模式全屏走查** | U | `cmd uimode night yes`→重走 配对/列表/会话 三页→逐屏**截**图→`cmd uimode night no` 复位→**leader 逐图目检** 018-1（全可读、无反色残留、无浅色硬编码块）。 |
| **B4 safe-area 与键盘** | U | 会话页 tap 输入框→IME 弹出→**截**图断言：内容不被状态栏/手势条压；键盘弹出时输入框可见不被遮（SessionScreen imePadding 已修，复验）。 |
| **B5 触控目标 ≥48dp** | U | `uiautomator dump`→解析主要可点控件 bounds→断言短边 ≥110px（API35 420dpi ⇒ 48dp=110px）。纯结构断言。 |
| **B6 信息层级与长文本截断** | U+H | 预置超长 cwd 与超长 session 名→**截**图 + U：断言长路径**中段省略**/长名尾部省略，非换行撑爆（018-3）。 |
| **B7 反馈与动效** | U | `screenrecord` 短片（列表↔会话来回、连接态变化）→抽关键帧断言有点击态/转场、状态不闪跳（018-6）→**leader 目检关键帧**。 |
| **B8 无障碍徽章语义** | U | `uiautomator dump` 徽章节点 `content-desc`→断言五态徽章非空（017 R-7；StateBadge 已核，运行时补 dump 实证）。 |

### C 组 · 017 当期必做项

| 用例 | 通道 | 判定手段 |
|---|---|---|
| **C1 特殊键条** | U+H | U：断言键条存在且七键齐（Esc/Ctrl-C/Tab/↑↓←→）。**逐键点击 + 每键 H 断言 pane 内效果**（补上轮 2/7 缺口）：Esc 打断、Ctrl-C 打断、Tab 补全、↑ 调历史、↓ 前进、←/→ 行内移动。**七键全验**。 |
| **C2 多行粘贴整段注入** | U+H | 剪贴板三行→粘贴→发送→H：`capture-pane` 断言**整段一次注入非拆分逐行执行**（017 R-2；代码已核 SessionViewModel 整段一条 input.text，运行时补实证）。 |
| **C3 重配对入口（单档覆盖）** | U | 设置页→「重新配对」→换地址→断言**断开并重连到新档**（D-14 运行时侧，fix-dogfood-pairing-ux 后入口可达）；U 断言入口节点可达。 |
| **C4 token 轮换文档化** | H | 查 `README.md`/`docs/protocol.md`→断言含「删 token 文件重启即全量吊销」说明（017 R-4；D-11 已修，复验）。 |
| **C5 锁中文并 README 明示** | H+U | H：README 含「当期锁中文」明示（017 R-6，D-12 已修）；U：各屏 UI 文案全中文（截图目检）。 |
| **C6 拍照直传** | U(菜单) + R(实拍) | ①U：会话页「+」→断言菜单含「拍照」入口（D-02 已修）；②**真实相机实拍→上传→路径注入 = 真机**（相机属不可用面）。 |

### D 组 · 工程常识红线五条（宿主 T 为主，零设备依赖）

| 用例 | 通道 | 判定手段 |
|---|---|---|
| **D1 静默经济** | H | 隔离 daemon 全部断开 5 分钟→`top`/`ps` 断言 CPU 趋近 0、无固定频率派生 tmux 子进程（红线1；fix-daemon-idle-cpu 后复验）。 |
| **D2 进程卫生** | H | ①同端口二启隔离 daemon→断言**显式失败可见**（单实例守卫，二启报错）；②收尾后 `lsof -i :<port>` + 进程表断言零监听零孤儿（红线2）。 |
| **D3 资源有界** | H | 查上传目录/日志的上限或轮转说明存在（红线3；D-13 fix-upload-auth 已修，复验生效）。 |
| **D4 可达性常识** | H | daemon 启动横幅候选地址清单→断言不含 198.18/169.254/utun/awdl/bridge（红线4；R-003 缺陷 A 未回归）。 |
| **D5 失败可见（汇总）** | U | 汇总 A3a/A3b/A9a/C1 各失败路径→断言每个动作有限时间内可见结果（红线5；补上轮缺的 A9a 路径）。 |

### E 组 · 其余需求条目的可观察行为

| 用例 | 通道 | 判定手段 |
|---|---|---|
| **E1 捏合缩放 → CLI 重排** | U | **主法（确定性）**：`settings put system user_rotation 1` 触发视口尺寸变化→TermViewPresenter 重算 rows/cols→resize 帧→H：`capture-pane` 断言**主机 tmux 尺寸随动 + CLI 重排**；恢复 `user_rotation 0`。**辅法（有界）**：`sendevent` 双指注入 virtio_input_multi_touch 设备（本机已实证该设备存在）模拟捏合→断言字号变化→tmux 尺寸随动。**非位图缩放**：截图对比（缩放后为重排而非拉伸）。辅法 ≤2 轮尝试，失败即记 E1「捏合手势实机」缺口交真机，**不阻塞主法结论**（主法已验证 005 resize 语义）。 |
| **E2 滚动/回到底部/触顶分页** | U | 造几百行输出→`input swipe` 上滑→断言视口锁定 + 「↓回到底部」按钮出现→点击回底→触顶能分页加载更多（006；上轮已验，复发才报）。 |
| **E3 alt-screen TUI 进出** | U+H | pane 开 `vim`→**截**图断言 alt-screen 正确渲染→`:q` 退出（主机 send-keys 或 App 键入）→断言主屏与历史恢复（006 已知边界）。 |
| **E4 大输出洪峰** | U | pane 内 `yes | head -50000`→断言不闪退不冻结、洪峰后画面正确、滚动仍可用（红线3 内存有界）。 |
| **E5 daemon 重启自愈** | U+H | 会话页中 kill 隔离 daemon→断言 App **可见断连态**→重启 daemon→断言自动恢复、流继续（004 无状态免疫）。 |
| **E6 断网自愈** | U | `cmd connectivity airplane-mode enable` 15s→恢复→断言断连态可见→**45s 内自愈到原画面**（003 标准3；飞行动作是 adb shell 命令，不经 Extended Controls）。 |
| **E7 输入即时性主观判定** | U | 连续快速输入 N 字符→H：`capture-pane` 断言逐字无卡顿、**不掉字、顺序正确**（003 标准1 零延迟）。 |

### X 组 · 探索性（不计入覆盖矩阵，本轮不强制；有余力才跑）

X1 大字号 `cmd uimode` font_scale｜X2 旋转横屏再转回｜X3 极长名/空格 CJK cwd｜X4 会话被主机 kill-session｜
X5 连点/快速来回切页｜X6 超长单行 >4KB｜X7 换 token 重连。**X 组按 TESTPLAN §10 不计入分母**，
作为修复回炉轮的补充探索，不做独立批次。

---

## 2. 已知不可用面（避免重蹈 2026-08-10 空转八代）

1. **相机**：本 AVD（wedding_user_a_api35）**无真实摄像头**（系统镜像无 camera HAL），
   真实扫码/拍照**不可测**。凡需相机帧的用例 → **真机**（A2 真实扫码、C6 真实拍照直传）。
2. **Extended Controls 的 GUI 窗口寻址**：对模拟器控制台窗口做鼠标级操作已**实证不可用**
   （2026-08-10 为此空转八代）。**凡依赖其 GUI 的能力一律不用**。
3. **不受影响的操作（明确可用，本方案全部采用 adb shell 命令）**：
   - 权限弹窗（A2a）——系统权限对话框，与相机硬件无关；
   - 通知栏展开 `cmd statusbar expand-notifications`（A10）；
   - 锁屏/唤醒 `input keyevent 26/224`（A12）；
   - 深色模式 `cmd uimode night yes`（B3）；
   - 飞行模式 `cmd connectivity airplane-mode enable`（E6）；
   - 旋转 `settings put system user_rotation`（E1）；
   - 双指捏合 `sendevent` 直写 `virtio_input_multi_touch_*`（已实证存在，E1 辅法）。
4. **不受代表的真机维度**（REPORT U-5）：Doze/厂商 ROM 杀后台、真实锁屏策略、真实通知投递、
   真实多网卡与耗电——模拟器**能测基本路径**，但**全保真验收权在真机**（016b）。

---

## 3. 判定方式规范（U / S / T 三类 + 目检卡 + 失败四归因）

### 3.1 U 结构断言（uiautomator）——写什么

- 取树：`adb shell uiautomator dump /sdcard/u.xml && adb shell cat /sdcard/u.xml`。
- 断言形状：**节点存在 + 属性条件**，例如
  `//node[@text='连接主机' or contains(@content-desc,'重连')]`（Compose 控件经 uiautomator 的
  content-desc/text 映射，按实测节点形状为准，但**每条用例必须给出具体匹配谓词**，不许写"看有没有对应控件"）。
- **几何断言**：B5 用 `@bounds` 解析短边 ≥110px；B6 用长文本节点的文本长度/省略号判定。
- **token 不上屏**（A3）：对全树做字符串 grep，断言**不含** token 明文。
- 深链断言（A10）：点击通知后树中出现会话页特征节点（如会话标题/键条）。
- **逐条断言目标已在上表给出**，执行席按表落地脚本；**断言失败先归因（§3.3）再报**。

### 3.2 S 截图目检——截哪一屏、怎么命名

- 命令：`adb exec-out screencap -p > e2e/artifacts/stage4-execution/<case>-<step>.png`。
- **每屏固定截**：上表「截…屏」列即必截图；每用例**至少动作前/后两张**，leader 目检时能看出状态变化。
- 落盘后**立即校验**：`sips -g pixelWidth -g pixelHeight` 断言 1080×2400（防截到黑屏/空帧，§5 阳性对照）。
- **018 目检卡七条逐图执行**（TESTPLAN §9）：M3 主题 / safe-area / 信息层级截断 / 密度 48dp /
  状态可视五态 / 反馈动效 / 终端页专项。任一不过即落 REPORT 缺陷。
- **leader 逐图目检**（018 审查关）：执行席交付时按用例分组贴图清单，**逐张**给出七条结论；
  测试绿但目检不过 = 打回（ui-redesign 先例）。

### 3.3 T 对账（tmux/主机）与失败四归因（013）

- H 对账统一用隔离 tmux `capture-pane -e`、daemon listing、`-upload-dir` 文件、`lsof`/进程表。
- **失败四归因必做**（013）：任何失败先归因再上报，归因结论写进 REPORT，不许只写"失败"。
  - **product**：断言目标该在而不在、行为不符 → 报缺陷（P0/P1/P2 按 TESTPLAN §0.4）。
  - **harness**：设备未就绪 / uiautomator 空树 / daemon 未起 / capture 空 → 先修测试装置再重跑，
    不记 product。
  - **baseline**：命中既有已知缺陷/已修不复报清单 → 记录对照条款，不计新缺陷。
  - **flaky**：时序抖动，复跑 ≥2 次统计后再判，不靠单次通过/失败定案（013 老化纪律）。
- **判错归因比失败本身危害更大**（013）——归因拿不准时写 harness 倾向并留证，不硬扣 product。

---

## 4. 执行顺序与批次切分（Gradle 模块锁）

### 4.1 并发约束（CLAUDE.md 实证铁律，违者即冲突）

- **同一 Gradle 模块同一时刻只放一席**：`:app` 与 `:terminal` 是两个独立锁；任何
  `./gradlew :app:*`（assembleDebug/test/lint）都会编译整个 `:app` 模块，把别的席写到一半的源码
  一起编进去——**UI 自动化批次与 `:app`/`:terminal` 施工席不得并行**。
- **模拟器同时只放 1 席**（多席分时共用实证）。隔离 daemon/tmux 每批自建自清
  （`TMUX='' TMUX_TMPDIR=/tmp/st4.XXXX/tmux tmux -f /dev/null` + `-listen 0.0.0.0:19983` +
  独立 `AGENTMIRROR_STATE_DIR` 与 `-upload-dir`），绝不触碰生产 daemon（pid 3393，:9900）
  与用户真实 tmux；测试一律 `env -u TEAM_AGENT_*`。

### 4.2 排期形状（阶段三收口后）

```
[前置] 阶段三收口：tools/gate/run.sh 三面全绿 + archwiki --check 通过。
       此后 :app / :terminal 模块零施工席在途，才放本批第一个坐席。

B1 ── 模拟器 UI 批次 · 首触主干（独占 :app 编译 + 独占模拟器）
     ：装配最新 debug APK（app/app/build/outputs/apk/debug/app-debug.apk）
     → 预置隔离 tmux 夹具（3 session / 2 cwd + 一个 blocked pane + 长路径 session）
     → 跑 A1,A2,A2a,A3,A3a,A3b,A4,A5,A6,A7,A8,A9,A9a
     → 逐屏截图 + 结构断言 + 阳性对照，落 REPORT。
     参考 wall-clock：一次 assembleDebug + 13 条首触用例 ≈ 1 席独占半天级。

B2 ── 模拟器 UI 批次 · 后台/通知/视觉（同一模块锁，接续 B1）
     → 跑 A10,A10a,A11,A12 + B1,B2,B3,B4,B5,B6,B7,B8 + C1,C2,C3,C5,C6(菜单) + D5
     → 逐屏截图 + 断言 + 对照，落 REPORT。

B3 ── 宿主 T 批次（零设备依赖，与 B1/B2 不同窗口并行可行，但需独立 daemon 端口）
     → 跑 C4,C5(H 面),D1,D2,D3,D4。纯 shell + 隔离 daemon，不碰 :app 编译单元。

B4 ── 性能批次（独占模拟器 + 需 :app 最新构建，故排在 B1/B2 之后）
     → F1 终端滚动帧率（§6，P-3 决策前提，必须） + F2 App 冷启动时间与首屏内存。

B5 ── 修复回炉轮（占据 :app/:terminal 模块）
     ：B1–B4 报出的新缺陷逐条立账（转阶段四缺陷条目）→ 修 → 复跑该用例 + 该用例阳性对照，
     并回归受影响的邻接用例（改 :app 源码后复跑 :app:testDebugUnitTest + archwiki --check）。

B6 ── 真机交付批次（全绿后）
     ：重打 APK 交用户真机验收：真实相机扫码/拍照、Doze/厂商 ROM 杀后台、锁屏策略、
     多网卡可达、耗电。**未验证清单按 016(d) 显式交付**，未验项不得计入已验收。
```

**批次间切换条件**：B1→B2→B4 均要求 `:app`/`:terminal` 模块无施工席；B3 可与任意批次并行
（独立 daemon 端口）；B5 修完每轮复跑 gate 三面确认 0 新回归（013 标尺=0 新回归而非全绿）。

---

## 5. 阳性对照方案（每类判定配必然非空对照——本轮已三次栽坑）

三轮实证：T3-2 抓不住原型（判据没真扫）、gradle UP-TO-DATE 假绿（exit 0 但没跑测试）、
terminal 模块整体不在扫描根（覆盖范围错）。**每类判定的对照 = 自证"判定真的在判定"**。

| 判定类 | 阳性对照（必做） |
|---|---|
| **U 结构断言** | 每次 dump 解析后**同树自证**：断言目标节点存在 + 断言一个**必然不存在的假节点**
  （如 `resource-id="dev.agentmirror.app:id/assert_never_exists"`）不存在。两者同真 ⇒ 解析器与
  设备快照活、失败才可能真是 product。假节点意外存在 ⇒ harness 问题，停。 |
| **S 截图目检** | 每张落盘后 `sips -g pixelWidth -g pixelHeight` 断言 1080×2400 且非纯色
  （可用 `sips` 采样或 `python3` PIL 直方图非单峰）。每用例 ≥2 张（动作前/后）。截到黑屏/空帧 = harness。 |
| **T 对账** | capture-pane 后断言**输出非空且含预期文本**；capture 为空 = 没连到 pane（harness），
  不是 product 通过。上传文件/日志用 `test -s` + 内容 grep 断言非空存在。 |
| **gradle 构建** | 跑 `:app:assembleDebug` 后断言 APK mtime 更新 + `aapt dump badging` 可读 +
  `unzip -l` 非空；跑测试后**解析 `app/app/build/test-results/**/TEST-*.xml` 断言用例数非零**
  （杜绝 UP-TO-DATE 假绿）。`:terminal:test` 同法（终端模块上次栽在"不在扫描根"）。 |
| **设备/uiautomator 通路** | 批次开头先跑 `adb shell getprop sys.boot_completed`=1 +
  未装 App 时 `uiautomator dump` 非空（证明 uiautomator 活着）→ 后续"找不到节点"才可能是真失败。 |
| **F1 帧率量测** | `dumpsys gfxinfo ... framestats` 断言 N_frames>0；且**人为加载对照**：洪峰场景滚动
  帧时长显著劣于轻载场景 ⇒ 量测真反映帧耗时（perf 方法学 §3：注入延迟必须被测出来）。 |
| **性能门限** | 每项 p50/p95 对照 baseline 落盘 JSON，超门限 exit 非 0 并打印实测 vs 门限，不静默。 |

---

## 6. 性能场景接入点（A–F 六组）

### 6.1 本轮必须进 / 建议进

| 场景 | 是否本轮 | 理由与接入点 |
|---|---|---|
| **F1 终端滚动帧率** | **必须** | P-3 决策前提（round-findings-20260811）：先量 F1，达标则整帧重绘可接受，
  不达标才接脏区局部重绘。方法：模拟器会话页驱动 N 次连续 `input swipe`，同时 `dumpsys gfxinfo
  dev.agentmirror.app framestats` 采样，算丢帧率（超出 16.67ms 预算的帧占比）；配 §5 加载对照。
  量测落 B4 批次，结果进证据专项字段 + REPORT 判定（达标/不达标）。 |
| **F2 App 冷启动时间与首屏内存** | 建议进 | 一测即得：`am force-stop` 后 `am start -W` 取 totalTime +
  `dumpsys meminfo dev.agentmirror.app` 首屏内存。低成本，随 B4。 |

### 6.2 本轮后置（独立性能批次或既有管线承接）

| 场景 | 后置去向 |
|---|---|
| A1 首触端到端总账 | 后置独立性能波次（需全链路一次跑通，先等用例全绿） |
| A2/A2b 按键往返 RTT、A3 输出首字节/稳态拆分、A4 深翻 1万/10万行 | 后置性能波次（A2 可随 A8 顺带量测不新增轮次，可选） |
| B1 输出洪流（large_output 槽位）、D4 上传耗时（upload 槽位） | **perf 管线（test-api-user-scenarios-perf / perf-thresholds-enforce）承接**，
  该两条为 perf-thresholds-enforce 要求补齐的基线缺口 |
| B2 粘贴风暴、B3 resize 风暴 | 后置性能波次 |
| C1 舰队规模、C2 多会话并发订阅、C3 多客户端 | 后置（test-fleet-scale 系） |
| D1 LAN vs tailnet 对比 | **API 通道**（perf-scenarios 通道分工明裁）——本阶段 API/instrumentation
  通道只承接 TS 网络；待 feat-ts-wire / headscale 链稳定后接 |
| D2 弱网、D3b 切网/长断线/重连风暴 | 后置（需网络条件注入，本机模拟器不具备弱网造数能力） |
| E1b 补两态、E2 长时老化、E3 上传目录增长 | 后置老化/性能波次（E2 为当前最大盲区，建议单独立案） |
| F3 App 内存峰值、F4 耗电 | F3 后置性能波次；**F4 真机**（perf-scenarios 明裁：不编数字，用户真机反馈） |

### 6.3 P-3 决策接线（必须写进执行证据）

F1 量测结果连同判定写入 B4 批次证据 JSON 的专项字段：
`{ "f1_frame_metrics": {...}, "f1_dropped_frame_ratio": x, "f1_decision": "整帧重绘达标可接受" | "接脏区局部重绘" }`。
P-3 已裁：**先量 F1 再决定要不要接脏区局部重绘**，量测前不预先施工。

---

## 7. 证据与交接约定（执行席照此落盘）

1. **每用例判定结果**逐条落 `e2e/artifacts/stage4-execution/REPORT.md`：
   用例号 ｜ 需求出处 ｜ 通道 ｜ 结果（PASS/FAIL/partial）｜ 判定手段（结构断言截图/对账证据）｜
   归因（§3.3）｜ 缺陷（编号+严重度+截图+对照条款）。
2. **截图**按 §3.2 命名入 `e2e/artifacts/stage4-execution/`；交付时给 leader **逐图目检清单**
   （每图 018 七条结论）。
3. **阳性对照留证**：每类判定把对照证据（假节点 dump、XML 用例数、framestats N_frames、sips 尺寸）
   附进 REPORT，空对照 = 判定不算数。
4. **未验证清单**（016d）：真机项（相机/Doze/耗电/多网卡）与后置性能项**显式列出**，禁止计入已验收。
5. 每批收尾：杀干净自建 daemon/tmux，`lsof -i :19983` 与进程表自证零残留（D2 同款）。
6. 执行席证据 JSON 至少含：`status`、`per_case_results`（43 条逐条）、`positive_control`、
   `unverified`、`f1_metrics`（B4 批）、`deviation`。

> 本文档只允许在 `docs/` 白名单内引用；所有 T 对账一律隔离环境，生产 daemon 与用户真实 tmux 禁触。
