# baseline-bundle successor9 final recovery ledger 创作结果

## 产物

- DSL 源：`.team/ledgers/src/baseline-bundle-successor9-v1.py`
- compiled ledger：`.team/ledgers/baseline-bundle-successor9-v1.json`
- 路径级总任务书：`.team/nodes/spec-sol/baseline-bundle-successor9-final/任务书.md`
- 结构/谱系/破坏齿门：`.team/ledgers/acceptance/baseline-bundle-successor9-structure.sh`
- fresh 日志：本目录 `final-{syntax,compile-schema,preflight-dry-run,structure-teeth,provenance-wt}.log`

## 图与连续性

新账本 identity=`ledger.baseline-bundle.successor9.v1`、revision=1，共 9 tasks、8 条 `requires_success`，无 statuses。首 frontier 精确是三个原 WT command consume：

- `wt-maple-core` fresh 运行 successor7 continuity；
- `wt-s7-cedar` fresh 运行 successor7 test required，消费冻结 RED；
- `wt-s7-orbit` fresh 运行 successor7 probe required，消费冻结 PROBE。

三格都是 `write_paths=[]`、expect0/unjudgeable2，不发 Agent。三边 success 才解锁 retained `wt-maple-core` 的 successor9 apparatus command；argv 已精确换成 `baseline-bundle-successor9-owned-emulator.sh`。apparatus required 在旧 apparatus/fixture/continuity 三门之外新增 SDK selector regression，旧门未删除。后续严格串行 fresh verify→user-gate→migrate→fresh measure→final；结构门逐字段证明五个后续格相对 successor8 r4 未弱化。

## 冻结谱系

- resources provenance pin=`e6c2e2625bb5ce463282b13b2949b3538a2dbbbe`；`61af5e3c4` successor8 r4、`6dbf110a5` apparatus 归因、`bd48271b9` SDK-root 归因、`0fdee1072` selector bootstrap、`e6c2e2625` retained-WT 证据全部 ancestry 与 cat-file 可取回。
- successor8 r4 compiled SHA-256=`e34f9833440abfef05deef0db40fa4b993b95c20d41ef96e1b13c36cc347caa6`，driver SHA-256=`3ba147fe15eb7221e7439335beb4fe26af18d7c3ad2ef1b1ab1928f3a794e58a`；结构门独立确认三 consume succeeded，旧 apparatus attempt 是 exit2/acceptance_pending，不改写历史。
- retained WT HEAD 精确为 maple=`0fdee1072`、cedar/orbit=`25517d808`；selector/bootstrap bytes、RED/PROBE digest 与 target untracked regular non-symlink 形状均机械闭合。
- command-compatible pair 固定 source/schema/DSL HEAD=`7485102b26ed34eb828e94900902147d5e00e995`，binary md5=`627f5e6fa5f47a61d23a09b918b50567`。

## Fresh 机械结果

- DSL 连续三份 compile byte-identical；jsonschema rc0，tasks=9/dependencies=8/revision=1。
- 固定 pair `ledger-run --preflight --json` rc0、issues=[]；`--dry-run --json` rc0，frontier 只含三个 command consume，apparatus=`dependency_unsatisfied`。
- `sh -n` 5 文件、shellcheck 5 文件、Python byte compile 2 文件均通过。
- structure green rc0；旧 apparatus argv、删除 selector required、consume 降级 agent、measure required 弱化、statuses 回流、删除依赖均 rc1；compiled 缺失 rc2。
- retained `wt-maple-core` fresh selector regression rc0，continuity rc0（four_tasks=succeeded、manifest/archive bound）。
- successor9 lease 不存在；本轮未 drive、collect、重派、运行 owned-emulator 或启动设备，未改旧 ledger/attempt，未 commit。

性能后链仍要求 strict envcheck、同批三夹具 A/B/A/B、每夹具每段 n>=10、nearest-rank p50/p95、所有 B/A≤1.10；终局仍由用户真机蜂窝+广州中转“秒开、没有空白”裁决。

verdict: pass
