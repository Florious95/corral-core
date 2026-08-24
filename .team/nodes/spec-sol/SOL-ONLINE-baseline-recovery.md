# spec-sol 能力恢复：基线资产丢失死锁

## 供给与席位边界

- 我是唯一的任务书与机械判据撰写席 `spec-sol`；写任务书、判据和 ledgerdsl 账本源时必须使用精确 slug `gpt-5.6-sol`，产物先交 leader 审核，审核后才能 `plan/apply`。
- 产品执行席默认精确 slug 是 `gpt-5.6-luna`。当前可写入产品工作树的干活席是由账本派发的 Codex luna 席（如 `sampler-dev-luna2`、`sampler-test-luna2`、`sampler-review-luna2`）；它们在各自 write_paths 内完成 impl/test/probe/verify，并各自只交一次标准 `report_result`。
- 备测席不是干活席的同义词，也不是失败后的人工替补。它只为后续获准的测试保留，未被账本依赖解锁时不得接产品任务、不得主动启动模拟器或采样、不得自找替代 A；Grok 执行席当前更是未开放，除非用户点名，不能向它派 Team Agent 产品消息。需要它真正干活时，必须由新账本把它变成有任务、有写集、有机械 required 的正式席位。
- Claude 当前已登出；Deepseek、Fable 5 禁用。不能把 leader 自己运行在 Grok 上推导成 Grok 工作席可调度。

## 当前账本坐标

我以当前编译账本而不是 handoff 的叙述为准核得：

- ledger id：`ledger.perf-regress.v1`
- revision：`4`
- 四个 task 共同锁定的 git provenance SHA：`c2346f856abf99d25f4e7a1090490cda88525503`

账本源没有自定义 statuses；verify 依赖 test、probe、impl 全部 success。active ledger 的 durable result 归 waiter，不能人工 collect；磁盘已有产物也不会绕过 `report_result` 自动触发判据。

## 四态

- 通过，exit 0：量具、身份和必需证据完整，机械条件全部成立。
- 产品失败，exit 1：量具有效且证据充分，产品、测试或约定交付明确不满足；有效样本任一 B/A 大于 1.10 属于此态。
- 不可判，exit 2：环境、工具、身份或证据不足，无法形成产品结论；`measurement: unjudgeable`、精确 A 包缺失、SDK/Gradle 无法执行都必须落到 exit 2，不能折成 0 或 1。
- 不适用：仅表示账本从未派发该格；已派发但跑不起来仍是不可判。

历史 `.team/perf/baseline-20260822.json` 是 `INCONCLUSIVE`/全 null，只是历史材料，不是可执行性能门。实验室可执行门仍是同批 A/B/A/B、三夹具四段、每包每格 n>=10、nearest-rank p50/p95 全部 B/A<=1.10；最终金标准仍是用户真机在蜂窝加广州中转路径打开会话“秒开、没有空白”。实验室绿不能替代真机终局，真机感觉良好也不能把实验室红改成绿。

## 我准写与不准做的事

我准写：leader 裁定边界内的四要素任务书、独立可执行且具四态的 POSIX 机械判据、通过审核后所需的 ledgerdsl 源，以及本席自证/问询产物；判据中的破坏齿只冻结性质，具体选址留给实现后的独立判者。

我不准写产品实现，不改 App/server/tools 的产品或测量实现，不提交、推送、并线、开 worktree，不启动账本、不 `ledger-run`、不人肉补投或催产品格、不 collect active ledger result、不改阈值或判据放行、不伪造基线资产，也不读取 profile 原文、`tailnet-test.env`、Shadowrocket plist、`tailscale_keys.bin`、生产 daemon 明文日志或无过滤进程参数。正常出口只有落盘产物加一次 `report_result`，不另发进度消息给 leader。

## “基线资产丢失死锁根治”进入新链的原则

当前冻结 A 的身份是 tag `baseline-20260822-release`、APK md5 `0907d6881bb1e034ef33a49f89afaa44`、大小 35044459 bytes。按 tag 重建得到同体积但 md5 `2fda1fdec68f5aba9389b6a0a1e8598d`，这是明确反例，不能冒充 A；没有精确 A 时，新候选 B' 的 fresh A/B/A/B 合法结论只能是不可判。

根治不能靠继续 park 旧测量格或临时“找一个差不多的 APK”，而应先建立一条独立、可证伪的资产恢复前置链；本次只描述链，不落新任务书、判据或账本：

1. 先由 sol 写“基线资产恢复与封存”任务书和机械判据，leader 审核。任务书冻结允许查找/接收的来源、精确 md5+size、provenance 字段、受控封存位置和禁止分发边界；若封存位置或允许来源尚未裁定，先由 leader 裁定，不能由执行席猜。
2. 新链仍采用 impl、test、probe 并行，verify 依赖三者 success。impl 只从获准来源恢复候选并写 manifest；test 用仓内假夹具先红，覆盖正确身份、错误 md5、截断包和缺文件；probe 设计来源/哈希操作数与独立破坏齿。三者都不得启动真实性能测量。
3. 独立 verify 对真实候选逐字核 md5、字节数、可读性与来源 manifest，并从稳定受控位置再读一次，证明不是 worktree 临时件。把候选换成同大小错误 md5、篡改 manifest 或移走封存件时，门必须转红；缺授权来源、文件不可读或精确资产仍不存在时必须 exit 2。
4. 只有资产 verify exit 0，后续 fresh 性能测量格才可被依赖解锁；它使用冻结 A 对新 B' `daca6170aa58a8054aa3d20537a61e64` 跑唯一一批三夹具四段 A/B/A/B。不得复用 r13 raw、不得重跑旧 B `3ebc9c55703c780c842a2f410b85034e` 抽绿。
5. 终审必须分别核资产身份、fresh 原始样本和独立 nearest-rank 重算：资产/环境/样本不足为 exit 2，有效样本任一比值大于 1.10 为 exit 1，全部实验室条件成立才为 exit 0；之后仍保留用户真机金标准作为最终验收。

这样“资产不存在”会被前置门明确表达为不可判，而不会把宿主打脏、启动无效测量或把错误 A 带进性能结论；资产一旦恢复又有可重复读取的封存与 provenance，后续账本不再依赖某个席位或 worktree 的偶然文件。

verdict: pass
