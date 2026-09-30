# 知识基底 · tmux-discovery（系统编译产物）

## 0. 任务（taskbook.yaml#tmux-discovery）
- 目标：枚举主机全部 tmux server（含私有 socket），列出 session/pane 及 cwd，按 cwd 聚合为两级模型。
- 验收（exit 0 = 过）：`bash -lc 'cd /Volumes/nvme/Projects/远程Agent安卓/server && go test ./internal/discovery/...'`
- 写范围：`server/internal/discovery/`。红线：对不可连 socket 容错跳过，一个死 socket 不得拖垮扫描；不得连接/干扰 socket 上正在运行的会话（只读枚举）。

## 1. 架构基
- socket 发现面：`$TMUX_TMPDIR` 与默认 `/tmp/tmux-<uid>/`（macOS 实为 /private/tmp/tmux-<uid>/）下**所有** socket 文件——默认 socket `default` 之外还有任意命名者（实测本机就有 team-agent 私有 socket 如 `ta-b7cc1c640ccf`，这正是产品核心需求：通用产品只看 default，我们必须全见，见需求 001）。
- 每 socket 一个 tmux server：`tmux -S <sock> list-sessions` / `list-panes -a -F '#{session_name}|#{window_index}|#{pane_id}|#{pane_current_path}|#{pane_current_command}|#{pane_width}x#{pane_height}'`。
- 聚合：一级 key = pane 的 cwd（多 pane 同 cwd 合并计数）；二级 = 各 pane（展示标签用 session 名+pane），见需求 002。session 名不参与分组。
- 死 socket 判定：连接超时/拒绝 → 跳过并记 debug 日志；文件存在但 server 已死属常态（tmux 退出不清 socket）。
- 输出为纯数据结构（供 api 层消费），本包不做任何缓存策略（扫描一次一个快照；轮询/推送节奏由上层定）。

## 2. 现场基
- 本机 tmux 3.6a；活 socket 示例：/private/tmp/tmux-501/ 下有 team-agent 私有 socket（**测试时禁止触碰真实 socket**——用自建隔离 socket）。
- 测试铁律：测试内自起 tmux server 用独一无二的 `-L test-disc-$$-<case>` socket 名 + `TMUX_TMPDIR` 指向 t.TempDir()，测试结束 kill-server 清理；全程 `env -u TMUX` 防嵌套。这既是净化也是防"杀真团队"事故（工程血泪，绝对红线）。

## 3. 需求基（指针）
1. requirement-base/entries/001-产品命题-tmux镜像范式.md（私有 socket 必须全见——本任务存在的理由）
2. requirement-base/entries/002-两级分组模型.md（聚合语义权威）

## 4. 经验基
- 红测先行：先写"两个 socket 各一 session、cwd 相同应聚合为一组"的红测与"死 socket 混入不影响结果"的红测。
- 阳性对照：空结果测试必须配一个非空对照（空输出可能是"没扫到"）。
- 测试净化前缀 + `-timeout 60s`。注释红线照旧。

## 5. 沉淀区（唯一允许你追加写入的区域）
