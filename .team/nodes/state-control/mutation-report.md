# 定点变异报告：判据「零字形差分/存在判据」验红测真的会红

- 席位：状态判定对照席（r.control，零上下文定点变异）
- 账本：`.team/ledgers/state-detection-v1.json`（任务 t.verify）
- 日期：2026-08-15
- 判据命令：`bash .team/nodes/state-oracle/run-probe.sh`（判据唯一入口，`go test ./probe/ -count=1` 退出码权威）
- 只读文档：`.team/nodes/state-oracle/判据基底摘要.md`（唯一允许的判据文档）

## 结论：supports（判据有效）

**基线（未变异）exit 0 → 变异后 exit 1 → 恢复后 exit 0。判据能区分绿与红，且变异点是精确命中，非误伤。**

| 阶段 | 退出码 | 探针结果 | 说明 |
|---|---|---|---|
| 基线（未变异 live） | **0** | R1 PASS / R2 PASS / G1 PASS / G2 PASS | live 已重建为导数判定，判据绿 |
| 变异（`track.go` 导数分支永远返回 idle） | **1** | R1 **RED** / R2 **RED** / G1 PASS / G2 PASS | 判据精准抓红，未误伤守卫 |
| 恢复（live 回到基线） | **0** | 全部 PASS | 现场还原，可复现 |

## 变异点（一字符级定向破坏）

- 文件：`server/internal/agentstate/track.go`，第 44-47 行（`Track` 函数 `cur != old` 导数分支）
- 变的是：`return State{State: protocol.StateWorking, ...}` → `return State{State: protocol.StateIdle, ...}`
- 机理：删掉「内容在动 = working」的差分判定，让导数路径**永远返回 idle**。这正好是摘要 §6 自检第 60 行原话描述的倒退（「把 working 判定删掉、永远返回 idle」），也是判据文档声称必须被抓到的那类回归。
- 变异方式：单行单 token 替换（`StateWorking` → `StateIdle`），结构不破坏、能编译、能跑。

## 变异前后对照（判据为什么能区分）

- 基线绿：`Track` 对「标题 ◐→◑ 两帧在动」→ working（R1 绿）；对「底部块 ◐→◑ 两帧在动」→ working（R2 绿）。
- 变异红：同一输入下 `Track` 两帧都在动却判 **idle** → R1/R2 各自 `RED`（断言 `got.State != StateWorking` FAIL）。
- **G1/G2 同时保持绿**：真实 working 版式（esc to interrupt）仍判 working、真实完成态（✻ 前缀，无工作信号）仍判非 working——证明判据是在抓「导数被删」这个特定倒退，不是把整个实现判成红。变异命中的是判据要保护的语义，而非无关路径。

## 判据有效性论证（对照摘要 §0 / §6 / §8）

1. **基线绿**（exit 0）：live 当前是重建后的实实现（非占位 stub），四条探针全 PASS——判据对「对」的实现不误杀。
2. **变异红**（exit 1）：把摘要声称该被抓的倒退（永远返回 idle）注入后，R1/R2 立即被抓住——判据会响。
3. **可复现**：三阶段全在本报告记录退出码与哈希，判据命令无缓存依赖（`-count=1`），重跑结论稳定。
4. **判据模块载体未被动坏**：跑完判据命令 scratch 化石还原（`track.go` 哈希 `34b72e35…` 与基线一致），`.fossil-bak` 清理干净——红测载体（归档旧实现）不被重建代码污染，符合摘要 §7 边界。

## 变异/恢复哈希对照（可审计）

| 文件 | 阶段 | SHA-256 |
|---|---|---|
| `server/internal/agentstate/track.go`（live） | 基线 | `2b768a64a009ebcbd40d4c791a4c526aeb725c5ef338921ea222b97accd3e37d` |
| `server/internal/agentstate/track.go`（live） | 变异 | `139110b75c08646c5a05201e2600b64138fa1a234a8442fd6e5fc09c913e505c` |
| `server/internal/agentstate/track.go`（live） | 恢复 | `2b768a64a009ebcbd40d4c791a4c526aeb725c5ef338921ea222b97accd3e37d`（回到基线） |
| `scratch/agentstate/track.go`（判据模块） | 全程 | `34b72e35e0ed00f08404fc00e01bce63c85701269f019aff5745ea0ddcb5b3f9`（化石未变） |

## 现场状态（收尾自查）

- live 变异已还原：`server/internal/agentstate/track.go` 回到基线哈希，判据命令恢复 exit 0。
- 判据模块 scratch 载回归档化石，未被变异代码污染。
- 只写过 `.team/nodes/state-control/mutation-report.md`（本报告）；未 commit / push / 未发 team-agent send / 未读禁读文件。
