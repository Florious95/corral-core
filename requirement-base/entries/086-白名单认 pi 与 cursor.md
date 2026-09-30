# 086 · 节点白名单要认出 `pi` 与 `cursor`（Agent）

**提出**：用户，2026-08-20
**用户原话**：「服务端把 Agent 和 Pi 都开一下。agent 是 cursor」「cursor Agent 它的命令行就是 agent」「Pi 的话，命令行就是 pi」

## 现状（leader 已实测，操作数如下）

两个 CLI 已由 leader 起在专用 socket `/tmp/tmux-501/ta-user-agents`（session `agents`，
两个 window：`cursor` / `pi`）。⛔ 未碰用户真实 tmux（默认 socket 仍只有 `0` 和 `1`，已自检）。

```
nodeprobe -S /tmp/tmux-501/ta-user-agents  →  "nodes": []      ← 一个都认不出
```

进程 comm（只取 comm，⛔ 未取 argv）：

| window | pane 进程 comm | 基名 | 白名单能否匹配 |
|---|---|---|---|
| `pi` | `pi` | **`pi`** | ❌ 不在表里，加一行即可 |
| `cursor` | `/Users/alauda/.local/share/cursor-agent/versions/2026.08.11-e8db854/node` | **`node`** | ❌ 表里有 `cursor-agent` 但基名是 `node`，匹配不上 |

`~/.cursor/proxy/bin/` 下 `agent` 与 `cursor-agent` **都是软链** → `cursor-wrapper-proxy.sh`，
最终 exec 的是 node，所以 comm 基名是 `node`。

当前白名单（`tools/nodeprobe/fixtures/providers.tsv`，**Go 与 Rust 共用，注释明写 Do not fork**）：
```
claude	claude_code	Claude Code
codex	codex	Codex
copilot	copilot	Copilot
grok	grok	Grok
cursor-agent	cursor	Cursor
```

## 要做什么

1. **`pi` 进白名单**：`pi` → `pi` → `Pi`。基名直接命中，最简单的一条。
2. **`cursor` 要能被认出**：`cursor-agent` 那一行现在是**死条目**（现实中匹配不到）。
   需要一个不依赖 argv 的识别办法。候选（实现席自己定，给理由）：
   - 匹配 comm **完整路径**是否含 `/cursor-agent/`（而不是只看基名）
   - 或走 068 §8 已有的 identity-first / pane 标题路径
   - ⛔ **绝不允许**把裸 `node` 加进白名单——那会把所有 node 系 CLI 全认成 cursor
   - ⛔ **绝不允许**读 argv（凭据红线：argv 含席位明文 token）

## 判据

🔴 **断言世界变了**：改前 `nodeprobe -S /tmp/tmux-501/ta-user-agents` 返回 **0 个节点**；
改后必须返回 **2 个**，且 provider 分别为 `pi` 与 `cursor`。
⛔ 只跑单测不算——单测绿而真 socket 上仍是 0，等于没做。

配套：
- Go 侧 `server/internal/provider/table_test.go` 现在断言 `rows=5`，加行后必须同步更新（⛔ 不许删这条断言）
- Rust 侧 `tools/nodeprobe` 共用同一张表，两边都要绿
- ⛔ **反向断言**：裸 `node` 不得成为白名单条目（防止过度放宽）

## 已知但不在本条范围

- cursor pane 当前停在 `⚠ Workspace Trust Required`，**等用户确认信任**。
  那是授予 Cursor Agent 在该目录执行代码的权限，**由用户自己决定**，⛔ leader 与席位都不代按。
  白名单修好后用户可以直接在手机上确认。
