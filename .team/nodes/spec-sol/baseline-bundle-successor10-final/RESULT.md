# baseline-bundle successor10 final recovery ledger 创作结果

## 产物

- DSL 源：`.team/ledgers/src/baseline-bundle-successor10-v1.py`
- compiled ledger：`.team/ledgers/baseline-bundle-successor10-v1.json`
- 路径级总任务书：`.team/nodes/spec-sol/baseline-bundle-successor10-final/任务书.md`
- 结构/谱系/破坏齿门：`.team/ledgers/acceptance/baseline-bundle-successor10-structure.sh`
- fresh 日志：本目录 `final-{syntax,compile-schema,preflight-dry-run,structure-teeth,regressions,provenance-wt}.log`

## 新账本与连续性

新 identity=`ledger.baseline-bundle.successor10.v1`、revision=1，共 9 tasks、8 条 `requires_success`，无 statuses。首 frontier 精确是 successor9 r4 原三 WT 的 command consume：maple continuity、cedar RED required、orbit PROBE required；三者 `write_paths=[]`、expect0/unjudgeable2，不发 agent。它们 fresh 成功后才解锁 retained maple 的 successor10 apparatus command。

apparatus argv 精确为 `.team/ledgers/acceptance/baseline-bundle-successor10-owned-emulator.sh`，required 是 successor10 AVD regression、successor9 SDK selector regression 与 successor7 apparatus/fixture/continuity 五项合取；command/handoff 同时要求 0600 `AVD-CREATE.json` 与 `APPARATUS.json`。后续严格串行 fresh verify→user-gate→migrate→fresh measure→final，并相对 successor9 r4 对 title/owner/budget/handoff/required/mechanical/WT/write paths/环境忠实度逐字段未弱化。

## 冻结谱系与量具

- resources provenance pin=`ad7468f747d421305279632f0db9cbc227b08cd4`；`efed31310` successor9 r4、`918b4c06f` AVD 归因、`9ea73dff8` reviewed bootstrap、`ad7468f74` retained-WT 证据全部 ancestry 与 cat-file 可取回。
- successor9 r4 ledger/driver SHA-256 分别为 `f579b99d...84e5` / `24a014cf...72fd`；结构门独立确认三 consume succeeded，旧 apparatus 是 exit2/acceptance_pending，未改写历史。
- retained WT HEAD：maple=`9ea73dff8`、cedar/orbit=`25517d808`；RED/PROBE digest 与 bootstrap bytes 均闭合。
- command-compatible pair 固定 HEAD=`7485102b26ed34eb828e94900902147d5e00e995`、binary md5=`627f5e6fa5f47a61d23a09b918b50567`。

## Fresh 机械结果

- DSL 两次 compile 与 compiled ledger byte-identical；jsonschema rc0，tasks=9/dependencies=8/revision=1。
- 固定 pair preflight rc0、issues=[]；dry-run rc0，frontier 只含三个 command consume，apparatus=`dependency_unsatisfied`。
- `sh -n` 6 文件、shellcheck 6 文件、Python byte compile 3 文件全绿。
- structure green rc0；旧 apparatus argv、删除 AVD/selector required、consume 降级 agent、measure 弱化、statuses 回流、删除依赖/AVD artifact、错误 pin 均 rc1；compiled 缺失 rc2。
- retained maple fresh AVD regression rc0、SDK selector regression rc0、continuity rc0；均未运行 production owned wrapper，未启动 adb/AVD/emulator/qemu。
- successor10 lease 不存在；本轮未 drive、collect、重派、改旧 ledger/attempt、改 App/server或 commit。

性能后链仍要求 strict envcheck、同批三夹具 A/B/A/B、每夹具每段 n>=10、nearest-rank p50/p95、所有 B/A<=1.10；终局仍由用户真机蜂窝+广州中转“秒开、没有空白”裁决。

verdict: pass
