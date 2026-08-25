# successor6 final startup review

## 判定

`verdict: refutes`。

除追踪性外，fresh 只读核验通过：compiled ledger 为
`ledger.baseline-bundle.successor6.v1` revision 1，9 个 task 全为 `planned`，无
attempt/status 字段、无 transitions，10 条依赖形成
`repro → (test ∥ probe ∥ impl) → verify → user-gate → migrate → measure → final`；
首 frontier 是 repro。三枚 `wt-maple-core`、`wt-indigo-tests`、`wt-falcon-review`
在磁盘和 Git metadata 中均不存在，所有资源声明 provenance
`fdf7f64970351d51e616491850e2c49d03d24b22` 且 HEAD 是其后代。

## 门与破坏齿

17 个 ScriptRef 均存在并绑定 `${worktree}`、期望 0/不可判 2。test、probe、impl、
verify、final 的 successor6 required 集合精确；legacy impl/probe、旧 argv、坏四态
和 missing mutation 分别保持 1/1/1/1/2。impl wrapper 不调用旧
`baseline-bundle-impl.sh` 或 successor3 impl wrapper，仍串接 successor5 SDK、
successor6 projection/deep、真实 canonical fixture 与 controlled bypass。

对真实 successor5 manifest 的独立重算确认旧循环前缀门为 1，新内容 identity、两个
非循环独立槽位和 id-scoped archive 为 0。fresh projection 矩阵为合法 0、bundle id
篡改/越界/槽位改名/交换/旧 id 路径/malformed 为 1、missing 为 2。deep gate 仅将
独立 build 的循环 prefix 约束移至先行 projection gate，来源、APK/运行内容/签名、
provenance、双归档、A2、focused 防伪检查未弱化。SDK continuity、1.10、真机 gate、
迁移前置、历史 provenance 与 preflight/dry-run 首 frontier 均保留。

## 阻断性事实

“所有引用 tracked”不成立。当前 Git 状态中 successor6 final ledger、DSL、final-only
acceptance scripts（以及 final 任务书）均为未跟踪；`git cat-file` 进一步证明 final
ledger 与 DSL 不存在于声明的 bootstrap commit `fdf7f649...`。因此该 commit 无法
重取启动所需 final package，存在 candidate 自引用/未提交输入风险。此为启动前结构
反证，不是通过后才可忽略的工作树噪声。

本审查未启动 ledger、未创建 WT、未修改被审包或产品实现。完整证据与命令见同目录
`tests.log`。

verdict: refutes
