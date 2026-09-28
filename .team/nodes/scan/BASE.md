# 知识基底 · ledger.market.v1 / t.scan（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.scan · 市场定位与同类产品调研（leader 直派，用户 2026-08-20 要求）

🔴🔴🔴 **你这一格只出调研报告，⛔ 一行产品代码都不许改。** 写范围只有 `.team/nodes/market-scan/`。
⛔ 不许碰 `app/`（正被另外两张账本占着施工），⛔ 不许跑 gradlew。

## 用户原话（这是需求，不要改写）

> 「你派一个 teammate 做一下调研，就是这种**以 Tmux 为基底，然后开一个服务端，然后在手机上可以用 APP 去远程操控**。
> 这样产品在市场上的**定位**，以及**是否有同类产品**。这种同类产品**我们的优势在哪里？劣势在哪里？**」

## 被调研的对象是我们自己这个产品，先把它认准（⛔ 不要凭名字猜）

**远程Agent安卓**（暂名），Apache-2.0 开源：
- 主机上跑一个 daemon（`agentmirrord`，监听 9900），把**主机 tmux 里的大量 Agent CLI 会话**接出来
- 手机 APP（Android，Compose）通过配对码 + LAN / Tailscale 连上去，**远程操控**这些会话
- 被操控的是**真实的 Agent CLI 进程**（claude_code / grok / codex …），不是某家自己的云端 agent
- 有真终端渲染（自绘 SGR / 真彩 / CJK 对齐 / alt-screen）、会话列表、收藏、工作区隔离
- 还有一个同协议的 Web 客户端
- 关键定位词：**「不是新的 agent，是已有 agent CLI 的远程操控层」**

## 你要产出什么

**唯一产物**：`/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/market-scan/调研.md`

至少回答五件事：

### 1. 同类产品普查（**本格最花时间也最有价值的一块**）
把「手机/远程操控本机 coding agent」这条赛道扫一遍。**每一条都必须带可点开的链接**，
⛔ 没有链接的条目不许进表。至少覆盖这几类，每类尽量找全：

- **和我们几乎同形的**：例如 `github.com/ZingerLittleBee/Heeler`（iOS + libghostty + SSH + 配对 + 推送，AGPL-3.0，
  配套服务端叫 herdr）——这个我方已确认存在，**你要去看它到底怎么做的、做到哪一步**。
- **官方自带的移动/网页入口**：Anthropic 的 Claude Code 在网页/移动端能不能用、OpenAI Codex 的云端与 app、
  Cursor 的 background agents / 网页端。🔴 **这一类是最大的威胁，不要漏。**
- **开源的 Claude Code 手机客户端**（社区里有若干，自己搜，别猜名字）。
- **纯 SSH/终端 App**（Termius / Blink Shell / a-Shell / JuiceSSH 之类）——它们不是 agent 专用，
  但**用户完全可以用它们 SSH 进去开 tmux**，这是我们真正的替代品，⛔ 不许因为"它们不是同类"就跳过。
- **主机侧的多 agent 编排面板**（Conductor / Crystal / vibe-kanban 之类桌面或本地 Web 面板）。

输出一张表：`产品 | 链接 | 形态 | 许可 | 被控对象是谁 | 传输 | 平台 | 还活着吗`。
🔴 **查不到就写「查不到」，⛔ 严禁编一个看起来合理的产品名**。编出来的竞品会让整份报告失效。

### 2. 我们的定位
用一句话说清我们在这条赛道上**卖的是什么**，并说清和上面每一类的**分界线**在哪。
特别要回答：**「为什么不直接用 Termius SSH 进去开 tmux？」** —— 这是最尖锐的一问，答不了这条就没有定位。

### 3. 优势（必须可验证，⛔ 不许写「体验更好」这种没法证伪的话）
每条优势写成「**因为 X（可核的事实），所以 Y（用户能感知的差别）**」。
候选（你自己核，核不实就删掉，不要替我圆）：真终端渲染、多会话/多工作区、
被控对象是用户自己的 CLI 而不是厂商云端 agent、Apache-2.0、自托管不过第三方服务器、Android。

### 4. 劣势与真实威胁（**这一节写不狠就是没写**）
🔴 **不许只写「我们还年轻」这类软话。** 至少直面这四条，逐条给判断：
- 官方入口（Claude Code 网页/移动端）如果做好了，我们这层还剩什么价值？
- 一个 SSH App + tmux 已经能解决 80% 的场景，我们多出来的 20% 值不值得装一个 APP？
- 我们只有 Android，iOS 那边已经有 Heeler，这意味着什么？
- 依赖主机常开 + 网络可达（LAN/Tailscale），这个前提劝退了多少人？

### 5. 结论与建议
基于上面的事实，给出**我们应该往哪打**的判断，以及**哪些方向不该打**。给理由，⛔ 不要列一堆"也可以做"的清单。
🔴 **风险与查不清另起一节**：查不到的、拿不准的明写「查不清」。⛔ 不许为了让报告完整而补一个说得通的因果。

---
## 方法与纪律

- ✅ 查 GitHub 用 `gh api` / `gh search repos`（只读，⛔ 不要 clone 大仓）。有联网检索能力就用，没有就明写"没有联网检索，以下只基于 GitHub 可查到的部分"。
- 🔴 **每一条事实都要能被 leader 复核**：给链接、给 `gh api` 命令、给 star 数与最后提交时间（判断"还活着吗"）。
- 🔴 **区分「我看到的」和「我推断的」**，报告里分开写。
- ⛔ 不要为了凑数把不相关的东西塞进竞品表（例如通用远程桌面、通用笔记 App）。

🔴🔴🔴 **最高优先级 · 与派单正文下方「## 工作目录」那段冲突时以本条为准**：忽略那段，
一律在**仓根** /Volumes/nvme/Projects/远程Agent安卓 干活。⛔ 不要 `git worktree add`，⛔ 不要进 `.worktrees/`。
⛔⛔ 绝不 `git checkout` / `git restore` 任何文件。
🔴🔴 **静默纪律（用户令：节点禁止给 leader 发消息）**：⛔ 不许 `team-agent send`、不发进度/提问/完工通知。
唯一对外动作是干完调一次 `report_result`。卡住写进 `调研.md`。
⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux。
⛔ **禁读任何凭据文件**（`.team/current/profiles/*.env` 全员禁读）。调研不需要碰它们。

```

- write_paths: .team/nodes/market-scan/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/README.md, /Volumes/nvme/Projects/远程Agent安卓/CLAUDE.md, /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/
- 判据: A-mk-doc, A-mk-sections, A-mk-links, A-mk-table

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
