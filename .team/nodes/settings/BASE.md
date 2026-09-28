# 知识基底 · ledger.theme.v1 / t.settings（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
## 知识基底（已内联） —— tools/basegen_ledger.py 现算的模块影响闭包，**正向依赖=你消费的契约，反向依赖=你的回归自查范围**。⛔ 不看它就动手 = 凭空猜架构。原件 .team/nodes/settings/BASE.md。

# 知识基底 · ledger.theme.v1 / t.settings（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.settings · 终端主题库落位（契约 085 · theme-v1）

🔴 **本格的方案不是你现想的**：思路席 `t.plan` 已产出完整方案并**经 leader 客观核过**
（52 条主题清单逐条比对过上游 605 个文件，核到 52、对不上 0）。
**你的工作是照方案落地，⛔ 不是重新设计、⛔ 不是重新调研主题清单。**
方案里写「查不清」的地方（§8）才需要你自己判断，其余照做。

用户此刻的抱怨（这是本轮的起点）：「**这个绿色不太行**。」
当前深色板底 `0xFF0A1120`、cursor `0xFF4FD1C0` 青绿——就是刺眼的来源。

## 你这一格做什么（方案 §7 第 3 行）

设置页加「终端主题」入口（浅槽 / 深槽各选一个），并**用像素采样证明切换真的改变了渲染**。

**写范围（⛔ 越界即红）**：
- `app/.../ui/screens/SettingsScreen.kt` 及其接线
- `.team/nodes/theme-settings/ui-check.sh`（可重跑探针，🔴 `trap` 收尾不留后台进程）
- `.team/nodes/theme-settings/说明.md`

🔴 **⛔ 不许改 `TermSchemes.kt` / `TermPalette.kt`**（前两格的写范围）。
🔴 60 项列表要 `LazyColumn` + 搜索 + 分组，⛔ 不要退化成 Heeler 那种约 30 行无搜索的 Form（方案 §8 风险 10）。
🔴 方案 §8 风险 7：设置页「外观」卡文案仍写"终端永远深色"，已过期（083 已落地浅色终端）——**本格修它**。
⚠️ 判据脚本里的路径 `.team/nodes/theme-impl/ui-check.sh` 一律改成 `.team/nodes/theme-settings/ui-check.sh`。

## 4. 设置页信息架构

### 4.1 和现有「外观」的关系

现有设置页四张卡：主机配对 / 字体大小 / 诊断日志 / **外观**（浅色 / 深色 / 跟随系统），持久化在 `SharedPreferencesAppearanceStore`（`app_appearance` / `appearance`）。

这套已经决定 **APP 外壳**走哪套，以及终端读哪个槽（`dark` boolean）。**不要再加第二个「跟随系统」总开关**，否则和外观三段打架。

Heeler 键名读出来的模型落到我们这边是：

| Heeler 键（想法） | 我们的落点 |
|---|---|
| （系统外观） | 已有「外观」三段，不动 |
| `terminal-theme-light` | 新键：浅槽选哪个**族 id** |
| `terminal-theme-dark` | 新键：深槽选哪个**族 id** |
| `terminal-theme`（旧单键） | 我们没有历史值，不必做 legacy 迁移 |
| `follow-system` 作为主题选项 | 族 id `follow-system` = Alabaster 浅 + Afterglow 深，出现在两个槽的列表里 |

所以用户看到的是：**外观决定此刻用浅槽还是深槽；每个槽各自记住一个族。** 不是全局单选一个主题。

### 4.2 落在哪、长什么样

在「外观」卡**下面**加第五张卡「终端主题」（`SettingsCard`）。现有那句「终端正文始终保持深色，这里只切换列表、设置和外壳」**删掉**——083 已经让浅色终端走浅色板，这句是过期的，留着会把用户教错。

卡片内容（不要把 30 族摊在设置首页）：

1. 一行「浅色时」：右侧当前族展示名 + 8 色小 swatch（该族的浅半 bg+ansi 1/2/4/6）。点进去。
2. 一行「深色时」：同上，深半。点进去。
3. `testTag`：`term-theme-light-row` / `term-theme-dark-row`。展示名 `testTag`：`term-theme-family-<id>`。

点进去 = 全屏选择页（`LazyColumn`，不是 Nested 60 行）：

- 顶上：当前槽的预览条（沿用设置页已有的 `TerminalPreviewLine` 思路，但 bg/fg 用该槽候选主题）。预览必须按**这个槽的半**画，即使此刻系统是另一个外观。
- 搜索框：过滤展示名（30 项也要有；契约禁止裸列表）。
- 分组：
  - 「成对深浅」（lightSource != darkSource）
  - 「仅深色」（深浅同文件）
- 每行：swatch 54×38 dp + 标题 + 一行说明（自己写中文短句，**不要**翻译抄 Heeler 的 `detail` 英文字符串）+ 选中勾。
- 点选立刻 `save`，返回后设置首页 swatch 变。已打开的会话不需要重连：`TermPalette.of` 下一次绘制读新 Scheme（`TermSurfaceView` 已有 theme token 变化路径，走它）。

滚动：`LazyColumn` + 行高固定 ≈ 56dp，30 行无图全量也能滑。swatch 用 8 个 `Box` 纯色，禁止每行解码 bitmap。搜索是内存 `contains`，不上网络。

### 4.3 持久化

新 store，不要塞进 `app_appearance`（外观键保持单字段，避免 t.set 的旧探针被脏数据打红）：

```
SharedPreferences 名: app_term_theme
KEY_LIGHT = "terminal-theme-light"   // 族 id
KEY_DARK  = "terminal-theme-dark"
缺键 → vesper / vesper（§5）
未知族 id → 回退 vesper，并 DiagLog 记 raw 值（两边都记：读到的串、是否命中目录）
```

文件建议：`app/app/src/main/java/dev/agentmirror/app/ui/theme/TermThemeStore.kt`，镜像 `AppearanceStore` 的写法。

---

## 5. 默认值与迁移

用户原话：「这个绿色不太行。」当前深色板 `TerminalPaletteDark`：底 `0xFF0A1120`，cursor `0xFF4FD1C0`（青绿），ansi 绿 `0xFF6FD79B`。这就是刺眼的来源。

**新默认：两个槽都是 `vesper`（`Vesper.itermcolors`）。**

理由：
- 底是 `#101010`（本格已从上游 plist 读到），暖中性黑，不是青绿海军底。
- 与想法来源的**出厂默认**同族（Heeler 两个槽缺省都是 Vesper）。我们不抄它的代码，只采用「出厂用 Vesper」这个选择。
- 和当前 APP 深色底 `0xFF0A1120` 数值不同，§6.5 的 A→B 采样从第一天就能红/绿（Vesper `#101010` vs Afterglow `#212121` vs 旧 APP 底，三值互不等）。

浅槽也默认 Vesper（深色终端出现在浅色外壳上）。用户要浅色正文再自己把浅槽改成 Alabaster / Latte / Lotus。不把 `follow-system`（Alabaster/Afterglow）当出厂默认——Afterglow 底 `#212121` 仍偏灰，而且「默认」这个名字容易和外壳的「跟随系统」混。

迁移：
- 今天没有任何 `terminal-theme-*` 键。升级 = 缺键走 `vesper`。
- `app_appearance` 的浅/深/跟随系统 **原样保留**。
- 不要把旧 `TerminalPaletteDark` 做成可选族「原厂」——那正好是用户讨厌的绿。
- 没有「已存过终端主题偏好」的用户。不必写 legacy `terminal-theme` 单键分支。

---

## 6. 判据细化（可执行 argv）

全部 cwd = 仓根 `/Volumes/nvme/Projects/远程Agent安卓`。实现席先验红再改。
`ADB` 一律 `$HOME/Library/Android/sdk/platform-tools/adb`（不在 PATH）。
渲染类必须 **density 480（整数 3.0）和 440（非整数 2.75）各一遍**（083 §0；与 `t.bg` 的 `VZ_BG_DENSITIES` 同值）。跑完 `adb shell wm density reset`。
⛔ 不许用截图 md5 互不相同（077 §2）。

### 6.1 数据完整性（契约 §2.1）

```
argv: ["sh","-c","cd app && env -u TEAM_AGENT_API_KEY ./gradlew :app:testDebugUnitTest --tests dev.agentmirror.app.ui.theme.TermSchemeCatalogTest"]
expected: 0
```

测试类必须：
- 每个进清单的 `sourceFile`：`ansi.size==16`，且 `background/foreground/cursor/ansi[0..15]` 都是具体 `Int`，禁止只 `assertNotNull`。
- 金样：`colors("Vesper.itermcolors").background == 0xFF101010`；`colors("Afterglow.itermcolors").background == 0xFF212121`。
- 缺一项的主题不得出现在 `families`。
- `One Dark Two.itermcolors` / `Horizon Bright.itermcolors` **不在** `colorsBySourceFile`。

先验红：生成物还没写时该类编译失败或不通过。

### 6.2 来源可核（契约 §2.2）

同一测试类内：

- 每个 `TermSchemeColors.sourceFile` 等于 §1.2 的文件名。
- `TermPalette.of(…)` 拼出的 `Scheme.source` 等于当前槽文件名（例如默认深色 `Vesper.itermcolors`）。

另备脚本（生成器自检）：

```
argv: ["python3","tools/import_iterm_schemes.py","--check"]
expected: 0
```

（`--check` 需要网络拉钉死 SHA。若实现席无 `gh`，这条标 skip 并在说明里写清；**单测常量断言不能 skip**。）

### 6.3 NOTICE（契约 §2.3）

```
argv: ["python3","-c","import sys,io;p='app/app/src/main/assets/NOTICE-iterm2-color-schemes.txt';s=io.open(p,encoding='utf-8').read();need=['iTerm2-Color-Schemes','Mark Badolato','MIT'];miss=[n for n in need if n not in s];print('MISSING',miss);sys.exit(1 if miss else 0)"]
expected: 0
```

```
argv: ["test","-s","app/app/src/main/assets/NOTICE-iterm2-color-schemes.txt"]
expected: 0
```

### 6.4 设置页可选中（契约 §2.4）

实现席写 `.team/nodes/theme-impl/ui-check.sh`（或拆格后的对应 node）。UI 树断言，不用 md5。

进入设置页后：

```
argv: ["python3","tools/uiassert.py","has","终端主题","浅色时","深色时"]
expected: 0
```

选中变化（脚本内顺序，退出码即判据）：
1. `uiassert.py has Vesper`（默认展示名在浅槽或深槽行上）。
2. 点 `term-theme-dark-row` → 选择页 `uiassert.py has Dracula Nord`。
3. 点 Dracula 行 → 回设置页 → `uiassert.py has Dracula`，并且 **不再**在深槽行上显示 Vesper（深槽行的 `text` 变成 Dracula）。
4. 用 `uiautomator dump` 看 `term-theme-family-dracula` 的 `selected=true`（若 Compose 不写 selected，就断言深槽行文本从 Vesper 变成 Dracula——**文本变了**才算选中态变化）。

先验红：设置页还没有「终端主题」时第 1 条就红。

### 6.5 切换真的改变了终端渲染（契约 §2.5）——世界变了

禁止：只断言设置项被点了；禁止截图 md5。

做法抄 `t.bg` 的像素采样（`.team/nodes/vz-bg/ui-check.sh` 的 `screencap` raw + 终端 SurfaceView bounds），但比较的是**两个主题的纸色**，不是 paper vs ansi0。

脚本：`.team/nodes/theme-impl/ui-check.sh`

对每个 density ∈ `{480, 440}`：

1. `adb shell wm density $D`
2. 写入浅槽 `alabaster`、深槽 `vesper`（直接 `run-as` 写 `shared_prefs/app_term_theme.xml`，避免 UI 路径把「点了」和「画了」绑死）。
3. `cmd uimode night yes`，进会话页。
4. 用 `content-desc` 匹配 `term-theme-dark` 的 SurfaceView bounds，在垫 (x1+12, y1+24) 采 RGB → `A`。
5. **断言** `A` 接近 Vesper 纸色 `(16,16,16)`：`dist(A, (16,16,16)) <= 48`（阈值与 t.bg 的 paper 容差同类；记两边原始 RGB + dist）。
6. 把深槽改成 `afterglow`（再写 prefs + force-stop + 重进会话，或若实现了热切换就只重绘）。
7. 同一垫点采 `B`。
8. **断言世界变了**：`A != B`，且 `dist(B, (33,33,33)) <= 48`（Afterglow `#212121`），且 `dist(A,B) >= 16`。
9. 打日志：`density=… A=… B=… distAB=… distA_vesper=… distB_afterglow=… token_before=… token_after=…`。token 也必须从含 `Vesper.itermcolors` 变成含 `Afterglow.itermcolors`。

```
argv: ["bash",".team/nodes/theme-impl/ui-check.sh"]
expected: 0
env: VZ 同款 ADB；densities 写死 480 440，可用 THEME_DENSITIES 覆盖但不能只跑一个
```

先验红：prefs 写了但 `TermPalette.of` 仍返回旧 `0A1120` 时，`A` 会靠近 `(10,17,32)` 而不是 `(16,16,16)`，第 5 步红。

### 6.6 不倒退（契约 §2.6）

```
argv: ["sh","-c","cd app && env -u TEAM_AGENT_API_KEY ./gradlew :app:testDebugUnitTest"]
expected: 0
```

必须仍绿（实现席改完相对断言之后）：
- `dev.agentmirror.app.termview.TermThemeTest`
- `dev.agentmirror.app.termview.TermBgRemapTest`
- `dev.agentmirror.app.termview.TermBgCjkAlignTest`

land-v1 五格的 ui-check 在实现席收工时重跑（路径已在各 node）：
- `.team/nodes/land-base/ui-check.sh`
- `.team/nodes/land-list/ui-check.sh`
- `.team/nodes/land-set/ui-check.sh`（设置页仍要有「主机配对 / 字体大小 / 诊断日志 / 外观」，**新增卡不得把最后一项滚出判据**）
- `.team/nodes/land-term/ui-check.sh`
- `.team/nodes/land-reflow/` 对应脚本（若有）

073/075/076/077/078/081/082：本格不改身份键、转圈、收藏、md5 禁令、主题数据源分层以外的东西。实现席 diff 若碰到这些文件 = 越界。

### 6.7 双 density（契约 §2.7）

§6.5 的脚本内部 for-loop 两个 density，缺一个就 `FAIL density list`。不要写成两条账本判据各跑一台却漏非整数。

```
argv: ["sh","-c","grep -E '480|440' .team/nodes/theme-impl/ui-check.sh | grep -q 440 && grep -q 480 .team/nodes/theme-impl/ui-check.sh"]
expected: 0
```

（这只能证明脚本写了两个值。真正守门是 §6.5 跑过 440。）

---


---
## 🔴 全格通用纪律（违反任何一条 = 本格红）

🔴🔴🔴 **最高优先级 · 与派单正文下方「## 工作目录」那段冲突时以本条为准**：忽略那段，
一律在**仓根** /Volumes/nvme/Projects/远程Agent安卓 干活。⛔ 不要 `git worktree add`，⛔ 不要进 `.worktrees/`。
（2026-08-19 实撞两次：席位进了 worktree ⇒ 判据在仓根跑 ⇒ 测试类找不到 ⇒ 整张账本停机；
另一次判据自己的 `cwd` 被解析进了 worktree。本账本所有判据路径已全部改成绝对路径。）
⛔⛔ 绝不 `git checkout` / `git restore` 任何文件（仓根有大量未提交产品代码）。
🔴🔴 **静默纪律（用户令：节点禁止给 leader 发消息）**：⛔ 不许 `team-agent send`、不发进度/提问/完工通知。
唯一对外动作是干完调一次 `report_result`。卡住写进本格说明.md。
⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux。
🔴 **先验红再改**：本格判据在改之前必须先红一次，红的输出贴进说明.md。⛔ 判据一个字不许改。
🔴 **一次修复一个提交**。
✅ ADB 一律 `$HOME/Library/Android/sdk/platform-tools/adb`（不在 PATH）。⛔ 截图前先关输入法 `adb shell input keyevent 111`。
🟢 **⛔ 不得倒退**：073 身份键含 socket / 075 一级转圈 / 076 三条 / 077 §1 会话页标题 /
078 §1 首列不被裁 / 081 cols 仪表 / 082 收藏页按各工作区取数 / land-v1 设计落位五格 /
vz-v1 的 t.glyph（字形接缝）与 t.bg（显式背景色重映射）。

---
## 🔴 许可红线（契约 085 §0，⛔ 不许推翻、不许"重新评估"）

- ⛔ **一行 Heeler（github.com/ZingerLittleBee/Heeler）的代码都不许抄**。它是 AGPL-3.0，我们是 Apache-2.0，抄了整个 APP 被拖下水。
- ✅ 色值**只**从上游 `mbadolato/iTerm2-Color-Schemes`（MIT）取，⛔ 不从 Heeler 转手。
- ✅ Heeler 只当"做哪些主题 / 设置页怎么组织"的**想法**参考（想法不受版权保护，代码表达受）。
- 🔴 **NOTICE 必须随包**，含 `iTerm2-Color-Schemes` / `Mark Badolato` / `MIT` 三个串。缺它 = 我们违反 MIT。

---
## 方案原件（586 行，本格已内联你需要的章节；拿不准时读全文）

`/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/theme-plan/方案.md`

## 8. 风险与不确定（查不清就写查不清）

1. **51 份 `.itermcolors` 的 `Color Space` 是否全是 sRGB**：本格只完整解开 Nord（sRGB）和 Vesper/Afterglow 的 Background。其余未逐份打开。若有 Display P3，生成器按 §2.2 失败，不要当 sRGB。
2. **Alabaster 等其余主题的 Background hex**：本格拉 Alabaster plist 时 GitHub TLS timeout。除 Vesper `#101010`、Afterglow `#212121` 外，其它 hex **查不清**，留给生成器。
3. **浅色主题纸色 vs APP `userBlock` `E6F5F2` 的 luma 关系**：没算 22 个浅半。可能翻。翻了就 halt（§3.3），不要改 083 的 userBlock 去凑。
4. **Heeler 对 follow-system 实际渲染用 libghostty 内建 Afterglow/Alabaster builder，不是目录里同名文件的每一个 accent**。它自己注释说背景相同、selection/bright 可能不同。我们**只**用 iTerm2 文件，和 iOS 上 Heeler 看到的 Afterglow **允许不完全一样**。不要为了像素对齐去读 Ghostty/Heeler 资源。
5. **单个主题的版权人**：上游 LICENSE 写明 collection 是 MIT、单个主题版权归作者。NOTICE 已要求抄这句。有没有哪个主题**不能**按 MIT 再分发，本格**查不清**（没去审计 51 个文件头）。若实现席在某个 `.itermcolors` 里读到与 MIT 冲突的声明，停那一个，不要整库作废。
6. **`t.bg` 仓根代码与本方案读取时一致，但该格可能还在改 `TermPalette.colorFor`**。实现席开工前必须再读一遍仓根 `TermPalette.kt`，按当时的函数签名接 `of()`，不要按本方案里的旧快照改分支。
7. **设置页「外观」文案仍写终端永远深色**：这是过期文案（083 已落地浅色终端）。主题格修它。若 `t.chrome` 同时改 SettingsScreen，会写冲突——以「外观卡文案 + 新增终端主题卡」归主题格，间距/按钮归 chrome。
8. **像素容差 48**：从 t.bg 抄来，未经主题切换实测。Vesper `#101010` 和 Afterglow `#212121` 的 dist=3×17=51，大于 16，A≠B 够用；若 SurfaceView 垫点采到的是 ansi 黑块而不是纸色，判据会误红。实现席必须把采样点打到日志（x,y,bounds），采到字色就换点，不要放宽成 md5。
9. **模拟器资源**：083 §0 说模拟器在渲染层没有分辨力，所以 440 是守门。本格**不能**替实现席保证现在这台机器 free 内存够起 AVD。
10. **未把 Heeler 的 Swift 结构当设计**：选择页用 `LazyColumn`+搜索+分组，这是契约对 60 项性能的要求；Heeler 本身是约 30 行 Form 无搜索。不要回头改成「跟它一模一样」而丢掉搜索。

---

## 9. 实现席禁令（再写一遍）

- ⛔ 不 copy `Sources/Heeler/**`。
- ⛔ 不 `git clone` iTerm2-Color-Schemes 全仓；`gh api` / raw 按文件拉。
- ⛔ 不把 `One Dark Two` / `Horizon Bright` / `Rose Pine Moon` 等变体顶进清单。
- ⛔ 不改 `colorFor` 的 083 重映射表去「适应」新主题。
- ⛔ 不等 vz-v1 结束就写 `app/`。
- ⛔ 不在 worktree 里施工、在仓根跑判据（2026-08-19 实撞）。

```

- write_paths: app/app/src/main/java/dev/agentmirror/app/, app/app/src/test/kotlin/dev/agentmirror/app/, .team/nodes/theme-settings/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/theme-plan/方案.md, /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/085-终端主题库落位.md, /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/083-真机视觉收口六条.md
- 判据: A-set-ui, A-set-density, A-set-nomd5, A-set-suite, A-set-doc

## 2. 架构基（wiki 现算影响闭包）
- 写作用域包：dev.agentmirror.app
- 正向依赖（你消费的契约，只读）：kt_dev_agentmirror_app_pairing, kt_dev_agentmirror_app_service, kt_dev_agentmirror_app_session, kt_dev_agentmirror_app_ui_theme, kt_dev_agentmirror_app_workspace
- **反向依赖（波及面 = 回归自查范围）**：kt_dev_agentmirror_app_service

### 闭包架构卡内联

### Kotlin · dev.agentmirror.app

- **职责**：Compose 应用根组合。
- **导出面**：AgentMirrorApp, MainActivity, MainNavState, Session
- **依赖边**：dev.agentmirror.app.pairing, dev.agentmirror.app.service, dev.agentmirror.app.session, dev.agentmirror.app.ui.theme, dev.agentmirror.app.workspace
- **doc 全文**：Compose 应用根组合。 依需求 004「客户端无状态」，本组件只做路由，不持有任何业务状态。 首启路由（pairing-ui 知识基底 §1）： - 无配对配置 → 配对页（扫码/手填，可跳过进空工作区）； - 有配对配置 → 直进工作区列表； - 配对页可从设置/重配入口重进（重新配对）。 会话页跳转沿用 session-ui 挂载的 [SessionRoute]。 导航态由 [navState]（D-3 修复）注入：MainActivity 在 onSaveInstanceState 持久化、重建恢复，深链/旋转都不丢导航位置（审计 D-2/D-3）。 工作区 VM（[workspaceViewModel]）由 MainActivity 持有（fix-workspace-wiring 修复， navState 同模式提升），本组件只负责在工作区分支用 [DisposableEffect] 把它接入 [ServiceWire.uiConnector]——配对成功后列表能渲染（此前 VM 裸建从未接线，uiConnector 全仓无调用点，见 fix-workspace-wiring 知识基底）。 @consumes dev.agentmirror.app.pairing @consumes dev.agentmirror.app.service @consumes dev.agentmirror.app.session @consumes dev.agentmirror.app.ui.theme @consumes dev.agentmirror.app.workspace

### Kotlin · dev.agentmirror.app.service

- **职责**：前台服务：常驻连接 + 通知栏（需求 004 Android 前台服务路线）。
- **导出面**：AppClockPump, MirrorForegroundService, NetworkConnectivityWatcher, NoopTransportFactory, NotificationHelper, OkHttpTransportFactory, OkHttpWebSocketTransport, OnScreenFallbackPump, ServiceWire, StateWatcher, TsnetBootstrap
- **依赖边**：dev.agentmirror.app, dev.agentmirror.app.conn, dev.agentmirror.app.tsnet
- **doc 全文**：前台服务：常驻连接 + 通知栏（需求 004 Android 前台服务路线）。 分层（fg-service 知识基底 §1）： - [StateWatcher]：纯 JVM 核心逻辑（验收单测全打这里），消费 conn 层 listing/list_delta 流，检测会话状态沿变化（→blocked/→done）→ 通知；同状态重复抑制；unknown 不通知。 - [NotificationHelper]：通知渠道（常驻/状态两条）+ 常驻通知与状态通知 + 会话页深链 PendingIntent（action/extra 由 [MainActivity] 的 handleDeepLink 消费，非本包）。 - [MirrorForegroundService]：薄 Android 层，startForeground（dataSync）+ 生命周期绑定 [ConnectionManager]（经 [ServiceWire]）；断连静默重连归 conn 层，本服务只反映状态。 已接线（feat-fg-service-wiring）：配对成功/冷启动/进入会话经 [MirrorForegroundService.start] 启动（startForegroundService），连接与时钟泵由本服务承接（004/011 前台服务路线）。 - [ServiceWire]：接线点——传输工厂（默认 [OkHttpTransportFactory]）、UI 监听桥 （[uiConnector]）与服务监听槽（[serviceListener]）、连接配置注入；进程级持有唯一 [ConnectionManager]，服务与 UI 都经它访问同一单例。 电量策略（004 裁定）：服务被系统杀 → 冷启动重连即恢复（客户端无状态，没有丢失可言）。 服务**不持有连接状态**（004 无状态底线）：连接是 [ServiceWire] 进程级单例，配置唯一来源 是 SharedPreferences，服务只经 [ServiceWire.managerOrNull] 读取并驱动时钟泵 （[MirrorForegroundService.pumpOnce]，2s 一拍，在屏组合不再各自持有）。服务不可用时 在屏兜底泵 [OnScreenFallbackPump] 接管（fix-app-runtime-sa：服务被杀前台仍推进）， 服务恢复即让出（泵归属判据 [ServiceWire.servicePumpActive]，不双泵）。 @consumes dev.agentmirror.app @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.tsnet

## 3. 需求基
- 标题引用条目：（无编号引用）
- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）

## 4. 纪律（本工程通用，违反即返工）
- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。
- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。
- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。


---
以上是基底，以下是任务。

# t.settings · 终端主题库落位（契约 085 · theme-v1）

🔴 **本格的方案不是你现想的**：思路席 `t.plan` 已产出完整方案并**经 leader 客观核过**
（52 条主题清单逐条比对过上游 605 个文件，核到 52、对不上 0）。
**你的工作是照方案落地，⛔ 不是重新设计、⛔ 不是重新调研主题清单。**
方案里写「查不清」的地方（§8）才需要你自己判断，其余照做。

用户此刻的抱怨（这是本轮的起点）：「**这个绿色不太行**。」
当前深色板底 `0xFF0A1120`、cursor `0xFF4FD1C0` 青绿——就是刺眼的来源。

## 你这一格做什么（方案 §7 第 3 行）

设置页加「终端主题」入口（浅槽 / 深槽各选一个），并**用像素采样证明切换真的改变了渲染**。

**写范围（⛔ 越界即红）**：
- `app/.../ui/screens/SettingsScreen.kt` 及其接线
- `.team/nodes/theme-settings/ui-check.sh`（可重跑探针，🔴 `trap` 收尾不留后台进程）
- `.team/nodes/theme-settings/说明.md`

🔴 **⛔ 不许改 `TermSchemes.kt` / `TermPalette.kt`**（前两格的写范围）。
🔴 60 项列表要 `LazyColumn` + 搜索 + 分组，⛔ 不要退化成 Heeler 那种约 30 行无搜索的 Form（方案 §8 风险 10）。
🔴 方案 §8 风险 7：设置页「外观」卡文案仍写"终端永远深色"，已过期（083 已落地浅色终端）——**本格修它**。
⚠️ 判据脚本里的路径 `.team/nodes/theme-impl/ui-check.sh` 一律改成 `.team/nodes/theme-settings/ui-check.sh`。

## 4. 设置页信息架构

### 4.1 和现有「外观」的关系

现有设置页四张卡：主机配对 / 字体大小 / 诊断日志 / **外观**（浅色 / 深色 / 跟随系统），持久化在 `SharedPreferencesAppearanceStore`（`app_appearance` / `appearance`）。

这套已经决定 **APP 外壳**走哪套，以及终端读哪个槽（`dark` boolean）。**不要再加第二个「跟随系统」总开关**，否则和外观三段打架。

Heeler 键名读出来的模型落到我们这边是：

| Heeler 键（想法） | 我们的落点 |
|---|---|
| （系统外观） | 已有「外观」三段，不动 |
| `terminal-theme-light` | 新键：浅槽选哪个**族 id** |
| `terminal-theme-dark` | 新键：深槽选哪个**族 id** |
| `terminal-theme`（旧单键） | 我们没有历史值，不必做 legacy 迁移 |
| `follow-system` 作为主题选项 | 族 id `follow-system` = Alabaster 浅 + Afterglow 深，出现在两个槽的列表里 |

所以用户看到的是：**外观决定此刻用浅槽还是深槽；每个槽各自记住一个族。** 不是全局单选一个主题。

### 4.2 落在哪、长什么样

在「外观」卡**下面**加第五张卡「终端主题」（`SettingsCard`）。现有那句「终端正文始终保持深色，这里只切换列表、设置和外壳」**删掉**——083 已经让浅色终端走浅色板，这句是过期的，留着会把用户教错。

卡片内容（不要把 30 族摊在设置首页）：

1. 一行「浅色时」：右侧当前族展示名 + 8 色小 swatch（该族的浅半 bg+ansi 1/2/4/6）。点进去。
2. 一行「深色时」：同上，深半。点进去。
3. `testTag`：`term-theme-light-row` / `term-theme-dark-row`。展示名 `testTag`：`term-theme-family-<id>`。

点进去 = 全屏选择页（`LazyColumn`，不是 Nested 60 行）：

- 顶上：当前槽的预览条（沿用设置页已有的 `TerminalPreviewLine` 思路，但 bg/fg 用该槽候选主题）。预览必须按**这个槽的半**画，即使此刻系统是另一个外观。
- 搜索框：过滤展示名（30 项也要有；契约禁止裸列表）。
- 分组：
  - 「成对深浅」（lightSource != darkSource）
  - 「仅深色」（深浅同文件）
- 每行：swatch 54×38 dp + 标题 + 一行说明（自己写中文短句，**不要**翻译抄 Heeler 的 `detail` 英文字符串）+ 选中勾。
- 点选立刻 `save`，返回后设置首页 swatch 变。已打开的会话不需要重连：`TermPalette.of` 下一次绘制读新 Scheme（`TermSurfaceView` 已有 theme token 变化路径，走它）。

滚动：`LazyColumn` + 行高固定 ≈ 56dp，30 行无图全量也能滑。swatch 用 8 个 `Box` 纯色，禁止每行解码 bitmap。搜索是内存 `contains`，不上网络。

### 4.3 持久化

新 store，不要塞进 `app_appearance`（外观键保持单字段，避免 t.set 的旧探针被脏数据打红）：

```
SharedPreferences 名: app_term_theme
KEY_LIGHT = "terminal-theme-light"   // 族 id
KEY_DARK  = "terminal-theme-dark"
缺键 → vesper / vesper（§5）
未知族 id → 回退 vesper，并 DiagLog 记 raw 值（两边都记：读到的串、是否命中目录）
```

文件建议：`app/app/src/main/java/dev/agentmirror/app/ui/theme/TermThemeStore.kt`，镜像 `AppearanceStore` 的写法。

---

## 5. 默认值与迁移

用户原话：「这个绿色不太行。」当前深色板 `TerminalPaletteDark`：底 `0xFF0A1120`，cursor `0xFF4FD1C0`（青绿），ansi 绿 `0xFF6FD79B`。这就是刺眼的来源。

**新默认：两个槽都是 `vesper`（`Vesper.itermcolors`）。**

理由：
- 底是 `#101010`（本格已从上游 plist 读到），暖中性黑，不是青绿海军底。
- 与想法来源的**出厂默认**同族（Heeler 两个槽缺省都是 Vesper）。我们不抄它的代码，只采用「出厂用 Vesper」这个选择。
- 和当前 APP 深色底 `0xFF0A1120` 数值不同，§6.5 的 A→B 采样从第一天就能红/绿（Vesper `#101010` vs Afterglow `#212121` vs 旧 APP 底，三值互不等）。

浅槽也默认 Vesper（深色终端出现在浅色外壳上）。用户要浅色正文再自己把浅槽改成 Alabaster / Latte / Lotus。不把 `follow-system`（Alabaster/Afterglow）当出厂默认——Afterglow 底 `#212121` 仍偏灰，而且「默认」这个名字容易和外壳的「跟随系统」混。

迁移：
- 今天没有任何 `terminal-theme-*` 键。升级 = 缺键走 `vesper`。
- `app_appearance` 的浅/深/跟随系统 **原样保留**。
- 不要把旧 `TerminalPaletteDark` 做成可选族「原厂」——那正好是用户讨厌的绿。
- 没有「已存过终端主题偏好」的用户。不必写 legacy `terminal-theme` 单键分支。

---

## 6. 判据细化（可执行 argv）

全部 cwd = 仓根 `/Volumes/nvme/Projects/远程Agent安卓`。实现席先验红再改。
`ADB` 一律 `$HOME/Library/Android/sdk/platform-tools/adb`（不在 PATH）。
渲染类必须 **density 480（整数 3.0）和 440（非整数 2.75）各一遍**（083 §0；与 `t.bg` 的 `VZ_BG_DENSITIES` 同值）。跑完 `adb shell wm density reset`。
⛔ 不许用截图 md5 互不相同（077 §2）。

### 6.1 数据完整性（契约 §2.1）

```
argv: ["sh","-c","cd app && env -u TEAM_AGENT_API_KEY ./gradlew :app:testDebugUnitTest --tests dev.agentmirror.app.ui.theme.TermSchemeCatalogTest"]
expected: 0
```

测试类必须：
- 每个进清单的 `sourceFile`：`ansi.size==16`，且 `background/foreground/cursor/ansi[0..15]` 都是具体 `Int`，禁止只 `assertNotNull`。
- 金样：`colors("Vesper.itermcolors").background == 0xFF101010`；`colors("Afterglow.itermcolors").background == 0xFF212121`。
- 缺一项的主题不得出现在 `families`。
- `One Dark Two.itermcolors` / `Horizon Bright.itermcolors` **不在** `colorsBySourceFile`。

先验红：生成物还没写时该类编译失败或不通过。

### 6.2 来源可核（契约 §2.2）

同一测试类内：

- 每个 `TermSchemeColors.sourceFile` 等于 §1.2 的文件名。
- `TermPalette.of(…)` 拼出的 `Scheme.source` 等于当前槽文件名（例如默认深色 `Vesper.itermcolors`）。

另备脚本（生成器自检）：

```
argv: ["python3","tools/import_iterm_schemes.py","--check"]
expected: 0
```

（`--check` 需要网络拉钉死 SHA。若实现席无 `gh`，这条标 skip 并在说明里写清；**单测常量断言不能 skip**。）

### 6.3 NOTICE（契约 §2.3）

```
argv: ["python3","-c","import sys,io;p='app/app/src/main/assets/NOTICE-iterm2-color-schemes.txt';s=io.open(p,encoding='utf-8').read();need=['iTerm2-Color-Schemes','Mark Badolato','MIT'];miss=[n for n in need if n not in s];print('MISSING',miss);sys.exit(1 if miss else 0)"]
expected: 0
```

```
argv: ["test","-s","app/app/src/main/assets/NOTICE-iterm2-color-schemes.txt"]
expected: 0
```

### 6.4 设置页可选中（契约 §2.4）

实现席写 `.team/nodes/theme-impl/ui-check.sh`（或拆格后的对应 node）。UI 树断言，不用 md5。

进入设置页后：

```
argv: ["python3","tools/uiassert.py","has","终端主题","浅色时","深色时"]
expected: 0
```

选中变化（脚本内顺序，退出码即判据）：
1. `uiassert.py has Vesper`（默认展示名在浅槽或深槽行上）。
2. 点 `term-theme-dark-row` → 选择页 `uiassert.py has Dracula Nord`。
3. 点 Dracula 行 → 回设置页 → `uiassert.py has Dracula`，并且 **不再**在深槽行上显示 Vesper（深槽行的 `text` 变成 Dracula）。
4. 用 `uiautomator dump` 看 `term-theme-family-dracula` 的 `selected=true`（若 Compose 不写 selected，就断言深槽行文本从 Vesper 变成 Dracula——**文本变了**才算选中态变化）。

先验红：设置页还没有「终端主题」时第 1 条就红。

### 6.5 切换真的改变了终端渲染（契约 §2.5）——世界变了

禁止：只断言设置项被点了；禁止截图 md5。

做法抄 `t.bg` 的像素采样（`.team/nodes/vz-bg/ui-check.sh` 的 `screencap` raw + 终端 SurfaceView bounds），但比较的是**两个主题的纸色**，不是 paper vs ansi0。

脚本：`.team/nodes/theme-impl/ui-check.sh`

对每个 density ∈ `{480, 440}`：

1. `adb shell wm density $D`
2. 写入浅槽 `alabaster`、深槽 `vesper`（直接 `run-as` 写 `shared_prefs/app_term_theme.xml`，避免 UI 路径把「点了」和「画了」绑死）。
3. `cmd uimode night yes`，进会话页。
4. 用 `content-desc` 匹配 `term-theme-dark` 的 SurfaceView bounds，在垫 (x1+12, y1+24) 采 RGB → `A`。
5. **断言** `A` 接近 Vesper 纸色 `(16,16,16)`：`dist(A, (16,16,16)) <= 48`（阈值与 t.bg 的 paper 容差同类；记两边原始 RGB + dist）。
6. 把深槽改成 `afterglow`（再写 prefs + force-stop + 重进会话，或若实现了热切换就只重绘）。
7. 同一垫点采 `B`。
8. **断言世界变了**：`A != B`，且 `dist(B, (33,33,33)) <= 48`（Afterglow `#212121`），且 `dist(A,B) >= 16`。
9. 打日志：`density=… A=… B=… distAB=… distA_vesper=… distB_afterglow=… token_before=… token_after=…`。token 也必须从含 `Vesper.itermcolors` 变成含 `Afterglow.itermcolors`。

```
argv: ["bash",".team/nodes/theme-impl/ui-check.sh"]
expected: 0
env: VZ 同款 ADB；densities 写死 480 440，可用 THEME_DENSITIES 覆盖但不能只跑一个
```

先验红：prefs 写了但 `TermPalette.of` 仍返回旧 `0A1120` 时，`A` 会靠近 `(10,17,32)` 而不是 `(16,16,16)`，第 5 步红。

### 6.6 不倒退（契约 §2.6）

```
argv: ["sh","-c","cd app && env -u TEAM_AGENT_API_KEY ./gradlew :app:testDebugUnitTest"]
expected: 0
```

必须仍绿（实现席改完相对断言之后）：
- `dev.agentmirror.app.termview.TermThemeTest`
- `dev.agentmirror.app.termview.TermBgRemapTest`
- `dev.agentmirror.app.termview.TermBgCjkAlignTest`

land-v1 五格的 ui-check 在实现席收工时重跑（路径已在各 node）：
- `.team/nodes/land-base/ui-check.sh`
- `.team/nodes/land-list/ui-check.sh`
- `.team/nodes/land-set/ui-check.sh`（设置页仍要有「主机配对 / 字体大小 / 诊断日志 / 外观」，**新增卡不得把最后一项滚出判据**）
- `.team/nodes/land-term/ui-check.sh`
- `.team/nodes/land-reflow/` 对应脚本（若有）

073/075/076/077/078/081/082：本格不改身份键、转圈、收藏、md5 禁令、主题数据源分层以外的东西。实现席 diff 若碰到这些文件 = 越界。

### 6.7 双 density（契约 §2.7）

§6.5 的脚本内部 for-loop 两个 density，缺一个就 `FAIL density list`。不要写成两条账本判据各跑一台却漏非整数。

```
argv: ["sh","-c","grep -E '480|440' .team/nodes/theme-impl/ui-check.sh | grep -q 440 && grep -q 480 .team/nodes/theme-impl/ui-check.sh"]
expected: 0
```

（这只能证明脚本写了两个值。真正守门是 §6.5 跑过 440。）

---


---
## 🔴 全格通用纪律（违反任何一条 = 本格红）

🔴🔴🔴 **最高优先级 · 与派单正文下方「## 工作目录」那段冲突时以本条为准**：忽略那段，
一律在**仓根** /Volumes/nvme/Projects/远程Agent安卓 干活。⛔ 不要 `git worktree add`，⛔ 不要进 `.worktrees/`。
（2026-08-19 实撞两次：席位进了 worktree ⇒ 判据在仓根跑 ⇒ 测试类找不到 ⇒ 整张账本停机；
另一次判据自己的 `cwd` 被解析进了 worktree。本账本所有判据路径已全部改成绝对路径。）
⛔⛔ 绝不 `git checkout` / `git restore` 任何文件（仓根有大量未提交产品代码）。
🔴🔴 **静默纪律（用户令：节点禁止给 leader 发消息）**：⛔ 不许 `team-agent send`、不发进度/提问/完工通知。
唯一对外动作是干完调一次 `report_result`。卡住写进本格说明.md。
⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux。
🔴 **先验红再改**：本格判据在改之前必须先红一次，红的输出贴进说明.md。⛔ 判据一个字不许改。
🔴 **一次修复一个提交**。
✅ ADB 一律 `$HOME/Library/Android/sdk/platform-tools/adb`（不在 PATH）。⛔ 截图前先关输入法 `adb shell input keyevent 111`。
🟢 **⛔ 不得倒退**：073 身份键含 socket / 075 一级转圈 / 076 三条 / 077 §1 会话页标题 /
078 §1 首列不被裁 / 081 cols 仪表 / 082 收藏页按各工作区取数 / land-v1 设计落位五格 /
vz-v1 的 t.glyph（字形接缝）与 t.bg（显式背景色重映射）。

---
## 🔴 许可红线（契约 085 §0，⛔ 不许推翻、不许"重新评估"）

- ⛔ **一行 Heeler（github.com/ZingerLittleBee/Heeler）的代码都不许抄**。它是 AGPL-3.0，我们是 Apache-2.0，抄了整个 APP 被拖下水。
- ✅ 色值**只**从上游 `mbadolato/iTerm2-Color-Schemes`（MIT）取，⛔ 不从 Heeler 转手。
- ✅ Heeler 只当"做哪些主题 / 设置页怎么组织"的**想法**参考（想法不受版权保护，代码表达受）。
- 🔴 **NOTICE 必须随包**，含 `iTerm2-Color-Schemes` / `Mark Badolato` / `MIT` 三个串。缺它 = 我们违反 MIT。

---
## 方案原件（586 行，本格已内联你需要的章节；拿不准时读全文）

`/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/theme-plan/方案.md`

## 8. 风险与不确定（查不清就写查不清）

1. **51 份 `.itermcolors` 的 `Color Space` 是否全是 sRGB**：本格只完整解开 Nord（sRGB）和 Vesper/Afterglow 的 Background。其余未逐份打开。若有 Display P3，生成器按 §2.2 失败，不要当 sRGB。
2. **Alabaster 等其余主题的 Background hex**：本格拉 Alabaster plist 时 GitHub TLS timeout。除 Vesper `#101010`、Afterglow `#212121` 外，其它 hex **查不清**，留给生成器。
3. **浅色主题纸色 vs APP `userBlock` `E6F5F2` 的 luma 关系**：没算 22 个浅半。可能翻。翻了就 halt（§3.3），不要改 083 的 userBlock 去凑。
4. **Heeler 对 follow-system 实际渲染用 libghostty 内建 Afterglow/Alabaster builder，不是目录里同名文件的每一个 accent**。它自己注释说背景相同、selection/bright 可能不同。我们**只**用 iTerm2 文件，和 iOS 上 Heeler 看到的 Afterglow **允许不完全一样**。不要为了像素对齐去读 Ghostty/Heeler 资源。
5. **单个主题的版权人**：上游 LICENSE 写明 collection 是 MIT、单个主题版权归作者。NOTICE 已要求抄这句。有没有哪个主题**不能**按 MIT 再分发，本格**查不清**（没去审计 51 个文件头）。若实现席在某个 `.itermcolors` 里读到与 MIT 冲突的声明，停那一个，不要整库作废。
6. **`t.bg` 仓根代码与本方案读取时一致，但该格可能还在改 `TermPalette.colorFor`**。实现席开工前必须再读一遍仓根 `TermPalette.kt`，按当时的函数签名接 `of()`，不要按本方案里的旧快照改分支。
7. **设置页「外观」文案仍写终端永远深色**：这是过期文案（083 已落地浅色终端）。主题格修它。若 `t.chrome` 同时改 SettingsScreen，会写冲突——以「外观卡文案 + 新增终端主题卡」归主题格，间距/按钮归 chrome。
8. **像素容差 48**：从 t.bg 抄来，未经主题切换实测。Vesper `#101010` 和 Afterglow `#212121` 的 dist=3×17=51，大于 16，A≠B 够用；若 SurfaceView 垫点采到的是 ansi 黑块而不是纸色，判据会误红。实现席必须把采样点打到日志（x,y,bounds），采到字色就换点，不要放宽成 md5。
9. **模拟器资源**：083 §0 说模拟器在渲染层没有分辨力，所以 440 是守门。本格**不能**替实现席保证现在这台机器 free 内存够起 AVD。
10. **未把 Heeler 的 Swift 结构当设计**：选择页用 `LazyColumn`+搜索+分组，这是契约对 60 项性能的要求；Heeler 本身是约 30 行 Form 无搜索。不要回头改成「跟它一模一样」而丢掉搜索。

---

## 9. 实现席禁令（再写一遍）

- ⛔ 不 copy `Sources/Heeler/**`。
- ⛔ 不 `git clone` iTerm2-Color-Schemes 全仓；`gh api` / raw 按文件拉。
- ⛔ 不把 `One Dark Two` / `Horizon Bright` / `Rose Pine Moon` 等变体顶进清单。
- ⛔ 不改 `colorFor` 的 083 重映射表去「适应」新主题。
- ⛔ 不等 vz-v1 结束就写 `app/`。
- ⛔ 不在 worktree 里施工、在仓根跑判据（2026-08-19 实撞）。

```

- write_paths: app/app/src/main/java/dev/agentmirror/app/, app/app/src/test/kotlin/dev/agentmirror/app/, .team/nodes/theme-settings/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/theme-plan/方案.md, /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/085-终端主题库落位.md, /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/083-真机视觉收口六条.md, .team/nodes/settings/BASE.md
- 判据: A-set-ui, A-set-density, A-set-nomd5, A-set-suite, A-set-doc

## 2. 架构基（wiki 现算影响闭包）
- 写作用域包：dev.agentmirror.app
- 正向依赖（你消费的契约，只读）：kt_dev_agentmirror_app_pairing, kt_dev_agentmirror_app_service, kt_dev_agentmirror_app_session, kt_dev_agentmirror_app_ui_theme, kt_dev_agentmirror_app_workspace
- **反向依赖（波及面 = 回归自查范围）**：kt_dev_agentmirror_app_service

### 闭包架构卡内联

### Kotlin · dev.agentmirror.app

- **职责**：Compose 应用根组合。
- **导出面**：AgentMirrorApp, MainActivity, MainNavState, Session
- **依赖边**：dev.agentmirror.app.pairing, dev.agentmirror.app.service, dev.agentmirror.app.session, dev.agentmirror.app.ui.theme, dev.agentmirror.app.workspace
- **doc 全文**：Compose 应用根组合。 依需求 004「客户端无状态」，本组件只做路由，不持有任何业务状态。 首启路由（pairing-ui 知识基底 §1）： - 无配对配置 → 配对页（扫码/手填，可跳过进空工作区）； - 有配对配置 → 直进工作区列表； - 配对页可从设置/重配入口重进（重新配对）。 会话页跳转沿用 session-ui 挂载的 [SessionRoute]。 导航态由 [navState]（D-3 修复）注入：MainActivity 在 onSaveInstanceState 持久化、重建恢复，深链/旋转都不丢导航位置（审计 D-2/D-3）。 工作区 VM（[workspaceViewModel]）由 MainActivity 持有（fix-workspace-wiring 修复， navState 同模式提升），本组件只负责在工作区分支用 [DisposableEffect] 把它接入 [ServiceWire.uiConnector]——配对成功后列表能渲染（此前 VM 裸建从未接线，uiConnector 全仓无调用点，见 fix-workspace-wiring 知识基底）。 @consumes dev.agentmirror.app.pairing @consumes dev.agentmirror.app.service @consumes dev.agentmirror.app.session @consumes dev.agentmirror.app.ui.theme @consumes dev.agentmirror.app.workspace

### Kotlin · dev.agentmirror.app.service

- **职责**：前台服务：常驻连接 + 通知栏（需求 004 Android 前台服务路线）。
- **导出面**：AppClockPump, MirrorForegroundService, NetworkConnectivityWatcher, NoopTransportFactory, NotificationHelper, OkHttpTransportFactory, OkHttpWebSocketTransport, OnScreenFallbackPump, ServiceWire, StateWatcher, TsnetBootstrap
- **依赖边**：dev.agentmirror.app, dev.agentmirror.app.conn, dev.agentmirror.app.tsnet
- **doc 全文**：前台服务：常驻连接 + 通知栏（需求 004 Android 前台服务路线）。 分层（fg-service 知识基底 §1）： - [StateWatcher]：纯 JVM 核心逻辑（验收单测全打这里），消费 conn 层 listing/list_delta 流，检测会话状态沿变化（→blocked/→done）→ 通知；同状态重复抑制；unknown 不通知。 - [NotificationHelper]：通知渠道（常驻/状态两条）+ 常驻通知与状态通知 + 会话页深链 PendingIntent（action/extra 由 [MainActivity] 的 handleDeepLink 消费，非本包）。 - [MirrorForegroundService]：薄 Android 层，startForeground（dataSync）+ 生命周期绑定 [ConnectionManager]（经 [ServiceWire]）；断连静默重连归 conn 层，本服务只反映状态。 已接线（feat-fg-service-wiring）：配对成功/冷启动/进入会话经 [MirrorForegroundService.start] 启动（startForegroundService），连接与时钟泵由本服务承接（004/011 前台服务路线）。 - [ServiceWire]：接线点——传输工厂（默认 [OkHttpTransportFactory]）、UI 监听桥 （[uiConnector]）与服务监听槽（[serviceListener]）、连接配置注入；进程级持有唯一 [ConnectionManager]，服务与 UI 都经它访问同一单例。 电量策略（004 裁定）：服务被系统杀 → 冷启动重连即恢复（客户端无状态，没有丢失可言）。 服务**不持有连接状态**（004 无状态底线）：连接是 [ServiceWire] 进程级单例，配置唯一来源 是 SharedPreferences，服务只经 [ServiceWire.managerOrNull] 读取并驱动时钟泵 （[MirrorForegroundService.pumpOnce]，2s 一拍，在屏组合不再各自持有）。服务不可用时 在屏兜底泵 [OnScreenFallbackPump] 接管（fix-app-runtime-sa：服务被杀前台仍推进）， 服务恢复即让出（泵归属判据 [ServiceWire.servicePumpActive]，不双泵）。 @consumes dev.agentmirror.app @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.tsnet

## 3. 需求基
- 标题引用条目：requirement-base/entries/083*, requirement-base/entries/085*
- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）

## 4. 纪律（本工程通用，违反即返工）
- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。
- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。
- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。
