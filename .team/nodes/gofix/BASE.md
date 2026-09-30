# 知识基底 · ledger.pr3.v1 / t.gofix（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.gofix · 🔴 main 上服务端编译坏了（P0 回归）

## 现象（全量门在 main 上实测）
```
server  cases 353 → 248（棘轮下行）  真单测失败 3
  ❌ cmd/agentmirrord (package failure)
  ❌ internal/api  (package failure)
  ❌ go vet
```
```
$ cd server && go build ./...
internal/api/lifecycle.go:252:12: undefined: scrubbedEnv
```

## 根因（leader 已定位，⛔ 你不用重新查，但要自己复核一遍）
`server/internal/api/lifecycle.go:252` 的**生产代码**调用 `scrubbedEnv()`，
而 `scrubbedEnv` **只定义在 `server/internal/api/tmux_test.go:157`**（测试文件）。
⇒ `go build` 失败；`go test` 却能过（测试构建包含 `_test.go`）——所以它一直没被发现。

## 你要做的
把 `scrubbedEnv` 挪到**生产文件**里（放哪个文件你判断，`lifecycle.go` 或同包一个合适的位置），
并**删掉 `tmux_test.go` 里那份重复定义**（同包重复定义会直接编译失败）。

⚠️ `server/internal/bridge/bridge_test.go:66` 也有一个同名函数，**那是另一个包，⛔ 不要动它**。

🔴 **⛔ 不要顺手改别的**。这是 P0 修复，diff 越小越好。

## 判据
- `A-gofix-build`：`go build ./... && go vet ./...` 绿。🔴 **先验红**：改之前跑，必须报
  `undefined: scrubbedEnv`，把原始输出贴进说明.md。
- `A-gofix-gotest`：`go test ./... -count=1` 绿，且**用例数不少于 353**
  （本轮开始时的棘轮基线；⛔ 不许靠删测试凑绿）。
- `A-gofix-gofmt`：`gofmt -l .` 输出为空。
- `A-gofix-app`：App 套件仍绿（防跨面回归）。
- `A-gofix-doc` / `A-gofix-seal`：说明含先验红原始输出 + 封版。

## 🔴 这一格存在的原因，请你也读一遍
**是我的判据只盖了半个仓**：`t.close`/`t.newpane` 改了 `server/`，
而它们的判据只跑了 `./gradlew :app:testDebugUnitTest` + archwiki 棘轮 + Android Lint 棘轮——
**没有任何一条编译过 Go**。四位评审席也没抓到（它们审 diff 与说明，⛔ 不跑构建）。
⇒ 本格的判据里**同时含 `go build` 与 `go test`**：
**`go test` 不能替代 `go build`**，生产码调 `_test.go` 里的符号，只有 `go build` 抓得到。

---
## 🔴 流程（PR 链）
开工先跑并把输出贴进说明.md：`pwd` 与 `git branch --show-current`。
1. 建分支 `git checkout -b pr/gofix-scrubbedenv`，只改自己 worktree 里的文件。
2. ⛔ 不 commit、⛔ 不 push、⛔ 不并线 —— 封版由 leader 的判据自动做。报完⛔ 别再改那棵 worktree。
3. ⛔ 不写 `/tmp`；临时文件写 `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/pr3-gofix/tmp/`（自己 mkdir -p）。
4. ⛔ 判据红了不许改判据让它变绿；判据本身写错 ⇒ 报 `blocked`。
5. **发现 `write_paths` 不够 ⇒ 报 `blocked` 让 leader 改账本，⛔ 不要自行扩写。**

```

- write_paths: server/internal/api/, .team/nodes/pr3-gofix/
- read_paths: .team/nodes/pr3-gofix/说明.md
- 判据: A-gofix-build, A-gofix-gotest, A-gofix-gofmt, A-gofix-app, A-gofix-doc, A-gofix-seal

## 2. 架构基（wiki 现算影响闭包）
- 写作用域包：internal/api
- 正向依赖（你消费的契约，只读）：go_internal_agentstate, go_internal_bridge, go_internal_discovery, go_internal_protocol
- **反向依赖（波及面 = 回归自查范围）**：go_cmd_agentmirrord

### 闭包架构卡内联

### Go · internal/api

- **职责**：Package api implements the service-side WebSocket API and the image upload endpoint, wiring together discovery and bridge (task ws-api).
- **导出面**：Discoverer, NewServer, NewStateProvider, Options, Server, StateProvider, TokenValidator
- **依赖边**：internal/agentstate, internal/bridge, internal/discovery, internal/protocol

### Go · cmd/agentmirrord

- **职责**：Command agentmirrord is the service-side daemon of AgentMirror (product github.com/agentmirror/agentmirror): a sidecar that mirrors the user's existing tmux sessions to the Android app over WebSocket.
- **导出面**：main
- **依赖边**：internal/api, internal/config, internal/pairing, internal/tsnetd

## 3. 需求基
- 标题引用条目：（无编号引用）
- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）

## 4. 纪律（本工程通用，违反即返工）
- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。
- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。
- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。
