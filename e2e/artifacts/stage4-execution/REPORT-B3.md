# 阶段四 B3 批次 REPORT（宿主 T 对账）

> 执行席：`w-stage4-b3`（阶段四 B3 宿主 T 对账执行席，一次性，交件即退役）
> 日期：2026-08-11
> 执行权威：`docs/stage4-execution-plan.md` §1（C 组 + D 组）
> 知识基底：`.team/nodes/stage4-b3-host-t/CLAUDE.md` + `FIELD.md`
> 通道：H（宿主 T 对账，零设备依赖）
> 隔离：自建 `TMUX_TMPDIR` + 高端口 daemon（:19983 → 后移 :19985），`env -u TEAM_AGENT_*` 净化，
> 绝不触碰生产 daemon（pid 3393，:9900）与用户真实 tmux；不碰 :app/:terminal Gradle 模块。

---

## 总判：6 条用例全部 PASS

| 用例 | 通道 | 结果 | 判定手段 |
|---|---|---|---|
| C4 token 轮换文档化 | H | **PASS** | 文档断言（protocol §9.1 + server/README）+ 运行时实测（隔离 HOME） |
| C5(H) 锁中文 README 明示 | H | **PASS** | 文档断言（README.md 界面语言节） |
| D1 静默经济 | H | **PASS** | 零连接采样 12/12 点 CPU 0.0% + 零子进程；阳性对照（已连接态抬升） |
| D2 进程卫生 | H | **PASS** | 二启显式失败（单实例守卫/端口冲突）+ kill 后零监听零孤儿 |
| D3 资源有界 | H | **PASS** | 上传上限文档+代码+测试三面齐；日志轮转缺失备注 |
| D4 可达性常识 | H | **PASS** | 启动横幅候选断言不含 198.18/169.254/utun/awdl/bridge |

---

## C4 token 轮换文档化 — PASS

**判定目标**：README/docs 含「删 token 文件重启即全量吊销」说明（017 R-4；D-11 已修，复验）。

**证据**：
- `docs/protocol.md` §9.1「token 生命周期与全量吊销」：`停止 daemon、删除该文件并重启会生成新 token，从而让全部旧 App 配置在下一次认证时失效`（protocol.md:371）。
- `server/README.md`「配对 token 吊销与轮换」：`要全量吊销已配对 App：先停 daemon，删除该 token 文件，再启动 daemon；启动时会生成新 token，所有仍持有旧 token 的 App 都会认证失败`（server/README.md:72-74）。

**运行时生效实测（隔离 HOME，不碰生产 token）**：
- 隔离 `HOME=/tmp/st4-b3-host-t/home-isolated` 起 daemon → 生成 token 文件，权限 `0600`，长度 26。
- kill 后删 token 文件重启 → 新 token `GLXM23… → G76NXL…`，旧 App 将认证失败。
- 生产 token 文件（`~/Library/Application Support/agentmirror/token`）仅 `ls` 确认存在，未读未动。

**阳性对照**：真文本 `删除该文件并重启会生成新 token` 命中 1 次；假节点 `ASSERT_C4_NEVER_EXISTS` 命中 0。单测 `TestEnsureTokenGeneratesPersistsReuses` 覆盖生成/0600/重启复用。

**归因**：PASS，无归因。

---

## C5(H) 锁中文并 README 明示 — PASS

**判定目标**：README 含「当期锁中文」明示（017 R-6；D-12 已修）。U 面（各屏 UI 全中文）归模拟器批次 B2。

**证据**：
- `README.md` §「界面语言：当期锁定中文」：`产品界面（Android App 与终端交互文案）当期锁定中文`，引用 `017 R-6` 裁定「当期锁中文并在 README 明示；抽取翻译后置」（README.md:67-79）。
- `server/README.md` §「界面语言」同步明示（server/README.md:132-137）。
- 需求基原文 `requirement-base/entries/017-场景审计八项裁定.md` R-6：`当期锁中文并在 README 明示；抽取翻译后置`。

**阳性对照**：真文本 `R-6.*当期锁中文` 命中 1 次；假节点 `ASSERT_C5_NEVER_EXISTS` 命中 0。

**归因**：PASS。U 面（UI 文案全中文截图目检）不在本批通道，已在 REPORT 标注为 B2 批次职责。

---

## D1 静默经济 — PASS

**判定目标**：隔离 daemon 全部断开（零连接）观察，CPU 趋近 0、无固定频率派生 tmux 子进程（红线1；fix-daemon-idle-cpu 后复验）。

**零连接采样（第一轮，daemon pid 65712, :19983）**：12 采样点（30s 间隔，6 分钟），全部 `cpu_pct=0.0`、`n_child=0`。

**阳性对照（关键，证明「0」非采样工具损坏）**：已连接态（自建 WS client auth+list）下 daemon 周期性派生 `tmux` 扫描子进程（瞬时 ~0.1s，密集采样 0.1s 间隔捕获 3 次子进程=1），CPU 抬升至 0.6–1.8%；断开后回落到 0。机制确认于 `server.go:230-255` listingLoop idle-gate：零连接 park 不扫描，0→1 唤醒。

**实现佐证**：`server.go` idle-gate + `state_wiring.go`（零客户端无 state 子进程）；`TestConnectedIdleEconomySamplingRateFairnessAndVisibility` 三态（零连接/已连接零订阅/已连接单订阅）CPU ≤5.0% 已由 fix-connected-idle-economy 终审验证。

**重跑确认（最终数据）**：第一轮采样中 19:56:04 采样点 CPU=1.6% 系我的阳性对照连接撞上（已连接态），非零连接真实值。改用独立命名空间（daemon 54093, :19985）重跑干净零连接采样 6 分钟，**12/12 点全部 `cpu_pct=0.0`、`n_child=0`**（`D1-samples-clean.tsv`）。与阳性对照「已连接态 CPU 抬升至 0.6–1.8% + 周期性子进程派生」形成明确反差 ⇒ D1 判定成立。

**归因**：PASS。注：采样期间隔离 daemon（pid 64036）一度被外部 SIGTERM（并行 B1 批次 clean-run.py 超时进程管理波及，watchdog 无 kill 逻辑），改用独立命名空间规避后完成重跑，非产品缺陷。

---

## D2 进程卫生 — PASS

**判定目标**：①同端口二启显式失败（单实例守卫，二启报错）；②收尾 `lsof -i :<port>` + 进程表断言零监听零孤儿（红线2）。

**① 二启显式失败（两路径全验）**：
- 同 state-dir 二启：`single-instance guard refused startup: another agentmirrord instance is already running (lock held: /tmp/st4-b3-host-t/state/agentmirrord.pid)`，退出码 **1**（`d2-second-launch.log`）。
- 异 state-dir + 同端口二启：单实例守卫被绕过（state2 拿锁成功），监听阶段失败：`tsnetd: LAN listen on "0.0.0.0:19983": bind: address already in use`，退出码 **1**（`d2-second-launch-port.log`）。
- 机制：`pidfile.go` advisory flock（`syscall.Flock LOCK_EX|LOCK_NB`），flock 是权威，pidfile 内容仅提示。

**② 收尾零残留**：
- SIGTERM 停隔离 daemon（pid 87425）：`shutting down` 优雅退出，`lsof -i :19983` 后为空，进程消失，无孤儿后代（D2 收尾自证段落）。

**阳性对照**：二启错误文本 `another agentmirrord instance is already running` 非空且含 pidfile 路径（T 对账：错误输出非空含预期文本）。单测 `pidfile_test.go` 覆盖。

**归因**：PASS。

---

## D3 资源有界 — PASS（日志轮转说明缺失备注）

**判定目标**：上传目录/日志的上限或轮转说明存在（红线3；D-13 fix-upload-auth 已修，复验生效）。

**上传目录（三面齐）**：
- **代码**：`options.go:26/31` `defaultMaxUploadBytes = 20<<20`（20 MiB）、`defaultMaxUploadDirBytes = 1<<30`（1 GiB）；`upload.go` 目录总量硬上限 + HTTP 507 `storage_limit_exceeded`。
- **文档**：`docs/protocol.md` §8 `单文件默认不超过 20 MiB，上传目录内的常规文件总量硬上限为 1 GiB；本次写入会越过目录上限时返回 HTTP 507`；`server/README.md` 同述。
- **测试**：`TestUploadDirectoryLimit`（api_test.go:383-427）断言 507 + `storage_limit_exceeded`。

**日志**：`server/README.md` §日志仅描述 slog 结构化输出，无日志轮转/上限说明。此为 `docs/scenario-coverage.md:194` 已记录的已知未自证项，非本批新缺陷，标注后置（aging-longrun 补轮转说明）。

**归因**：PASS（上传上限复验生效）；日志轮转说明属已知后置项，单独标注不阻塞本用例判定。

---

## D4 可达性常识 — PASS

**判定目标**：daemon 启动横幅候选地址清单→断言不含 198.18/169.254/utun/awdl/bridge（红线4；R-003 缺陷 A 未回归）。

**本机硬环境（R-003 同类）**：
- 默认路由走 `utun4`（198.18.0.1，fake-IP TUN）——`defaultRouteSource()` 因 `classifyIP(198.18.0.1)==""` 拒绝，正确 fallback。
- 存在 `en7` 169.254.7.232（link-local）+ 多 utun/awdl/bridge/llw 接口。

**启动横幅实测（隔离 daemon :19983）**：
```
服务端 ws 地址 : ws://10.20.55.20:19983/ws     [lan]  (feth2979)
  ws://192.168.31.116:19983/ws   [lan]  (en0)
```
候选仅 2 个 LAN 地址，**不含** 198.18/169.254/utun/awdl/bridge/loopback。

**机制**：`probe.go` `classifyIP` 排除 link-local、RFC 2544（198.18/15）、IPv6；`DetectAddresses` 排序 + loopback 兜底；`printOnboardingAll` 明文列出非 loopback 候选。单测 `candidates_test.go` 覆盖（fix-pairing-candidates）。

**阳性对照**：横幅候选 `grep -E '198\.18\.|169\.254\.|utun|awdl|bridge'` 命中 0（`D4-banner-candidates.txt`）；真文本 `ws://` 命中 3（主选 + 2 候选）。

**归因**：PASS。R-003 缺陷 A（fake-IP TUN 198.18.0.1 入选）未回归——默认路由虽走 utun4，但被排除后正确回落到真实 LAN 地址。

---

## 阳性对照汇总

| 判定类 | 对照 | 结果 |
|---|---|---|
| T 文档断言（C4/C5/D3） | 真文本命中 + 假节点 `ASSERT_*_NEVER_EXISTS` 命中 0 | 全部真>0 假=0 |
| D4 横幅候选 | `ws://` 命中>0 + 排除项正则命中 0 | PASS |
| D2 二启 | 错误文本非空含 pidfile 路径，退出码 1 | PASS |
| D1 静默经济 | 零连接 0.0%/0 子进程 vs 已连接 CPU 抬升 + 子进程派生 | PASS |
| C4 运行时 | 删 token 重启新 token（GLXM23→G76NXL） | PASS |

---

## 未验证清单（016d，不计入已验收）

- **C5 U 面**：各屏 UI 文案全中文截图目检 → B2 模拟器批次（本批 H 面已验 README 明示）。
- **D1 长时老化**：>6 分钟的长时静默曲线 → 后置 aging-longrun（perf-scenarios E2）。
- **D3 日志轮转**：daemon 日志轮转说明 → 后置（scenario-coverage 已知项）。
- 真机维度（相机/Doze/多网卡）非本批通道。

## 隔离自证

- 全程未触碰生产 daemon（pid 3393, :9900，仅 `lsof` 只读确认存在）。
- 未触碰用户真实 tmux（仅自建 `TMUX_TMPDIR` socket）。
- 未读 `.team/current/profiles/*.env` 密钥原文。
- 证据中 token 已脱敏为 `<TOKEN>`。
- 不碰 :app/:terminal Gradle 模块，零编译冲突。
- 禁 git commit/push 遵守。

## 零残留收尾（实测自证）

本席自建全部进程/端口/tmux 已清零：

- **本席 agentmirrord**（`/tmp/st4-b3*` 路径）：`ps -axo ... | grep agentmirrord | grep /tmp/st4-b3` → 零残留。
- **本席隔离 tmux**（`st4b3` / `d1iso` session，`/tmp/st4-b3-host-t` + `/tmp/st4-b3-d1` socket）：`kill-server` 后无 session。
- **端口**：19983（本席第一轮）与 19985（D1 独立轮）在 kill 后零监听。**注**：19983 现被并行 B1b 批次 daemon（pid 79985, `/tmp/st4-b1b/agentmirrord`, 显式 token）占用——该进程启动于本席清理完成后，属并行批次隔离环境，非本席残留，本席不触碰。
- **WS client / 采样脚本**：`pgrep -f wsrun/wsc`、`pgrep -f d1-sample` → 空。
- **孤儿进程**：本席所有 daemon 均 SIGTERM 优雅退出（日志 `shutting down`），无孤儿后代。
- 生产 daemon（pid 3393, :9900）与用户真实 tmux 全程未触碰。

## 与并行批次的边界

- 本批交付物命名 `REPORT-B3.md` + `D1-*`/`D4-*` 前缀，与并行 B1/B2 批次的 `REPORT.md`/`A1-*.png` 等不冲突。
- 并行 B1b 批次 daemon 占用 19983/共享 `e2e/artifacts/stage4-execution/` 目录，均为各自隔离环境，互不触碰。
