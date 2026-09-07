# T-N 交付收据（持续施工）

状态：源码与 PR86 写界已提交/推送；候选远端 Cargo 构建与最终 G-SAFE 尚待 Grok Bot 空间回收后执行，当前不宣称全链 PASS。

## 精确坐标

- worktree：`.team/nodes/status-probe-fix/`
- PR：<https://github.com/Florious95/corral-core/pull/86>
- owning branch：`pr/nodeprobe-fg-comms`
- PR base：`pr/status-core-nodeprobe-863c`（C70）
- source checkout HEAD：`1cc086246e6973da1c570320072f606d7607acf1`
- source checkout tree：`a98793724a5e63a626a240ba40bddd30fa4e4b07`
- delivery docs commit：`016ab1a86bb9b1c5797622637ffa4f36e13dba13`
- merge parents：C86 `81790368e41900a9085c722e89147924f0806dfe`；C70 `23e0c4f1529b7b51192d6e65ecc62b3b517e2cf5`
- scope：相对 C86 仅 `tools/nodeprobe` crate、Pi extension、fixture corpus、同源 tests/docs；无 server/App 写界。

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
| `tools/nodeprobe/pi/nodeprobe-pi-activity.js` | `67c1916ade725f73646f062fbf03d0cc22d6823d` | `51ffcad3f68ac22330d98ba0240b81f41b210939709a91637c917e1555494c27` | 3571 |
| `tools/nodeprobe/fixtures/titles.tsv` | `690e651f445793a36ed1cb6e6f6143e6ce5c989e` | `cff45d25492fdfe9689330c630c80bad20a1f27243e5aae1d93bc57de0a22b58` | 1332 |
| `tools/nodeprobe/fixtures/providers.tsv` | `e93afa54e473e90099f03cb932d9a54798c0dfe4` | `c68f50115b33ae6a6806b463cc27c71d8bbfc92273435a533502e9bb84b8b522` | 481 |
| candidate binary | — | **待远端构建** | — |

Git extension SHA 与当前已安装 extension SHA 一致；本席未修改全局文件。

## 判定与风险

- basegen：临时 T-N envelope 编译 exit 0；Rust 无 wiki card，cards=0，原始输出留 `tmp/basegen-T-N*`。
- archwiki：`archwiki check tools/nodeprobe` exit 3 / `partial`，无 blocking finding；报告 `tmp/archwiki-final.json`，未将 partial 说成 PASS。
- 远端构建：受 Grok Bot 远端空间闸阻塞（a2 preflight 记录约 9 GiB，可执行候选要求 ≥20 GiB）；不降 `--min-gib`，不重用 a1，不扩大同步占用。
- 生产/默认/共享/他队 socket、9900、server/App、全局 binary/extension 均未触碰。
- 完整 N-RECEIPT：`N-RECEIPT.json`；状态与所有未执行项如实记录。
