# baseline-bundle successor8 创作结果

## 产物

- DSL 源：`.team/ledgers/src/baseline-bundle-successor8-v1.py`
- compiled ledger：`.team/ledgers/baseline-bundle-successor8-v1.json`
- 任务书：`.team/nodes/spec-sol/baseline-bundle-successor8/任务书.md`
- 结构/谱系门：`.team/ledgers/acceptance/baseline-bundle-successor8-structure.sh`
- fresh 日志：本目录 `final-{syntax,compile-schema,preflight-dry-run,structure-teeth,provenance-wt}.log`

## 图与连续性

`ledger.baseline-bundle.successor8.v1` revision1 共9 tasks/8 `requires_success` 边，无 statuses。首 frontier 精确是三个 command：

- `continuity-consume` 在原 `wt-maple-core` 运行 successor7 continuity；
- `apparatus-test-consume` 在原 `wt-s7-cedar` 运行 successor7 test required，消费已有 RED.md；
- `apparatus-probe-consume` 在原 `wt-s7-orbit` 运行 successor7 probe required，消费已有 PROBE.md。

三者都是只读 `write_paths=[]`，command 四态为 expect0/unjudgeable2，不发 agent、不等新 report_result。三边成功才解锁原 apparatus owned-emulator command，再串行 fresh verify→user-gate→migrate→fresh measure→final。apparatus required 仍是 apparatus/fixture/continuity。结构门将六个后续格的 title/owner/budget/handoff/acceptance/executor/command/worktree/write paths/environment fidelity 与 successor7 r1 独立对照，并要求 read paths 只可增加审计 provenance，因此 1.10、envcheck、A/B/A/B、真机 gate 与迁移前置均未弱化。

## 冻结证据

- final commit `79cd08f0f53d0bd2e44dfd4d4e2fb33cbde001f2` 与 command review commit `25517d808cc19e3f002ceba51000b2a269bec362` 均是 provenance pin `132e635761060c92edbcc789d0eac852c2a4d1e4` 的祖先，指定路径 `git cat-file` 可取回。
- command-compatible pair 精确 source/schema/DSL HEAD=`7485102b26ed34eb828e94900902147d5e00e995`，binary md5=`627f5e6fa5f47a61d23a09b918b50567`。
- r1 compiled SHA=`447d8a6f…`；两轮 dispatch 日志 SHA=`aff56f8c…`，机械重算 start/test-dispatch/probe-dispatch 均精确2次；frontier verdict SHA=`1191853e…`。
- 原三 WT 均注册存在；RED SHA=`04cdbd66…`且末行 `test: pass`，PROBE SHA=`88868a1a…`且末行 `probe: pass`。
- successor7 r1 源/编译账本 diff=0；successor8 lease 不存在；本轮 collect=false、redispatch=false、drive=false、device=false。

## fresh 验证

- 两次 DSL compile byte-identical；command schema jsonschema=0（9 tasks/8 dependencies）。
- 固定 binary `ledger-run --preflight --json`=0，issues=[]；`--dry-run --json`=0，frontier 只含三 command，apparatus=`dependency_unsatisfied`。
- `sh -n`、shellcheck、`py_compile` 均通过。
- structure green=0；consume executor/argv/WT/required 泄漏、依赖删除、statuses、apparatus required 弱化、measure acceptance 弱化均=1；compiled ledger 缺失=2。

本包仍为待 leader 审核提交的 authoring 产物；本轮未启动账本。

verdict: pass
