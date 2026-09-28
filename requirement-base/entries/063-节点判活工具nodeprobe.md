# 063 节点判活工具 nodeprobe：Rust 实现 + sh 包装，从 tmux 直接算结构化节点信息

- 日期：2026-08-18
- 裁定人：用户
- 前置条件（用户明确）：**所有节点都在 tmux 下运行**。不在 tmux 下的节点不在本工具范围内。

## 一、为什么做

**team-agent 自己的「某节点在不在工作」很不稳定。** 2026-08-18 实测：
四个 grok 席位持续干活期间，`status --json` 一律报 `PROBABLY_IDLE`、
`last_output_at` 冻住不动（见 `.team/artifacts/ledger-trial-findings.md` F-04）。
据此判断会得出「没人在干活」，而世界里正相反。

同一天，leader 为这一个问题**连续量错四次**（`worker_state` / `find -newermt` /
`pgrep -x` 全机匹配 / `pane_current_command` 显示 bash）。
⇒ 需要一个**独立、可复算、能自证**的判活工具，不依赖框架的推断字段。

## 二、交付物

- Rust crate：`tools/nodeprobe/`
- shell 包装：`tools/nodeprobe.sh`（给 leader、心跳脚本、以及任何人直接调）
- 输入：tmux socket（`-S` 路径或 `-L` 名字）
- 输出：结构化 JSON

```json
{
  "socket": "/private/tmp/tmux-501/ta-xxxx",
  "sampled_at": "2026-08-18T13:57:12Z",
  "nodes": [
    {
      "session": "team-grok-l2",
      "window_index": 3,
      "window_name": "dev-server",
      "pane_id": "%4",
      "name": "dev-server",
      "provider": "grok",
      "state": "working",
      "evidence": { "method": "...", "detail": "..." }
    }
  ]
}
```

- `state` 取值只有三个：`working` / `idle` / `unknown`
- `provider` 判不出时为 `unknown`，**不许猜**

## 三、🔴 三条硬纪律

### 1. 三态，不是布尔。判不出就是 `unknown`

`unknown` 必须**稀有**且**带证据**（是哪个前导符号、码点是多少、原始标题是什么），
让人能据此把新 CLI 加进去。**绝不允许把判不出回落成 `idle`**——
二级菜单前四轮翻车就死在「猜一个 idle 顶上」。

### 2. ⛔ 绝对禁止读进程 argv

本工具要遍历 pane 的进程树来辅助识别 provider。
**只能取 `comm`（进程名），禁止取 `args` / 命令行。**

理由是实发事故：2026-08-18 leader 用 `pgrep -fl` 查进程，
**当场把一个席位的 API key 打上了屏**（席位的 argv 里内联了 `ANTHROPIC_AUTH_TOKEN`）。
一个遍历进程树的工具如果读 argv，它就是一个凭据泄露器。

⇒ 这条要写成机械判据：源码中不得出现读取命令行的调用。

### 3. 🔴 Go 与 Rust 两份实现共用同一份判例语料

服务端已有 Go 版探测器（`server/internal/api/l2detect_*.go`，契约 062）。
现在再写 Rust 版 ⇒ **两份实现必然漂移，而漂移是静默的**：
某天改了 Go 那份忘了改 Rust，二级菜单和诊断工具会对同一个标题给出不同判定，
而两边的测试各自都绿。

⇒ **同一份 fixtures（判例语料，纯文本：标题 → 期望判定），Go 与 Rust 都必须跑它并全过。**
语料是单一真相，加一个新 CLI 就往语料里加样本，两边同时被约束。
这条写成机械判据，不靠自觉。

## 四、判活方法：由席位做对比实验后选定，不由 leader 拍板

已知候选（不完备，实现席自己补）：

| 方法 | 已知优点 | 已知弱点 |
|---|---|---|
| `pane_title` 前导符号 | 今日实测最准；CLI 厂商为了让人看懂必然维护它 | 每个 CLI 一套符号，需要维护表 |
| pane 内容哈希在短间隔内变化 | 与 CLI 无关 | 转圈动画会变、静止的工作态不变 |
| 进程树 CPU 时间增量 | 与 CLI 无关 | 等网络响应时 CPU 为 0，正是工作态 |
| CLI 会话文件 mtime | 今日实测有效 | 路径因 provider 而异，且是私有实现细节 |
| tmux `pane_in_mode` / activity 标志 | 便宜 | 与"在不在工作"不是同一件事 |

🔴 **选定方法必须有正控与反控**，两头夹住才可信：
- **正控**：一个**已知在工作**的节点，方法必须判 `working`
- **反控**：一个**已知空闲**的节点，方法必须判 `idle`
- 只有正控不算数——一个恒返回 `working` 的方法也能通过正控。

## 五、范围边界

- 本工具**只读**：不 attach、不发按键、不改任何 pane 状态。
- 不做修复动作（不重启节点、不重投消息）。它只回答「现在是什么样」。
