# T-S serve#6 接线交付收据

状态：`blocked_remote_workflow_scope`。产品候选已构造并推送到现有 PR6；远端 Go/Candidate/G-SAFE 未执行，不能宣称 accepted 或全链 PASS。唯一阻塞是 GitHub OAuth App token 无 `workflow` scope，推送最小 workflow 被服务端拒绝；本地未编译 Go/Rust，未部署/未碰 9900。

## PR 与精确谱系

- owning PR：<https://github.com/Florious95/corral-serve/pull/6>
- base：`main`
- remote head：`4ce491c3d58768d24371aad819d51cb930b97f41`
- candidate tree：`6ddccb7b4df77452f3bcdc985f9e0b77fc05a3aa`
- merge parents：S6 `f679f306ddecd2782461057cdf73b961c97c874f` + S-COMP `7e55502f0a0c9eda77c3f7c791b36d3432024979`
- 本构造保留 S7 `911dd94176a34e11f66b61b15b191216463c48cf`、S11 `1858a535266cba1baaff50e32151c66975f48123`、S12 `b1e11457e22397cdeb1c24b6d0fab0f84ececd2f`；相对精确 S-COMP 的已提交产品差量仅 `internal/nodeprobe/**` 与必要的 `cmd/agentmirrord/daemon_nodeprobe_gsafe_test.go`，未改枚举/过滤实现。
- `cmd/agentmirrord/upload_e2e_test.go`、`internal/api/session_filter_regression_test.go`、`internal/api/upload_fs_test.go`、`internal/discovery/socket_inventory_regression_test.go` 及 locale/upload/filter/all-socket 源均来自 S-COMP 保留组合，不是本次新改。

## N 坐标（精确引用）

- 实际 GitHub checkout：`6e47021a7e3502843d490a2eeb0342c78c0231f3`；同树：`047bfb0539047f592ef64d4d8bf9dfaba4278e20`。不把 PR 分支 `4f77e3bdc2df08c81b5f50c13050cbddc7053b62` 写成 built-from。
- Darwin binary：`tools/nodeprobe/artifacts/nodeprobe-darwin-arm64`（N Git artifact），Mach-O arm64，885504 bytes，SHA256 `e5667b9ebe931de7805a87538e03b9a6ee1fb9cb9250c25282f8b054268226b5`，Git blob `19a33813679337252984a4b20fcf51a39c6e382e`。
- Pi extension：Git blob `67c1916ade725f73646f062fbf03d0cc22d6823d`，3572 bytes，SHA256 `51ffcad3f68ac22330d98ba0240b81f41b210939709a91637c917e1555494c27`。
- titles corpus：Git blob `690e651f445793a36ed1cb6e6f6143e6ce5c989e`，1332 bytes，SHA256 `cff45d25492fdfe9689330c630c80bad20a1f27243e5aae1d93bc57de0a22b58`。
- providers corpus：Git blob `e93afa54e473e90099f03cb932d9a54798c0dfe4`，481 bytes，SHA256 `c68f50115b33ae6a6806b463cc27c71d8bbfc92273435a533502e9bb84b8b522`。
- manifest `internal/nodeprobe/accepted-source.json` 已精确钉住上述 source/tree、平台、binary、extension、两个 corpus；显式 `NODEPROBE_FIXTURES`/`NODEPROBE_PROVIDERS` 路径按 hash 验证，serve split checkout 不再因找不到本地 `tools/nodeprobe` 根而误判。

## 写界与门

- 保留 report schema 1、Pi channel schema 2、四轴结构透传、同 ref join、listing/Level2 原失败不清空。
- `acceptedEnv` 保留 S-COMP 的 `LANG`/`LC_CTYPE`/`LC_ALL` 传递；不传任意父环境/凭据。
- 修旧 `TestAcceptedUniquePiMissingChannelHealthIsNormal` 为当前技能语义的 unknown 命名/断言，并新增 manifest N 坐标测试。
- 新增 `cmd/agentmirrord/daemon_nodeprobe_gsafe_test.go`：真实编译 daemon 子进程、真实 Runner→nodeprobe 子进程、认证 listing，断言 workspace/session count、ref、provider/activity/status；仅自有合成 socket。
- 本地保留但尚未推送的最小 runner：`.team/nodes/status-probe-fix/tmp/pr6-nodeprobe-serve.yml.pending`。它使用 `macos-14`，精确 N source checkout，`go test -count=1`，实际 daemon G-SAFE，正控 list-panes/窄 ps，独立 capture-pane/危险 ps 反控拒绝；权限恢复后应以该文件写入 `.github/workflows/pr6-nodeprobe-serve.yml` 并只跑一次。

## 红绿与执行状态

- 冻结旧 S6 红边界已写入 pending workflow：在精确 S6 `f679...` 注入 `TestTSManifestMustPinNRed`，要求旧 manifest source != N actual checkout；应以非零为红。当前未在远端执行，未伪报红/绿。
- Go `-count=1`、候选 Darwin daemon、listing ref/count、实际 daemon→nodeprobe G-SAFE、positive trace、forbidden rejection、cleanup：均 `not executed`，因为 workflow 文件 push 被 GitHub 明确拒绝。
- 已尝试的唯一远端写入：普通 `git push origin HEAD:pr/status-core-nodeprobe-863c`；产品提交成功，含 workflow 的提交被拒绝，原文事实为 `refusing to allow an OAuth App to create or update workflow ... without workflow scope`。未 force-push、未重试、未降安全门。
- GitHub PR6 当前 `gh pr checks`：无 checks；不是测试通过。

## 安全与清理

- 未调用真实/default/shared/他队 socket；仅设计/待远端运行自有 `/tmp`/runner 临时合成 socket；未读 pane 正文、argv、凭据或用户 Pi。
- 未编译 Go/Rust/Gradle；未安装全局 binary/extension；未启动 daemon/9900；本地只写 serve-binding worktree 与本席 tmp。
- 待 workflow scope 恢复后，独立执行红→候选 Go/daemon G-SAFE→上传证据，再更新本收据为实际 exit/执行数/失败集合；当前保持 blocked。
