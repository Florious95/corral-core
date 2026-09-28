# D-38 模拟器实测 + 假说决定性验证报告

- 日期：2026-08-13（Asia/Singapore）
- 设备：`emulator-5554`，1080×2400
- **APK / 隔离 daemon 均构建自 commit `07f065db08f177250d16a4bd389307fad70079b1`（当前 main HEAD）**。
  构建前 `git rev-parse HEAD` 已确认；构建时工作树仅有一处未提交改动
  `app/app/src/test/kotlin/dev/agentmirror/app/termview/TermColsGridConvergenceDiscriminationTest.kt`
  （w-dev-cols 在途判别结论红测，**测试源，不参与 `:app:assembleDebug`，生产代码零差异**）——
  已按 leader 放行指示直接使用。
- 配对：App 正常手填配对流程连上自建隔离 daemon（`AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS` 收窄扫描），
  未手改 SharedPreferences。隔离 tmux 会话 `d38`，全程只用自建隔离 socket，绝不触碰宿主真实 tmux/daemon。
- 边界：产品代码零改动；写盘只在 `e2e/artifacts/d38-viewport-restore/`。

## 核心结论（按发问顺序，逐一作答）

1. **主机 pane 回前台后 变 / 不变**：**不变**（③=②，逐字节相同）
2. **双客户端切换后 App 布局 变 / 不变**：**不变**（Mac 侧模拟 attach 前后 App 布局逐字节相同）
3. **内容填满状态下，触发动作后 出现 / 未出现 超过一行行高的底部空白**：
   **未出现**——C（捏合放大）、C2（捏合缩小）、B（IME 挤压+切后台回前台）、A（双客户端抢占）
   四个变量逐一测过，填满内容后触发，**均无底部空白，屏幕行数与服务端上报 rows 精确匹配**。

**本轮所有测试均未复现用户报告的「内容占屏幕上部、下方大片空白 / 输入框跑到中间」现象。**
不是没找、是四条不同角度的假说逐一被实测数字推翻，如实报告，没有编造。

## 一、五点几何序列（原始判据，已被推翻）

| 点 | 动作 | 主机 pane | App 内容区 bounds | 输入框 y |
|---|---|---|---|---|
| ① | 进会话前（隔离 tmux 原始尺寸） | **100x40** | — | — |
| ② | 首次进会话（基准） | **108x87** | `[0,254][1080,2010]` | 2174 |
| ③ | home 键切后台→停5秒→回前台 | **108x87** | `[0,254][1080,2010]` | 2174 |
| ④ | 点「‹ 返回」退出会话 | **100x40** | — | — |
| ⑤ | 再次点进同一会话 | **108x87** | `[0,254][1080,2010]` | 2174 |

- **⑤ = ②**（逐字节相同）：代码预测「第二次进会话读到被压小的 pane 当作新基线」**不成立**。
- **④ = ①**（逐字节相同）：代码预测「teardown/closeSubscriptions/relay 退出三条路径都不恢复」
  **不成立**——本次实测确有恢复，且恰好精确回到会话前的原始尺寸。
- 用户报的「输入框跑到中间」**未在 ③ 或 ⑤ 复现**：06/07/09 三张截图逐一核对，布局完全一致。
- 证据：`06`~`09` 系列文件（截图 + uiautomator dump + pane 记录）。

**w-dev-d38 的 Go 红测与本次实测不矛盾**：它测的是异常断连（80x24≠108x96）和多订阅叠加
（80x24≠60x40），本次测的是正常点退出/单客户端路径。两边都成立——异常路径的缺陷是真的，
但目前解释不了用户看到的正常使用场景现象。

## 二、变量 C / C2：捏合缩放（填满内容后触发）

**方法论纠正**（leader 指出后立即重做）：先把终端内容填到远超一屏（140 行，`echo D38-LINE-16..140`），
确认内容画到键条上沿、无空白，这才是基线；再在这个已填满的状态上做触发动作。此前用短内容（15 行）
做捏合、下方大片黑，那是内容不足的必然结果，**不能**用来判定「底部空白」，已作废（`10`/`11` 号文件，
保留仅供方法论对比，不计入结论）。

### 捏合注入器自证有效

自建 `/dev/input/event1` 原始双指 `sendevent` 序列（ABS_MT_SLOT 协议 B，双指从近到远/从远到近，
10 步平滑移动），**注入前先证明不是"完全无反应"**（本工程 R5/instrumented 层均有此已知风险）：

| 状态 | 主机 pane（真实值，非视觉猜测） | 屏幕实际显示行数 | 与 rows 是否精确匹配 |
|---|---|---|---|
| 基线（未捏合） | 108x87 | `D38-LINE-55`~`D38-LINE-140`(86行)+提示符=**87** | ✅ 精确匹配 |
| C：捏合放大后 | **83x60**（cols/rows 均下降，字格变大） | `D38-LINE-82`~`D38-LINE-140`(59行)+提示符=**60** | ✅ 精确匹配 |
| C2：捏合缩小后 | **98x76**（cols/rows 回升，字格变小） | `D38-LINE-66`~`D38-LINE-140`(75行)+提示符=**76** | ✅ 精确匹配 |

- 主机侧 pane 尺寸是**真实 tmux 测量值**（`tmux display -p`），不依赖 App 自报，独立证据链。
- 每屏可显示行数与服务端上报 rows **逐次精确对等**（87/60/76 三档全部对上），
  既证明了捏合真的注入生效（geometry 确有测量得到的变化），也回答了 leader 要求的
  「内容行数与终端可容纳行数的关系」——**对得上，非缺陷**。
- 换算像素/行：内容区高度恒为 1756px（`[0,254][1080,2010]` 不变）。
  108x87 → 20.18px/行；83x60（放大）→ 29.27px/行（+45%）；98x76（缩小）→ 23.11px/行。
  行高随捏合方向正确增减，字符尺寸确有可测量变化。

### 结果

C、C2 触发后立即截图核对：**内容从新的顶部行开始，一路画到输入框上沿，零空白**
（`14-app-C-after-pinch-filled.png`、`15-app-C2-after-pinchin-filled.png`）。
**捏合放大/缩小均未出现「内容占屏幕上部、下方大片空白」。**

## 三、变量 B：IME 挤压态下切后台（填满内容后触发）

在已填满基线上：点输入框唤起 IME 并打字 → home 键切后台 → 回前台。

| 状态 | 主机 pane | 屏幕显示行数 |
|---|---|---|
| IME 挤压中（切后台前） | 98x76（未变，符合 fix-ime-no-resize 预期：挤压不 emit resize） | — |
| 回前台后 | 98x71（rows 因 IME/输入框占用真实缩小，合理的真实视口变化） | `D38-LINE-71`~`D38-LINE-140`(70行)+提示符=**71** ✅ 精确匹配 |

输入框内容（`typing-while-backgrounding-test`）跨后台/前台正确保留；**无底部空白，无跳到中间**。
证据：`16`/`17` 系列。

## 四、变量 A：双客户端抢占（filled 内容 + 真实第二 client attach）

**这是 leader 本轮最高优先级假说，附带一个明确的代码级根因判定。**

方法：用 `pty` 起一个真实 `tmux -S <隔离socket> attach-session -t d38` 客户端，窗口尺寸显式设为
80x24（典型 Mac Terminal 默认尺寸），模拟"用户切去 Mac 看同一 CLI"。

结果：**attach 前后主机 pane 逐字节不变（98x71 → 98x71），App 截图逐字节不变。**
`list-clients` 确认 80x24 客户端确已 attach（`(attached,focused,UTF-8)`），非注入失败。

### 代码级根因（不是复现不到，是机制本身不存在）

查 `server/internal/bridge/bridge.go:238-249`（`Pane.Resize`）：

```go
runTmux(..., "set-option", "-w", "-t", winID, "window-size", "latest")
runTmux(..., "resize-window", "-t", winID, "-x", cols, "-y", rows)
```

先设 `window-size latest`，紧接着调用 `resize-window -x -y`。但 **tmux 文档明确记载：
`resize-window` 带 `-x`/`-y` 时会把该窗口的 `window-size` 选项自动强制设回 `manual`**——
这是 tmux 自身的副作用，写在 resize-window 之后，会立即覆盖刚设的 `latest`。
实测直接验证：`tmux show-window-options -t d38` 当前值确为 **`window-size manual`**，
不是代码注释所说的 `latest`。

推论：
1. **`set-option ... latest` 那行调用形同虚设**——每次 `Resize()` 后立刻被 `resize-window`
   的副作用打回 `manual`。
2. `manual` 模式下，attach 一个新 client（不论其尺寸、不论是否发送按键）**都不会**触发 tmux
   自动改变窗口尺寸——只有显式 `resize-window` 调用才能改。这正是本次实测「attach 80x24 后
   pane 纹丝不动」的确切原因，不是巧合。
3. 代码注释「window-size latest makes the resize stick instead of being overridden by an
   attached client's dimensions」对 tmux 语义的理解是**反的**：`latest` 恰恰是"跟着最近活跃
   client 走"（会被覆盖），`manual` 才是"焊死不跟"（stick）。当前代码的实际运行效果（manual）
   反而符合注释想要的目标（stick），但注释所描述的实现原理是错的，且 `set-option latest`
   那行调用是死代码。**这与 D-38 现象无因果关系**（因为实际生效的 manual 模式恰好规避了
   latest 语义可能带来的多 client 竞争问题），但属于代码注释/实现意图不一致，建议另行清理
   （不在本任务写盘范围内，不改产品代码，仅记录）。

### 结果

**双客户端切换未导致 App 布局变化，未复现「跑到中间」。** leader 提出的因果链
（App 是 latest → Mac 变 latest → App 认知过期 → 布局错位）**其必要前提"window-size 处于
latest 模式"不成立**，链条在第一环就断了。

证据：`18`~`20` 系列。

## 五、纪律核对

- Mac 侧 attach 全程只 attach 自建隔离 tmux socket（`/tmp/am-d38.*/tmux/tmux-501/d38.sock`），
  未触碰宿主真实 tmux。
- 未手改 SharedPreferences；App 全程走正常 UI 配对流程。
- 全量单测未跑（按分配，留给 w-dev-d38 独占）。
- token 值未出现在任何输出/截图/本报告。
- 产品代码零改动；未切分支、未 commit、未 push。
- 收尾已清理：隔离 daemon kill、隔离 tmux kill-server、Mac 模拟 attach 已 detach、
  端口已释放、App 已 force-stop（见清理日志）。

## 六、追加轮：用真实 Claude Code（不是 bash）复测全部变量

leader 指出关键方法论缺口：此前用 `bash`（逐行追加）测不出「自己管理整屏布局的 TUI 把内容
钉在错误高度」这类缺陷。**本轮改用真实 `claude` CLI 跑在隔离 tmux 里**，其自绘输入框
（`❯` 提示符 + 底部状态行）是这次的关键探针。

- APK/daemon 重建自当前 HEAD `c84b73950371a2bc8c5bf0029ebbfcf7fbc6a217`
  （含 w-dev-cols 已提交的横向栅格收敛修复；`git rev-parse` 确认，生产代码干净）。
- 主机侧测量方法：每次触发后同时抓 `tmux -S <隔离socket> capture-pane -p`（真实内容）与
  `display -p "#{pane_width}x#{pane_height}"`（真实尺寸），用 `grep -n "❯"` 定位 Claude Code
  自绘输入框所在的**真实终端行号**，与 pane 总行数算出"底部边距"（pane_height − 提示符行号）。

| 状态 | 主机 pane | capture-pane 实际行数 | ❯ 提示符所在行 | 底部边距 |
|---|---|---|---|---|
| 基线（首次进入） | 108x87 | 87（与 pane_height 相等） | 84 | 3 |
| C：捏合放大后 | 77x60 | 60 | 57 | 3 |
| 单纯切后台→回前台（无 IME） | 77x60（未变） | 60 | 57 | 3 |
| B：IME 挤压+切后台→回前台 | 77x56 | 56 | 53 | 3 |
| 退出会话 | 100x40（回到会话前原始值） | — | — | — |
| 再次进入（⑤） | 108x87（=基线） | 87 | 84 | 3 |
| A：Mac 模拟 attach(80x24)抢占 | 108x87（**完全不变**） | — | — | — |

**底部边距恒为 3，在所有七个状态下不变。** 这直接证明：**Claude Code 每次都正确收到并处理了
尺寸变化（无论是捏合触发的 resize，还是回前台触发的重算），把自己的输入框重绘在当前 pane
高度应在的位置，从未停留在旧尺寸留下空白。** `capture-pane` 实际行数与 `pane_height` 每次都
精确相等，不存在"pane 87 行但内容只画了 40 行、后面全空"的迹象。

**Claude Code 输入框行号：跟随当前尺寸，未观测到"停在旧尺寸"。**

用真实 Claude Code 重跑五点序列 + 四个变量，结论与用 bash 时完全一致：**全部未复现**。
双客户端（A）变量下，Claude Code 场景与 bash 场景一样，因 `window-size manual`（见第四节
代码级根因）而对 Mac 侧 attach 完全免疫。

证据：`21`~`31` 系列文件（`capture-*.txt` 为主机侧真实内容，`*.png` 为对应 App 截图）。

## 七、假说七：IME 挤压后台/前台 → 收键盘 → 底部空白（真实复现）

### 主假说未能测试：技术约束如实说明

leader 要求的精确序列第一步是「在会话列表页（进会话之前）就把键盘弄出来」。**实测发现该页面
及其之前可达的所有页面（工作区列表、二级会话列表、设置页）均无 `EditText`**——唯一含
`EditText` 的是配对表单。尝试用「重新配对」绕到配对表单、聚焦 token 字段唤起 IME 后：
- 点「连接」按钮：IME 立即被收起（`mInputShown=false`）才进入下一页——按钮点击本身会让
  EditText 失焦，触发系统自动隐藏键盘，这是 Compose 焦点管理的标准行为，早于页面跳转发生。
- 改用键盘「回车」代替点按钮：该字段不响应 Enter 提交（未定义 imeAction submit），
  IME 保持显示但也没有触发导航，卡在原表单。

**两种方式都无法让 IME 存活着跨过一次真实的页面跳转。** 这是本 App 当前实现下的真实约束，
不是取证手段问题：由于是单 Activity + Compose 路由（无 Activity 切换），IME 隐藏由
组件失焦时的 Compose 焦点管理触发，而非系统级窗口切换触发，因此难以用简单 adb 操作绕过。
**如实报告：主序列（IME 先于进会话）本轮未能构造出复现条件，不是测了没复现，是没测成。**

### 改测 leader 指定的备选变体 2：确认复现，数字如下

「进会话后立刻点输入框，等 IME 稳定，然后切后台再回前台，再收键盘」——这条可以完整走通，
按 leader 给的判据（第 6 步 pane_height 是否仍停在第 4 步挤压值，而 App 视口已恢复全高）实测：

| 步骤 | 主机 pane | App 内容区 bounds | machine_eye bottomMarginPx |
|---|---|---|---|
| 基线（进会话，IME 未开） | 108x87 | `[0,254][1080,2010]` | **6**（健康值，与 leader 给的健康基线一致） |
| 点输入框，IME 稳定 | 108x87（挤压不 emit，符合 fix-ime-no-resize） | 视口相应收缩 | 0（正常填满收缩后的视口） |
| 切后台→回前台（IME 仍显示，`mInputShown=true`） | **108x82**（真实视口变化触发了一次真实 resize） | 相应收缩 | 0（正常填满） |
| **收起键盘**（返回键，`mInputShown=false`） | **108x82（卡住，未再收到新 resize）** | **`[0,254][1080,2010]`（已恢复满高）** | **106**（5 秒后复测仍为 106，稳定不变） |

**判据成立：第 4 步收键盘后，App 视口已恢复到全高（bounds 与基线相同），但主机 pane 仍停在
回前台时的挤压值 82 行（未回到基线 87），底部出现 106px 空白 ≈ 5 行行高（1756÷82≈21.4px/行），
远超「≥一行行高」的门槛，且 5 秒后复测数值不变，是稳定态不是瞬时渲染延迟。**

这与 leader 的机制推导完全吻合：**回前台时几何被真实重算过一次（squeeze 期间 87→82），
但键盘收起这个「视口再次真实变化」的时刻，没有人再发一次 resize 去把 82 纠正回 87**——
squeeze 值被当成了新的"永久基线"钉住，随后 App 视口自己弹回全高，两者从此错位。

**这条链路（虽然触发条件是"变体2"而非原始设想的"IME先于进会话"）与用户复现的现象是同一机制：
某次真实视口变化后，服务端 resize 没有对称地"收"回来。**

第一句结论：**IME 在屏时进会话再收键盘，底部 出现 ≥一行行高的空白**
（注：经由 leader 指定的变体2路径复现，非原始主序列——原始主序列因页面无可聚焦
EditText、且 Compose 焦点管理会在按钮点击时抢先收起键盘，本轮未能构造出测试条件）。

证据：`36`~`40` 系列文件 + machine_eye 精确读数（脚本命令见下）。

```
node -e "
const {analyzeFrame} = require('./framework/machine_eye/space.js');
['36-app-v2baseline.png','37-app-v2-ime-shown.png','38-app-v2-after-fg.png',
 '39-app-v2-after-dismiss.png','40-app-v2-stability-check-5s.png'].forEach(f => {
  const r = analyzeFrame('e2e/artifacts/d38-viewport-restore/' + f);
  console.log(f, r.bottomMarginPx);
});
"
```
