# successor7 final 编排包结果

## 产物

- DSL 源：`.team/ledgers/src/baseline-bundle-successor7-v1.py`
- compiled ledger：`.team/ledgers/baseline-bundle-successor7-v1.json`
- 总任务书：`.team/nodes/spec-sol/baseline-bundle-successor7/final-任务书.md`
- 新增路径级任务书：`final-{continuity,user-gate,migrate,measure,final}-任务书.md`
- 新增判据：`.team/ledgers/acceptance/baseline-bundle-successor7-{structure,test,probe,user-gate,migrate,measure,final}.sh`
- 已冻结并复用的 bootstrap 门：`baseline-bundle-successor7-{continuity,owned-emulator,apparatus,impl-bypass,verify}.sh` 及 permanent fixture。

## 图与机械契约

ledger id=`ledger.baseline-bundle.successor7.v1`，revision=1，无 statuses。九格固定为 `continuity ∥ apparatus-test ∥ apparatus-probe → apparatus(command) → fresh verify → user-gate → migrate → fresh measure → final`。retained 链为 `wt-maple-core`；两隔离审查 WT 为 `wt-s7-cedar`/`wt-s7-orbit`，两者启动前均不存在且未注册。

apparatus executor 精确 argv=`/bin/sh .team/ledgers/acceptance/baseline-bundle-successor7-owned-emulator.sh`，cwd=`${worktree}`，expect=0，unjudgeable=[2]；required 精确为 successor7 apparatus/fixture/continuity。结构门还锁定了所有格 required 全集、无 legacy 门回流、两审查格的无设备指令和完整写隔离。

后续门未弱化：测试禁缓存，性能测量先 envcheck，三夹具 A/B/A/B 每段 n>=10，nearest-rank p50/p95，同批 A2/B，所有 B/A<=1.10；终局仍需用户真机蜂窝+广州中转“秒开、没有空白”。四态统一 0=通过、1=有效反证、2=不可判、未派发=不适用。

## fresh 验证

- `/usr/bin/python3` 使用 command-executor ledgerdsl commit `7485102b26ed34eb828e94900902147d5e00e995`；两次 compile byte-identical。
- command-enabled ledger.v2 jsonschema：pass，9 tasks/8 dependencies。
- pinned command-enabled `ledger-run --preflight --json`：rc0，issues=[]。
- `ledger-run --dry-run --json`：rc0，frontier 精确为 continuity+apparatus-test+apparatus-probe，apparatus 仍 dependency_unsatisfied。
- `sh -n`/shellcheck：pass；DSL `py_compile`：pass。
- structure green=0；argv篡改、legacy required、statuses、并行 WT 冲突、依赖删除、首 frontier 设备禁令删除均=1；compiled ledger 缺失=2；test/probe 缺 RED/PROBE 先红=1。
- retained `wt-maple-core` HEAD=`da46a6b2b538faf7954fa4f9af7e8c09a194f45e`，fresh continuity=0；bootstrap 与 final 输入路径已分别用 `git cat-file` 从 da46/`0df3562b7f7479ce4a2683f8c98546fab69bcf1c` 取回。
- 本轮未 drive/apply，未创建 WT，未启动 adb/qemu/emulator。

日志：`final-{byte-syntax,compile-schema,preflight-dry-run,structure-teeth,provenance-catfile-wt}.log`。本轮新生 final 包仍为待 leader 审核提交的 working-tree 产物；禁止在该提交可取回前启动账本。这是本格“不 commit、不启动”的正常交接前置，不改写 da46/0df 历史。

verdict: pass
