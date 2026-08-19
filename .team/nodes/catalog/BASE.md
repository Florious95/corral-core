# 知识基底 · ledger.theme.v1 / t.catalog（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.catalog · 终端主题库落位（契约 085 · theme-v1）

🔴 **本格的方案不是你现想的**：思路席 `t.plan` 已产出完整方案并**经 leader 客观核过**
（52 条主题清单逐条比对过上游 605 个文件，核到 52、对不上 0）。
**你的工作是照方案落地，⛔ 不是重新设计、⛔ 不是重新调研主题清单。**
方案里写「查不清」的地方（§8）才需要你自己判断，其余照做。

用户此刻的抱怨（这是本轮的起点）：「**这个绿色不太行**。」
当前深色板底 `0xFF0A1120`、cursor `0xFF4FD1C0` 青绿——就是刺眼的来源。

## 你这一格做什么（方案 §7 第 1 行）

把 52 份上游配色变成随包的 Kotlin 常量 + NOTICE + 单测。

**写范围（⛔ 越界即红）**：
- `tools/import_iterm_schemes.py`（生成脚本，你写）
- `app/app/src/main/java/dev/agentmirror/app/ui/theme/TermSchemes.kt`（**生成物**）
- `app/app/src/main/assets/NOTICE-iterm2-color-schemes.txt`
- `app/app/src/test/kotlin/dev/agentmirror/app/ui/theme/TermSchemeCatalogTest.kt`
- `.team/nodes/theme-catalog/说明.md`

🔴 **本格⛔不许改 `TermPalette.kt`、⛔不许改设置页**——那是后面两格的写范围，改了就是写冲突。

## 1. 主题清单核对

### 1.1 怎么读这张表

- **展示名** = 设置页给用户看的族名（想法来自 Heeler 的 title，不是抄它的标识符）。
- **上游文件名** = `schemes/` 下的准确文件名。实现席必须用这一列去拉文件，禁止按感觉改空格/大小写。
- **深/浅** = 该文件用在哪个槽。`深浅同文件` = 该族没有浅色半，两个槽都用这一份（想法来源：Heeler 的 “dark terminal even in Light Mode”）。
- **核到没有** = 本格用 `gh api` tree 对过 606 个文件后的结论。

族 → 文件的配对（哪份当浅、哪份当深）是**想法参考**（Heeler 的 `catalogName(isDark:)` 映射）。色值仍只从上游文件取。

### 1.2 进清单的族（核到的才进）

| 展示名 | 上游文件名 | 深/浅 | 核到没有 |
|---|---|---|---|
| 默认（Alabaster / Afterglow） | `Alabaster.itermcolors` | 浅 | 核到 |
| 默认（Alabaster / Afterglow） | `Afterglow.itermcolors` | 深 | 核到 |
| Vesper | `Vesper.itermcolors` | 深浅同文件 | 核到 |
| Apple System Colors | `Apple System Colors Light.itermcolors` | 浅 | 核到 |
| Apple System Colors | `Apple System Colors.itermcolors` | 深 | 核到 |
| Dracula | `Dracula.itermcolors` | 深浅同文件 | 核到 |
| Solarized | `iTerm2 Solarized Light.itermcolors` | 浅 | 核到 |
| Solarized | `iTerm2 Solarized Dark.itermcolors` | 深 | 核到 |
| Catppuccin | `Catppuccin Latte.itermcolors` | 浅 | 核到 |
| Catppuccin | `Catppuccin Mocha.itermcolors` | 深 | 核到 |
| Tokyo Night | `TokyoNight Day.itermcolors` | 浅 | 核到 |
| Tokyo Night | `TokyoNight Night.itermcolors` | 深 | 核到 |
| Gruvbox | `Gruvbox Light.itermcolors` | 浅 | 核到 |
| Gruvbox | `Gruvbox Dark.itermcolors` | 深 | 核到 |
| Nord | `Nord Light.itermcolors` | 浅 | 核到 |
| Nord | `Nord.itermcolors` | 深 | 核到 |
| Monokai Pro | `Monokai Pro Light.itermcolors` | 浅 | 核到 |
| Monokai Pro | `Monokai Pro.itermcolors` | 深 | 核到 |
| Rosé Pine | `Rose Pine Dawn.itermcolors` | 浅 | 核到 |
| Rosé Pine | `Rose Pine.itermcolors` | 深 | 核到 |
| Ayu | `Ayu Light.itermcolors` | 浅 | 核到 |
| Ayu | `Ayu.itermcolors` | 深 | 核到 |
| One Half | `One Half Light.itermcolors` | 浅 | 核到 |
| One Half | `One Half Dark.itermcolors` | 深 | 核到 |
| Kanagawa | `Kanagawa Lotus.itermcolors` | 浅 | 核到 |
| Kanagawa | `Kanagawa Wave.itermcolors` | 深 | 核到 |
| Everforest | `Everforest Light Med.itermcolors` | 浅 | 核到 |
| Everforest | `Everforest Dark Hard.itermcolors` | 深 | 核到 |
| GitHub | `GitHub Light Default.itermcolors` | 浅 | 核到 |
| GitHub | `GitHub Dark Default.itermcolors` | 深 | 核到 |
| Night Owl | `Night Owlish Light.itermcolors` | 浅 | 核到 |
| Night Owl | `Night Owl.itermcolors` | 深 | 核到 |
| Iceberg | `Iceberg Light.itermcolors` | 浅 | 核到 |
| Iceberg | `Iceberg Dark.itermcolors` | 深 | 核到 |
| Flexoki | `Flexoki Light.itermcolors` | 浅 | 核到 |
| Flexoki | `Flexoki Dark.itermcolors` | 深 | 核到 |
| Selenized | `Selenized Light.itermcolors` | 浅 | 核到 |
| Selenized | `Selenized Dark.itermcolors` | 深 | 核到 |
| Modus | `Modus Operandi.itermcolors` | 浅 | 核到 |
| Modus | `Modus Vivendi.itermcolors` | 深 | 核到 |
| Tomorrow | `Tomorrow.itermcolors` | 浅 | 核到 |
| Tomorrow | `Tomorrow Night.itermcolors` | 深 | 核到 |
| Melange | `Melange Light.itermcolors` | 浅 | 核到 |
| Melange | `Melange Dark.itermcolors` | 深 | 核到 |
| Zenbones | `Zenbones Light.itermcolors` | 浅 | 核到 |
| Zenbones | `Zenbones Dark.itermcolors` | 深 | 核到 |
| One Dark | `Atom One Dark.itermcolors` | 深浅同文件 | 核到 |
| Snazzy | `Snazzy.itermcolors` | 深浅同文件 | 核到 |
| Oceanic Next | `Oceanic Next.itermcolors` | 深浅同文件 | 核到 |
| Poimandres | `Poimandres.itermcolors` | 深浅同文件 | 核到 |
| Horizon | `Horizon.itermcolors` | 深浅同文件 | 核到 |
| Zenburn | `Zenburn.itermcolors` | 深浅同文件 | 核到 |

族 id（持久化用，自己的 kebab-case，**不要**用 Heeler 的 rawValue 当抄来的标识；语义对齐即可）：

| 族 id | 展示名 |
|---|---|
| `follow-system` | 默认（Alabaster / Afterglow） |
| `vesper` | Vesper |
| `apple-system-colors` | Apple System Colors |
| `dracula` | Dracula |
| `solarized` | Solarized |
| `catppuccin` | Catppuccin |
| `tokyo-night` | Tokyo Night |
| `gruvbox` | Gruvbox |
| `nord` | Nord |
| `monokai-pro` | Monokai Pro |
| `rose-pine` | Rosé Pine |
| `ayu` | Ayu |
| `one-half` | One Half |
| `kanagawa` | Kanagawa |
| `everforest` | Everforest |
| `github` | GitHub |
| `night-owl` | Night Owl |
| `iceberg` | Iceberg |
| `flexoki` | Flexoki |
| `selenized` | Selenized |
| `modus` | Modus |
| `tomorrow` | Tomorrow |
| `melange` | Melange |
| `zenbones` | Zenbones |
| `atom-one-dark` | One Dark |
| `snazzy` | Snazzy |
| `oceanic-next` | Oceanic Next |
| `poimandres` | Poimandres |
| `horizon` | Horizon |
| `zenburn` | Zenburn |

共 **30 族、51 个唯一上游文件**（Vesper 等 9 个深浅同文件只拉一份）。

### 1.3 核不到（禁止拿相近文件顶上）

| 展示名 / 契约字面 | 上游文件名 | 深/浅 | 核到没有 |
|---|---|---|---|
| One Dark（独立文件） | `One Dark.itermcolors` | — | **核不到** |
| Solarized（非 iTerm2 前缀） | `Solarized Dark.itermcolors` | 深 | **核不到** |
| Solarized（非 iTerm2 前缀） | `Solarized Light.itermcolors` | 浅 | **核不到** |

说明（不是顶替）：
- 契约写了 `Atom One Dark` 和 `One Dark`。上游**没有** `One Dark.itermcolors`。Heeler 的「One Dark」选项映射的就是 `Atom One Dark`。本方案把设置页展示名做成「One Dark」，`source` 字段必须是 `Atom One Dark.itermcolors`。
- ⛔ 禁止用 `One Dark Two.itermcolors`（tree 里**有**这个文件）去填「One Dark」。那是另一份配色。
- 契约写 `Solarized(iTerm2)`。上游**没有** `Solarized Dark.itermcolors` / `Solarized Light.itermcolors`，有的是 `iTerm2 Solarized Dark.itermcolors` / `iTerm2 Solarized Light.itermcolors`。进清单的是带 `iTerm2` 前缀的这两份，不是 `Solarized Darcula` / `Solarized Dark Higher Contrast` / `Solarized Dark Patched` / `Solarized Osaka Night`。

### 1.4 族内其它变体：核到了，但**不准进清单**

这些文件在 tree 里存在。把它们录进去 = 用户选了契约里的族名却拿到变体。全部排除：

| 不准进的上游文件名 | 为什么排除 |
|---|---|
| `Ayu Mirage.itermcolors` | 族只要 Ayu / Ayu Light |
| `Catppuccin Frappe.itermcolors` | 只要 Latte / Mocha |
| `Catppuccin Macchiato.itermcolors` | 只要 Latte / Mocha |
| `Dracula+.itermcolors` | 只要 `Dracula.itermcolors` |
| `Everforest Dark Soft/Med.itermcolors`、`Everforest Light Hard/Soft.itermcolors` | 浅=Light Med，深=Dark Hard |
| `GitHub.itermcolors`、`GitHub Dark.itermcolors`、Colorblind / Dimmed / High Contrast 各档 | 只要 Light Default / Dark Default |
| `Gruvbox Dark Hard.itermcolors`、`Gruvbox Light Hard.itermcolors`、`Gruvbox Material*.itermcolors` | 只要 Gruvbox Dark / Light |
| `Horizon Bright.itermcolors` | 该族深浅同用 `Horizon.itermcolors`，不准拿 Bright 当浅色半 |
| `Kanagawa Dragon.itermcolors`、`Kanagawabones.itermcolors` | 只要 Lotus / Wave |
| `Modus * Deuteranopia/Tinted/Tritanopia.itermcolors` | 只要 Operandi / Vivendi |
| `Monokai Pro Light Sun / Machine / Octagon / Ristretto / Spectrum.itermcolors` | 只要 Pro / Pro Light |
| `Nord Wave.itermcolors`、`Nordfox.itermcolors`、`Onenord*.itermcolors` | 只要 Nord / Nord Light |
| `Oceanic Material.itermcolors` | 只要 Oceanic Next |
| `Poimandres Darker/Storm/White.itermcolors` | 只要 `Poimandres.itermcolors` |
| `Rose Pine Moon.itermcolors` | 浅=Dawn，深=Rose Pine，不要 Moon |
| `Selenized Black.itermcolors` | 只要 Dark / Light |
| `Snazzy Soft.itermcolors` | 只要 `Snazzy.itermcolors` |
| `TokyoNight.itermcolors`、`TokyoNight Moon.itermcolors`、`TokyoNight Storm.itermcolors` | 浅=Day，深=Night |
| `Tomorrow Night Blue/Bright/Burns/Eighties.itermcolors` | 只要 Tomorrow / Tomorrow Night |
| `Zenbones.itermcolors`（无 Dark/Light 后缀） | 清单用 Zenbones Dark / Light，不用这份 |
| `Zenburned.itermcolors` | 只要 `Zenburn.itermcolors` |

---

## 2. 数据怎么进包

### 2.1 选定：生成期转 Kotlin 常量（一次性脚本，不进 Gradle）

| 方案 | 包体积 | 启动 | 单测能否钉具体色值 | 结论 |
|---|---|---|---|---|
| A. 运行期解析 `.itermcolors` / JSON | 51 份 XML ≈ 几百 KB + 解析器 | 冷启动或首次切主题要 parse | 能，但测的是解析器，色值随资源文件漂 | 否 |
| B. Gradle 每次构建去拉上游 | 构建依赖网络；`app/` 构建正被别的账本占用 | — | 能 | 否（且违反本账本「先 plan 再等 vz-v1」） |
| **C. 一次性脚本生成 `TermSchemes.kt`** | 51×(16+3) 个 `0xFFRRGGBB`，大约 15–25 KB 源码 | 零解析 | **能直接 `assertEquals(0xFF101010, Vesper.background)`** | **选这个** |

判据要求「断言常量值，不是非空」：Kotlin `const val` / `intArrayOf(...)` 是唯一不经过运行期解析就能钉死 hex 的路。

### 2.2 生成脚本（实现席要写，本格只设计）

路径：`tools/import_iterm_schemes.py`

```
用法（cwd = 仓根）：
  python3 tools/import_iterm_schemes.py --sha 4cbae6273354e5e91a7641d72c69daa3de6a867f
  python3 tools/import_iterm_schemes.py --check   # 不写文件，重拉后与仓内 TermSchemes.kt 比 hex，不一致 exit 1
```

行为：
1. 读本方案 §1.2 的「上游文件名」清单（脚本内写死同一张表，禁止扫整个 606）。
2. `gh api` 取 `repos/mbadolato/iTerm2-Color-Schemes/contents/schemes/<file>?ref=<SHA>`（只读，不 clone）。文件名含空格，必须 URL encode。
3. 解析 plist XML。每个主题必须读到这些 key，缺一则**该主题不得进生成物**（整次脚本 exit 1，不要默默跳过）：
   - `Ansi 0 Color` … `Ansi 15 Color`
   - `Background Color`
   - `Foreground Color`
   - `Cursor Color`
4. 分量是 0–1 的 `real`。ARGB = `0xFF000000 | (round(r*255)<<16) | (round(g*255)<<8) | round(b*255)`。
5. `Color Space`：Nord 抽样是 `sRGB`。若出现非 sRGB（Display P3 等）——**该文件生成失败，exit 1**，不要 silently 当 sRGB。本格没有把 51 份的 Color Space 逐个打开，见 §8。
6. 写出：
   - `app/app/src/main/java/dev/agentmirror/app/ui/theme/TermSchemes.kt`（生成文件头写 SHA + 生成命令）
   - `app/app/src/main/assets/NOTICE-iterm2-color-schemes.txt`（§2.4）
7. **不纳入 `app/build.gradle.kts`**。换主题清单或升 SHA 时人手跑一遍，提交生成物。`--check` 留给实现席的机械判据。

可选字段（有则写入，无则用 APP 现有值，见 §3）：
- `Selection Color` → `TerminalPalette.selection`
- `Cursor Text Color` 可忽略

### 2.3 生成物形状（实现席照这个写，名字可微调但字段不许少）

```kotlin
// 生成物。sourceFile 必须等于上游文件名（含 .itermcolors）。
data class TermSchemeColors(
    val sourceFile: String,
    val background: Int,
    val foreground: Int,
    val cursor: Int,
    val ansi: IntArray, // size == 16
)

data class TermThemeFamilyDef(
    val id: String,          // 持久化键，§1.2 族 id
    val title: String,       // 展示名
    val lightSource: String, // 浅槽文件名
    val darkSource: String,  // 深槽文件名
)

object TermSchemeCatalog {
    const val UPSTREAM_SHA = "4cbae6273354e5e91a7641d72c69daa3de6a867f"
    val families: List<TermThemeFamilyDef>
    val colorsBySourceFile: Map<String, TermSchemeColors>
    fun colors(sourceFile: String): TermSchemeColors
}
```

抽样（本格已用 `gh api` 解开 plist，实现席的单测必须钉这些值；生成后若对不上 = 解析器 round 错了）：

| sourceFile | Background（本格读到） |
|---|---|
| `Vesper.itermcolors` | `#101010` → `0xFF101010` |
| `Afterglow.itermcolors` | `#212121` → `0xFF212121` |

Alabaster / TokyoNight Night 等本格拉 plist 时遇到 GitHub TLS timeout，**没有**把其余 49 份的 hex 写进本方案。实现席以生成器读到的值为准，并用 `--check` + 单测钉 `Vesper`/`Afterglow` 这两份已核值当解析器金样。

### 2.4 NOTICE 正文要求

路径：`app/app/src/main/assets/NOTICE-iterm2-color-schemes.txt`（APK 内可读，判据 `test -s` + 串匹配即可）。

必须含这些子串（契约 §2 第 3 条）：
- `iTerm2-Color-Schemes`
- `Mark Badolato`
- `MIT`
- `Copyright (c) 2011-present Mark Badolato`
- `Copyright (c) 2011 to Present Mark Badolato`

并写明：色值来自 https://github.com/mbadolato/iTerm2-Color-Schemes ，commit `4cbae6273354e5e91a7641d72c69daa3de6a867f`；本 APP 是 Apache-2.0；**没有**使用 Heeler / Ghostty 的源码或资源。上游 LICENSE 还写了「单个主题的版权归该主题作者」——NOTICE 里抄这一句，不要假装整库色值都是 Badolato 个人作品。

把完整 MIT 许可正文附在 NOTICE 后半。缺 NOTICE = 违反 MIT。

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

- write_paths: tools/, app/app/src/main/java/dev/agentmirror/app/ui/theme/TermSchemes.kt, app/app/src/main/assets/, app/app/src/test/kotlin/dev/agentmirror/app/ui/theme/, .team/nodes/theme-catalog/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/theme-plan/方案.md, /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/085-终端主题库落位.md, /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/083-真机视觉收口六条.md
- 判据: A-cat-test, A-cat-notice, A-cat-count, A-cat-noimposter, A-cat-suite, A-cat-doc

## 2. 架构基（wiki 现算影响闭包）
- 写作用域包：dev.agentmirror.app, dev.agentmirror.app.ui.theme.TermSchemes.kt
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
