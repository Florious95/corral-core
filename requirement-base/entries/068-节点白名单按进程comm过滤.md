# 068 · 节点白名单：按进程树 comm 过滤，只检测五家 Agent CLI

- 日期：2026-08-19
- 来源：用户指令「加个过滤机制吧，只有 Claude、Codex、Copilot、Grok，还有 cursor 这些才检测」
- 关联：061（列表加状态）、062（三态规则与解耦）、063（nodeprobe）

## 1. 需求

二级菜单只列出运行着受支持 Agent CLI 的 pane。白名单五家：
Claude Code / Codex / Copilot / Grok / Cursor。其余 pane（裸 shell、编辑器、
构建进程等）**不进列表**，不占位、不显示「未知」。

## 2. 判别依据（实测得出，不得改回标题）

2026-08-19 在隔离 socket 上把五家全部起了一遍，实测三条候选依据：

| CLI | tmux #{pane_current_command} | 进程树 basename(comm) | pane 标题 |
|---|---|---|---|
| Claude Code | bash | claude | ◐… / ✳… |
| Grok | bash | grok（node 下第 2 层） | ⠋… / … - grok |
| Codex | node | codex（node 壳下的原生二进制） | **不设，= 主机名** |
| Copilot | node | copilot | GitHub Copilot |
| Cursor | node | cursor-agent | **不设，= 主机名** |

结论：

- ⛔ **不得用 pane 标题做过滤**。Codex 与 Cursor 不设标题，与空 shell 完全同形，
  按标题过滤会把这两家整个丢掉。
- ⛔ **不得用 #{pane_current_command}**。五家里四家返回 bash/node，零区分度。
- ✅ **唯一依据：遍历 pane_pid 及其全部后代进程，取 comm 的 basename**，
  与白名单求交，非空即为节点。深度不限（grok 在第 2 层，codex 在第 1 层）。

## 3. 硬纪律

- ⛔⛔ **只取 comm，绝不取 argv**（2026-08-18 实发：pgrep -fl 当场把席位的
  ANTHROPIC_AUTH_TOKEN 打上屏）。ps 一律窄字段 `pid,ppid,comm`。
- **basename 匹配**，不得整串相等：macOS 的 ps comm 给全路径
  （实测 `/opt/homebrew/.../bin/codex`、`/Users/…/bin/cursor-agent`），
  Linux 给短名。整串相等在 macOS 上永远不命中。
- **provider 身份先于状态判定**：先由 comm 确定是哪一家，再派给那一家自己的
  检测器判工作/空闲。⛔ 不得再让各家检测器在同一串标题上竞争认领——
  那是「认错家」这类错误的来源。
- **白名单是数据不是代码**：五家各自一条记录（comm basename 集合 + 显示名），
  加第六家不得改共享层。共享层零 CLI 字面量（沿用 062 §5，判据 grep 反测）。

## 4. 语义变更（收窄「未知」）

062 的三态里，「未知」原本兜住了所有认不出的标题。本条之后：

- 非白名单 pane ⇒ **不是节点**，不进列表（不是「未知」）。
- 「未知」只保留给：**已确定是哪一家，但该家的检测器认不出这串标题**。
  ⇒ 未知从此是一条真信号（某家的判据缺样本），必须记日志含 provider + 码位 + 全标题。

## 5. 判据

- A-wl-five：五家的 comm basename 各有一条 fixture，分类结果 = 对应 provider。
- A-wl-noise：裸 shell / 编辑器 / 构建进程的 pane 不出现在列表里。
- A-wl-noargv：实现文件与取数命令中不得出现 argv 读法
  （grep 反测：`ps` 的 -o 字段集合只含 pid/ppid/comm；无 `-f`、无 `command=`、无 `args=`）。
- A-wl-basename：给一条全路径 comm（如 `/opt/homebrew/…/bin/codex`）必须命中，
  **此判据须先在「整串相等」的实现上验红**。
- A-wl-decoupled：共享层文件 grep 五家名字必须零命中。
- A-wl-cost：轮询开销符合静默经济红线（空闲 CPU 趋近 0）；超标则改缓存 + 变更时重扫。

## 6. 已知边界（写明，不假装覆盖）

- **ssh 到远端跑的 agent 会被判为非节点**（进程不在本机，comm 不可见）。
  裁定：可接受，它本来不归本机 daemon 管。
- 五家之外的 Agent CLI 一律不列。加新家 = 加一条白名单记录。

## 7. 一级菜单同样过滤（2026-08-19 用户追加）

一级菜单（socket / 工作区列表）同样只列**含至少一个白名单节点**的 socket。

- 判别：对每个 socket 跑同一套 provider 识别，白名单命中数为 0 ⇒ **该 socket 不进一级菜单**。
- ⛔ 不得为一级菜单另写一套判别 —— 一二级必须走同一个 provider 识别函数，
  否则两级会给出互相矛盾的世界（一级有、点进去二级空）。
- 判据 A-wl-l1：造一个只含裸 shell 的 socket，它**不出现在一级菜单**；
  另造一个含任一白名单 CLI 的 socket，它必须出现。阴性阳性都要有。
