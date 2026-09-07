# Core PR73 + PR86 合并交付证据

以下两段分别保留 PR73 与已合入 main 的 PR86 原始 DELIVERY.md 收据；状态字段保留其各自产生时的历史语义。

---

# PR73 T-A 交付

状态：`ci_verified_unaccepted`；等待 leader/独立验收席复核，作者不宣称最终组合验收。

在 owning PR73 上撤掉 health 对合法 activity motion 的否决，保留 health 独立元数据轴。当前 PR 仍 OPEN，未 merge/部署/产 APK。

- PR：[Florious95/corral-core#73](https://github.com/Florious95/corral-core/pull/73)
- 当前 head：`cfb8d32a11cd3a97d2a096d57b09d7bc184924d9`
- 当前 source tree：`05fe74dc4996298c5292e49c8a9fda1712b32d1a`
- base：`pr/status-core-nodeprobe-863c` @ `23e0c4f1529b7b51192d6e65ecc62b3b517e2cf5`
- 精确 C73 起点：`1642902e7b9fe002f72a0a16a6963be2ef865141`，tree `650f7ddeca62d2a826dfa5f993691f35a9fc1f35`

## 写界

- `Models.kt`：`sessionRowMotion(activity, isOnline)`；删除 health 形参及完整 health veto。
- `SessionRow.kt`：caller 改为只传已解析 status 与显式 online；health 仍作为 SessionItem 元数据。
- 规则：online + working → Working；online + idle → Idle；online + unknown → None；offline 全 None。
- DTO 畸形 health 仍归 `unknown`，不改变合法 activity；activity/status 冲突 fail-closed 逻辑未改。
- 未改 provider 白名单/特例、布局、图标、节奏、网络订阅、收藏、名称投影及其他席位文件。

## 红绿

- 精确 C73 abnormal 红：CI run `34089083105`，产品未改，具名 matrix test 退出 1，`1 test completed, 1 failed`，失败集为 `ExternalSessionStatusUiTest > fourAxisProjectionUsesOnlyOnlineAndActivity`；41 actionable tasks executed。见 `RED-REPORT.md`。
- 修复提交：`c33317d7846b6fafc73718b5397780de3e7185c1`。
- 当前绿：CI run `34090682711`，Gradle 退出 0，`BUILD SUCCESSFUL`，41 actionable tasks executed，JUnit 20/0/0/0（tests/failures/errors/skipped）。见 `GREEN-REPORT.md`。
- 命令、退出码、执行数、日志 SHA、未执行项均在 `A73-RECEIPT.json`。

## 覆盖与边界

- 完整 online × activity(working/idle/unknown) × health(normal/unknown/abnormal) 矩阵通过。
- DTO→`toL2Entry`→`toSessionItem`→真实 Compose `SessionRow` 的 abnormal-health working row 同 ref 且连续帧变化；idle 静止、unknown 安静。
- Compose 证据是 hosted JVM/Robolectric 测试，不冒充 AVD/真机/最终 APK。最终 Grok 循环、组合 APK 和跨面收藏验收留给独立席位。
- 本机未运行 Gradle/Go/Rust；Grok 20 GiB 门未满足，未启动同步/构建。未读凭据、真实会话、生产日志或真实 socket。

## 架构/CI

- basegen 已执行；改动目标包 ArchWiki strict-T3：`dev.agentmirror.app.ui.model`、`dev.agentmirror.app`（UI 子包）均 exit 0。
- 精确 C73 根包检查的 5 条 T3-4 未声明 `@consumes` 为基线既有漂移，未扩大 T-A 写界；详见 `ARCH-RECEIPT.md`。
- 新增的 `.github/workflows/pr73-activity.yml` 仅使用标准 hosted runner、只读 contents 权限、临时公开 debug keystore、Gradle `--rerun-tasks`，无 `pull_request_target`/secrets/自建编排。

附属证据：`RED-REPORT.md`、`GREEN-REPORT.md`、`MATRIX-EVIDENCE.md`、`UI-EVIDENCE.md`、`ARCH-RECEIPT.md`、`REMOTE-CLEANUP-RECEIPT.md`。

---

# T-N 交付收据

状态：GitHub hosted runner 已完成冻结 C70 红、候选 Cargo/corpus、Linux G-SAFE 与 Darwin 交叉平台构建；本收据仍标记 `ci_verified_unaccepted`，不代替 leader 独立验收，不宣称 full-chain PASS。

## 精确坐标

- worktree：`.team/nodes/status-probe-fix/`
- PR：<https://github.com/Florious95/corral-core/pull/86>
- owning branch：`pr/nodeprobe-fg-comms`
- PR base：`pr/status-core-nodeprobe-863c`（C70）
- source checkout HEAD：`4f77e3bdc2df08c81b5f50c13050cbddc7053b62`
- source checkout tree：`047bfb0539047f592ef64d4d8bf9dfaba4278e20`
- source parent：`5e2cbb2b5fd3a1a832a7d63cf0ac81eb4d4d1cc0`
- merge commit：`cf97d545672689b80fb80460bd3b295f88a50a64`（parents=C86 `81790368e41900a9085c722e89147924f0806dfe` + C70 `23e0c4f1529b7b51192d6e65ecc62b3b517e2cf5`）
- CI run：<https://github.com/Florious95/corral-core/actions/runs/34089915825>（head=`4f77e3bdc2df08c81b5f50c13050cbddc7053b62`，三 jobs success）
- 产品 author diff relative C70：仅 `tools/nodeprobe/**`；测试入口为必要的 `.github/workflows/pr86-nodeprobe.yml`；delivery-only 为 `DELIVERY.md`、`N-RECEIPT.json`；无 server/App 写界。

## 已施工能力

- 从精确 C86 创建独立 worktree，以常规 `--no-ff` 合入精确 C70，保留双方 ancestry。
- 保留 C86 前台 Agent 优先、shell 持 `+` 时仍识别实际在跑 Agent、回 shell 不复活后台残留。
- 用同一 4096 有界/去环 `walk_identity_processes` 同时决定 provider 与唯一 Pi PID 集合。
- 恢复 report schema 1、provider/activity/session_name/health/workspace 四轴、Pi channel schema 2、Web 与错误 envelope。
- 恢复精确 Codex 10 帧、普通标题 idle、未收录 braille unknown；Pi 静态标题不判活。
- 修 Pi channel 缺失/目录不可用/损坏/TTL/未来时间/challenge/PID reuse/零或多进程的 fail-closed 语义；名称矩阵保留同名、单边，冲突/歧义 unknown。
- live probe 仅调用 `tmux list-panes` 与窄 `ps pid,ppid,stat,comm`；不读取 pane body/footer/argv。
- `tools/nodeprobe/tests/g-safe.sh` 是候选实际 binary 的 G-SAFE 调用夹具：私有 socket、结构字段变换、正控 trace 与 capture-pane/危险 ps 反控分离。
- 收入 Git 的 Pi extension 含当前已核 `socket.on("error", () => {})`，保留 lazy lifecycle、heartbeat、settled 结算与 shutdown 清理。
- fixtures 与 footer 共享显式 `NODEPROBE_FIXTURES`；providers 共享显式 `NODEPROBE_PROVIDERS`，坏路径不回退内嵌旧表。

## 改前红与反控

旧 f629 仅在本席自建物理隔离合成 socket 使用；未连接真实/默认/共享/其他队 socket：

- old binary SHA256：`d60cacc86f5ec432ddf8562782b7eb483e46c8b17e45a43d6c48f7f69ec7497f`。
- Codex 10 帧逐帧 `exit=1`、`unknown/codex`：`tmp/pre-red/codex-10-results.tsv`。
- 注入 `capture-pane` 和危险 `ps` 均被反控 wrapper 以 97 拒绝：`tmp/pre-red/trace/old-gsafe.log`。
- old probe 合成 shell sample `exit=0` 但 node 数 0；不把 exit0/JSON 当安全证明。
- C70 边界红 harness 已在 `tmp/pre-red/c70-boundary-harness.md` 具名：`t_n_missing_channel_must_not_be_normal`、`t_n_title_only_name_must_survive`。远端红测待空间恢复后执行，a1 sync 超时仅 status=missing，a2 preflight 因空间 exit=7，均未执行 Cargo。

## 哈希

| 产物 | Git blob | SHA256 | 字节 |
|---|---|---|---:|
| `tools/nodeprobe/pi/nodeprobe-pi-activity.js` | `67c1916ade725f73646f062fbf03d0cc22d6823d` | `51ffcad3f68ac22330d98ba0240b81f41b210939709a91637c917e1555494c27` | 3572 |
| `tools/nodeprobe/fixtures/titles.tsv` | `690e651f445793a36ed1cb6e6f6143e6ce5c989e` | `cff45d25492fdfe9689330c630c80bad20a1f27243e5aae1d93bc57de0a22b58` | 1332 |
| `tools/nodeprobe/fixtures/providers.tsv` | `e93afa54e473e90099f03cb932d9a54798c0dfe4` | `c68f50115b33ae6a6806b463cc27c71d8bbfc92273435a533502e9bb84b8b522` | 481 |
| Darwin candidate binary | `19a33813679337252984a4b20fcf51a39c6e382e` | `e5667b9ebe931de7805a87538e03b9a6ee1fb9cb9250c25282f8b054268226b5` | 885504 |
| Linux CI candidate binary | GitHub artifact（未入 Git） | `c1b60011a4e5a98f8aad7f12293240ac2c92aa960f4955d4534144ed04cdd30a` | 987344 |

Darwin 二进制已从 run 34089915825 下载并收入 `tools/nodeprobe/artifacts/nodeprobe-darwin-arm64`；本席只做 `file`/SHA/`--help` 验证，未本机编译。Git extension SHA 与当前已安装 extension SHA 一致；本席未修改全局文件。

## 判定与风险

- basegen：临时 T-N envelope 编译 exit 0；Rust 无 wiki card，cards=0，原始输出留 `tmp/basegen-T-N*`。
- archwiki：`archwiki check tools/nodeprobe` exit 3 / `partial`，无 blocking finding；报告 `tmp/archwiki-final.json`，未将 partial 说成 PASS。
- 冻结 C70 红：run 34089915825 的 `cargo test ... --lib t_n_c70`，exit 101；2 tests 均失败且具名为 `t_n_c70_missing_channel_must_not_be_normal`、`t_n_c70_title_only_name_must_survive`。
- 候选 Cargo/corpus：46 lib + 1 main + 1 fake_tmux + 0 doctests 全绿；显式 titles corpus command 执行 1 次、无失败。
- G-SAFE：真实 hosted-runner candidate binary 在 `/home/runner/work/_temp/nodeprobe-gsafe/gsafe.sock` 自有合成 socket 调用；正控 trace 2 行（`list-panes`、`ps-narrow`），candidate sample 1，forbidden rejection 0；control 的 `capture-pane` 与危险 `ps` 均 exit 97。证据见 `tmp/gh-artifacts-34089915825/pr86-nodeprobe-linux-evidence/nodeprobe-gsafe/`。
- Darwin：`macos-14` 产出 Mach-O arm64，SHA/size 已入 Git；Linux artifact 仅作 CI 证据。
- GitHub workflow：`.github/workflows/pr86-nodeprobe.yml` 使用 `contents: read`、无 `pull_request_target`/凭据；三 jobs 均 success。旧 Grok Bot a1/a2 空间阻塞保留在 N 收据中，不重用 unit、不降低 20 GiB 门。
- archwiki 仍为 partial（exit 3），未说成 PASS；accepted/full-chain PASS 留待 leader 独立验收。
- 生产/默认/共享/他队 socket、9900、server/App、全局 binary/extension 均未触碰。
- 完整 N-RECEIPT：`N-RECEIPT.json`；状态与未宣称项如实记录。
