# baseline-bundle / successor1..11 PR recovery inventory

## 审计边界

本盘点只读完成于 2026-08-25，范围为本地 Git 历史、`pr/*` 分支、
`gh api --method GET repos/Florious95/corral-core/pulls?state=all&per_page=100`（REST，未用
GraphQL）和 `tools/mirror-pr.sh`。
未安装工具、未推送、未开 PR、未改历史、未改脚本。

结论先行：原 45 个关键点均已有对应的 GitHub PR 且当前为 `MERGED`；#17–#60
覆盖 44 个关键点，#61 是原 `fdf7f6497` 在 HTTP 504 后的审计补救，补齐第 45 个；
#62 是独立 recovery review，不计入 45 个关键点。故唯一关键点 PR 缺口为 0，
覆盖率 `45/45`，额外独立 review 为 `1`。

这些 PR 均由本地已落 `main` 的单提交恢复分支产生；初始审计时
`git rev-list --left-right --count main...pr/baseline-bundle-successor11` 为 `0 0`，
该数值保留为初始事实。本次 fresh 核验同一命令为 `2 0`：#61/#62 合并后
`main` 前进两提交，而 successor11 分支未前进。原矩阵中的“缺 PR”是初始盘点快照，
以下补表是本次只读核实后的覆盖状态，不改变原 45 关键点矩阵。

GitHub 全量只读列表为 #1–#16 与 #17–#62；#1–#16 是其它 input/perf/ui/docs 主题，
#17–#62 全部 `MERGED`。本地没有 remote-tracking 的 baseline/successor PR 分支。

## 独立关键点矩阵

每行中的提交按逻辑先后列出，但每个短 SHA 都是独立 PR 单位；同一行不是建议把多个
提交合并成一个 PR。`不可单独回退` 表示该提交写入了运行状态/红证据，回退会抹掉事实，
不是说 Git 无法技术性 revert。为避免 merge parent 造成 `A..B` 省略歧义，
“独立提交”栏显式列出该关键点的完整审计 commit 集合（即本盘点的 commit 范围）。

|阶段 / 独立提交|产物与验收/评审证据|应建分支 / PR 标题|依赖、现状与回退边界|
|---|---|---|---|
|baseline：`9468854e1`|`.team/ledgers/baseline-bundle-v1.json`、`src/baseline-bundle-v1.py`、baseline acceptance 全集；`.team/nodes/spec-sol/baseline-bundle/{RESULT.md,compile.log,dry-run.log,structure.log}`；`.team/nodes/baseline-bundle-prelaunch-review/PRELAUNCH-VERDICT.md`|`recovery/bundle-baseline-9468854` / `baseline-bundle: establish recovery ledger and acceptance gates`|依赖主线父 `a538117cc`；缺 PR。可作为首个可回退 bootstrap 单元。|
|baseline 首红：`c4846de54`|`.team/nodes/_driver/baseline-bundle-v1.out`；`.team/nodes/baseline-bundle-repro-diagnosis/VERDICT.md`；ledger revision/首红 attempt 证据|`recovery/bundle-baseline-c4846de` / `baseline-bundle: record first-red evidence`|依赖 baseline bootstrap；缺 PR。`不可单独回退`：会删除首红事实和诊断链。|
|baseline 先红契约：`488a1f25b`|`.team/ledgers/acceptance/{baseline-bundle-repro-regression.sh,baseline-bundle-repro-translate.sh}`；`.team/nodes/spec-sol/baseline-bundle-repro-fix/{RESULT.md,regression.log}`；`.team/nodes/baseline-bundle-repro-fix-review/VERDICT.md`|`recovery/bundle-baseline-488a1f` / `baseline-bundle: harden first-red contract`|依赖 `c4846de54`；缺 PR。判据与候选评审可独立回退，但不能删除已记录的旧 attempt。|
|successor1 ledger：`ef7a02c1d`|`.team/ledgers/{baseline-bundle-successor-v1.json,src/baseline-bundle-successor-v1.py}`；`.team/nodes/spec-sol/baseline-bundle-successor/{RESULT.md,preflight.log,dry-run.log,verify.log}`；`.team/nodes/baseline-bundle-successor-review/{VERDICT.md,tests.log}`|`recovery/bundle-s1-ef7a02c` / `baseline-bundle successor1: add continuity ledger`|依赖 baseline 先红契约；缺 PR。新账本 bootstrap 可独立回退，旧 ledger 必须保留。|
|successor1 复用 WT 诊断：`a3421f19f`|`.team/ledgers/baseline-bundle-successor-v1.json` 更新；`.team/nodes/_driver/baseline-bundle-successor-v1.out`；`.team/nodes/baseline-bundle-continue-advice/{ADVICE.md,VERDICT.md}`；`.team/nodes/baseline-bundle-successor-run1-diagnosis/VERDICT.md`|`recovery/bundle-s1-a3421f` / `baseline-bundle successor1: preserve reused-worktree evidence`|依赖 `ef7a02c1d`；缺 PR。`不可单独回退`：会抹掉不可判现场/连续性证据。|
|successor2 新 WT ledger：`2263fd95f`|`.team/ledgers/{baseline-bundle-successor2-v1.json,src/baseline-bundle-successor2-v1.py}`；`.team/nodes/spec-sol/baseline-bundle-successor2/{RESULT.md,preflight.log,dry-run.log,worktree-preflight.log}`；`.team/nodes/baseline-bundle-successor2-review/{VERDICT.md,tests.log}`|`recovery/bundle-s2-2263fd` / `baseline-bundle successor2: create fresh-worktree ledger`|依赖 successor1；缺 PR。ledger/bootstrap 可单独回退，不能带回旧 WT。|
|successor2 首红派单：`98bf318e7`|`.team/nodes/_driver/baseline-bundle-successor2-v1.out`；successor2 ledger revision/派单证据|`recovery/bundle-s2-98bf318` / `baseline-bundle successor2: record first-red dispatch`|依赖 `2263fd95f`；缺 PR。`不可单独回退`：状态更新是运行证据。|
|successor2 结果归因：`99675a86a`|`.team/nodes/_driver/baseline-bundle-successor2-v1.out`；successor2 ledger acceptance 状态；`.team/nodes/baseline-bundle-successor2-review/VERDICT.md`|`recovery/bundle-s2-99675a` / `baseline-bundle successor2: record probe-test result state`|依赖 `98bf318e7`；缺 PR。`不可单独回退`：不能用 revert 清除不可判结果。|
|successor3 真实判据 bootstrap：`37baef7f7`, `f0fce0a44`|`.team/ledgers/acceptance/baseline-bundle-successor3-{canonical,impl,test,probe,measure,bypass}.sh`、`real-fixture.py`；`.team/nodes/spec-sol/baseline-bundle-successor3/{BOOTSTRAP-RESULT.md,bootstrap-*.log}`；`.team/nodes/baseline-bundle-successor3-bootstrap-review/{VERDICT.md,tests.log}`|`recovery/bundle-s3-37baef7`, `recovery/bundle-s3-f0fce0` / `baseline-bundle successor3: review and freeze real acceptance bootstrap`|依赖 successor2；两提交应各自独立 PR，后者依赖前者。缺 PR；bootstrap 可回退，旧 run 不可清除。|
|successor3 final ledger：`171db68d9`|`.team/ledgers/{baseline-bundle-successor3-v1.json,src/baseline-bundle-successor3-v1.py}`；`.team/nodes/spec-sol/baseline-bundle-successor3/{final-RESULT.md,final-preflight.log,final-dry-run.log,final-provenance.log}`；`.team/nodes/baseline-bundle-successor3-final-review/{VERDICT.md,tests.log}`|`recovery/bundle-s3-171db68` / `baseline-bundle successor3: freeze final recovery ledger`|依赖 `f0fce0a44`；缺 PR。ledger 可独立审计，不能把后续红状态倒转为绿。|
|successor3 运行归因：`3401706a8`, `ec1145820`|`.team/ledgers/baseline-bundle-successor3-v1.json` 状态更新；`.team/nodes/_driver/baseline-bundle-successor3-v1.out`；`.team/nodes/baseline-bundle-successor3-run1-diagnosis/VERDICT.md`；`.team/nodes/baseline-bundle-successor3-review/VERDICT.md`|`recovery/bundle-s3-3401706`, `recovery/bundle-s3-ec1145` / `baseline-bundle successor3: preserve run diagnosis` / `baseline-bundle successor3: preserve legacy-gate diagnosis`|依赖 successor3 final ledger；缺 PR。两个提交分别建 PR；均`不可单独回退`，因为会抹掉 run/判据归因。|
|successor4 ledger：`2f76349af`|`.team/ledgers/{baseline-bundle-successor4-v1.json,src/baseline-bundle-successor4-v1.py}`；`.team/nodes/spec-sol/baseline-bundle-successor4/{RESULT.md,compile-schema.log,preflight-dry-run.log,required-structure-teeth.log}`；`.team/nodes/baseline-bundle-successor4-review/{VERDICT.md,tests.log}`|`recovery/bundle-s4-2f7634` / `baseline-bundle successor4: freeze exact required ledger`|依赖 successor3 诊断闭环；缺 PR。结构/四态 bootstrap 可单独回退。|
|successor5 ledger：`b2af6fd18`|`.team/ledgers/{baseline-bundle-successor5-v1.json,src/baseline-bundle-successor5-v1.py}`；`.team/nodes/spec-sol/baseline-bundle-successor5/{RESULT.md,compile-schema.log,preflight-dry-run.log,required-structure-teeth.log}`；`.team/nodes/baseline-bundle-successor5-review/{VERDICT.md,tests.log}`|`recovery/bundle-s5-b2af6f` / `baseline-bundle successor5: freeze SDK and required ledger`|依赖 successor4；缺 PR。SDK/fixture 判据可独立回退。|
|successor5 impl 红：`4c1005867`|`.team/ledgers/baseline-bundle-successor5-v1.json` 状态更新；`.team/nodes/_driver/baseline-bundle-successor5-v1.out`；`.team/nodes/baseline-bundle-successor5-impl-diagnosis/VERDICT.md`|`recovery/bundle-s5-4c1005` / `baseline-bundle successor5: preserve substantive impl red`|依赖 `b2af6fd18`；缺 PR。`不可单独回退`：该提交记录产品实质红，回退等于擦证据。|
|successor6 projection bootstrap：`fdf7f6497`|`.team/ledgers/acceptance/{baseline-bundle-successor6-projection.py,baseline-bundle-successor6-projection-regression.sh,baseline-bundle-successor6-deep.sh}`；fixtures `projection-contract.json`/`legal-successor5-manifest.json`；`.team/nodes/spec-sol/baseline-bundle-successor6/BOOTSTRAP-RESULT.md`；`.team/nodes/baseline-bundle-successor6-bootstrap-review/VERDICT.md`|`recovery/bundle-s6-fdf7f6` / `baseline-bundle successor6: freeze canonical projection bootstrap`|依赖 successor5；缺 PR。可独立回退，但下游 final PR 必须依赖它。|
|successor6 final gates：`548572dfd`, `87ce64f03`|`.team/ledgers/acceptance/baseline-bundle-successor6-{final,probe,structure,test,verify}.sh`；`.team/ledgers/baseline-bundle-successor6-v1.json`；`.team/nodes/spec-sol/baseline-bundle-successor6/{final-RESULT.md,final2-RESULT.md,final*-log}`；`.team/nodes/baseline-bundle-successor6-final-review/{VERDICT.md,tests.log}`|`recovery/bundle-s6-548572`, `recovery/bundle-s6-87ce64` / `baseline-bundle successor6: add final evidence gates` / `baseline-bundle successor6: freeze final ledger`|两提交各自 PR，`87ce64` 依赖 `548572`；缺 PR。ledger 与 gates 不应压成一个 PR。|
|successor6 verify 归因：`3528c2ad5`|`.team/ledgers/baseline-bundle-successor6-v1.json` 状态更新；`.team/nodes/_driver/baseline-bundle-successor6-v1.out`；`.team/nodes/baseline-bundle-successor6-verify-diagnosis/VERDICT.md`|`recovery/bundle-s6-3528c2` / `baseline-bundle successor6: preserve verify unjudgeable evidence`|依赖 `87ce64f03`；缺 PR。`不可单独回退`：只读归因事实。|
|successor7 apparatus bootstrap：`6e729d729`, `da46a6b2b`|`.team/ledgers/acceptance/baseline-bundle-successor7-{apparatus,owned-emulator,continuity,verify,impl-bypass}.sh`；permanent fixture；`.team/nodes/spec-sol/baseline-bundle-successor7/BOOTSTRAP-RESULT.md`；`.team/nodes/baseline-bundle-successor7-bootstrap-review/{VERDICT.md,tests.log}`|`recovery/bundle-s7-6e729d`, `recovery/bundle-s7-da46a6` / `baseline-bundle successor7: review and freeze apparatus bootstrap`|两个提交各自 PR，`da46` 依赖 `6e729`；缺 PR。apparatus bootstrap 可回退；评审否决证据不可抹。|
|successor7 retained WT：`0df3562b7`|`.team/nodes/baseline-bundle-successor7-wt-preflight/{COMMAND.md,VERDICT.md}`；`.team/nodes/spec-sol/baseline-bundle-successor7/final-provenance-catfile-wt.log`|`recovery/bundle-s7-0df3562` / `baseline-bundle successor7: record retained-WT fast-forward preflight`|依赖 `da46a6b2b`；缺 PR。文档可独立回退，不改变产品/ledger。|
|successor7 final ledger：`79cd08f0f`|`.team/ledgers/{baseline-bundle-successor7-v1.json,src/baseline-bundle-successor7-v1.py}`、successor7 acceptance 全集；`.team/nodes/spec-sol/baseline-bundle-successor7-final-review/{VERDICT.md,tests.log}`；`.team/nodes/spec-sol/baseline-bundle-successor7/final-RESULT.md`|`recovery/bundle-s7-79cd08` / `baseline-bundle successor7: freeze device recovery ledger`|依赖 `0df3562b7`；缺 PR。ledger 是独立关键点，不应与诊断文档合并。|
|successor7 command/recovery evidence：`1df02dfb0`, `25517d808`, `132e63576`|`.team/nodes/baseline-bundle-successor7-command-pair/VERDICT.md`；`.team/nodes/baseline-bundle-successor7-frontier-recovery/VERDICT.md`；`.team/nodes/_driver/baseline-bundle-successor7-v1.out`|`recovery/bundle-s7-1df02d`, `recovery/bundle-s7-25517d`, `recovery/bundle-s7-132e63` / `baseline-bundle successor7: preserve command-pair dialect review` / `baseline-bundle successor7: preserve frontier recovery evidence`|按三个提交分别建 PR，依赖 `79cd08f0f`；缺 PR。driver/frontier 状态提交`不可单独回退`。|
|successor8 recovery ledger：`1481f4f8c`|`.team/ledgers/{baseline-bundle-successor8-v1.json,src/baseline-bundle-successor8-v1.py}`；`.team/nodes/spec-sol/baseline-bundle-successor8/{RESULT.md,final-compile-schema.log,final-preflight-dry-run.log,final-structure-teeth.log}`；`.team/nodes/baseline-bundle-successor8-review/{VERDICT.md,tests.log}`|`recovery/bundle-s8-1481f4` / `baseline-bundle successor8: freeze command-consume recovery ledger`|依赖 successor7 final；缺 PR。ledger/bootstrap 可独立回退。|
|successor8 apparatus 归因：`6dbf110a5`, `61af5e3c4`|`.team/nodes/baseline-bundle-successor8-apparatus-diagnosis/{VERDICT.md,INSTALLED-IMAGES.md}`；`.team/nodes/_driver/baseline-bundle-successor8-v1.out`；ledger 状态更新|`recovery/bundle-s8-6dbf11`, `recovery/bundle-s8-61af5e` / `baseline-bundle successor8: preserve apparatus image diagnosis` / `baseline-bundle successor8: preserve apparatus unjudgeable state`|两个提交各自 PR，后者依赖前者；缺 PR。`不可单独回退`。|
|successor9 selector bootstrap：`e6c2e2625`, `0fdee1072`|selector/SDK acceptance；`.team/nodes/spec-sol/baseline-bundle-successor9/BOOTSTRAP-RESULT.md`；`.team/nodes/baseline-bundle-successor9-bootstrap-review/VERDICT.md`；`.team/nodes/baseline-bundle-successor9-wt-preflight/{COMMAND.md,VERDICT.md}`|`recovery/bundle-s9-e6c2e2`, `recovery/bundle-s9-0fdee1` / `baseline-bundle successor9: preflight and freeze SDK selector`|按提交分别 PR，`0fdee1` 依赖 `e6c2e2`；缺 PR。selector/bootstrap 可独立回退。|
|successor9 final ledger：`5ab91a11d`|`.team/ledgers/{baseline-bundle-successor9-v1.json,src/baseline-bundle-successor9-v1.py}`；`.team/nodes/spec-sol/baseline-bundle-successor9-final/{RESULT.md,final-compile-schema.log,final-preflight-dry-run.log,final-provenance-wt.log}`；`.team/nodes/baseline-bundle-successor9-final-review/VERDICT.md`|`recovery/bundle-s9-5ab91a` / `baseline-bundle successor9: freeze SDK-root recovery ledger`|依赖 `0fdee1072`；缺 PR。ledger 独立 PR。|
|successor9 apparatus 归因：`918b4c06f`, `efed31310`|`.team/nodes/baseline-bundle-successor9-apparatus-diagnosis/VERDICT.md`；`.team/nodes/_driver/baseline-bundle-successor9-v1.out`；ledger 状态更新|`recovery/bundle-s9-918b4c`, `recovery/bundle-s9-efed31` / `baseline-bundle successor9: preserve AVD diagnosis` / `baseline-bundle successor9: preserve apparatus unjudgeable state`|分别 PR，依赖 `5ab91a11d`；缺 PR。状态提交`不可单独回退`。|
|successor10 AVD bootstrap：`ad7468f74`, `9ea73dff8`|AVD selector/owned-emulator acceptance；`.team/nodes/spec-sol/baseline-bundle-successor10/BOOTSTRAP-RESULT.md`；`.team/nodes/baseline-bundle-successor10-bootstrap-review/VERDICT.md`；`.team/nodes/baseline-bundle-successor10-wt-preflight/{COMMAND.md,VERDICT.md}`|`recovery/bundle-s10-ad7468`, `recovery/bundle-s10-9ea73d` / `baseline-bundle successor10: preflight and freeze bounded AVD bootstrap`|分别 PR，`9ea73d` 依赖 `ad7468`；缺 PR。bootstrap 与 WT 预检不可合并为一 PR。|
|successor10 final ledger：`c10186285`|`.team/ledgers/{baseline-bundle-successor10-v1.json,src/baseline-bundle-successor10-v1.py}`；`.team/nodes/spec-sol/baseline-bundle-successor10-final/{RESULT.md,final-compile-schema.log,final-preflight-dry-run.log,final-regressions.log}`；`.team/nodes/baseline-bundle-successor10-final-review/VERDICT.md`|`recovery/bundle-s10-c10186` / `baseline-bundle successor10: freeze AVD recovery ledger`|依赖 `9ea73dff8`；缺 PR。ledger 独立 PR。|
|successor10 verify/AVD 归因：`7c1a856ba`, `13c301fd0`|`.team/nodes/baseline-bundle-successor10-verify-diagnosis/VERDICT.md`；`.team/nodes/_driver/baseline-bundle-successor10-v1.out`；ledger 状态更新|`recovery/bundle-s10-7c1a85`, `recovery/bundle-s10-13c301` / `baseline-bundle successor10: preserve stale-gate diagnosis` / `baseline-bundle successor10: preserve verify unjudgeable state`|分别 PR，依赖 `c10186285`；缺 PR。状态更新`不可单独回退`。|
|successor11 verify bootstrap：`ebd0dc5c2`|`.team/ledgers/acceptance/baseline-bundle-successor11-verify{.py,.sh,-regression.sh}`；fixture `verify-contract.json`；`.team/nodes/spec-sol/baseline-bundle-successor11/{BOOTSTRAP-RESULT.md,bootstrap-*.log}`；`.team/nodes/spec-sol/baseline-bundle-successor11-bootstrap-review/{VERDICT.md,tests.log}`|`recovery/bundle-s11-ebd0dc` / `baseline-bundle successor11: freeze archive-backed verify gate`|依赖 successor10 final；缺 PR。bootstrap 可独立回退。|
|successor11 WT preflight：`3597b8232`|`.team/nodes/baseline-bundle-successor11-wt-preflight/{COMMAND.md,VERDICT.md}`|`recovery/bundle-s11-3597b8` / `baseline-bundle successor11: record retained-WT preflight`|依赖 `ebd0dc5c2`；缺 PR。只读文档，可独立回退。|
|successor11 recovery ledger：`7e8c93bda`|`.team/ledgers/{baseline-bundle-successor11-v1.json,src/baseline-bundle-successor11-v1.py}`、四个 consume/structure/final acceptance；`.team/nodes/spec-sol/baseline-bundle-successor11-final/{RESULT.md,final-compile-schema.log,final-preflight-dry-run.log,final-consume-*.log,final-structure-teeth.log}`；`.team/nodes/baseline-bundle-successor11-final-review/VERDICT.md`|`recovery/bundle-s11-7e8c93` / `baseline-bundle successor11: define recovery ledger and consume gates`|依赖 `3597b8232`；缺 PR。ledger/acceptance 是一个提交内的 bootstrap，但不应和后续 review commit 合并。|
|successor11 launch review：`49250115a`|`.team/nodes/baseline-bundle-successor11-final-wt-preflight/{COMMAND.md,VERDICT.md}`（cwd 修正需保持在该提交之后审计）；`.team/nodes/spec-sol/baseline-bundle-successor11-final-review/{VERDICT.md,tests.log}`|`recovery/bundle-s11-492501` / `baseline-bundle successor11: record launch-readiness review`|依赖 `7e8c93bda`；缺 PR。文档/评审可独立 PR。|
|successor11 user-gate wait：`80f6c3ce3`|`.team/nodes/baseline-bundle-successor11-user-gate-recovery/VERDICT.md`；明确 `unjudgeable/awaiting-human`，不伪造 USER-GATE|`recovery/bundle-s11-80f6c3` / `baseline-bundle successor11: preserve awaiting-human gate`|依赖 `49250115a`；缺 PR。只读裁定记录可独立回退，但回退会丢失等待用户事实，须先有替代审计记录。|

## PR 覆盖补表（45/45）

本次 REST 投影快照（#17–#62 共 46 条，含 `number/state/merged_at/head/title`）为
`.team/nodes/baseline-bundle-pr-recovery/PR-17-62-SNAPSHOT.json`，SHA-256 为
`c7c14cab2a20eea7afadc4ac85bb3f33452031bcd6f8b39dba026b87685f38bf`。原始 `state` 为
`closed`；`merged_at` 非空才归类为 `MERGED`。

下列映射逐个对应上表的 45 个 commit 单位；每个状态均由上述 `gh api` REST 快照只读核实，
`MERGED` 后附 GitHub PR 号。#61/#62 的时间顺序晚于 #60，但不改变前序依赖。

- baseline：`9468854e1` → **#17 MERGED**；`c4846de54` → **#18 MERGED**；`488a1f25b` → **#19 MERGED**。
- successor1：`ef7a02c1d` → **#20 MERGED**；`a3421f19f` → **#21 MERGED**。
- successor2：`2263fd95f` → **#22 MERGED**；`98bf318e7` → **#23 MERGED**；`99675a86a` → **#24 MERGED**。
- successor3：`37baef7f7` → **#25 MERGED**；`f0fce0a44` → **#26 MERGED**；`171db68d9` → **#27 MERGED**；`3401706a8` → **#28 MERGED**；`ec1145820` → **#29 MERGED**。
- successor4：`2f76349af` → **#30 MERGED**。
- successor5：`b2af6fd18` → **#31 MERGED**；`4c1005867` → **#32 MERGED**。
- successor6：`548572dfd` → **#33 MERGED**；`87ce64f03` → **#34 MERGED**；`3528c2ad5` → **#35 MERGED**；原 projection bootstrap `fdf7f6497` → **#61 MERGED（HTTP 504 后审计补救）**。
- successor7：`6e729d729` → **#36 MERGED**；`da46a6b2b` → **#37 MERGED**；`0df3562b7` → **#38 MERGED**；`79cd08f0f` → **#39 MERGED**；`1df02dfb0` → **#40 MERGED**；`25517d808` → **#41 MERGED**；`132e63576` → **#42 MERGED**。
- successor8：`1481f4f8c` → **#43 MERGED**；`61af5e3c4` → **#44 MERGED**；`6dbf110a5` → **#45 MERGED**。
- successor9：`0fdee1072` → **#46 MERGED**；`e6c2e2625` → **#47 MERGED**；`5ab91a11d` → **#48 MERGED**；`efed31310` → **#49 MERGED**；`918b4c06f` → **#50 MERGED**。
- successor10：`9ea73dff8` → **#51 MERGED**；`ad7468f74` → **#52 MERGED**；`c10186285` → **#53 MERGED**；`13c301fd0` → **#54 MERGED**；`7c1a856ba` → **#55 MERGED**。
- successor11：`ebd0dc5c2` → **#56 MERGED**；`3597b8232` → **#57 MERGED**；`7e8c93bda` → **#58 MERGED**；`49250115a` → **#59 MERGED**；`80f6c3ce3` → **#60 MERGED**。
- 独立审计 review（不计入 45）：**#62 MERGED**，head=`pr/baseline-bundle-s6-fdf7-review`。

## GitHub PR #17–#62 只读状态

以下为本次 `gh api` REST 快照的逐号投影；每号均核对 head、title、state、merged_at。

|PR|head|title|state|mergedAt|
|---:|---|---|---|---|
| #17 | `recovery/bundle-baseline-9468854` | 编排基线资产丢失死锁根治链 | MERGED | 2026-08-25T11:55:05Z |
| #18 | `recovery/bundle-baseline-c4846de` | 记录基线包首格形式红证据 | MERGED | 2026-08-25T11:55:04Z |
| #19 | `recovery/bundle-baseline-488a1f` | 修正基线包先红形式契约 | MERGED | 2026-08-25T11:55:05Z |
| #20 | `recovery/bundle-s1-ef7a02c` | 建立基线包根治审计连续性账本 | MERGED | 2026-08-25T11:55:05Z |
| #21 | `recovery/bundle-s1-a3421f` | 记录successor复用旧worktree不可判证据 | MERGED | 2026-08-25T11:55:04Z |
| #22 | `recovery/bundle-s2-2263fd` | 建立全新worktree基线包根治账本 | MERGED | 2026-08-25T11:55:05Z |
| #23 | `recovery/bundle-s2-98bf318` | 基线包根治先红格通过并派出三席 | MERGED | 2026-08-25T11:55:05Z |
| #24 | `recovery/bundle-s2-99675a` | 基线包探针与测试通过实现格不可判 | MERGED | 2026-08-25T11:55:05Z |
| #25 | `recovery/bundle-s3-37baef7` | 记录successor3启动审查否决 | MERGED | 2026-08-25T11:55:04Z |
| #26 | `recovery/bundle-s3-f0fce0` | 冻结基线包successor3真实判据bootstrap | MERGED | 2026-08-25T11:55:05Z |
| #27 | `recovery/bundle-s3-171db68` | 冻结并审过基线包successor3最终账本 | MERGED | 2026-08-25T11:55:04Z |
| #28 | `recovery/bundle-s3-3401706` | 记录successor3实现不可判与探针红 | MERGED | 2026-08-25T11:55:04Z |
| #29 | `recovery/bundle-s3-ec1145` | 归因successor3残留旧门与SDK前置缺失 | MERGED | 2026-08-25T11:55:04Z |
| #30 | `recovery/bundle-s4-2f7634` | 冻结并审过基线包successor4账本 | MERGED | 2026-08-25T11:55:04Z |
| #31 | `recovery/bundle-s5-b2af6f` | 冻结并审过基线包successor5账本 | MERGED | 2026-08-25T11:55:04Z |
| #32 | `recovery/bundle-s5-4c1005` | 基线包三门通过实现格实质红 | MERGED | 2026-08-25T11:55:04Z |
| #33 | `recovery/bundle-s6-548572` | 冻结successor6最终门与审查谱系 | MERGED | 2026-08-25T11:55:04Z |
| #34 | `recovery/bundle-s6-87ce64` | 冻结并审过基线包successor6最终账本 | MERGED | 2026-08-25T11:55:05Z |
| #35 | `recovery/bundle-s6-3528c2` | 基线包四格通过验证格不可判 | MERGED | 2026-08-25T11:55:04Z |
| #36 | `recovery/bundle-s7-6e729d` | 记录successor7设备bootstrap安全审查否决 | MERGED | 2026-08-25T11:55:04Z |
| #37 | `recovery/bundle-s7-da46a6` | 冻结successor7设备与永久夹具bootstrap | MERGED | 2026-08-25T11:55:04Z |
| #38 | `recovery/bundle-s7-0df3562` | 记录successor7保留工作树安全快进 | MERGED | 2026-08-25T11:55:04Z |
| #39 | `recovery/bundle-s7-79cd08` | 冻结并审过successor7最终账本 | MERGED | 2026-08-25T11:55:04Z |
| #40 | `recovery/bundle-s7-1df02d` | 记录successor7命令执行器方言拒绝 | MERGED | 2026-08-25T11:55:04Z |
| #41 | `recovery/bundle-s7-25517d` | 审过successor7兼容命令执行器组合 | MERGED | 2026-08-25T11:55:04Z |
| #42 | `recovery/bundle-s7-132e63` | 记录successor7三前格产物与waiter恢复方案 | MERGED | 2026-08-25T11:55:04Z |
| #43 | `recovery/bundle-s8-1481f4` | 冻结并审过successor8命令恢复账本 | MERGED | 2026-08-25T11:55:04Z |
| #44 | `recovery/bundle-s8-61af5e` | successor8三前格通过设备格不可判 | MERGED | 2026-08-25T11:55:04Z |
| #45 | `recovery/bundle-s8-6dbf11` | 归因successor8设备镜像缺失不可判 | MERGED | 2026-08-25T11:55:04Z |
| #46 | `recovery/bundle-s9-0fdee1` | 冻结successor9唯一SDK根选择器bootstrap | MERGED | 2026-08-25T11:55:04Z |
| #47 | `recovery/bundle-s9-e6c2e2` | 记录successor9保留工作树SDK快进 | MERGED | 2026-08-25T11:55:04Z |
| #48 | `recovery/bundle-s9-5ab91a` | 冻结并审过successor9设备恢复账本 | MERGED | 2026-08-25T11:55:04Z |
| #49 | `recovery/bundle-s9-efed31` | successor9三前格通过设备仍不可判 | MERGED | 2026-08-25T11:55:04Z |
| #50 | `recovery/bundle-s9-918b4c` | 归因successor9新AVD创建失败 | MERGED | 2026-08-25T11:55:04Z |
| #51 | `recovery/bundle-s10-9ea73d` | 冻结successor10有界AVD创建bootstrap | MERGED | 2026-08-25T11:55:04Z |
| #52 | `recovery/bundle-s10-ad7468` | 记录successor10保留工作树AVD快进 | MERGED | 2026-08-25T11:55:04Z |
| #53 | `recovery/bundle-s10-c10186` | 冻结并审过successor10设备恢复账本 | MERGED | 2026-08-25T11:55:04Z |
| #54 | `recovery/bundle-s10-13c301` | successor10设备链通过验证格不可判 | MERGED | 2026-08-25T11:55:04Z |
| #55 | `recovery/bundle-s10-7c1a85` | 归因successor10验证格残留实时ADB旧夹具门 | MERGED | 2026-08-25T11:55:04Z |
| #56 | `recovery/bundle-s11-ebd0dc` | test: bootstrap successor11 fresh verify gate | MERGED | 2026-08-25T11:55:04Z |
| #57 | `recovery/bundle-s11-3597b8` | docs: record successor11 retained worktree preflight | MERGED | 2026-08-25T11:55:04Z |
| #58 | `recovery/bundle-s11-7e8c93` | test: define successor11 recovery ledger | MERGED | 2026-08-25T11:55:04Z |
| #59 | `recovery/bundle-s11-492501` | docs: verify successor11 launch readiness | MERGED | 2026-08-25T11:55:04Z |
| #60 | `recovery/bundle-s11-80f6c3` | docs: record successor11 user gate wait | MERGED | 2026-08-25T11:55:04Z |
| #61 | `recovery/bundle-s6-fdf7f6-audit` | baseline-bundle successor6: restore projection bootstrap audit | MERGED | 2026-08-25T12:04:24Z |
| #62 | `pr/baseline-bundle-s6-fdf7-review` | baseline-bundle successor6: record recovery review | MERGED | 2026-08-25T12:07:00Z |

## 已直接落 main 的补救规则

1. 不对现有 `main` 做 reset、rebase、filter 或 force-push。以上每个短 SHA 都已由
   `git merge-base --is-ancestor <sha> HEAD` 核实为当前 `main` 祖先。
2. 对可回退的代码/判据关键点，从精确父提交建立对应 `recovery/bundle-*` 分支，重放
   **仅该一个提交**，再以表中前序 PR 为 base 做 stacked PR。PR body 必须写原 SHA、产物路径、
   acceptance/review 路径和“原提交已落 main”的事实。
3. 对“不可单独回退”的 ledger/driver 状态提交，PR 只能作为审计/补救说明或导出等价
   证据，不得用 revert 清掉旧 attempt、红态、unjudgeable 或 P0 记录；若要恢复产品状态，
   必须另建新 case/新提交并保留旧链。
4. 每个下游 PR 只依赖紧邻的前序 PR；不得把 baseline→successor11 全部 cherry-pick
   到一个总 PR。GitHub merge 后再推进下一个 stacked PR，才能得到独立回退点。
5. 由于本地没有 remote，实际创建前需由受管 mirror 工具提供过滤后的远端基线；本盘点
   不执行该动作。

## mirror-pr 工具现状与临时安全入口

`tools/mirror-pr.sh` 在 `set -euo pipefail` 后立即执行：

```sh
python3 -c 'import git_filter_repo;print(git_filter_repo.__file__)'
```

本机只读核验结果为 `ModuleNotFoundError: No module named 'git_filter_repo'`；
`git-filter-repo` 可执行文件也不存在。直接运行会在 clone/filter/push/gh create 之前退出。

本轮 PR 恢复使用的是既有脚本的临时受管入口：在不改脚本、不系统安装、无未过滤
原仓直推的边界内，以 uv 临时固定依赖后调用原脚本：

```sh
uv run --with git-filter-repo==2.47.0 -- bash tools/mirror-pr.sh <recovery-branch>...
```

`mirror-pr` 在过滤临时仓内按设计执行 `git push --force`；这不是未过滤原仓直推，
而是该镜像流程的受管过滤发布步骤。

该入口只把 `git_filter_repo` 注入本次脚本进程；本机普通 `python3` import 仍失败，
故无系统安装。#17–#60 的 44 个关键点 PR、#61 的 fdf7 HTTP504 后审计补救及 #62
独立 review 均已形成 `MERGED` 结果，作为该临时入口已被使用的远端结果证据。本次盘点
没有重新执行 mirror、push 或 create。

仓内未发现 vendored `git_filter_repo`、virtualenv、requirements 或其它 mirror wrapper；
现有入口只有 `tools/mirror-pr.sh`、`tools/mirror-push.sh`、`tools/mirror-pr-serve.sh`。
`git help -a` 仅显示 Git 内置 `filter-branch`、`fast-import`、`replace` 等低层命令；
它们不是该脚本相同的过滤/mailmap/commit-callback 受管实现，且 `filter-branch` 是重写
工具，不能在“不改脚本、无未过滤原仓直推、不系统安装”的边界下替代 `git_filter_repo`。

已有 driver 日志只证明早期的 `input/*` mirror 尝试：
`.team/nodes/_driver/mirror-pr.log` 记录 `No commits between main and input/contract-v1`；
`mirror-pr2.log`/`mirror-pr3.log` 对应 input/instr-v1 与 input/envhygiene-v1，均不是
baseline-bundle。baseline/successor 的远端成功状态以本次 `gh` PR #17–#62 快照为准，
不以这些旧 driver 日志代替。

因此 PR 谱系的唯一缺口已归零：45/45 关键点均有 `MERGED` PR；额外 #62 为独立 review。
工具层仍应长期提供同等固定版本的受管入口，但当前恢复不依赖系统安装或未过滤原仓直推。
未改产品、未改历史、未改脚本。

verdict: pass
