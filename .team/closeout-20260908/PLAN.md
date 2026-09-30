# 合并与清理准入核对（只读）

用户手机验收已通过，授权leader合并及清理。本席只核关系，不执行merge/delete/push。正在核对公开仓PR与N/S/A候选；未知项不进入合并或删除白名单。

## 已核实的重要边界

公开main：core=b267127ed1d6be1d76033936d89adff984775b15；serve=a6e22d6abcdac353d7365a7fb0767d9ddf37f515（leader connector快照）。本地主仓main=cf70d48b5a408915278818711d61d517034ca9df，含server目录；公开App候选不含server，禁止强行pull/force统一谱系。

A已验04bfde8d161c244c9dfced3b5ab90d22d1460d86→当前PR89 61b5015237856668b7d061f32c2a1fd9881d133a仅NAME-A-DELIVERY.md变化。N公开已验4f77e3bdc2df08c81b5f50c13050cbddc7053b62→PR86 4619b561ddbb8e931fbf17b280cd69edad52f58d仅DELIVERY.md/N-RECEIPT.json变化。S候选0f9010ce335833790c08431bee778b7ca98e2822包含PR6和S-COMP祖先，但PR19不是其祖先，属差量组合。

只读git merge-tree旧三参数预览：A89×N有L2Models.kt及8个nodeprobe文件文本冲突；A89×A73有debug/AndroidManifest.xml、DesignListMapping.kt、L2Models.kt、L2UnknownStatusTest.kt冲突；S×PR19有internal/nodeprobe/accepted-source.json冲突。不能自动选ours/theirs整仓。最终App产品须逐blob等于已验A；tools/nodeprobe须逐blob等于已验N；serve产品须等于已验S。未修改Git对象/索引/工作树，未执行merge。

## 当前18个open PR精确关系与动作

元数据来源：同目录pr-metadata.json/github-inventory.json/ci-metadata.json（leader GitHub connector）。所有当前PR均open、mergeable=true；这只适用于当前base，retarget/合入依赖后需重核，不是对main的保证。CI列“未列出”表示connector的PR事件/首屏未列出，绝非无CI或绿。

| 仓/PR | 当前head分支与精确SHA | 当前base分支与精确SHA | CI快照 | 建议 |
|---|---|---|---|---|
| core#89 | `pr/name-app`<br>`61b5015237856668b7d061f32c2a1fd9881d133a` | `base/status-name-a0`<br>`e42619b31803c28fec940f085d6897241ca7b9b5` | 34133134213 success | 必须原位落main；追加吸收已合main，冲突按已验A App/N probe分区保真；不合到A0即结束 |
| serve#19 | `pr/codex-pi-display-name`<br>`5668ca102aee17cc6a431b1f4e5689e061edfa4d` | `base/status-name-33b4c481`<br>`33b4c481e7545e1194935fd9ea373ed035463bea` | 未列出；已有精确收据34103044843 success | 必须原位落main；先追加merge最终S，accepted-source冲突保留S；再retarget main合入 |
| serve#6 | `pr/status-core-nodeprobe-863c`<br>`40496820b05be6b29ec439de5ad1dc196a773c9f` | `base/status-binding-7e55502`<br>`7e55502f0a0c9eda77c3f7c791b36d3432024979` | 34099062471 success | 必须原位落main；先retarget main合入，含S-COMP；此时尚非最终S |
| core#73 | `pr/external-session-status-ui`<br>`e7e7779085facbcc44d44520888100211da388d2` | `pr/status-core-nodeprobe-863c`<br>`23e0c4f1529b7b51192d6e65ecc62b3b517e2cf5` | 34091745276 success | 必须原位落main；在86后retarget main合入；不能把新N写回旧C70 |
| core#86 | `pr/nodeprobe-fg-comms`<br>`4619b561ddbb8e931fbf17b280cd69edad52f58d` | `pr/status-core-nodeprobe-863c`<br>`23e0c4f1529b7b51192d6e65ecc62b3b517e2cf5` | 34090337772 success | 必须原位落main；含C70祖先；retarget main后merge commit |
| core#88 | `cursor/ui-preview-clickable-a8cc`<br>`be8b6976aa2de0b5615058b1ec14a1f05bc29ace` | `main`<br>`b267127ed1d6be1d76033936d89adff984775b15` | 未列出 | 无关独立UI preview；保留，当前head对象本席无本地副本 |
| core#76 | `pr/favorites-online-filter`<br>`596fe65179e73210699f3e84c40168c930bbbbca` | `pr/foreground-resume-refresh`<br>`eaaa7d47d88e7e2de6c82988fe462e7adf29f86d` | 未列出 | C76完整祖先已在A89；A89入main后可关闭为已包含，随后清其base依赖 |
| serve#8 | `pr/host-auto-discovery-server`<br>`0b9c7acba69da7046763b444a2c21d7c36900b2e` | `pr/server-status-upload-compose`<br>`911dd94176a34e11f66b61b15b191216463c48cf` | 未列出 | 无关host discovery；保留，head对象缺失不能断言已包含 |
| core#75 | `pr/host-auto-discovery-client`<br>`d99c02b15614691d56d013008d1410020bf20aa1` | `pr/foreground-resume-refresh`<br>`eaaa7d47d88e7e2de6c82988fe462e7adf29f86d` | 未列出 | 无关未完成host discovery；保留，46产品路径均与A不同 |
| serve#12 | `pr/server-all-tmux-sockets`<br>`b1e11457e22397cdeb1c24b6d0fab0f84ececd2f` | `pr/server-status-upload-compose`<br>`911dd94176a34e11f66b61b15b191216463c48cf` | 未列出 | 完整祖先已在最终S；19使S落main后可关闭为已包含 |
| serve#11 | `pr/server-agent-cli-filter`<br>`1858a535266cba1baaff50e32151c66975f48123` | `pr/server-status-upload-compose`<br>`911dd94176a34e11f66b61b15b191216463c48cf` | 未列出 | 完整祖先已在最终S；19使S落main后可关闭为已包含 |
| core#74 | `pr/foreground-resume-refresh`<br>`eaaa7d47d88e7e2de6c82988fe462e7adf29f86d` | `pr/status-icons-manual-compose`<br>`a8901ea6cb53d002879a84760345787d3bcd201f` | 未列出 | 完整祖先已在A89；A89入main后可关闭为已包含 |
| core#72 | `pr/status-icons-manual-compose`<br>`a8901ea6cb53d002879a84760345787d3bcd201f` | `main`<br>`b267127ed1d6be1d76033936d89adff984775b15` | 未列出 | 组合/构建载体；完整祖先已在A89；A89入main后可关闭，不另作最终源 |
| core#69 | `feat/agent-cli-mobile-source-ui`<br>`322ce01424c35d2afafc693b1485e73efbde3441` | `main`<br>`b267127ed1d6be1d76033936d89adff984775b15` | 未列出 | 保留；非A89祖先，39产品路径仅34与A逐blob相同，不能证明整PR已包含 |
| core#70 | `pr/status-core-nodeprobe-863c`<br>`23e0c4f1529b7b51192d6e65ecc62b3b517e2cf5` | `main`<br>`b267127ed1d6be1d76033936d89adff984775b15` | 未列出 | 完整祖先已在N86；86入main后可关闭为已包含 |
| serve#5 | `pr/upload-http-500`<br>`b03ac2ad79fe3668b25c937bd5a46310588e8940` | `main`<br>`a6e22d6abcdac353d7365a7fb0767d9ddf37f515` | 未列出 | 保留；S-COMP有upload行为不等于本PR精确完整包含；当前head对象缺失 |
| serve#4 | `pr/status-core-exact-nodeprobe`<br>`bd34e1760f5d0b25006fbe091d8c11d3fdf1df1d` | `main`<br>`a6e22d6abcdac353d7365a7fb0767d9ddf37f515` | 未列出 | 保留旧PR；当前head对象缺失，不能依据标题关闭 |
| core#66 | `pr/status-core-exact-nodeprobe`<br>`9c6dbd178c94b30dedbf54fdf6860308872d5706` | `main`<br>`b267127ed1d6be1d76033936d89adff984775b15` | 未列出 | 保留旧PR；非N/A祖先，nodeprobe已被后续替代≠整PR完整包含 |

## 实际到main的最小顺序（leader执行，无需再次用户批准）

保留现有owning PR与提交史，使用merge commit，不squash/rebase/force；这样候选及被包含PR的祖先关系可机械证明。PR旧body的“keep open/no merge”是旧授权状态，应按本次手机验收与合并授权更新。

1. **core#86 → core main**：将base从C70改main，复核当前head仍4619b561…后合入。C70整祖先随之入main，不另把86只合进C70临时分支。合后N subtree须仍为下列精确值。
2. **core#73 → core main**：在86已入main后retarget main并合入，保留86的新N。补充只读预览已证86×73有根目录`DELIVERY.md` add/add文本冲突，须在73追加合入main并保留两份来源收据内容后再合；不是自动无冲突操作。此时只完成N+A73，不能宣称已验A候选已在main。
3. **core#89 → core main**：在其现有head追加合入此时的core main（绝不重写owning history）。以已验A的完整`app/`逐blob保真解决App冲突，以已验N的完整`tools/nodeprobe/`逐blob保真解决probe冲突。三参数只读预览已列出冲突；之外的新增/改变路径也须逐项review，不以“无文本冲突”等于已验。retarget #89到main，再merge。此时同时有86、73、89及C76、C74、C72、C70祖先，才是完整core落地。不用把72组合载体单独merge到main。
4. **serve#6 → serve main**：retarget main并合入40496820…，S-COMP祖先随之到main；不触碰生产。这只是中间态。
5. **serve#19 → serve main**：在现有5668ca10…追加merge候选0f9010ce…，保留双方祖先；`internal/nodeprobe/accepted-source.json`冲突必须取最终S准确内容，不能回退33b4旧来源。候选与PR19在`cmd/`、`internal/`还有实际内容差异，因此整个serve产品（不仅冲突文件）必须与最终S逐blob相同；仅允许明确列出的收据/合并元数据差别。将base改main后合入。这样S、PR19、PR6和S-COMP均到main。candidate/artifact carrier不单独代替owning PR闭环。

两仓步骤可分别执行，但每仓按上述依赖顺序。每次变更head/base之后旧mergeable/CI快照即失效；检查最新head对应CI，不假称已跑新矩阵。必要冲突解决仅为恢复已验精确产品，不引入新产品设计。

## 执行后的最小验证

- 通过GitHub确认两仓**远端main**最终SHA、5个owning PR状态/base/merge commit；机械检查core最终main包含86/73/89旧head与A候选，serve最终main包含0f9010ce、5668ca10、40496820。不能用本地主仓main充当公开main。
- core final `app/` tree = `ba02ebedbfb9b208ddbba9a165283bf48f218cbd`（A04bf）；`tools/nodeprobe/` tree = `bdc49c7b8cbaad7604e8c2a7066d89784d9d8abf`（N4f77）。不能要求core整树等于A，因为A树内probe是旧`5eec21f26ea09c781311a8546089d2f45eb91509`，必须带N。
- serve最终产品树与S `0f9010ce335833790c08431bee778b7ca98e2822`（完整tree `7758dd60d6aab573e83824349ed12fb2153ad1fd`）逐blob比较；若整tree仅因额外收据不同，列出全部差异路径并确认非产品，禁止宽泛排除。最终绑定manifest必须保持已验N。S workflow精确checkout `4619b561ddbb8e931fbf17b280cd69edad52f58d`，并核public_source `4f77e3bdc2df08c81b5f50c13050cbddc7053b62`；两对象必须永久可达，不能仅保留编译后二进制。
- CI沿最新head检查现有门，已有已验候选精确证据：S34104886796/34104897413成功，PR19 34103044843成功；A候选构建34098991746，当前89门34133134213成功；86/73见表。不重跑用户手机验收或新矩阵；新合并若产品blob不等即停止落地该分支并修正，不能用历史绿覆盖新差量。
- 已验APK SHA256 `bd4ababfcec3301c19ced02350ff2193f183311f9ec0bdd455145deab3b3e861`及S二进制SHA256 `1c9a07af18e0b0cd530ed1f50e0a8ec22c2a7405933dd2dfa8d11a14b5d3100f`保留；本次不重构建、不重启daemon、不重配AVD。

## 清理白名单（有明确先决条件，当前不删）

- **PR关闭集合**：core#70在86永久落main后；core#72/#74/#76在89永久落main后；serve#11/#12在19使S永久落main后。全部已有准确head祖先证明，关闭说明引用最终main/owning PR，不称“测试通过所以等价”。若GitHub已自动识别merged则不重复关。
- **临时base refs**：`core:base/status-name-a0@e42619b3…`、`serve:base/status-binding-7e55502@7e55502f…`、`serve:base/status-name-33b4c481@33b4c481…`，仅在5个owning PR已改main/完成、无剩余open PR引用且该base为最终main祖先后删除。#75仍依赖`pr/foreground-resume-refresh`，serve#8仍依赖`pr/server-status-upload-compose`，这两条base分支**不能删**。
- **唯一当前符合clean且可归属的工作树候选**：`.team/nodes/favorites-hand/core`，HEAD596fe651…，含ignored检查亦全clean。待89入main、对应C76证据继续保存在Git/现有验收记录后，可用正常`git worktree remove`移除，继而删其本地`pr/favorites-online-filter`；远端同名分支也须先确认无剩余PR依赖且永久main包含。不得用force remove或rm -rf替代前置核对。
- **owning远端分支**：core `pr/name-app`/`pr/external-session-status-ui`/`pr/nodeprobe-fg-comms`、serve `pr/status-core-nodeprobe-863c`/`pr/codex-pi-display-name`仅在各自已合main且所有引用消费者解除后才可删；本地同名不当然同SHA，尤其本地主仓`pr/nodeprobe-fg-comms`仍是b09f5708…、`pr/external-session-status-ui`仍是0cd38d8f…，不列本地删支白名单。core `pr/status-core-nodeprobe-863c`也不是公开同名SHA，禁止按名字批删。

## 保护集合／不进入本次清理

- 主仓所有既有M/D及unknown/untracked：只查status路径，未读配置原文。包括.cursor/mcp.json、.grok/config.toml、CLAUDE.md、heartbeat/ledger文件、tools/nodeprobe/src/classify.rs、历史删除的角色文件和server二进制等；主仓cf70d48b…不reset/clean/pull/force。
- `.team/nodes/name-app`有tmp与archwiki/basegen未跟踪输出；`status-app-fix`有FREEZE.md、tmp与多份未跟踪证据；`status-probe-fix`有serve-binding/与tmp/。未经永久保存/独有工作核清，整个目录及本地branch不删。不删除未知nested Git树、locked initializing worktree、其他队路径。
- 生产PID92596当前lsof映射`.team/nodes/_driver/deploy-status-20260907-183711/inputs/agentmirrord`，cwd为主仓；整个deploy目录、备份、私有N、extension、corpus、当前daemon均保护。`.team/nodes/status-serve-compose/serve`、artifact-carrier虽clean，仍为精确源/二进制留存，保护；不因clean而删除。
- `artifacts/candidate-status-names-20260907-darwin-arm64`（befa2534…）及N Git artifact carrier、候选APK draft release/签名资产/receipt全部保留；产品main不承担这些载体的唯一保留责任，未设永久替代tag前不删。
- 用户已配对AVD app_fix_working_api35 / emulator-5580 / adb5038、GUI进程与本席real-avd-astra2证据目录保护；不关闭窗口或清其userdata。
- core#75/#88与serve#8无关未完成，保护；core#69/#66、serve#4/#5完整包含关系尚未证明，保留open及所有独有refs。缺本地对象的明确集合：core88、serve8/5/4；这不阻塞已证5个owning PR计划，也不授权猜测关闭。
- 本轮性能Issue由其他席位另案，不修改/关闭/纳入本次放行条件。其余17个open Issue不按PR标题或close关键字批量关闭，只有已验对应问题及显式关联才由leader另核。

本席只执行Git对象/祖先/逐blob与只读merge-tree预览、status路径检查和现有收据核对。没有跑编译、产品测试、改变Git refs/index或触碰生产/AVD；merge-tree预览是静态冲突线索，不宣称已执行合并验证。
