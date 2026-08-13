# C1 delta 背压合并 —— 实现留档（2026-08-14）

> 任务 `perf-delta-backpressure-merge`。本文件是 C1 开发席的实现留档：
> 关卡1（sendCh 会不会满）由 probe 席判定。**若判定「不会满」，本实现不上线**，
> 本文件作为「未来真遇背压时的现成方案」与两个认知陷阱的记录，供后续参考。
> 未提交、未上线。相关代码在 server/internal/api/（未 commit 的工作区）。

---

## 一、改了什么（未提交）

`server/internal/api/ws_conn.go` 的 `sendMirror` 从「sendCh 满时丢弃 delta」改为
「并入背压缓冲，writeLoop 排空后 flush 成大帧」。

关键约束（简报 §一）：
- 合并后单帧 ≤ 1 MiB
- **不引入定时器**：合并的必须是「本就在排队的东西」，加延时就变味
- 队列不满时行为与现状完全一致（零回归）
- 丢弃计数语义随丢弃路径同步订正（纪律⑨）

## 二、设计（最终形态）

### 2.1 多 ref 缓冲（pendingStream）

```go
type pendingStream struct {
    ref     string
    payload []byte  // 该 ref 连续 delta payload 的拼接（≤1MiB）
}
// wsConn:
pending   []pendingStream   // 按到达顺序
pendingMu sync.Mutex
```

- `mergePendingDelta`：同 ref 续接进同一条目；**ref 切换或超限时 seal 旧条目**（入队
  失败则条目保留），新 payload 开新条目。**绝不 drop**。
- `flushPending`：writeLoop 每轮消费后调用，遍历条目逐个重建 KindDelta 帧入队，
  失败条目保留下轮。

**为什么必须多 ref 缓冲**：单缓冲在「队列满 + 第二个 ref 到达」时，seal 旧缓冲失败
（队列仍满）后新 ref 只能丢弃——违反关卡2「不丢字节」。多 ref 缓冲让每个 ref 各自
累积，flush 按到达顺序发各 ref 帧。这是 test 席红测 `TestDeltaMergeRefIsolationOnWire`
抓到的真 bug 的正确解。

### 2.2 零延迟唤醒（pendingWake）—— 时序陷阱的修法

**陷阱**：writeLoop 排空 sendCh 后阻塞在 `<-c.sendCh`，若 pending 还有数据（最后的
背压 delta）但 Agent 恰好输出结束、不再有新 delta，则**没有任何触发源 flush**——
用户屏幕停在倒数第二段，直到下次有人打字。形状 = 「最后一段没显示 / 要按一下才出来」。

**修法**：`pendingWake chan struct{}`（cap 1）。`mergePendingDelta` 每次入缓冲投递一次
信号，writeLoop 的 select 增加 `case <-c.pendingWake` 分支。**「入缓冲这个动作」本身
就是唤醒源**——零延迟、非定时器。

### 2.3 死锁安全

writeLoop 用 `pendingMu.TryLock` flush：拿不到锁（relay 正在并入）就跳过本轮，绝不阻塞
writer——否则 writer 停下来，等入队/重排的 relay 会死锁。

## 三、关卡5：计数语义订正

| 计数器 | 语义（作用域） |
|---|---|
| `DeltasBuffered` | 合并路径命中次数：sendCh 满时 delta 并入缓冲（新增） |
| `DeltasDropped` | 残余丢弃路径：防御性丢弃（保留；多 ref 缓冲下当前实现恒 0） |

纪律⑨原文意：仪表要说清它测的是什么。**DeltasDropped 不能删**——丢弃路径即使收窄
也是真实失败模式，必须可见。

## 四、验证（全绿，未提交）

- 关卡2（test 席红测，先红后绿）：`TestDeltaMergeClientBytesEquivalent`（1577472 字节
  逐字节等价）、`TestDeltaMergeIdlePathUnchanged`（零回归）、`TestDeltaMergeRefIsolationOnWire`
  （双 ref 隔离）。e2e `delta_merge_bytes.test.js` 绿。
- 关卡3：`go test ./...` 全绿。
- 关卡4：strict-t3 PASS exit 0。
- `-race`：通过。

## 五、两个认知陷阱（leader 已认可写入文档）

1. **pendingWake 时序陷阱**：写侧排空队列后阻塞，最后的缓冲数据可能无触发源。
   解法是「入缓冲动作当唤醒源」，不是定时器。
2. **ref 隔离 bug**：单缓冲 ref 切换 seal 失败会 drop 第二个 ref。解法是多 ref 缓冲，
   每 ref 独立、绝不跨流拼接（AnsiParser 顺序状态机语义）。

## 六、与链路改善的关系

用户装官方 Tailscale 后打洞成功（`snapshot-03-direct.txt`：direct 直连、ICMP avg 147ms
从 1221ms 降、0% 丢包）。链路变快 → 256 槽位更填不满 → C1 可能不上线。本实现作为
「未来真遇背压」的现成方案保留。

---

## 七、C1 留下的三条认知资产（leader 2026-08-14 收口指令）

1. **pendingWake 时序陷阱**：合并缓冲若只靠 writeLoop 每轮触发 flush，sendCh 排空后
   writer 阻塞在 `<-sendCh`，缓冲里最后一段永远发不出去（用户形状：「最后一段没显示 /
   要按一下才出来」）。解法是让「入缓冲这个动作本身」当唤醒源——`pendingWake` 通道，
   入缓冲即投递信号，writeLoop select 监听。零延迟，非定时器。
2. **ref 隔离 bug**：单缓冲在 ref 切换时 seal 失败会丢掉第二条流，内容还会串
   （AnsiParser 顺序状态机语义破坏）。解法是多 ref 缓冲（`pendingStream` slice），
   每 ref 独立条目、按到达顺序 flush，绝不跨流拼接。
3. **macOS loopback 自动调窗 → 纯黑盒造不出背压**：对端不读 + 2KB 读窗口 + 2000 行突发
   都被内核 socket 缓冲整包吸收，writeFrame 从不真正阻塞，sendCh 从不积压。要验证
   背压相关逻辑，必须在真实链路或能绕过自动调窗的装置（如 e2e/delay_proxy.py 的
   SO_RCVBUF 缩窗版本）上测，本地 loopback 的黑盒实验会给出假阴性。

---

## 八、C1 验收（build tag 隔离的红测，leader msg_42656bdce350 裁定）

关卡1 halt 后，test 席红测（`server/internal/api/delta_merge_scenario_test.go`）加
`//go:build c1_backpressure_merge` 隔离：默认 `go test ./...` 排除它（全绿，不阻塞
常规 CI），未来重启 C1 时显式运行验收。

**验收命令**：

```sh
cd server && env -u TEAM_AGENT_* go test -tags c1_backpressure_merge ./internal/api/ -run TestDeltaMerge -v
```

**预期结果（当前 HEAD，无 C1 实现——红是预期，红了才对）**：

| 测试 | 无实现 HEAD | C1 实现后 |
|---|---|---|
| TestDeltaMergeClientBytesEquivalent | **红**（1577472B 丢帧） | 绿（逐字节等价） |
| TestDeltaMergeWireCap1MiB | **红**（1MiB 上限帧丢） | 绿 |
| TestDeltaMergeRefIsolationOnWire | **红**（双 ref 丢字节） | 绿（各流独立） |
| TestDeltaMergeIdlePathUnchanged | 绿（零回归） | 绿 |

谁要重启 C1，照这三条验收：三条红转绿、idle 恒绿，即实现正确。

e2e 零回归闸 `test/cases/delta_merge_bytes.test.js` 合并前后都绿（不红不碍事），
保留即可。
