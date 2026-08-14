# 交接：远程Agent安卓 · leader · 2026-08-14 傍晚

> 本文覆盖同日更早的 `HANDOFF-leader-20260814.md`（那份写于当日凌晨，内容已大量过时）。
> 落笔前跑过客观核对，下面所有 sha / pid / 数字均为实测值，不是席位自报。

---

## §0 compact 后先做什么

**一句话现状**：用户已在真机测完 `agentmirror-fontsize-v1.apk`，**三条主症状全消**
（闪烁 / 内容跑出屏幕 / 大小不持久），架构方向被证实是对的。随后他一口气提了 **5 条新问题**，
全部已定位并写进 taskbook（144 条），**其中 2 条已修并提交，3 条卡在"必须先取用户真机的一个数"**。
用户明确说：「我们现在就是把问题给定位清楚，把接下来的任务书给弄清楚，然后你就开始交接了。」
⇒ **本次交接的定位是：问题已定位、任务书已就绪，等下一轮开工。**

**⚠️ 桌面那个 APK 已经落后于 HEAD**：`agentmirror-fontsize-v1.apk` 打包于 commit `ff58c409c`，
**不含**其后提交的设置页 UI 修复（`ff8a99d88`）。下一轮出包必须重打。

**开口第一句**（compact 后对用户说这个）：

> 「问题都定位完了，任务书 144 条已就绪。**有 3 条卡在同一个数上**：你手机上的
> `cell_width_measured`——设置 → 查看诊断日志 → 复制全部，把带 `grid` 的那行给我，
> 字号量纲、右边距这两条当场就能算准。
> 另外桌面那个包已经落后了（不含设置页 UI 修复），要不要我先重打一个给你？」

**必读清单**（按优先级）：
1. 本文
2. `/Volumes/nvme/Projects/远程Agent安卓/CLAUDE.md` —— 当日新增了 4 条常驻规则，见 §1
3. `/Volumes/nvme/Projects/远程Agent安卓/docs/archive/scroll-rounds-8-10/README.md` —— ④ 为什么被整轮归档
4. `/Volumes/nvme/Projects/远程Agent安卓/.team/evidence/fix-tsnet-resume-reconnect.json` —— ⑤ 的完整根因链
5. `/Volumes/nvme/Projects/远程Agent安卓/taskbook.yaml` —— 139 条，末 7 条是当日新立

**恢复动作**：席位全部退役属**正常收尾**，不是故障。要重开某一席：
```bash
cd /Volumes/nvme/Projects/远程Agent安卓
.team/ta add-agent <名字> --role-file agents/retired/<名字>.md --workspace .
.team/ta send <名字> '<派单内容>'
```
role 文件全在 `agents/retired/`，一条命令即可原样重建，未丢任何东西。

---

## §1 身份与不变量

**角色**：leader，只编排不施工。不亲自跑测试改产品代码、不 push。
但**验收必须亲自跑**（下面「不凭自报」那条）。

### 当日新增的 4 条 CLAUDE.md 常驻规则（都已提交，务必遵守）

1. **提交纪律**（commit `382a05c93`）：commit 无需用户确认，每个关键点都必须提交；
   一次修复一个提交；**验过才提交**（席位自报不算）；不许攒。
2. **诊断日志纪律**（commit `0da6dd6c1`）：**记判据的操作数，不要只记判决**。
   凡有守卫/阈值/比较处，把参与比较的两边原始数值都记下来再记结论；记触发来源。
   判据：光看日志就能判断出是「该做而没做」还是「做了但做错了」。
3. **隔离 tmux 自检**（commit `382a05c93`）：tmux 建 socket 失败时**不报错、静默回退到默认
   socket＝用户真实 tmux**。已实证两条回退路径（TMUX_TMPDIR 路径过长、目录未预建）。
   规则主体是**起完必须 `tmux -S <sock> list-sessions` 自检**，不是背那两条机理。
4. **⛔ 第 2 层（安卓模拟器）暂停**（commit `8609fd084`）：**用户指令，未解除前一律遵守**。
   任何席位不得启动 emulator/qemu、不得依赖 adb 设备。替代：第 1 层 web 端 + 第 3 层用户真机。
   解除由用户或 `refactor-maintainability` team leader 通知。

### 当日反复付学费的一条判据（比任何技术结论都重要）

**「验证的不是真正的被测对象」当日出现 7 次**，前 6 次列在
`docs/archive/scroll-rounds-8-10/README.md` 的表里：

| # | 我们验的 | 真正的被测对象 |
|---|---|---|
| 1 | `capture-pane`（tmux 视角） | `pipe-pane`（产品推流通道） |
| 2 | 红测手搭的 OkHttpClient 副本 | `OkHttpTransportFactory.create()` |
| 3 | 直接注入 `protocol.ScrollWheel` 帧 | 手势层 |
| 4 | 断言 `<0`，而实际值是 `Int.MIN_VALUE` | 真实 `lineHeightPx` |
| 5 | `adb input swipe` 合成事件 | 真手指 |
| 6 | **裸 shell**（`alt=0 hist=1850`） | **Claude Code**（`alt=1 hist=0`） |
| 7 | **单次跑绿的红测** | **一条 1/3 概率失败的测试**（详见 §3-B） |

第 6 条废掉了 ④ 的三轮架构；第 7 条是 leader 自己的验证方法出问题。
**动手前先问：我验的东西，就是用户用的东西吗？**

### 不凭自报（当日实证有效，救过多次）

- 席位报「全绿」→ leader 自己跑 `--rerun-tasks`
- 席位报「变异证明了检测力」→ leader **自己独立做一次变异**（当日做了，两条用例应声转红）
- 席位报「包里有那个修复」→ leader 用 `dexdump` 反汇编具体类核字节码
- **当日席位顶回 leader 7 次，7 次都对。** 保护「顶回来」这件事。

---

## §2 排期与封存令

| 编号 | 缺陷 | 状态 |
|---|---|---|
| ① | 图片上传（蜂窝+TS 必失败） | 已闭环（更早轮次） |
| ② | 最右列文字跑出屏幕 | **复发，未闭环** → §4-A |
| ③ | 重进 CLI 输入框位置 | 已闭环（更早轮次）；但当日出现疑似同族新症状 → §4-B |
| ④ | 上滑投送到远端 | **整轮归档回退，用户裁定「再议」** → §3-A |
| ⑤ | TS token 后台回前台连不上 | **已闭环，用户真机验过** → §3-C |
| ⑥ | 诊断日志导出 | 已闭环；当日追加「App 内展示 + 一键复制」，已闭环 |

**封存令（用户原话）**：
- 「④ 再议，先把 5 修复好」
- 「暂时不能开模拟器」
- 「commit 无需我确认，每个关键点都需要提交代码，记到 claude md」（已执行并入库）

---

## §3 P0 / 插队项

### §3-A ④ 全轮归档（最大的一次插队，废掉三轮工作）

**用户裁定原话**：「把上滑修了好多次的改动全部归档，不能让错误方向的修改影响污染后续」。

**推翻三轮的三个数**（leader 在自己跑着 Claude Code 的 tmux pane 上实测）：
```
tmux display-message -p '#{alternate_on} #{history_size} #{mouse_any_flag}'
→ alternate_on=1   Claude Code 是 alt-screen TUI
→ history_size=0   它的 pane 在 tmux 里【零行】scrollback
→ mouse_any_flag=1 它自己【已经开着】鼠标上报
（对照：裸 bash pane 是 alt=0 hist=1850 mouse=0）
```
⇒ 第 8~10 轮做的「服务端自持 scrollOffset + `capture-pane -S/-E` 读 scrollback + 推 SNAPSHOT」，
对 Claude Code **读的是一个空历史**，实现再正确也读不出东西。
⇒ 更早的结论「SGR 鼠标字节注入是死路」**也是错的**：当时测的 less/vim 是 `mouse=0`。
**Claude Code 是 `mouse=1`，鼠标路没死，而且很可能是唯一对的那条。**

**归档位置**：`docs/archive/scroll-rounds-8-10/`
（README.md 说明为什么错 + `diffs/rounds-8-10.patch` 932 行 + `tests/` 三份仍有效的红测）

**仍然成立、下一轮不要丢的**：
- copy-mode 滚动的结果进不了 pipe-pane 推流（字节级实证，post-scroll 恒 0 字节）
- 手势层符号反了 + 不足一行的位移被丢弃无累加器（700px 拖动只送达 3 行）——真 bug，与架构无关

**⚠️ 部署与源码的分歧（下一轮开工第一件事就要处理）**：
源码已回退，但生产 daemon **pid 86755** 跑的是第 8~10 轮编出来的二进制
（sha256 `14001c9d2b754ec964ee7f57aa6988723eba8e1875fd0b62280d5597faa20557`，含 `ScrollState`、无 `InjectScroll`），
**与回退后的源码不一致**。回退前的二进制备份在 `server/agentmirrord.bak-20260814144430`。
当前不立即回滚部署的理由：用户在用它，且新二进制对滚动帧只是"不推快照"、不报错。
**但这正是当日 ④ 第一次失败的坑（跑旧二进制、验新源码）。**

### §3-B leader 自己的验证方法出过一次问题（教训，非故障）

提交 ⑤ 时（commit `ec9dffa7b`）leader 跑 `--rerun-tasks` 全绿，据此把证据写成 `pass_user_verified`。
后来发现 `PairingTsnetWindowProbeTest` **1/3 概率失败**（隔离单跑 PASS/PASS/FAIL）——
**那次全绿只是运气好**。根因：`ReconnectPolicy` 默认用真随机做 ±20% 抖动，
attempt2 delay = 4s±20% = 3200~4800ms，而红测固定推进 4s。
已根治（commit `98ab725dd`，测试侧注入 `random = { 0.5 }`），连跑 20 次全绿。

**教训**：**对一条不稳的测试，单次绿不是证据。一个不稳的红测比没有红测更危险——它给人已被覆盖的错觉。**

### §3-C ⑤ 已闭环（用户真机验过，非模拟器非自报）

三个根因一条链，证据在 `.team/evidence/fix-tsnet-resume-reconnect.json`：
- **R1** WebSocket 的 OkHttp 未设 `Proxy.NO_PROXY`，继承手机系统代理（`127.0.0.1:7892`），
  让 tsnet SOCKS 去连一个在 tsnet netstack 内不存在的 loopback ⇒ dial ok 但握手 unexpected EOF。
  **同一 bug 早在缺陷① 的上传路径修过（`HttpUrlConnectionUploader.kt:174`），旁边这条没人扫。**
- **R2** 配对页 RECONNECTING 分支无 tsnet 起网窗口保护 ⇒ 起网 5~6 秒内任一次拨号失败即锁死 Failed，
  随后 READY 被 `is Pairing` 守卫挡住永不解锁。**这解释了用户最早那组 A/B。**
- **R3** 因 R2 到不了 Success，而 `configStore.save` 是全工程唯一保存点且只在成功路径
  ⇒ tsAuthKey 从不落盘 ⇒ 冷启动无 tsnet ⇒ 永远超时。

**用户真机验证日志**（16:19:28，冷启动到可用 5.9 秒）：
```
ON_CREATE 28.801 → [tsnet] Idle→Starting 28.826 → Up 34.202
→ [socks] dial ok host=100.75.207.88 port=9900 → READY 34.661
```
同时删除了 `notifySocksRouteFailure` 自愈——用户 15:11 日志实锤它有害：
READY 状态下一次瞬时 EOF 触发它拆掉健康节点重建，把瞬时故障变成永久断线。

---

## §3-D 【本次交接新增】用户真机测完后提的 5 条问题

> 全部已写进 `taskbook.yaml`（144 条，末 5 条即本节）。
> **共同点：其中 3 条卡在同一个数据上——用户真机 `grid` 日志里的 `cell_width_measured`。**
> 一次取数可同时解锁 `fix-font-size-scale-unit` 与 `fix-terminal-right-margin`。

### 先说好消息（别把它读丢了）
用户原话：「尺寸这个问题就相当于已经完全把上面几个问题解决了——第一个是闪烁，
第二个是部分跑到屏幕外面，第三个是一致性。」
⇒ `feat-font-size-setting-drop-pinch`（commit `196cf4228`）**三条主症状全消，方向正确，不要推翻。**

| taskbook id | 状态 | 卡在哪 / 下一步 |
|---|---|---|
| 设置页 UI（两位数折行 + 卡片贴边） | ✅ **已修已提交** `ff8a99d88`，leader 核过全量绿 | 无，但**桌面 APK 不含它**，需重打包 |
| `fix-font-size-scale-unit` | 🔴 根因已定位，禁止动手 | **等用户真机 `cell_width_measured`** |
| `fix-terminal-right-margin` | 🔴 未验因 | 同上；且**建议先修量纲再看本条是否自动缓解** |
| `feat-terminal-theme-selection` | 🔴 `contention: contract` | **先做甲（查白底成因）再决定乙（调色板形态）** |
| `fix-image-upload-input-box` | 🔴 `contention: contract` | **先实测 Claude Code 是否支持内联附图** |
| `feat-remote-scroll-mouse-wheel` | 🔴 `contention: contract`，方向已由用户定死 | 见下 |

### 三条最容易被下一轮做错的，单独强调

**① 字号量纲（`fix-font-size-scale-unit`）——不是"选项不够多"**
```
TermSurfaceView.kt:446   val sizePx = fontSizeSp * resources.displayMetrics.scaledDensity
旧世界（已删的捏合时代）  textSize = DEFAULT_CELL_HEIGHT(20) × 0.85 = 17【物理像素】
```
预设用 **sp**（受系统字体缩放），旧世界用**物理像素**。3.0x 密度下 12sp = 36px ≈ 旧字号 2.1 倍。
**"最小档"比用户一直在用的字号还大一倍——整个刻度盘错了量纲。**
附带一个待 leader 裁的设计问题：**终端字号该不该受系统 sp 缩放影响？**
终端用户要的是"一屏看多少内容"，而 sp 会被无障碍设置放大，两者诉求冲突。

**② 主题（`feat-terminal-theme-selection`）——这是两件事，只做后一件不会让白底消失**
- **甲**：白底**不是我们画的，是 Claude Code 自己画的**。它探测终端背景色选明暗主题，
  现在猜成了浅色终端。leader grep 实证：**服务端与 App 侧都没有任何 `COLORFGBG` / OSC 11
  背景查询的处理**。这是**假设不是结论**，要实测证否。
- **乙**：App 自己的调色板主题选择（用户要的功能）。
  若 Claude Code 发的是 truecolor 背景，调色板改不了它 ⇒ **先做甲定案，再决定乙的形态。**

**③ ④ 上滑（`feat-remote-scroll-mouse-wheel`）——方向已由用户定死，不要再自由发挥**
用户原话：「你要做的实际上就是要把鼠标上滑这个行为改成滚轮往上滑，**给这个窗口发这样的指令**。」
用户附证据：Mac 上滚轮上滑，Claude Code 出现 **"Jump to Bottom"**
⇒ **它自己就有滚动能力，只是没人给它发滚轮事件。**
与 leader 实测吻合（`alt=1 / hist=0 / mouse_any_flag=1`）。
⇒ 前十轮的 scrollback 方向全部作废（已归档），**更早那条「SGR 注入是死路」的结论也是错的**
（当时测的 less/vim 是 `mouse=0`）。
**开工前必须先消掉部署分歧**（见 §3-A 末尾）。

---

## §4 在途未收尾任务

> **无常驻进程在跑**。席位已全部退役，没有 pid 可查。
> 进度信号 = 用户的真机反馈 + `taskbook.yaml` 的 status 字段。
> 「等用户测」是**合法阻塞**，不是卡死，不要去干预。

### §4-A 【最高】② 复发：中文双宽行最右列被切 —— 等用户数据

- **taskbook id**：`fix-cols-cjk-doublewidth`（另见 `feat-font-size-setting-drop-pinch` 的 `supersedes`）
- **基线**：HEAD `042353332`
- **负责人**：无（席位已退役，需重开）
- **卡在哪**：**等用户装 `agentmirror-fontsize-v1.apk` 后切到 12sp 看结果**
- **下一步怎么做**：
  1. 用户复现后，让他 **设置 → 查看诊断日志 → 复制全部 → 粘过来**
  2. 找 `grid` 那条记录，字段是
     `viewport_width_px / cell_width_nominal / cell_width_measured / reported_cols /
      canvas_capacity_cols / overflow_px / half_cell_px`
  3. **分野已写死在 taskbook，不许先入为主**：
     - `overflow_px > 0` ⇒ cellWidth 回写失效（走 a 路线）
     - `overflow_px == 0` 而屏幕仍在切字 ⇒ **仪表没覆盖 CJK 双宽**，先补仪表再谈修复
- **为什么用 12sp**：溢出 ≈ 列数 ×（实测字宽 − 名义字宽），**字越大列越少越不明显**。
  测试席自报：只用中间档 16sp 时误差被 floor 除法吸收、变异后测试不红，**差点漏掉**。
- **⚠️ 一个至今没有的数字**：真机上 `cellWidth`/`cellHeight` 实测值是多少，
  JVM（Robolectric LEGACY 图形 fontMetrics 恒返回 0）和现有仪表都给不出。
  **不许用推断的数字代替**（taskbook 已写死这条）。

### §4-B 回前台后终端只画屏幕上面 1/3 —— 仪表已就位，等用户复现

- **并入** `feat-font-size-setting-drop-pinch` 已随主体提交（commit `196cf4228`）
- **卡在哪**：等用户复现并导日志
- **仪表已补，下一次复现就能定案**：
  - `TermSurfaceView.onWindowVisibilityChanged` 一进来就记 `source=windowVisibility visibility=…`
  - `TermViewPresenter` 两个视口入口各记 `source= / oldW oldH / newW newH / viewportSeeded /
    emulatorRows emulatorCols`，守卫结论另记 `resized= outgrewGuard=`
- **未解的核心问题（w-font-test 提出，leader 认为可能是真根因，但当日未定案）**：
  **Activity `ON_STOP`/`ON_START` ≠ `View.onWindowVisibilityChanged`**——窗口级可见性与
  Activity 生命周期不是一回事，长后台被系统回收 Surface 时尤其可能对不上。
  用户出问题的正是隔了 3.7 分钟的那次后台。
  **presenter 层已证无算术错误**（定点变异：把 outgrew 判定硬编码为 false ⇒
  `expected:<50> but was:<18>`，18/50≈36%，与用户主诉「只占 1/3」量级吻合）。
  ⇒ 剩下的问题只可能在**调用点是否被触发**，而这正是新仪表要回答的。

### §4-C ④ 上滑 —— 已归档，等用户说「再议」的时机

- **归档**：`docs/archive/scroll-rounds-8-10/`
- **重开时的方向**（用户已暗示但未拍板）：从 scrollback 改为**给开了鼠标上报的 TUI 投送滚轮事件**
  （`Claude Code` 的 `mouse_any_flag=1`），scrollback 那套降级为裸 shell 兜底
- **重开前必须先做**：消掉 §3-A 那条部署与源码的分歧
- **leader 当日问过但用户未回答的问题**：「你除了 Claude Code，还会在别的 TUI 里上滑吗
  （vim、htop、lazygit）？还是只要 Claude Code 能滚就够？」——**这决定方案边界，重开时先问**

### §4-D 其余已立项未派单（taskbook 139 条，末 7 条为当日新立）

| taskbook id | 一句话 | 第一步是什么 |
|---|---|---|
| `fix-cellheight-writeback` | cellHeight 从不回写实测值 | 已被 `feat-font-size-setting-drop-pinch` 的 `supersedes` 吸收，**大概率可关闭，需复核** |
| `fix-local-scroll-degrade-sign` | 断线降级时上滑完全无效（presenter 与手势层符号约定相反） | 写红测复现 |
| `investigate-short-swipe-deadzone` | 模拟器上 150px 以下上滑 0 帧，而 TapSlop 只有 20px | **禁止改代码，先取真手指数据** |
| `fix-mainactivity-store-injectable` | MainActivity 硬编码 Keystore store，冷启动读配置这环 JVM 验不了 | 风险已被用户真机降低，非紧急 |
| `fix-shared-gradle-build-dir` | 多席共用 build 目录，一夜三次假警报 | **参考实现已有**：w-font-dev 用隔离 worktree `/tmp/e2e-w-font-dev/pkg-fontsize` 打包 |

---

## §5 运维与外部

### 生产环境（实测值，2026-08-14 19:40 复核）
- **席位**：`w-font-dev` **已重开并在跑**（role 文件从 `agents/retired/` 恢复），
  其余仍全部退役。coordinator 曾因"全席退役 ⇒ 会话零窗口被收 ⇒ 报 session_missing"而停止，
  已用 `.team/ta restart . --team remote-agent-android` 恢复，会话 `team-remote-agent-android` 正常。
  **这是竞态不是故障**：后续若再全席退役，预期会再次出现同样的 coordinator 停止，照此恢复即可。
- 已清理一个残留 tmux 会话 `scrollqa`（w-scroll-qa 那次 socket 事故的产物，cwd 指向
  `/private/tmp/e2e-scroll-qa.RIGwaK/`，非用户会话）。
- **daemon**：pid **86755**，`./agentmirrord -host 192.168.31.116`，监听 `*:9900`
  - 二进制 sha256 `14001c9d2b754ec964ee7f57aa6988723eba8e1875fd0b62280d5597faa20557`
  - 日志 `/tmp/agentmirrord-fix4.log`（**只 grep 目标行，禁止 tail 全文**，见 §6）
  - 备份 `server/agentmirrord.bak-20260814144430`
- **看门狗**：`tools/probe/mac/health_watchdog.py --daemon --observe-only`，pid 26362
- **席位**：running **0**（全部退役，role 文件在 `agents/retired/`）
- **本机负载**：load 5.86（当日峰值 29，模拟器被终止后回落）

### 交付物
- **桌面唯一 APK**：`~/Desktop/agentmirror-fontsize-v1.apk`
  sha256 `665aab06a1261dc5ae865d7eced1ec5fc504bf256ea47c42f74532b5b8ea0387`
  - leader 已用 `dexdump` 独立核过 5 项：`SharedPreferencesFontSizeStore` ✓、`seedCellMetrics` ✓、
    捏合旧接口命中 0 ✓、`OkHttpTransportFactory` 里 `NO_PROXY`=1 ✓、日志展示 ✓
  - 说明 `e2e/artifacts/apk-for-user/agentmirror-fontsize-v1.md`
  - **用户按要求桌面只留一个**，旧包已删（内容被新包完全包含）

### 跨团队
- **`refactor-maintainability` team**（workspace `/Users/alauda/Documents/code/agent前沿探索/多agent协作`）
  当日按用户指令 `kill -TERM 4697` 终止了本工程的模拟器进程。
  **这是人为终止，不是故障，不要记环境债、不要排查。**
  leader 已回信确认并说明本方无「必须用模拟器且不可延后」的任务。
  **模拟器解除由对方或用户通知，收到前不得自行启动。**
  直报通道：
  ```bash
  team-agent send '/Users/alauda/Documents/code/agent前沿探索/多agent协作::refactor-maintainability/leader' '<内容>'
  ```

---

## §6 安全约束（原文保留，不可弱化）

- 密钥只存在于 `.team/current/profiles/*.env`，**任何席位禁止读其原文**。
- **`.team/current/profiles/tailnet-test.env` 全员禁读**（含 leader）。里面是用户 tailnet 的
  auth key，只能通过 `TS_AUTHKEY` 环境变量注入测试节点，任何形式的 cat/grep/plist/Read 都禁止。
  取值只用 `set -a; . <file>; set +a` 注入子进程，不打印、不落日志、不入截图。
- **查任何配置前先想凭据**：`grep -i tailscale` 一个"偏好设置"文件就把 authkey 打上了屏
  （2026-08-13 实发，已请用户轮换）。同类禁令：无过滤 `ps aux`（暴露席位 API key）、
  `tail .team/logs/agentmirrord-prod.log`（daemon 明文打配对 token）。
  **Shadowrocket 的偏好 plist 与 `tailscale_keys.bin` 列入禁读。**
- **凭据已泄露 ≠ 停工**（2026-08-13 用户裁定，2026-08-14 重申并批评过一次违反）：
  用户对 `tailnet-test.env` 的长期决定是「既然泄露了，就写进文件，接下来就用它去测」。
  再次泄露时**只做三件事：一行上报（不复述泄露的值）、就地收紧做法、继续干活**。
  **禁止**因此停工、禁止等新 key、禁止把删本地产物当成风险处置——
  片段一旦进入上下文就擦不掉，删截图减少的是执行者的不适而非真实风险。
  轮换与否是用户的事，不是开工前置条件。
- **当日 leader 自己的一次近失**：`tail` daemon 日志时用 `token|key|secret|Bearer` 过滤，
  **英文词过滤一份中文日志，过滤是弱的**，没打出配对码是运气不是设计。
  **今后取 daemon 日志只 grep 明确要的那一行，不 tail。**
- 给席位发消息只走 `team-agent send` / `.team/ta send`，**禁 tmux `send-keys`**。
- ⛔ **绝不触碰生产 daemon（pid 86755）与用户真实 tmux**，席位只读也不行。

---

## §7 给后继的一句话

当日最贵的一课不是任何一个技术结论，是这个形状：**「验证的不是真正的被测对象」，一天出现了 7 次。**
它一次废掉三轮架构（裸 shell 替 Claude Code），一次差点让整轮修复白干（`capture-pane` 替 `pipe-pane`），
最后一次的被测对象是 **leader 自己的验证方法**（拿一条 1/3 概率失败的测试的单次绿当证据）。

所以动手前先问一句：**我验的这个东西，就是用户在用的那个东西吗？**

第二贵的一课：**当日席位顶回 leader 7 次，7 次都对。**
其中包括顶回 leader 自相矛盾的裁定、顶回 leader 写错的路径、顶回 leader 派错角色边界的单。
**如果他们不敢顶，这 7 次就是 7 个错误决定进主线。保护「顶回来」这件事。**
