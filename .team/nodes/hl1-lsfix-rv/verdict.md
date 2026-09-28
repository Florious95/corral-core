VERDICT: supports

# t.lsfix.rv · 异源评审 pr/listing-ps-storm（只读）

被审对象：`pr/listing-ps-storm` @ `8272ec1ff`（评审开始时分支尚未封版、改动在 `.worktrees/hl1.lsfix` 工作区；
中途 leader 完成封版，**逐字节比对：封版后 `git diff main...HEAD` 与我先前审的工作区 diff 内容一致**，5 个产品文件 + 1 个新测试文件）。
评审席：Claude 订阅（Opus 5），实现席为 grok ⇒ 异源成立。⛔ 全程未改产品代码、未 commit/push。

## 1. 先验红：**独立复现成功**（不是只信 log）

说明.md 贴了 `tmp/probe-red.log` 原文，且我**没有采信它**，而是自己重建 main 形状复现：
把 `server/` 全量副本里 5 个产品文件用 `git show main:...` 覆盖回 main 版本，
只手工加回 `procTableReads` 计数器（`var` + `readProcTable()` 里 `Add(1)`），
并删掉引用 fixed-only 字段（`f.ident` / `snap.start`）因而在 main 上编译不过的
`TestProcFinderIdentityCacheKeyedByPidStarttime`。结果：

```
--- FAIL: TestListingTickFullTablePsForkAtMostOnce (0.65s)
    listing tick full-table ps forks=11 want<=1 (unfixed shape is N+1=11)
--- FAIL: TestHandleListDoesNotBlockReadLoop (0.40s)
    timed out waiting for expected frame
--- FAIL: TestProcFinderIdentifyDoesNotInvalidateSetCache (0.15s)
    Identify after IdentifySet forks=3 want 1 (single-pid key overwrote the table)
```

原文 `tmp/rv-red-repro.log`。与席位 `probe-red.log` 的两条数字**逐字一致**（forks=11、N+1=11）。
红是**真红且红在被指认的根因上**：11 = 1 次 IdentifySet + 10 次 buildSnapshot 里的单 pid `Identify`，
不是"测试写死一个 fail"。

红的机理我也核过：`paneKey = paneSetKey(pids)` 被单 pid 调用覆盖 ⇒ 下一次集合请求 key 不匹配 ⇒ 必 fork；
第二条红是 `handleFrame` 里 `c.handleList(t)` 同步跑在 readLoop 上，Discover 阻塞期间 Input 帧根本没被解析。

## 2. 转绿：在 PR worktree 只读复跑，含 `-race`

```
go build ./... && go vet ./... && go test ./internal/api/ -run 'TestListingTick|TestHandleListDoesNotBlock|TestProcFinder' -count=1 -race
ok  github.com/agentmirror/agentmirror/internal/api  1.646s
go test ./... -count=1   → 10 个包全 ok（api 46.3s）
```
原文 `tmp/rv-green.log`。`-race` 是我加的（席位只跑了普通模式）——因为本 PR 把 handleList 挪进了新 goroutine，
不带竞态检测的绿不足以为并发改动背书。加了仍绿。

## 3. 三条重点核验

**(a) fork 次数探针先验红真实（main 上 N+1）** — 成立，见 §1，独立复现 forks=11。
修后 `rebuildCatalog` 只做一次 `identifyModel`，`buildSnapshot` 收到的是闭包 `func(pid) string { return hits[pid] }`，
不再触达 procFinder ⇒ 1 次 fork。等价性核过：`filterModelHits` 只保留 `hits[PanePID] != ""` 的 pane，
所以 catalog 里每个 entry 在 hits 中必有值，与原先逐个 `identifyProvider` 结果相同，无行为变更。

**(b) handleList 不再阻塞读循环有证据** — 成立。`go c.handleList(t)`，探针以 gatedDiscoverer 卡住 Discover、
断言 400ms 内拿到 InputAck(req_id=99)；main 上超时，修后通过。写路径安全性我另外核过：
`send → sendMsg → c.sendCh`（chan + writeLoop 单写者，且 `select ctx.Done()` 兜底），
所以离开 readLoop 后并发发帧不会撕帧、也不会在连接关闭后卡死；`sessionCatalog.rebuild` 自带 mu。
并发多次 refresh 的可能性在 main 上就已存在（listing ticker goroutine vs readLoop），本 PR 不新增该类别。

**(c) 不引入陈旧识别（TTL 语义正确）** — 成立。
- 表复用条件从「pid 集合键相等 + TTL」改成「TTL 未过期 **且** 请求的每个 pid 都在 `snap.comm` 里」——
  这只会**变严**（缺 pid 必刷新），不会让更旧的表被复用，TTL 上界仍是 10s。
- 识别结果缓存键是 `(pid, snap.start[pid])` + `until = now+10s`：pid 复用会因 starttime 变化而 miss，
  `start == ""` 时干脆不写缓存。最坏陈旧度 ≈ 快照自身的 10s，与 main 的 procTTL 同量级，**无新陈旧类别**。
- `lstart` 解析核过：`LANG=C/LC_ALL=C`（Go os/exec 去重保留后出现者 ⇒ C 生效），格式 `Thu Aug 21 21:56:01 2026`，
  从 fields[2] 起找首个 4 位纯数字 token = 年份，索引恒为 6（Day/Mon/DD/HH:MM:SS 不可能是 4 位纯数字），
  comm 取年份之后全部字段 ⇒ 带空格的路径（`.../Google Chrome`）不会被截断。本机 `ps` 实测格式相符。
- 095 第 4 条（过滤与打标用两张不同时刻 ps 表 ⇒ 假 ChangedSessions）随共用一张 `hits` 一并消除。

## 4. 说明.md 与 diff 一致性

一致。说明列的四条根因、四条修法、fork 前后表格逐条能在 diff 里找到对应；先验红/转绿原始输出均已落盘且路径有效。
说明里 `HEAD=3b23ae657（未 commit）` 是写作当时的事实，封版后为 `8272ec1ff`，内容未变 —— 属陈述时点差异，不算不一致。

## 5. 非阻断观察（不影响本判决，供 land 后跟进）

1. `go c.handleList(t)` 无并发上限：客户端连发 List 会派生任意多个并发 discover+ps 通道。
   readLoop 原本天然串行化了它们。建议后续加单飞/限流（一个连接同时最多一个在途 List）。
2. `procFinder.ident` 只增不删（TTL 过期不删键）。键是 pane pid，长驻 daemon 下随 pid 轮换缓慢增长——
   量级很小，但与「资源有界」红线的字面要求有距离，扫一次过期键即可。
3. `Identify(pid<=0)` 现在会走进 `IdentifySet` → 冷启动时可能白 fork 一次全表（旧版直接 return ""）。
   非风暴级，顺手补个前置守卫即可。
4. 被审快照里的 pane pid 若已死亡，`snapCovers` 永远为 false ⇒ 该 tick 必刷新全表；
   仍是 ≤1 次/tick，满足判据，但 TTL 的省 fork 效果在有僵尸 pane 时会失效。

以上四条都不是「该做没做/做了做错」，是新形状带出的边界，故判 supports 而非 inconclusive。

---

## 复核记录（账本 revision 47，同一任务重发）

重发后我重新核了「被审对象有没有变」，结论：**没变，判决维持 supports**。

- `pr/listing-ps-storm` HEAD 仍是 `8272ec1ff`，worktree `.worktrees/hl1.lsfix` 干净（无新的未提交改动）。
- `git diff --stat main...pr/listing-ps-storm` 与 r46 时逐字相同：6 文件 / +277 −47。
- main 从 `3b23ae657` 前进到 `67be1154b`（096 契约：停更横幅裸调试串），
  `git diff --name-only <merge-base>..main -- server/` **为空** ⇒ 与本 PR 零重叠，不存在语义/冲突交互，
  r46 的构建与测试证据（含 `-race` 与 `go test ./...` 十包全绿）在当前 main 下仍然有效。
- 未重跑测试：代码与依赖面均未变动，重跑不会产生新信息；如账本要求"每次重发都要新鲜跑一遍"，请明示，我照办。

其余结论、四条非阻断观察（§5）不变。

---

## 复核记录（账本 revision 49，同一任务第三次重发）

**判决维持 supports。** 被审对象仍无任何变化：

- `pr/listing-ps-storm` HEAD 仍是 `8272ec1ff`；worktree `.worktrees/hl1.lsfix` 干净。
- `git diff --stat main...pr` 与 r46/r47 逐字相同：6 文件 / +277 −47。
- main 已前进到 `b3dd3a01d`（097 契约：对话页重做接入 + 主题一致性），
  `git diff --name-only <merge-base>..main -- server/` **仍为空** ⇒ 与本 PR 零重叠。
- `.team/nodes/hl1-lsfix/说明.md` 未被改动（3203 字节，mtime 21:56），先验红/转绿原文路径依旧有效。

本轮**重跑了一次新鲜的门**（回应 r47 里我自己留的那条说明）：
```
go build ./... && go vet ./internal/api/ && go test ./internal/api/ -run 'TestListingTick|TestHandleListDoesNotBlock|TestProcFinder' -count=1 -race
ok  github.com/agentmirror/agentmirror/internal/api  2.221s
```
仍绿。`go test ./...` 全量未再跑（r46 已跑过且代码零变动），如需每轮全量请明示。

其余结论、四条非阻断观察（§5）不变。
