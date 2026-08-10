# 结账复跑判定书（recheck-settle-20260811）

- 复跑者：`w-recheck-settle`（一次性判卷席位）
- 时间：2026-08-11 00:50 +08:00
- 结论：**5 案 pass 可入库，1 案 red**（`perf-thresholds-enforce`，仅 acceptance 路径错配，实现正确）。

## 阳性对照总览（`exit 0` 不算证据，全部配证）

- **Gradle UP-TO-DATE 陷阱已触发并绕过**：第一次 `-q` 无参运行 rc=0，但全部 51 个测试 XML
  mtime=23:43 早于启动 ⇒ 根本没跑测试。加 `--rerun-tasks` 强制真执行后：42 app + 9 terminal
  XML 全部 fresh（00:08:20），**345 + 67 用例，0 失败 0 错误 0 skip**。
- 五个新红测文件逐个核对计数非零、fresh、目标用例在场：
  `PairingUxTest` 2 / `CameraPermissionCardTest` 2 / `AttachmentButtonTest` 1 /
  `AttachmentNameTest` 4 / `TermGestureDirectionTest` 1。
- Go 全程 `-count=1` 禁缓存：`internal/api` 66 个 `=== RUN`，`internal/pairing` 26 个；
  goal 点名用例单跑见 `=== RUN` + `--- PASS`。
- e2e 隔离套件：高端口 62792 + 自建 `TMUX_TMPDIR`，生产 daemon pid 3393 / `:9900` 未触碰，
  8 项零残留全 true。

---

## fix-recovery-baseline — **PASS**

- acceptance `go test ./internal/api/...`（原样 + `-count=1`）rc=0。
- 阳性对照：`TestDiscoveryRecoveryReachesConnectedClientFromStartFailure`
  `-run -v` 见 `=== RUN` / `--- PASS (0.05s)`；源码 `t.Skip` 已完全移除。
- goal 核对：`server.go publishListing` 现对空 seq-1 listing 做 recovery diff（`prev==nil && prevSeq==0`
  才建基线），首个成功扫描推 delta、seq 推进，不再静默滞留空列表。**met**。
- 窄提交：`server/internal/api/server.go` + `discovery_failure_test.go`。

## fix-dogfood-pairing-ux — **PASS**

- acceptance 两条（app:testDebugUnitTest / go pairing）rc=0。
- 阳性对照：PairingUxTest 2 用例 fresh green。
- goal 核对：
  - D-07 token 不上屏：`manualToken_isAbsentFromVisibleTextNodes` 断言 Text/EditableText 不含哨兵 token。**met**。
  - D-14 重配入口从主导航可达：Workspace 顶栏「设置」→ SettingsScreen「重新配对」→ 配对页，
    单测点击链断言 `nav.showPairing`。**met**。
  - 谎称「设置里有重配按钮」的注释已改真：源码无残留，AgentMirrorApp 注释与实现相符。**met**。
  - D-11 README/protocol token 吊销与轮换说明已补齐。**met**。

## fix-dogfood-upload-media — **PASS**

- acceptance `app:testDebugUnitTest` rc=0（345 例 0 失败）。
- 阳性对照：AttachmentNameTest 4 / AttachmentButtonTest 1 / CameraPermissionCardTest 2 fresh green。
- goal 核对：D-03 真实 displayName + MIME 补扩展名（无扩展/中文/重名/回退四用例）；
  D-02 拍照直传入口存在且可点；D-01 二次拒绝可见原因 + 去系统设置引导。**met**。

## fix-dogfood-term-ux — **PASS**

- acceptance 两条（app:testDebugUnitTest / terminal:test）rc=0。
- 阳性对照：TermGestureDirectionTest / WorkspaceViewModelTest / CharWidthTest / WideCharTest
  目标用例全部在场且通过。
- goal 核对：D-04 滚动方向（真实 MotionEvent 下拖断言 `!isFollowingBottom`）；
  D-08 加载态状态机（`isLoading` 与 `isEmpty` 分流 + 断言）；D-09 emoji 双列网格（U+26A0/2705/274C
  双列 + `✅ ❌ ` 空格保留断言）。**met**。

## fix-upload-auth — **PASS**（从零判，席位被杀无证据）

- acceptance 两条（go -count=1 api+pairing / e2e 套件）rc=0。
- 阳性对照：
  - `TestUploadAuthentication` 三分支 missing/wrong/valid 全 PASS，响应不回显凭据。
  - e2e `fix-upload-auth/baseline.json` status=pass，14 条硬门限 0 fail，14 场景全 pass，
    8 项零残留，daemon.log 只含 `token_source=explicit` 无 token 值。
  - **零鉴权 bug 实证**：`git show HEAD:upload.go` 的 `serveUpload` 无任何 token 校验 → 真实 P0。
- goal 核对：docs/protocol.md §8 现含 Bearer 契约（与 WS 同源 TokenValidator、401 + code/reason、
  不回显）；实现与之完全一致；D-13 1 GiB 目录上限 + 507 + README 说明 + `TestUploadDirectoryLimit`。**met**。

## perf-thresholds-enforce — **RED**（从零判，实现正确、acceptance 落点错）

- acceptance #1 `e2e/api-user-scenarios.sh` rc=0：套件 PASS，硬门限基线落
  `e2e/artifacts/fix-upload-auth/baseline.json`，**14 条 hard_numeric_thresholds 全 pass**。
- **acceptance #2 rc=1**：断言读 `e2e/artifacts/test-api-user-scenarios-perf/baseline.json`，
  该文件无脚本写入，`hard_numeric_thresholds` 恒为 `[]` ⇒ 「门限未落盘」。
- goal 核对（实现层全部 **met**）：
  - 五条时延门限 + 三态静默经济门限真写进脚本（1747-1768）；
  - large_output 与 upload 均已非 null（30.6MB/s、p50 1.884/p95 3.005）；
  - 超门限会 exit 非 0 并打印实测 vs 门限（1938-1962 无条件 gate）。
- **gaps（差口，非本席修）**：
  1. acceptance #2 与脚本 ART 目录错配——脚本把硬门限基线写 `fix-upload-auth/`，acceptance #2
     读 `test-api-user-scenarios-perf/`。三选一窄修：(a) acceptance 改指 fix-upload-auth；
     (b) 脚本同时写/拷到 test-api-user-scenarios-perf；(c) ART 恢复 test-api-user-scenarios-perf。
  2. 跨案耦合：同一 `e2e/api-user-scenarios.sh` 被两案共用，一方重指 ART 即破坏另一方 acceptance。
- 窄提交：`e2e/api-user-scenarios.sh` + `fix-upload-auth/baseline.json` + `REPORT.md`
  （含是否需回写 test-api-perf 基线的决策由 leader 定）。

---

## 差口汇总（全部只记账不修，交 leader 定夺）

| 案 | 差口 |
|---|---|
| perf-thresholds-enforce | acceptance #2 读 `test-api-user-scenarios-perf/baseline.json` 但脚本写 `fix-upload-auth/` ⇒ 恒红；两案共享脚本的 ART 契约冲突 |

## 附：正面对照补充说明

- 5 个新红测文件的名字、用例数、fresh 均已在 `verdict.json.positive_control` 结构化记录。
- Go 两包均确证为「真跑」：`-v` 下 66+26 条 `=== RUN`，不存在 `no test files` 假绿。
