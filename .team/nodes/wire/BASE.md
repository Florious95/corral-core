# 知识基底 · ledger.theme.v1 / t.wire（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
## 知识基底（已内联） —— tools/basegen_ledger.py 现算的模块影响闭包，**正向依赖=你消费的契约，反向依赖=你的回归自查范围**。⛔ 不看它就动手 = 凭空猜架构。原件 .team/nodes/wire/BASE.md。

# 知识基底 · ledger.theme.v1 / t.wire（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.wire · 终端主题库落位（契约 085 · theme-v1）

🔴 **本格的方案不是你现想的**：思路席 `t.plan` 已产出完整方案并**经 leader 客观核过**
（52 条主题清单逐条比对过上游 605 个文件，核到 52、对不上 0）。
**你的工作是照方案落地，⛔ 不是重新设计、⛔ 不是重新调研主题清单。**
方案里写「查不清」的地方（§8）才需要你自己判断，其余照做。

用户此刻的抱怨（这是本轮的起点）：「**这个绿色不太行**。」
当前深色板底 `0xFF0A1120`、cursor `0xFF4FD1C0` 青绿——就是刺眼的来源。

## 你这一格做什么（方案 §7 第 2 行）

把 `t.catalog` 生成的色表接进绘制层，让**默认 Vesper 真的画出来**。

**写范围（⛔ 越界即红）**：
- `TermPalette.kt`（只改 `of` / `schemeFrom` / `token` / `SOURCE`，🔴 **⛔ 不改 `colorFor` 的 083 重映射分支**）
- `AppTheme.kt` 的 `currentTerminalPalette()`
- `TermThemeStore.kt`（新文件，偏好读写）
- 对应单测（含把 `TermThemeTest` 的绝对断言改成相对断言）
- `.team/nodes/theme-wire/说明.md`

🔴🔴🔴 **范围已扩大（契约 085 §1.5，用户 2026-08-20 澄清）**：
主题不是"换默认色板"，是**重新着色层**——CLI 发什么颜色都不作数，
**256 色索引 16–255 和 24 位真彩也必须投影到主题色板**。
⇒ **`colorFor` 就是你要改的地方**（上一版任务书写着"不许改 colorFor"，**那条已作废**）。
⚠️ 但机制不动、数据源换成主题：`t.bg` 落地的 083 §2 三条映射（索引 0/16→纸色、254/近白→userBlock、
真彩背景亮度守卫）**保留**，只是目标值改成选中主题的色板。⛔ 不得推翻 083 §2。
🔴 **投影规则不许你自己拍脑袋**——`t.remap` 格已产出 `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/theme-remap/投影规则.md`，
照它做。它写"查不清"的地方才轮到你判断。

🔴 **⛔ 本格不许碰设置页**（那是 `t.settings`），也⛔不许改 `TermSchemes.kt`（那是 `t.catalog` 的生成物）。
🔴 **开工第一件事：重读仓根 `TermPalette.kt`**（方案 §8 风险 6）——
`vz-v1` 的 `t.bg` 格刚改过 `colorFor`，按**当时的真实签名**接 `of()`，⛔ 不要按方案里的旧快照改分支。
设置页还没入口时，用直接写 prefs 的方式验证。

## 3. 与 083 §2 / `t.bg` 怎么合流（不得倒退）

### 3.1 分层：谁管什么

| 色 / 度量 | 现在落在 | 主题库之后 |
|---|---|---|
| APP 外壳（列表底、卡片描边、导航、设置页） | `DesignTokens.kt` 的 `LightPalette` / `DarkPalette` | **仍归外壳**。外观三段开关不动 |
| 终端默认 bg / fg | `TerminalPaletteLight/Dark.background/foreground` | **被选中主题接管** |
| ANSI 0–15 | `TerminalPalette*.ansi` | **被选中主题接管** |
| cursor | `TerminalPalette*.cursor` | **被选中主题接管**（上游 `Cursor Color`） |
| selection | `TerminalPalette*.selection` | 上游有 `Selection Color` 就用；没有则留 APP 值 |
| **userBlockBackground / userBlockForeground** | 浅 `E6F5F2` / `0E3B35`，深 `10241F` / `DCF3EF` | **仍归 APP**。083 §2 的「浅色白底 + 灰底用户消息块」是这条，不是 16 色板 |
| 终端 padding / 行高 / 字号档 | `TerminalMetrics` | **不动**（083 §3 的间距是 `t.chrome` 的事） |
| 显式背景重映射 | `TermPalette.colorFor`（`t.bg` 正在改） | **规则不动，操作数换成当前主题的 Scheme** |

`TerminalPaletteLight` / `TerminalPaletteDark` 两份字面量**保留当缺省回退**（目录损坏、未知族 id），不要删。设置页不再把它们当成用户可选的「原厂绿」。

### 3.2 `TermPalette` 合流点（谁先谁后）

`t.bg`（账本 `vz-v1`）已经把绘制层收成：

```
TermSurfaceView.colorFor(...) → TermPalette.colorFor(color, background, dark)
TermPalette.of(dark) → Light 或 Dark 静态 Scheme
```

主题库**只改 Scheme 从哪来**，不改 `colorFor` 的分支表。

顺序（实现席按这个接，禁止把重映射搬进主题文件）：

1. **外观**（已有）`Appearance` → `dark: Boolean`（`AppTheme` 现逻辑）。
2. **槽** → `dark` 则读 `terminal-theme-dark` 族 id，否则读 `terminal-theme-light`。
3. **族 → 文件** → `family.darkSource` 或 `family.lightSource`。
4. **文件 → 16+fg/bg/cursor** → `TermSchemeCatalog.colors(sourceFile)`。
5. **拼 Scheme**：
   - `defaultBg/defaultFg/ansi16` = 上游
   - `userBlockBg` = **仍然** `TerminalPaletteLight/Dark.userBlockBackground`（按 `dark` 选，不按主题）
   - `source` = 上游文件名（替换今天的恒定 `"app-theme"`）
6. **`colorFor`（t.bg 的规则，后执行）**：
   - Default → `defaultBg` / `defaultFg`（此时已是主题色）
   - Indexed 0 / 16 当背景 → `defaultBg`（主题纸色，不是 ansi[0]）
   - Indexed 254 / 近白 → `userBlockBg`（APP 灰底）
   - 真彩近黑 / 近白 → 现有亮度守卫，操作数仍是当前 Scheme

`t.bg` 先落地，`t.impl` 后动。主题格 **不许重写** `indexed` / `guardRgbBg` / `USER_MESSAGE_INDEX`。若发现要对着 `colorFor` 加参数，只加「当前 Scheme」，不要加第二条重映射。

`TermPalette.token(dark)` 今天是 `term-theme-dark source=app-theme`。改成带上游文件名，例如 `term-theme-dark source=Vesper.itermcolors`。`t.bg` 的 UI 探针靠 `content-desc` 以 `term-theme-` 开头 —— **前缀保留**。

`currentTerminalPalette()`（`AppTheme.kt`）今天按外壳深浅返回 `TerminalPaletteDark/Light`。改成返回「当前槽拼出来的 `TerminalPalette`」（16+fg/bg/cursor 来自主题，userBlock 来自 APP）。`SessionShellScreen` 卡片底用的就是这个函数，必须跟正文同色，否则 083 §3「卡片与终端同色」会再显影。

### 3.3 083 相对关系在换主题后

`TermThemeTest` / `TermBgRemapTest` 今天钉死 `F7F8FB` / `E6F5F2` / `0A1120`。默认改成 Vesper 后，这些绝对值会红——实现席要改成**相对断言**：

- 浅槽：`luma(userBlockBg) < luma(defaultBg)`（白底开更深块）
- 深槽：`luma(userBlockBg) > luma(defaultBg)`（黑底开更亮块）
- `colorFor(Indexed(0), bg)` == 当前 Scheme 的 `defaultBg`，且 ≠ `ansi16[0]`（`t.bg` 的 paper≠ansi0）

风险：某些浅色主题的纸色可能比 APP 的 `E6F5F2` 还深，相对关系会翻。本格**没有**把 22 个浅色文件的 Background luma 算完（§8）。实现席生成后必须跑一遍相对断言；失败的浅色主题 **halt，不要偷偷改 userBlock 去凑**。

---

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

- write_paths: app/app/src/main/java/dev/agentmirror/app/ui/theme/, app/app/src/test/kotlin/dev/agentmirror/app/, .team/nodes/theme-wire/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/theme-plan/方案.md, /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/085-终端主题库落位.md, /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/083-真机视觉收口六条.md
- 判据: A-wire-suite, A-wire-noregress, A-wire-default, A-wire-colorfor-untouched, A-wire-doc

## 2. 架构基（wiki 现算影响闭包）
- 写作用域包：dev.agentmirror.app, dev.agentmirror.app.ui.theme
- 正向依赖（你消费的契约，只读）：kt_dev_agentmirror_app_pairing, kt_dev_agentmirror_app_service, kt_dev_agentmirror_app_session, kt_dev_agentmirror_app_workspace
- **反向依赖（波及面 = 回归自查范围）**：kt_dev_agentmirror_app_pairing, kt_dev_agentmirror_app_service, kt_dev_agentmirror_app_session, kt_dev_agentmirror_app_workspace

### 闭包架构卡内联

### Kotlin · dev.agentmirror.app

- **职责**：Compose 应用根组合。
- **导出面**：AgentMirrorApp, MainActivity, MainNavState, Session
- **依赖边**：dev.agentmirror.app.pairing, dev.agentmirror.app.service, dev.agentmirror.app.session, dev.agentmirror.app.ui.theme, dev.agentmirror.app.workspace
- **doc 全文**：Compose 应用根组合。 依需求 004「客户端无状态」，本组件只做路由，不持有任何业务状态。 首启路由（pairing-ui 知识基底 §1）： - 无配对配置 → 配对页（扫码/手填，可跳过进空工作区）； - 有配对配置 → 直进工作区列表； - 配对页可从设置/重配入口重进（重新配对）。 会话页跳转沿用 session-ui 挂载的 [SessionRoute]。 导航态由 [navState]（D-3 修复）注入：MainActivity 在 onSaveInstanceState 持久化、重建恢复，深链/旋转都不丢导航位置（审计 D-2/D-3）。 工作区 VM（[workspaceViewModel]）由 MainActivity 持有（fix-workspace-wiring 修复， navState 同模式提升），本组件只负责在工作区分支用 [DisposableEffect] 把它接入 [ServiceWire.uiConnector]——配对成功后列表能渲染（此前 VM 裸建从未接线，uiConnector 全仓无调用点，见 fix-workspace-wiring 知识基底）。 @consumes dev.agentmirror.app.pairing @consumes dev.agentmirror.app.service @consumes dev.agentmirror.app.session @consumes dev.agentmirror.app.ui.theme @consumes dev.agentmirror.app.workspace

### Kotlin · dev.agentmirror.app.ui.theme

- **职责**：历史品牌深蓝（`0xFF1B2A4A`）。
- **导出面**：AgentMirrorTheme, DarkStateTones, LightStateTones, LocalStateTones, MonoFontFamily, Spacing, StateTone, StateTones
- **依赖边**：（无）
- **doc 全文**：历史品牌深蓝（`0xFF1B2A4A`）。 ui-redesign（018）后 M3 深浅两套 primary 均已改用独立取值（深 `0xFF9DBDFF`、 浅 `0xFF2F5DA8`），本 token 自彼时起无任何消费点——保留仅为存档（原 colors.xml 的 `brand_primary` 资源随 stage3 #19 删除，本 token 不再有资源对照）。

### Kotlin · dev.agentmirror.app.pairing

- **职责**：配对：扫码连接（路线 a：QR 载服务端地址 + 配对 token，可选 TS authkey，需求 011）。
- **导出面**：Failed, Pairing, PairingConfig, PairingConfigStore, PairingFailCause, PairingRoute, PairingScreen, PairingViewModel, QrParseException, QrPayload, QrPayloadParser, SharedPreferencesPairingConfigStore
- **依赖边**：dev.agentmirror.app.conn, dev.agentmirror.app.service, dev.agentmirror.app.tsnet, dev.agentmirror.app.ui.theme
- **doc 全文**：配对：扫码连接（路线 a：QR 载服务端地址 + 配对 token，可选 TS authkey，需求 011）。 负责相机扫码、地址解析与配对握手、配置持久化与常驻连接装配；替代 "终端 App + Tailscale App + SSH 配置"三件套（需求 001 单一 App 原则）。 配对成功与冷启动重连共用 [startPersistentConnection] 作为唯一装配入口。 @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.service @consumes dev.agentmirror.app.tsnet @consumes dev.agentmirror.app.ui.theme

### Kotlin · dev.agentmirror.app.service

- **职责**：前台服务：常驻连接 + 通知栏（需求 004 Android 前台服务路线）。
- **导出面**：AppClockPump, MirrorForegroundService, NetworkConnectivityWatcher, NoopTransportFactory, NotificationHelper, OkHttpTransportFactory, OkHttpWebSocketTransport, OnScreenFallbackPump, ServiceWire, StateWatcher, TsnetBootstrap
- **依赖边**：dev.agentmirror.app, dev.agentmirror.app.conn, dev.agentmirror.app.tsnet
- **doc 全文**：前台服务：常驻连接 + 通知栏（需求 004 Android 前台服务路线）。 分层（fg-service 知识基底 §1）： - [StateWatcher]：纯 JVM 核心逻辑（验收单测全打这里），消费 conn 层 listing/list_delta 流，检测会话状态沿变化（→blocked/→done）→ 通知；同状态重复抑制；unknown 不通知。 - [NotificationHelper]：通知渠道（常驻/状态两条）+ 常驻通知与状态通知 + 会话页深链 PendingIntent（action/extra 由 [MainActivity] 的 handleDeepLink 消费，非本包）。 - [MirrorForegroundService]：薄 Android 层，startForeground（dataSync）+ 生命周期绑定 [ConnectionManager]（经 [ServiceWire]）；断连静默重连归 conn 层，本服务只反映状态。 已接线（feat-fg-service-wiring）：配对成功/冷启动/进入会话经 [MirrorForegroundService.start] 启动（startForegroundService），连接与时钟泵由本服务承接（004/011 前台服务路线）。 - [ServiceWire]：接线点——传输工厂（默认 [OkHttpTransportFactory]）、UI 监听桥 （[uiConnector]）与服务监听槽（[serviceListener]）、连接配置注入；进程级持有唯一 [ConnectionManager]，服务与 UI 都经它访问同一单例。 电量策略（004 裁定）：服务被系统杀 → 冷启动重连即恢复（客户端无状态，没有丢失可言）。 服务**不持有连接状态**（004 无状态底线）：连接是 [ServiceWire] 进程级单例，配置唯一来源 是 SharedPreferences，服务只经 [ServiceWire.managerOrNull] 读取并驱动时钟泵 （[MirrorForegroundService.pumpOnce]，2s 一拍，在屏组合不再各自持有）。服务不可用时 在屏兜底泵 [OnScreenFallbackPump] 接管（fix-app-runtime-sa：服务被杀前台仍推进）， 服务恢复即让出（泵归属判据 [ServiceWire.servicePumpActive]，不双泵）。 @consumes dev.agentmirror.app @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.tsnet

### Kotlin · dev.agentmirror.app.session

- **职责**：会话页：单个 tmux 会话的交互界面。
- **导出面**：Attachment, Failed, Failure, HttpUrlConnectionUploader, SessionRoute, SessionScreen, SessionViewModel, Success
- **依赖边**：dev.agentmirror.app.conn, dev.agentmirror.app.service, dev.agentmirror.app.termview, dev.agentmirror.app.tsnet, dev.agentmirror.app.ui.theme, dev.agentmirror.terminal
- **doc 全文**：会话页：单个 tmux 会话的交互界面。 组合终端渲染（termview）与输入下发（conn），承载缩放、手势与快捷输入条； 图片附件走 multipart HTTP 上传（上传基地址由 service 装配的 ServiceWire 统一注入）， 跨层共享连接经 service 的 ServiceWire.uiConnector 扇出订阅。会话页已完整落位： 镜像流（snapshot/delta/scrollback 本地滚动补页）、发送必达回执、附件路径注入光标处。 @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.service @consumes dev.agentmirror.app.termview @consumes dev.agentmirror.app.tsnet @consumes dev.agentmirror.app.ui.theme @consumes dev.agentmirror.terminal

### Kotlin · dev.agentmirror.app.workspace

- **职责**：工作区：两级导航（舰队 → 会话），对应需求 001「舰队视角」、002「两级分组」。
- **导出面**：ConnectionUi, SessionUi, StateBadge, StateBadgeStyle, WorkspaceScreen, WorkspaceUi, WorkspaceUiState, WorkspaceViewModel
- **依赖边**：dev.agentmirror.app.conn, dev.agentmirror.app.tsnet, dev.agentmirror.app.ui.theme
- **doc 全文**：工作区：两级导航（舰队 → 会话），对应需求 001「舰队视角」、002「两级分组」。 - [WorkspaceViewModel]：纯 JVM 视图模型，消费 conn 层 listing/list_delta 帧流 → UI 状态；聚合字段（session_count / aggregate_state）为服务端权威值，只渲染不重算（012）。 - [WorkspaceScreen] / [StateBadge]：薄 Compose 渲染层；状态徽章五值（008）。 二级导航经 [WorkspaceScreen] 的 onOpenSession 回调把 (ref, name) 交给根路由 [AgentMirrorApp]，由其挂载 [SessionRoute] 进入会话页。 @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.tsnet @consumes dev.agentmirror.app.ui.theme

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

# t.wire · 终端主题库落位（契约 085 · theme-v1）

🔴 **本格的方案不是你现想的**：思路席 `t.plan` 已产出完整方案并**经 leader 客观核过**
（52 条主题清单逐条比对过上游 605 个文件，核到 52、对不上 0）。
**你的工作是照方案落地，⛔ 不是重新设计、⛔ 不是重新调研主题清单。**
方案里写「查不清」的地方（§8）才需要你自己判断，其余照做。

用户此刻的抱怨（这是本轮的起点）：「**这个绿色不太行**。」
当前深色板底 `0xFF0A1120`、cursor `0xFF4FD1C0` 青绿——就是刺眼的来源。

## 你这一格做什么（方案 §7 第 2 行）

把 `t.catalog` 生成的色表接进绘制层，让**默认 Vesper 真的画出来**。

**写范围（⛔ 越界即红）**：
- `TermPalette.kt`（只改 `of` / `schemeFrom` / `token` / `SOURCE`，🔴 **⛔ 不改 `colorFor` 的 083 重映射分支**）
- `AppTheme.kt` 的 `currentTerminalPalette()`
- `TermThemeStore.kt`（新文件，偏好读写）
- 对应单测（含把 `TermThemeTest` 的绝对断言改成相对断言）
- `.team/nodes/theme-wire/说明.md`

🔴🔴🔴 **范围已扩大（契约 085 §1.5，用户 2026-08-20 澄清）**：
主题不是"换默认色板"，是**重新着色层**——CLI 发什么颜色都不作数，
**256 色索引 16–255 和 24 位真彩也必须投影到主题色板**。
⇒ **`colorFor` 就是你要改的地方**（上一版任务书写着"不许改 colorFor"，**那条已作废**）。
⚠️ 但机制不动、数据源换成主题：`t.bg` 落地的 083 §2 三条映射（索引 0/16→纸色、254/近白→userBlock、
真彩背景亮度守卫）**保留**，只是目标值改成选中主题的色板。⛔ 不得推翻 083 §2。
🔴 **投影规则不许你自己拍脑袋**——`t.remap` 格已产出 `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/theme-remap/投影规则.md`，
照它做。它写"查不清"的地方才轮到你判断。

🔴 **⛔ 本格不许碰设置页**（那是 `t.settings`），也⛔不许改 `TermSchemes.kt`（那是 `t.catalog` 的生成物）。
🔴 **开工第一件事：重读仓根 `TermPalette.kt`**（方案 §8 风险 6）——
`vz-v1` 的 `t.bg` 格刚改过 `colorFor`，按**当时的真实签名**接 `of()`，⛔ 不要按方案里的旧快照改分支。
设置页还没入口时，用直接写 prefs 的方式验证。

## 3. 与 083 §2 / `t.bg` 怎么合流（不得倒退）

### 3.1 分层：谁管什么

| 色 / 度量 | 现在落在 | 主题库之后 |
|---|---|---|
| APP 外壳（列表底、卡片描边、导航、设置页） | `DesignTokens.kt` 的 `LightPalette` / `DarkPalette` | **仍归外壳**。外观三段开关不动 |
| 终端默认 bg / fg | `TerminalPaletteLight/Dark.background/foreground` | **被选中主题接管** |
| ANSI 0–15 | `TerminalPalette*.ansi` | **被选中主题接管** |
| cursor | `TerminalPalette*.cursor` | **被选中主题接管**（上游 `Cursor Color`） |
| selection | `TerminalPalette*.selection` | 上游有 `Selection Color` 就用；没有则留 APP 值 |
| **userBlockBackground / userBlockForeground** | 浅 `E6F5F2` / `0E3B35`，深 `10241F` / `DCF3EF` | **仍归 APP**。083 §2 的「浅色白底 + 灰底用户消息块」是这条，不是 16 色板 |
| 终端 padding / 行高 / 字号档 | `TerminalMetrics` | **不动**（083 §3 的间距是 `t.chrome` 的事） |
| 显式背景重映射 | `TermPalette.colorFor`（`t.bg` 正在改） | **规则不动，操作数换成当前主题的 Scheme** |

`TerminalPaletteLight` / `TerminalPaletteDark` 两份字面量**保留当缺省回退**（目录损坏、未知族 id），不要删。设置页不再把它们当成用户可选的「原厂绿」。

### 3.2 `TermPalette` 合流点（谁先谁后）

`t.bg`（账本 `vz-v1`）已经把绘制层收成：

```
TermSurfaceView.colorFor(...) → TermPalette.colorFor(color, background, dark)
TermPalette.of(dark) → Light 或 Dark 静态 Scheme
```

主题库**只改 Scheme 从哪来**，不改 `colorFor` 的分支表。

顺序（实现席按这个接，禁止把重映射搬进主题文件）：

1. **外观**（已有）`Appearance` → `dark: Boolean`（`AppTheme` 现逻辑）。
2. **槽** → `dark` 则读 `terminal-theme-dark` 族 id，否则读 `terminal-theme-light`。
3. **族 → 文件** → `family.darkSource` 或 `family.lightSource`。
4. **文件 → 16+fg/bg/cursor** → `TermSchemeCatalog.colors(sourceFile)`。
5. **拼 Scheme**：
   - `defaultBg/defaultFg/ansi16` = 上游
   - `userBlockBg` = **仍然** `TerminalPaletteLight/Dark.userBlockBackground`（按 `dark` 选，不按主题）
   - `source` = 上游文件名（替换今天的恒定 `"app-theme"`）
6. **`colorFor`（t.bg 的规则，后执行）**：
   - Default → `defaultBg` / `defaultFg`（此时已是主题色）
   - Indexed 0 / 16 当背景 → `defaultBg`（主题纸色，不是 ansi[0]）
   - Indexed 254 / 近白 → `userBlockBg`（APP 灰底）
   - 真彩近黑 / 近白 → 现有亮度守卫，操作数仍是当前 Scheme

`t.bg` 先落地，`t.impl` 后动。主题格 **不许重写** `indexed` / `guardRgbBg` / `USER_MESSAGE_INDEX`。若发现要对着 `colorFor` 加参数，只加「当前 Scheme」，不要加第二条重映射。

`TermPalette.token(dark)` 今天是 `term-theme-dark source=app-theme`。改成带上游文件名，例如 `term-theme-dark source=Vesper.itermcolors`。`t.bg` 的 UI 探针靠 `content-desc` 以 `term-theme-` 开头 —— **前缀保留**。

`currentTerminalPalette()`（`AppTheme.kt`）今天按外壳深浅返回 `TerminalPaletteDark/Light`。改成返回「当前槽拼出来的 `TerminalPalette`」（16+fg/bg/cursor 来自主题，userBlock 来自 APP）。`SessionShellScreen` 卡片底用的就是这个函数，必须跟正文同色，否则 083 §3「卡片与终端同色」会再显影。

### 3.3 083 相对关系在换主题后

`TermThemeTest` / `TermBgRemapTest` 今天钉死 `F7F8FB` / `E6F5F2` / `0A1120`。默认改成 Vesper 后，这些绝对值会红——实现席要改成**相对断言**：

- 浅槽：`luma(userBlockBg) < luma(defaultBg)`（白底开更深块）
- 深槽：`luma(userBlockBg) > luma(defaultBg)`（黑底开更亮块）
- `colorFor(Indexed(0), bg)` == 当前 Scheme 的 `defaultBg`，且 ≠ `ansi16[0]`（`t.bg` 的 paper≠ansi0）

风险：某些浅色主题的纸色可能比 APP 的 `E6F5F2` 还深，相对关系会翻。本格**没有**把 22 个浅色文件的 Background luma 算完（§8）。实现席生成后必须跑一遍相对断言；失败的浅色主题 **halt，不要偷偷改 userBlock 去凑**。

---

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

- write_paths: app/app/src/main/java/dev/agentmirror/app/ui/theme/, app/app/src/test/kotlin/dev/agentmirror/app/, .team/nodes/theme-wire/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/theme-plan/方案.md, /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/085-终端主题库落位.md, /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/083-真机视觉收口六条.md, .team/nodes/wire/BASE.md
- 判据: A-wire-suite, A-wire-noregress, A-wire-default, A-wire-doc

## 2. 架构基（wiki 现算影响闭包）
- 写作用域包：dev.agentmirror.app, dev.agentmirror.app.ui.theme
- 正向依赖（你消费的契约，只读）：kt_dev_agentmirror_app_pairing, kt_dev_agentmirror_app_service, kt_dev_agentmirror_app_session, kt_dev_agentmirror_app_workspace
- **反向依赖（波及面 = 回归自查范围）**：kt_dev_agentmirror_app_pairing, kt_dev_agentmirror_app_service, kt_dev_agentmirror_app_session, kt_dev_agentmirror_app_workspace

### 闭包架构卡内联

### Kotlin · dev.agentmirror.app

- **职责**：Compose 应用根组合。
- **导出面**：AgentMirrorApp, MainActivity, MainNavState, Session
- **依赖边**：dev.agentmirror.app.pairing, dev.agentmirror.app.service, dev.agentmirror.app.session, dev.agentmirror.app.ui.theme, dev.agentmirror.app.workspace
- **doc 全文**：Compose 应用根组合。 依需求 004「客户端无状态」，本组件只做路由，不持有任何业务状态。 首启路由（pairing-ui 知识基底 §1）： - 无配对配置 → 配对页（扫码/手填，可跳过进空工作区）； - 有配对配置 → 直进工作区列表； - 配对页可从设置/重配入口重进（重新配对）。 会话页跳转沿用 session-ui 挂载的 [SessionRoute]。 导航态由 [navState]（D-3 修复）注入：MainActivity 在 onSaveInstanceState 持久化、重建恢复，深链/旋转都不丢导航位置（审计 D-2/D-3）。 工作区 VM（[workspaceViewModel]）由 MainActivity 持有（fix-workspace-wiring 修复， navState 同模式提升），本组件只负责在工作区分支用 [DisposableEffect] 把它接入 [ServiceWire.uiConnector]——配对成功后列表能渲染（此前 VM 裸建从未接线，uiConnector 全仓无调用点，见 fix-workspace-wiring 知识基底）。 @consumes dev.agentmirror.app.pairing @consumes dev.agentmirror.app.service @consumes dev.agentmirror.app.session @consumes dev.agentmirror.app.ui.theme @consumes dev.agentmirror.app.workspace

### Kotlin · dev.agentmirror.app.ui.theme

- **职责**：历史品牌深蓝（`0xFF1B2A4A`）。
- **导出面**：AgentMirrorTheme, DarkStateTones, LightStateTones, LocalStateTones, MonoFontFamily, Spacing, StateTone, StateTones
- **依赖边**：（无）
- **doc 全文**：历史品牌深蓝（`0xFF1B2A4A`）。 ui-redesign（018）后 M3 深浅两套 primary 均已改用独立取值（深 `0xFF9DBDFF`、 浅 `0xFF2F5DA8`），本 token 自彼时起无任何消费点——保留仅为存档（原 colors.xml 的 `brand_primary` 资源随 stage3 #19 删除，本 token 不再有资源对照）。

### Kotlin · dev.agentmirror.app.pairing

- **职责**：配对：扫码连接（路线 a：QR 载服务端地址 + 配对 token，可选 TS authkey，需求 011）。
- **导出面**：Failed, Pairing, PairingConfig, PairingConfigStore, PairingFailCause, PairingRoute, PairingScreen, PairingViewModel, QrParseException, QrPayload, QrPayloadParser, SharedPreferencesPairingConfigStore
- **依赖边**：dev.agentmirror.app.conn, dev.agentmirror.app.service, dev.agentmirror.app.tsnet, dev.agentmirror.app.ui.theme
- **doc 全文**：配对：扫码连接（路线 a：QR 载服务端地址 + 配对 token，可选 TS authkey，需求 011）。 负责相机扫码、地址解析与配对握手、配置持久化与常驻连接装配；替代 "终端 App + Tailscale App + SSH 配置"三件套（需求 001 单一 App 原则）。 配对成功与冷启动重连共用 [startPersistentConnection] 作为唯一装配入口。 @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.service @consumes dev.agentmirror.app.tsnet @consumes dev.agentmirror.app.ui.theme

### Kotlin · dev.agentmirror.app.service

- **职责**：前台服务：常驻连接 + 通知栏（需求 004 Android 前台服务路线）。
- **导出面**：AppClockPump, MirrorForegroundService, NetworkConnectivityWatcher, NoopTransportFactory, NotificationHelper, OkHttpTransportFactory, OkHttpWebSocketTransport, OnScreenFallbackPump, ServiceWire, StateWatcher, TsnetBootstrap
- **依赖边**：dev.agentmirror.app, dev.agentmirror.app.conn, dev.agentmirror.app.tsnet
- **doc 全文**：前台服务：常驻连接 + 通知栏（需求 004 Android 前台服务路线）。 分层（fg-service 知识基底 §1）： - [StateWatcher]：纯 JVM 核心逻辑（验收单测全打这里），消费 conn 层 listing/list_delta 流，检测会话状态沿变化（→blocked/→done）→ 通知；同状态重复抑制；unknown 不通知。 - [NotificationHelper]：通知渠道（常驻/状态两条）+ 常驻通知与状态通知 + 会话页深链 PendingIntent（action/extra 由 [MainActivity] 的 handleDeepLink 消费，非本包）。 - [MirrorForegroundService]：薄 Android 层，startForeground（dataSync）+ 生命周期绑定 [ConnectionManager]（经 [ServiceWire]）；断连静默重连归 conn 层，本服务只反映状态。 已接线（feat-fg-service-wiring）：配对成功/冷启动/进入会话经 [MirrorForegroundService.start] 启动（startForegroundService），连接与时钟泵由本服务承接（004/011 前台服务路线）。 - [ServiceWire]：接线点——传输工厂（默认 [OkHttpTransportFactory]）、UI 监听桥 （[uiConnector]）与服务监听槽（[serviceListener]）、连接配置注入；进程级持有唯一 [ConnectionManager]，服务与 UI 都经它访问同一单例。 电量策略（004 裁定）：服务被系统杀 → 冷启动重连即恢复（客户端无状态，没有丢失可言）。 服务**不持有连接状态**（004 无状态底线）：连接是 [ServiceWire] 进程级单例，配置唯一来源 是 SharedPreferences，服务只经 [ServiceWire.managerOrNull] 读取并驱动时钟泵 （[MirrorForegroundService.pumpOnce]，2s 一拍，在屏组合不再各自持有）。服务不可用时 在屏兜底泵 [OnScreenFallbackPump] 接管（fix-app-runtime-sa：服务被杀前台仍推进）， 服务恢复即让出（泵归属判据 [ServiceWire.servicePumpActive]，不双泵）。 @consumes dev.agentmirror.app @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.tsnet

### Kotlin · dev.agentmirror.app.session

- **职责**：会话页：单个 tmux 会话的交互界面。
- **导出面**：Attachment, Failed, Failure, HttpUrlConnectionUploader, SessionRoute, SessionScreen, SessionViewModel, Success
- **依赖边**：dev.agentmirror.app.conn, dev.agentmirror.app.service, dev.agentmirror.app.termview, dev.agentmirror.app.tsnet, dev.agentmirror.app.ui.theme, dev.agentmirror.terminal
- **doc 全文**：会话页：单个 tmux 会话的交互界面。 组合终端渲染（termview）与输入下发（conn），承载缩放、手势与快捷输入条； 图片附件走 multipart HTTP 上传（上传基地址由 service 装配的 ServiceWire 统一注入）， 跨层共享连接经 service 的 ServiceWire.uiConnector 扇出订阅。会话页已完整落位： 镜像流（snapshot/delta/scrollback 本地滚动补页）、发送必达回执、附件路径注入光标处。 @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.service @consumes dev.agentmirror.app.termview @consumes dev.agentmirror.app.tsnet @consumes dev.agentmirror.app.ui.theme @consumes dev.agentmirror.terminal

### Kotlin · dev.agentmirror.app.workspace

- **职责**：工作区：两级导航（舰队 → 会话），对应需求 001「舰队视角」、002「两级分组」。
- **导出面**：ConnectionUi, SessionUi, StateBadge, StateBadgeStyle, WorkspaceScreen, WorkspaceUi, WorkspaceUiState, WorkspaceViewModel
- **依赖边**：dev.agentmirror.app.conn, dev.agentmirror.app.tsnet, dev.agentmirror.app.ui.theme
- **doc 全文**：工作区：两级导航（舰队 → 会话），对应需求 001「舰队视角」、002「两级分组」。 - [WorkspaceViewModel]：纯 JVM 视图模型，消费 conn 层 listing/list_delta 帧流 → UI 状态；聚合字段（session_count / aggregate_state）为服务端权威值，只渲染不重算（012）。 - [WorkspaceScreen] / [StateBadge]：薄 Compose 渲染层；状态徽章五值（008）。 二级导航经 [WorkspaceScreen] 的 onOpenSession 回调把 (ref, name) 交给根路由 [AgentMirrorApp]，由其挂载 [SessionRoute] 进入会话页。 @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.tsnet @consumes dev.agentmirror.app.ui.theme

## 3. 需求基
- 标题引用条目：requirement-base/entries/083*, requirement-base/entries/085*
- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）

## 4. 纪律（本工程通用，违反即返工）
- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。
- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。
- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。
