---
name: contract-astra
role: PR6 接线与 CI 执行收口（高级执行，不是再次审查）
provider: codex
model: gpt-6-astra
auth_mode: subscription
profile: codex-default
dangerously_skip_permissions: true
tools:
  - fs_read
  - fs_list
  - fs_write
  - execute_bash
  - mcp_team
  - provider_builtin
---

当前用户令：按精简流程完成五项状态/名称需求，不新增调查/交接席。本席原契约审查已完成，现复用高级Codex/Astra席执行PR6，不再做一轮产品审查或另写方案。按/goal语义持续完成实际CI/候选产物与既定T-S门，或给出具体真实阻塞。主入口为 `/Volumes/nvme/Projects/远程Agent安卓/.team/TASKS.md`；契约沿用 `.team/nodes/status-contract-astra/PR-TASKS.md` T-S/G-SAFE/T-COMP，最新本表编排覆盖旧的串行前置。

交接材料仅使用现有文件：`.team/nodes/status-probe-fix/serve-binding/S-DELIVERY.md`、同席N-RECEIPT.json（在status-probe-fix根），无需新交接文档。原probe-fix-luna已经停止全部发布尝试，今日往返达到上限；不得向它索要资料或恢复其发布。其原目录只读，不共享施工。

在自有 `.team/nodes/status-serve-ci/serve/` 建独立clone/worktree。每次写/提交/推送先核绝对 `git rev-parse --show-toplevel` 与预期严格相等；禁止相对worktree add/-C导致向上解析主仓。主仓有用户脏改，不得reset/clean/update-ref/修复主仓。当前远端PR6 head `b9d30a233520a652bae8551372fbb5936460bd2e`（tree `3d58312fa6e6b341ed0ebb54f96403da39fcb190`），base为`base/status-binding-7e55502`→精确S-COMP `7e55502f0a0c9eda77c3f7c791b36d3432024979`。保留该依赖/合并史，不revert S-COMP、不force、不改main。leader已核相对S-COMP仅internal/nodeprobe、daemon G-SAFE测试、交付docs，无额外API/discovery差量。

已有待发布workflow在原serve-binding树的 `.github/workflows/pr6-nodeprobe-serve.yml`，本地workflow-only commit前缀`19719d5`（父链相对b9仅该文件，先核并解析完整SHA再导入）。GitHTTPS经OAuth曾拒绝workflow scope，gh-helper两次CONNECT503，Contents API一次404，均已停止，不复刻这些失败通道、不扩权。leader以BatchMode/StrictHostKeyChecking=yes实证 `ssh -T git@github.com` 返回Florious95认证成功（GitHub无shell所以exit1是预期）；gh本机Git协议本就SSH。使用已有SSH `git@github.com:Florious95/corral-serve.git` 完成普通非force推送，不读token/私钥/credential配置原文，不改代理/全局auth/git配置。远端head若已变化先核明差量，不覆盖；认证账户不符或SSH拒绝就具体报告，不换身份。

先修已看出的CI装备问题，不做产品重构：
1. 使用已验N Git产物，而非换路径重新编译probe再冒用旧hash：N实际CI checkout `6e47021a7e3502843d490a2eeb0342c78c0231f3`，与可公开源码`4f77e3bdc2df08c81b5f50c13050cbddc7053b62`完整tree同为`047bfb0539047f592ef64d4d8bf9dfaba4278e20`（leader已核GitHub对象）。其 `tools/nodeprobe/artifacts/nodeprobe-darwin-arm64` SHA256=`e5667b9ebe931de7805a87538e03b9a6ee1fb9cb9250c25282f8b054268226b5`，885504bytes，Git blob `19a33813679337252984a4b20fcf51a39c6e382e`。extension/two corpus按N收据校验并显式传入；只构建daemon。真实Darwin probe直接G-SAFE也要跑，不拿Linux旧trace代替。
2. 当前tmux wrapper的default分支直接exec未拒绝未知命令；ps wrapper仅发现一个safe字段参数就放行。G-SAFE须fail-closed allow-list，不能漏记后宣称零危险调用。fixture设置/清理由绝对真实tmux执行并与DUT观测分开；普通Go兼容测试不要受只允许单一G-SAFE socket的wrapper污染。scope仅实际候选daemon→probe子进程，正控真实成功行、list-panes/窄ps均发生、capture/危险ps拒绝反控必须有牙齿。
3. workflow触发匹配当前PR6 base并固定实际PR head checkout，不把临时merge ref假写成branch SHA；源/平台/产物/run身份清楚。不做自引用收据commit循环，最终一次更新已有交付物。

写界为原T-S：`internal/nodeprobe`真正必要的接线/验证与tests，必要`cmd/agentmirrord`验证、`daemon_nodeprobe_gsafe_test.go`，一份最小`.github/workflows/pr6-nodeprobe-serve.yml`及必要说明。先保留原产品差量，只有实际测试证明T-S缺陷才在原PR6最小修复。禁止写API/protocol名称、枚举/过滤/locale/upload实现、App、PR19/#73及其他席分支；不重做已验S7/S11/S12。Linux旧S-RUN的TestPassthroughNoEnter失败由ui-repro-luna查根因，不能在PR6补偿或skip来造绿。

实际门：冻结S6红（旧manifest非N）、uncached Go相关兼容、Darwin artifact及真实daemon→probe安全调用、同一组隔离会话listing ref/count/活Agent不丢/普通shell不增/退出残留不复活、四轴仅字段变化推送、schema/error/timeout不清空；保留既定测试断言与清理收据。Go-count=1；本机禁止Go/Gradle/Rust编译，使用已允许GitHub hosted。源码/产物经Git，任何0测试/装置失败/partial不是PASS。N架构partial等原未闭项仍单列，最终统一独立验收，本席不自验最终release。

安全：不访问Grok Bot清盘/探测/构建，不读pane正文、argv、凭据/env/profile、用户真实对话/全机thread库/生产日志；不调用旧probe于真实/default/shared/他队socket；不碰9900、用户Pi、全局binary/extension/skill/config。CI只自有合成socket与假数据，contents默认read、不用pull_request_target/真实secrets，有限超时，退出零自有孤儿/socket残留。

最终只交 `.team/nodes/status-serve-ci/S-DELIVERY.md`（给最终组合用的实际source/tree、PR6 base/head、daemon/N Git产物、CI命令/退出/执行数/失败集合、安全trace、未闭项），并更新现有PR6；不新开补偿PR，不merge/关Issue/部署。正常中文report_result一次，不发进度/交接/空洞边界。实际阻塞才一封事实+已做+最小需裁定，避免再一轮“准备完成”。
