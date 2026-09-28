# 终审 VERIFICATION · fix-connected-idle-economy

终审员：`v-connected-idle-economy`（处女独立终审员，一次性，交件即退役）
日期：2026-08-10
裁定额（FREEZE，未调任何阈值/窗口）：online_mean_cpu ≤5.0%，三态窗口各 ≥60s，fairness ≤60s，fleet 3/27/200，27 真实 pane。
隔离红线：未连接/扫描/attach/signal 生产 PID 3393 与用户/Team Agent tmux；`ps -axo` 只读快照仅沿自有 pane roots 分类，原始进程表未落盘/上屏。

---

## 一、独立复跑记录（全部本次重跑，未复用旧结果）

| 验收/定向 | 命令 | exit | 结果 |
|---|---|---|---|
| taskbook acceptance 1（server 全量回归） | `bash -lc 'env -u TEAM_AGENT_* bash -lc "cd server && go test ./internal/api/... ./cmd/..."'` | 0 | PASS |
| taskbook acceptance 2（隔离真进程三态 e2e） | `bash -lc 'env -u TEAM_AGENT_* bash e2e/connected-idle-economy.sh'`（在 throwaway 副本 `/tmp/am-cidle-verif.*` 中运行，原 evidence 目录零改动） | 0 | PASS |
| race 定向 | `bash -lc 'env -u TEAM_AGENT_* bash -lc "cd server && go test -race ./internal/api -run \"TestConnectedIdleEconomy\" -count=1 -v"'` | 0 | PASS（3/27/200 fairness 子项全绿） |

本轮独立三态实测（throwaway 副本产出，原 `metrics.json`/`measurements.tsv` 未动）：

```
zero_connection                wall=60.067374080s cpu_delta=0.000000s mean_cpu=0.000000% capture=0 rate=0.000000/s panes=27
connected_zero_subscription    wall=60.044826880s cpu_delta=1.450000s mean_cpu=2.414862% capture=240 rate=3.997014/s panes=27
connected_single_subscription  wall=60.061739008s cpu_delta=1.370000s mean_cpu=2.280986% capture=240 rate=3.995888/s panes=27
```

原 evidence（`metrics.json`）独立复算同样成立：零连接 0.000%，已连接零订阅 2.481746%，已连接单订阅 2.398631%，均未四舍五入 ≤5.0%；三态窗口均 ≥60s；两在线态 capture 率 ≈3.997/s（≈ 恒定 4 次/秒上界）。

## 二、逐项核验结果

1. **CPU time / 墙钟 / 公式** — 独立重算一致（delta CPU / wall ×100），两在线静默态均 ≤5.0% 冻结线。✅
2. **27 pane** — 脚本断言 `actual_panes == 27`，三态 pane 数均 27；本轮复跑确认。✅
3. **3/27/200 FIFO 公平性 + 最坏 ≤60s** — `TestConnectedIdleEconomySamplingRateFairnessAndVisibility` 对 3/27/200 全绿；200 pane 首轮调度算术 50s + 3s 单次预算 + 2s 下一 listing = 55s ≤ 60s。代码 `state_wiring.go:221-266` fleet-wide FIFO + token bucket（250ms/token、burst 8），`maxConcurrent=16` 仅作重叠上界不再承担频率限流。✅
4. **capture-pane 派生率 / 4s 上界** — 两在线态持续 ≈4 次/秒，零连接 0 次；token bucket 是唯一放行路径，`State()` 纯缓存读取 + 去重入队（`state_wiring.go:186-215`）。✅
5. **scoped discovery：nil 生产默认 + 显式 fail-closed** — `discoverer.go` `resolvedDiscoverySocketDirs`：nil 且无 e2e env ⇒ 返回 nil ⇒ 走生产 `discovery.Discover`（测试 `TestConnectedIdleEconomyProductionDiscoveryDefaultIsUnchanged` 钉死）；显式 slice/空 slice ⇒ `DiscoverWithDirs` fail-closed。e2e 全部 594 次 tmux 目标仅落在 `/tmp/am-cidle.DhGORv/...` 自有 socket 目录，`isolation-violations.log` 空，越界目标 0。✅
6. **生产零触碰** — 未连接/扫描/attach/signal 生产 PID 3393；未打开用户/Team Agent tmux socket；`tmux-targets.tsv` 与 `capture-pane.log` 唯一目标均为自有隔离 socket。✅
7. **red-first / baseline replay** — `red-first.log` 旧实现 3/27/200 pane 扇出 3/27/187 次（expected exit 1）；`baseline-stable-red-replay.log` 在 source_sha throwaway archive 上稳定缓存+TTL 全过期扇出 3/27/151 次（expected exit 1）。红测先行成立。✅

## 三、红项（契约明确判 fail，未修）

### 红项 1 · FIELD #5「每态结束该态自身零残留」未证——脚本跨三态复用同一 daemon/tmux/runtime

- 证据：`e2e/connected-idle-economy.sh`
  - `:43` 单次 `mktemp -d /tmp/am-cidle.*` 运行时根，跨三态复用；
  - `:301` 单 tmux server（`TMUX_SERVER_PID` 一次性赋值），三态始终存活；
  - `:511-532` daemon 在三态前一次性 `env -i` 启动，期间永不停止/重启；
  - `:665-681` 三态顺序量测仅 `stop_client`（`:673`,`:681`）停止瞬时 client，daemon/tmux/socket/runtime 全程同一实例；
  - 唯一残留核验是 `cleanup()`（`:126-233`），经 EXIT trap（`:235-247`）在最终退出时执行一次。
- 对照 FIELD #5：**「每态结束都必须证明该态自身 client/daemon/tmux/listener/socket/runtime 零残留；仅脚本最终 cleanup 不等价。若当前脚本跨三态复用同一 daemon/tmux/runtime，判 fail。」**
- 判定：脚本跨三态复用同一 daemon/tmux/runtime，且无任何态间残留核验 ⇒ **fail**。每态结束仅对 client 做了清理，daemon/tmux/listener/socket/runtime 的零残留从未在对应态边界被证明。
- 附带说明：最终 `cleanup.log` 显示退出后 client/daemon/tmux/pane PID、listener、runtime handles、socket、runtime tree 全零，out_of_scope=0 —— 这仅证明最终清场，按契约不等价于 FIELD #5 的每态证明。

### 红项 2 · 客户端 token 经 argv（`-token`）进入 helper，非仅 env

- 证据（presence/argv-shape 报告，不打印 token 值）：
  - `e2e/connected-idle-economy.sh:642` `"$CLIENT_BIN" -mode "$mode" -url "ws://127.0.0.1:$PORT/ws" -token "$TOKEN" ...` —— token 以 `-token <value>` argv 形式传给自建 client helper；
  - `:385` 内联 helper `flag.String("token", …)` 从 argv 读取该值。
  - daemon 侧正确：`:517` `AGENTMIRROR_TOKEN="$TOKEN"` 经 env 进入 daemon。
- 对照契约：**「客户端 token 只能经 env 进入 helper，不能在 argv/日志/证据出现；若当前 `-token` argv 持有，判 fail。」**
- 判定：客户端 token 以 `-token` argv 持有（argv-shape 确认），未走 env ⇒ **fail**。
- 附带说明（presence-only）：token 值未出现在 `daemon.log`（仅 `token_source=explicit`）、`client-*-subscription.log`（仅 `ready mode=…`）或 evidence 中；红项是 argv 形状本身。

## 四、裁决

- 独立复跑（server 回归、隔离三态 e2e、race 定向）全部通过，数值/限流/FIFO/scoped discovery/零越界/红测先行全部达标。
- 但红项 1（FIELD #5 每态零残留未证，跨态复用）与红项 2（客户端 token argv 持有）均为契约明文判 fail 项。
- **最终裁决：FAIL**。按终审纪律仅记录 file:line/argv/证据，不修实现、不调阈值、不 push/commit。
- 建议（供 leader/下席参考，不在本席修复）：将三态拆为三个独立 daemon+tmux+runtime 生命周期并在每态边界核 listener/socket/进程全零；client token 改为 `CLIENT_TOKEN` env 注入并保持 argv 无 token 形状。
