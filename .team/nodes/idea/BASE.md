# 知识基底 · ledger.ux4.v1 / t.idea（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.idea · 借鉴 Heeler 的**做法**给出我们的性能思路（🔴 禁止参考代码）

🔴 **用户原话（2026-08-20）**：
> 「那就**借鉴 heeler 这个 app**，还是要**独立席位给出思路**，**禁止参考代码**」

🔴🔴🔴 **本格只出思路文档，⛔ 一行产品代码都不许改。** 写范围只有 `.team/nodes/ux4-idea/`。

---
## ⛔⛔ 第一硬约束：禁止参考代码（两个理由，任一独立成立）

1. **用户明令**：「禁止参考代码」。
2. **许可**：`github.com/ZingerLittleBee/Heeler` 是 **AGPL-3.0**，我们是 Apache-2.0。
   契约 085 §0 已裁定：⛔ 一行都不许抄。**看了再"凭印象写"同样不行**——那是把表达洗一遍。

### 允许的信息来源（**每条结论都必须标出来源，⛔ 无来源不许进文档**）
- ✅ 仓库 **README / 文档站 / 官网**（`heeler.bybee.dev`）里的**行为与架构描述**
- ✅ **公开的 notices 文件名与依赖清单**（例如它用 libghostty —— 这是事实不是表达）
- ✅ App Store / TestFlight 的**功能描述与更新说明**
- ✅ issues / discussions / release notes 里**开发者自己写的行为说明**
- ✅ 它依赖的**上游项目的公开文档**（Ghostty / libghostty 自己的文档，MIT，随便读）

### ⛔ 禁止的
- ⛔ `Sources/**` 下任何 `.swift`
- ⛔ `gh api .../contents/...` 去取任何源码文件内容
- ⛔ clone 该仓库
- ⛔ 反编译 / 解包 IPA
- ⛔ 「我看了它怎么写的，所以我们这样写」——**一句都不许出现**

🔴 **在文档开头写一行自证**：「本格未读取 Heeler 任何源码文件；信息来源见每条结论的链接。」

---
## 要解决的问题（用户实测的三症状，perf-v1 已量过基线）

| # | 症状 | perf-v1 的读数（**基线包与当前包一样**） |
|---|---|---|
| S1 | 进入会话有明显重排 | 两包完全相同：`viewport=4 resized_true=1 reflow=2 grid=1` |
| S2 | 滑动掉帧 | janky% 基线 92.69 / 当前 92.89（d480）；p95 34→32ms |
| S3 | 进入会话短暂白屏 | 现有采样粒度测不到（`screencap` 单次约 1.2s，亚秒白闪抓不住） |

🔴 **已排除**：不是 085 主题投影引入的。`colorFor` 已优化 10×（857.6ns→87.4ns）而屏幕表现没变。
⇒ 大头在 `TermSurfaceView.onDraw` 的铺格/`drawText`/GPU，以及首帧 snapshot 到达。
读数原件：`/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/perf-remap/说明.md`

---
## 你要产出什么

**唯一产物**：`/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/ux4-idea/思路.md`

### 1. Heeler 在这件事上**据公开资料**是怎么做的
它是 iOS 上同形态的产品（SSH + 真终端 + 手机端）。从**公开描述**能确定的是它用 **libghostty** 做终端。
⇒ 去读 **Ghostty / libghostty 自己的公开文档**（MIT，可放心读），回答：
- 它的渲染管线是什么形态（GPU 着色器？纹理图集 glyph atlas？脏矩形？）
- 它怎么处理**滚动**（整屏重绘 vs 位移已有纹理）
- 它怎么处理**首帧 / resize**（是否有 reflow 分期、是否复用旧帧）
🔴 **这一节写的是"上游终端引擎的公开设计"，不是"Heeler 的代码"。** 分清楚。

### 2. 我们现在是怎么做的（读我们自己的代码，这是我们的仓库，随便读）
`/Volumes/nvme/Projects/远程Agent安卓/app/app/src/main/java/dev/agentmirror/app/termview/TermSurfaceView.kt`
- `onDraw` 每帧做了什么？逐格 `drawText`？有没有 glyph 缓存/图集？
- 滚动时是整屏重画还是有增量？
- 首帧/尺寸协商走哪条路（081 已补 cols 仪表）？
**给出量化描述**（一屏多少格、每格几次 draw 调用），⛔ 不要只写"可能比较慢"。

### 3. 差在哪 —— **逐条对照**，并给可验证的判断
每条写成：**「上游引擎用 X（来源链接）／我们现在是 Y（代码位置）／差距导致 Z（可量的指标）」**。
⛔ 不许写"他们更专业所以更快"这种无法证伪的话。

### 4. 我们该怎么做（**自己的设计**，不是照搬）
针对 S1/S2/S3 各给方案，每条含：
- 改哪里（文件 + 函数）
- **预期收益的量级**，以及**用什么指标验证**
- **风险与代价**（会不会伤到 085 重着色语义 / 083 已判绿的几何 / CJK 对齐）
- **拆格建议**（写范围两两不相交，串行顺序）

🔴🔴 **⛔ 不许提出遮掩手段**（用户明令）：延迟展示、等排好再画、占位、动画遮挡、
把白屏换成主题底色、节流输入压 janky%——**一个都不许出现在方案里**。
若某条只能靠遮掩，**照实写"这条我给不出真解"**。

### 5. 🔴 模拟器可信度
perf-v1 量到 janky% ~93%，这个绝对值很可能是**模拟器伪影**（083 §0）。
⇒ 方案里必须包含**能在用户真机上复现的量法**（例如把 `onDraw` 耗时打进诊断日志，
用户导出日志即可定罪），⛔ 不要设计一个只能在模拟器上跑的验证。

### 6. 风险与查不清
⛔ 不许为了让方案完整而补一个说得通的因果。查不清就写查不清。

---
## 判据（⛔ 一个字不许改）
- `A-id-doc`：`思路.md` 非空
- `A-id-src`：文档必须含 `未读取 Heeler 任何源码`、`libghostty`、`TermSurfaceView`、`onDraw`、`查不清` 这几个串
- `A-id-links`：文档内 `http` 链接数 ≥ 6（每条结论要有来源）
- `A-id-noswift`：🔴 **文档内⛔不得出现 `.swift`、`Sources/Heeler`、`import SwiftUI` 等源码痕迹串**（判据会 grep，命中即红）

---
## 全格通用（违反任一条 = 本格红）

🔴🔴🔴 **开工第一件事**
```
cd /Volumes/nvme/Projects/远程Agent安卓 && pwd
```
`pwd` 必须输出仓根。**若输出里出现 `.worktrees/`，立刻 cd 回仓根**——
派单正文下方那段「## 工作目录」是框架自动附加的，**它是错的，以本条为准**。
⛔ 不要 `git worktree add`，⛔ 不要进 `.worktrees/`。
⛔⛔ 绝不 `git checkout` / `git restore` 任何文件（仓根有大量未提交产品代码）。
🔴 **一次修复一个提交**。⛔ 不要顺手改相邻代码。
🔴🔴 **静默纪律（用户令：节点禁止给 leader 发消息）**：⛔ 不许 `team-agent send`、不发进度/提问/完工通知。
唯一对外动作是干完调一次 `report_result`。卡住写进说明.md。
⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux。⛔ 禁读 `.team/current/profiles/*.env`。

```

- write_paths: .team/nodes/ux4-idea/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/perf-remap/说明.md, /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/083-真机视觉收口六条.md, /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/085-终端主题库落位.md
- 判据: A-id-doc, A-id-src, A-id-links, A-id-noswift

## 2. 架构基（wiki 现算影响闭包）
- 写作用域包：（未命中已知包，报 leader）
- 正向依赖（你消费的契约，只读）：无
- **反向依赖（波及面 = 回归自查范围）**：无

### 闭包架构卡内联

（无卡命中——报 leader，不要猜）

## 3. 需求基
- 标题引用条目：（无编号引用）
- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）

## 4. 纪律（本工程通用，违反即返工）
- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。
- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。
- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。
