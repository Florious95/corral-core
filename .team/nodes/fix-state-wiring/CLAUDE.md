# 知识基底 · fix-state-wiring（实锤缺陷 D-1 修复）

## 0. 任务（taskbook.yaml#fix-state-wiring）
- 总图纸：docs/scenario-coverage.md §0.4 D-1 行（代码坐标 server/internal/api/server.go:84-86）与 §12 P0-1 行——先读这两处。
- 目标：main.go 组装 agentstate（Registry + Identify 进程树识别）→ api.Options.StateProvider，打通"识别→listing state 字段→012 聚合→App 通知"主链。红测先行：api 集成测试隔离 tmux + 假 claude 进程树断言 state≠unknown 与聚合正确；e2e/harness 层1 增状态断言场景。
- 验收：`bash -lc 'cd server && go test ./...'`。写范围：server/cmd/、server/internal/api/、e2e/harness/。
- 红线：008 隔离铁律——StateProvider 任何失败/超时降级 unknown 不影响镜像；Identify 的 500ms 有界 IO 不得进 listing 热路径同步阻塞（采样/缓存策略你设计，注释说明）。

## 1. 现场基
- 可用件：internal/agentstate 全部已交付（Detect/DetectForKind/Identify/Track，41 测）；沉淀区必读：.team/nodes/state-parser/CLAUDE.md §5 与 state-ident-wrapper §5（真实 argv 形状/标题旁证/坑）。
- discovery.Pane 有 PaneID/Command/Socket；pane_pid 若未采集需在 discovery 加法性补 `#{pane_pid}`（先查现状，需扩权字段照 ws-api 先例：纯加法+重跑 discovery 验收+报 leader 一行即可，预授权）。
- Track 需要 prev 状态记忆：api 层已有 per-ref 会话簿，挂那里。

## 2. 需求基（指针）
1. requirement-base/entries/008、012（状态语义与聚合权威）
2. requirement-base/entries/003（第四标准——本链是它的数据源）

## 3. 经验基
- 红测先行；最小装配不重构；净化前缀；tmux 隔离 socket；交件前全量门自查。
