VERDICT: supports

# t.sortlabel.rv · pr/sort-label 异源评审（pr2-judge-listui，Claude 席）

## 审了什么
- `git log main..pr/sort-label --stat`：1 个新 commit `84fa762f9`（icons 已落 main 后 ff 续接，无夹带）。
- `git diff main...pr/sort-label` 全量逐处看（产品码 8 文件 + 测试 8 文件 + 说明）。
- `.team/nodes/pr2-sortlabel/说明.md`（72 行）逐段核对。
- 本席在 detached HEAD `84fa762f9` 上独立复跑（分支被实现席 worktree 占用，sha 相同）。

## 说明与 diff 一致（逐条）
- E9 排序：新纯函数 `SortSessions.kt:31-36`（`compareByDescending{starred}.thenByDescending{Busy}.thenBy{displayName}`，`sortedWith` 不改输入，带 @contract 外骨骼）。四处接入全核到：`L2SessionList.kt:57`、`FavoriteList.kt:39`、`WorkspaceScreen.kt:190`、`SessionScreen.kt:222`（「查看」sheet）。✅
- E16 文案：`L2Models.kt:25` WORKING.label `进行中`→`运行`；`CommonUi.kt:74` statusVisuals 同步。✅
- E16 等宽：`CommonUi.kt:169-180` TextMeasurer 对「运行」「空闲」取 max 宽，`:223-225` 文字槽 `Modifier.width(labelMin)` 居中；`DesignTokens.kt:51-52` 新 token。✅
- 说明自报的 ff 合并 icons（不另建分支）与 diff 相符：main..分支只剩本格 1 个 commit。

## 范围（否决线①）
write_paths 只有 `workspace/`，实改另涉 `ui/`（文案/芯片）与 `session/`（overlay 排序）——说明第 17-27 行显式报出，是需求（E16 芯片在 CommonUi、sheet 列表在 SessionScreen）的必要闭包。不触发否决。

## 判据改动（否决线②）
既有测试 7 个文件的改动逐个核过，全部是需求驱动适配、非弱化：
- 6 处 `进行中`→`运行` 文本断言（OverlayMenuTest.kt:82、LandBaseTest.kt:123、LandListTest.kt:76/101/138、FavRowParityTest.kt:114/147、L2ListRendersStatusTest.kt:80）——E16 本身就是改名，旧文案断言必然要跟。
- `L2StaleStatusReplacedTest.kt:83-96`：只换文案，红绿结构原样——先推 working 令「运行」Exists（造出条件），再推 idle 断言 DoesNotExist，恒真防线完好。
- `TabScrollTest.kt:161` 末行 `sess-15`→`sess-9`：排序后名称升序末位就是字典序最后的 sess-9，是 E9 的直接后果，滚动溢出断言（contentPx>viewportPx）原样保留。
- 棘轮基线（tools/gate/）零改动，未 --freeze。不触发否决。

## 先验红与恒真（红线 3/4）
- 说明 33-40 行有改前原始输出：`compileDebugUnitTestKotlin FAILED / e: SortSessionsAndLabelTest.kt:62:19 Unresolved reference 'sortSessions'`。✅ 有先验红（编译级，新函数类判据的自然红）。
- 本 PR 新判据（`SortSessionsAndLabelTest.kt`）无「不应出现」型断言：排序判据是 8 项四档乱序夹具对全序列 assertEquals（:51-71，夹具乱序即「造出错序条件」，恒真不可能——不 sort 必错序）；等宽判据是宽度差 <0.5dp 的正向性质断言 + `WORKING.label=="运行"`（:88-93）。规则 4 无适用对象，过关。

## 独立复验（detached HEAD 84fa762f9）
SortSessionsAndLabelTest + TabScrollTest + L2StaleStatusReplacedTest + LandBaseTest + LandListTest + FavRowParityTest + L2ListRendersStatusTest + OverlayMenuTest → BUILD SUCCESSFUL。

## 三条否决线：均不命中（见上）。

## 瑕疵（不构成否决）
- 等宽判据区分度弱：改名后「运行」「空闲」同为 2 个 CJK 字，无宽度槽时两芯片也近等宽——该断言修前修后可能都绿（先验红是编译级，未在旧文案上跑出运行时红）。等宽实现（width 槽）本身在 diff 里真实存在，判据弱不等于实现假，故不否决，记档。
- `Dims.statusChipLabelMinWidth` 是从组合内写入的全局 `var`（CommonUi.kt:180 `.also{Dims...=it}`），可变全局 token 属小味道；smell 棘轮 16=16 未新增，留给后续格。
- 说明自曝：全量 suite 因与其它格抢写 test-results 未稳定拿到 exit 0；本席上面 8 个测试类独立跑全绿，可作旁证。
