VERDICT: supports

# t.e6.rv · 审 pr/e6-upload-success（089 §3 成功态不组节点）

评审席：pr1-judge-session（异源，只读）。在 worktree `.worktrees/pr1.e6`（分支 `pr/e6-upload-success`，HEAD cd8d8d0）上审。

## 0. 前置说明：为什么 `git log main..pr/e6-upload-success` 是空的

本格硬约束禁止 commit，改动全部在 `.worktrees/pr1.e6` 工作区（说明.md L17 已显式报出，不是静默改任务定义）。实际被审 diff = 该 worktree `git diff`：
`SessionModels.kt` +24/−4、`SessionScreen.kt` +5/−13、`PackageDoc.kt` +4、新增 `StatusBannerTest.kt`。
另注：`git diff main --stat` 里出现的 .team/ledgers、tools/gate 等大片差异是 main 在分支切出（sha cd8d8d0）之后自己前进产生的，与本 PR 无关，未计入范围判定。

## 1. 说明 vs diff 逐条核对（一致）

- `SessionModels.kt:67` 新增 `internal interface TransientSuccess`；`:78` `Sent` 实现之；`:90` `UploadStatus.Success` 实现之；`:100-108` 共同出口 `bannerFrom`，第一句 `if (status is TransientSuccess) return null`（:101），when 里确实没有 `UploadStatus.Success` case——与说明.md L21、L28 完全一致。
- `SessionScreen.kt:484-486` `StatusArea` 改为只走 `bannerFrom(inputStatus) ?: bannerFrom(uploadStatus) ?: transientError`，删掉了原来 `is UploadStatus.Success -> "已附加图片"` 那个 when（删除行在 diff 中可见）。失败/在途文案语义逐条保留（Failed→message、Sending→"发送中…"、Uploading→"上传中…"），`transientError` 兜底未动。
- `PackageDoc.kt:31-35` 补 4 条 `@consumes`。diff 未新增任何 import，故这 4 条对应的是存量 import，属 T3-4 存量违规，是本格判据 A-e6-wiki（strict-t3）过门的必要改动，且说明.md L23、L53-66 已带先验红原始输出显式申报——判为在格内，不算顺手改相邻代码。

## 2. 先验红（有，且真红）

说明.md L34-46 有改前原始输出：`ae6Both_sentAndUploadSuccessDoNotComposeNodes FAILED / AssertionError at StatusBannerTest.kt:70 / expected:<0> but was:<1>`，报错文案「UploadStatus.Success 不得组『已附加图片』节点」与测试 `StatusBannerTest.kt:72` 的断言消息、与被删的旧代码 `is UploadStatus.Success -> "已附加图片"` 三方互证：红恰红在 Success 组出 1 个节点，Sent 半段当时已是 0（083 §12 已修），与契约 089 §3「兄弟成功态」的叙述吻合。
A-e6-struct 依赖新符号 `TransientSuccess`/`bannerFrom`，改前编译不过，先验红只跑 A-e6-both——说明.md L50 已显式申报，任务书也只要求这一条先验红，接受。
A-e6-wiki 的先验红也有原始输出（T3-4 FAIL、4 条违规、exit 1，说明.md L55-63）。

## 3. 判据非恒真

- A-e6-both 先制造条件再断言不出现：`StatusBannerTest.kt:58` 先置 `InputStatus.Sent`、`:66-68` 先置 `UploadStatus.Success("/tmp/shot.png")`，才断言节点数 0。先验红 `was:<1>` 证明该断言在旧代码上确实会命中，不是恒真。
- 同一测试 `:77-82` 把状态换成 `InputStatus.Failed("发送失败：超时")` 后断言节点**仍在**（assertIsDisplayed 通过），排除了「StatusArea 整个死掉导致 0 节点」这种假绿。
- A-e6-struct（`:86-92`）用只在测试里存在的 `object : TransientSuccess {}` 走 `bannerFrom` 得 null，验的是共同出口结构而非又补了一个 case。
- 判据未被改动过：`StatusBannerTest.kt` 是本格新建（判据本身就是本格产物），既有测试文件零改动（git status 仅 3 个 M 产品文件 + 该新测试）。

## 4. 本席独立复跑（不采信自报）

在 `.worktrees/pr1.e6` 上全部复跑，与说明一致：
- `:app:testDebugUnitTest --tests StatusBannerTest` → exit 0，结果 XML `tests=2 failures=0 errors=0`（ae6Both、ae6Struct 均在）。
- 全量 `:app:testDebugUnitTest` → exit 0（A-e6-suite 不倒退）。
- `build_wiki.py --check --strict-t3 --pkg dev.agentmirror.app.session` → T3-1..T3-4 全 PASS，exit 0。
- `smell-ratchet.py --face app` → `基线=16 本次=16 新增=0`，exit 0，未 --freeze、未改棘轮脚本。

## 5. 范围与否决线检查

- 无顺手重构、无格式漂移：SessionScreen 只动了 StatusArea 一个函数及其注释；SessionModels 只加接口/出口并给两个成功态补实现；无其他文件被碰。
- 测试目录不在 write_paths 一事说明.md L24 已显式报出（判据要落地必须写测试，属报出的任务定义偏差，非静默）。
- 真机截图验收（A-089-e6-ui）不在本格，说明如实申报未跑模拟器。

结论：说明与 diff 一致、先验红有原始输出且与旧代码互证、判据经制造条件+失败态对照排除恒真、四项机械判据本席独立复跑全绿、无越范围改动 ⇒ supports。

## 附记（r19 重审，2026-08-21）

r18 审时改动尚未提交（在 pr1.e6 工作区）；现 leader 已代提交为 `f737cee31`（「席位交付封版」）。
核对：该提交的逐文件 stat 与 r18 所审工作区 diff 完全一致（SessionModels.kt +31/−4、SessionScreen.kt +5/−13、PackageDoc.kt +4、StatusBannerTest.kt +93、说明.md +117；合计 246 插入/17 删除），且提交后工作树干净——内容与本席 r18 独立复跑判据（StatusBannerTest 2/2、全量单测 exit 0、strict-t3 全 PASS、smell 16→16）时逐字节相同，无新增改动混入。`git log main..pr/e6-upload-success` 现能直接看到该提交。结论维持 VERDICT: supports。
