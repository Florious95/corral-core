# 知识基底 · fix-bridge-restart-pipe（系统编译产物）——缺陷修复任务

## 0. 任务（taskbook.yaml#fix-bridge-restart-pipe）
- 目标与验收见任务书条目；红测先行是硬要求：先把 e2e 实证形状变成 bridge 集成红测（先红），再最小修复（后绿）。
- 写范围：`server/internal/bridge/`、`server/internal/api/`（relay/detach 相关）、`server/cmd/`（优雅关闭可选加固）。红线：不改协议；e2e/ 只读；不动 discovery/protocol 等无关包。

## 1. 现场基（e2e 验收席的完整案卷，一手实证）
- **现象**：老化「杀 daemon→重启→重连重放」第 2 轮起：subscribe 成功（快照 208B 到）、input ack=ok、pane 有回显，**delta 流 0 帧**。第 1 轮过；connection-drop（不杀 daemon）老化 20/20 全过——精确切割出"跨 daemon 重启"这一维。
- **根因链（已三重实证）**：
  1. bridge 流是 FIFO 会合：relay goroutine `os.OpenFile(fifo, O_RDONLY)` 阻塞等 writer；tmux 侧 `pipe-pane -o 'cat >> fifo'` 的 cat O_WRONLY 阻塞等 reader；首次订阅两侧成对解开，正常。
  2. **daemon 被杀（SIGKILL/SIGTERM 均实测）后旧 cat 不退出**，仍 attach 在 pane 上持有旧 FIFO。
  3. 新 daemon `pipe-pane -o` 见 pane 已有 pipe → **静默 no-op**（tmux 语义）→ 新 FIFO 永远无 writer → 新 relay 永久阻塞在 OpenFile。
  4. teardown 的 `sub.detach()` 排在 relay 之后，relay 阻塞则 detach 永不执行——死锁不能自愈。
- **实证证据**（e2e/harness 内，可参考其手法写红测）：restart 后 `#{pane_pipe}` 1→0；ps 见 `cat >> <fifo>` 残留；隔离复现 TestDbgRestart：round2 ack=true、pane contains marker=true、delta=false。
- 涉及代码：`server/internal/bridge/stream.go:99`（OpenFile 阻塞点）、`server/internal/api/ws_conn.go`（relay/detach 编排）、`server/cmd/agentmirrord/main.go`（关闭不 drain）。

## 2. 架构基（修复方向裁定）
- **主修（必做）**：subscribe 路径**先无条件 `pipe-pane`（无命令=detach）再 `pipe-pane -o`**——把"崩溃残留"当常态输入做幂等自愈（004 哲学：崩溃是常态路径）。注意 detach 会让残留 cat 收 EOF/SIGPIPE 退出，验证之。
- **必做加固**：relay 打开 FIFO 用 O_NONBLOCK+重试（或带超时的 open），使 relay 永不无限阻塞、teardown 必可达；超时后明确报错上抛（静默失效猎杀：阻塞≠等待，超时要可判定）。
- **可选加固**：daemon 优雅关闭时 drain 全部 subscription（detach+关 FIFO）；但 SIGKILL 路径必须靠主修自愈，不得依赖优雅关闭。
- 红测形状：真实隔离 tmux（短 socket 路径，见 term-bridge 沉淀）：①subscribe→杀掉 bridge 侧（模拟 daemon 死，保留 stale cat）→新 bridge 实例重新 subscribe→写 pane→**断言 delta 到达**（修前红）；②OpenFile 超时红测（无 writer 的 FIFO，断言限时报错而非挂死）。

## 3. 需求基（指针）
1. requirement-base/entries/004-后台策略-无状态免疫.md（被违反的承诺本体）
2. requirement-base/entries/013-测试体系与回归门禁.md（老化实验为何存在）
3. `.team/nodes/term-bridge/CLAUDE.md` §5（tmux 实测纪要：pipe-pane 幂等/EOF 语义/短路径坑——前任已记录 pipe-pane 无参即断开+读端 EOF 行为，直接可用）

## 4. 经验基
- 最小修复：不顺手重构 stream.go；改动每行可追溯到根因链某一环。
- 交件前跑 bridge 全包 + `go test ./internal/api/...`（relay 编排若动）+ 全量门自查。
- 注释红线、净化前缀、-race 照旧。

## 5. 沉淀区（唯一允许你追加写入的区域）

### 交件记录（2026-08-09，w-fix-bridge，验收 exit 0）

- **主修**（`server/internal/bridge/stream.go`）：subscribe 先 `pipe-pane -t`（无参 detach，幂等 rc=0）再 `pipe-pane -o`。实测证实：detach 后残留 cat 收 EOF 退出，-o 才真正挂上新 writer。
- **加固**（`openFIFO`）：FIFO 读端 open 有界化——goroutine 阻塞 open + 3s 超时后自身 O_WRONLY|O_NONBLOCK open 解开阻塞（macOS 实测 0s 生效），超时上抛可判定错误。**关键实测**：macOS(BSD) 上 poll 对"writer 已连接但无数据"不触发 POLLIN（与 Linux 不同），所以不能用 poll 做 open 有界化，必须用 self-writer 技法。
- **可选加固**（`server/internal/api/server.go`+`ws_conn.go`）：`Server.Close` drain 所有活跃连接订阅（`wsConn.closeSubscriptions`）。
- **红测 4 个**全先红后绿：restart-pipe 复现 2 个（bridge）+ OpenFIFO 超时 + Close drain（api）。
- **验收**：bridge 全包、api 全包、server 全量、关键测试 -race 全过；vet/build/gofmt 净；e2e/ 严格只读。
- 注意：bridge 改动已被 leader commit e446859 带入 HEAD；api 加固改动在工作树未提交。
