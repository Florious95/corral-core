VERDICT: supports

# t.fav.rv · 异源评审（只读）· 契约 094 收藏不变序 + 点击身份绑定

评审席：hl1-judge-fav（Opus 5，与实现席 grok 异源）。评审工作目录 `.worktrees/hl1.rv.fav`（detached @ ce72d6c52）。
⛔ 全程只读，未改任何产品代码，未 commit / push。

## 0. 先说一处「我改了任务定义」——必须显式报出

派单要求 `git log main..pr/fav-order-binding --stat` / `git diff main...pr/fav-order-binding`。**这两条都是空的。**

```
$ git rev-list --left-right --count main...pr/fav-order-binding
4	0
$ git merge-base --is-ancestor pr/fav-order-binding main  → 真
$ git rev-parse pr/fav-order-binding
ce72d6c52f89b1f438c94df672d73d2aab19484a   （= 账本打针 rev7 那个 commit，已在 main 上）
```

即：**分支上零个自有 commit，分支是 main 的祖先，PR 的 diff 为空。**
原因不是施工席偷懒——`.team/nodes/hl1-fav/说明.md` 明写「未 commit（派单禁止）」，
改动以**未提交工作树**形式躺在施工席 worktree `.worktrees/hl1.fav`：

```
$ git -C .worktrees/hl1.fav status -sb
## pr/fav-order-binding
 M app/.../session/SessionScreen.kt
 M app/.../workspace/FavoriteList.kt
 M app/.../workspace/L2SessionList.kt
 M app/.../workspace/WorkspaceScreen.kt
?? app/app/src/test/kotlin/.../FavoriteOrderAndClickBindingTest.kt
```

⇒ 我把评审对象从「分支上的 commit diff」改成「分支 worktree 的 `git diff` + 未跟踪测试文件」。
⛔ 没有在 main 上验（main 上根本没有这些改动）。**这是定义变更，不是静默通过。**

🔴 **给 leader 的处置建议（不属于本判决，属于风险）**：这份修复目前没有任何 commit 保护，
任何一次 `git checkout` / `restore` / 席位重置都会把它抹掉——本工程 2026-08-12 已经出过一次
「整条修复以未提交状态被回退抹掉」。判决为 supports 之后应尽快落 commit。

## 1. 说明 vs 实际改动：一致 ✅

说明写的根因：`FavoriteList` 把 `FavoriteBook.rows` 又套一遍 `sortSessions`，再用
`items.zip(rows)` 把**重排后的展示项**和**未重排的源行**按下标对齐 ⇒ 点 zeta 开出 alpha。
实际 diff（`FavoriteList.kt`）：

```diff
-    val items = sortSessions(rows.map { it.toSessionItem() })
-    val byId = items.zip(rows).associate { it.first.id to it.second }
+    val items = rows.map { it.toSessionItem() }
+    val byId = rows.associateBy { it.ref }
```

逐字对得上。另三个文件（`L2SessionList.kt` / `WorkspaceScreen.kt` / `SessionScreen.kt`）
diff 确为**纯注释 + @contract 外骨骼标注**，零行为改动——与说明「已有 associateBy(ref)，只标 094」一致。
说明里没有夸大，也没有隐藏未提及的改动（4 个改文件 + 1 个新测试，与 status 列一致）。

## 2. 🔴 先验红原始输出：有 ✅

说明正文贴了两条 FAILED 原文，且落盘了机器产物 `.team/nodes/hl1-fav/tmp/prior-red.xml`（13126 B），
我读了原文，不是转述：

```
timestamp="2026-08-21T10:31:07.141Z"  tests="2" failures="2"
clickRowNOpensDisplayedSessionIdOnSessionAndFavoritePages
  ComparisonFailure: 收藏页点击行展示 id=sess-zeta，打开的必须是同一身份，opened=sess-alpha
  expected:<sess-[zet]a> but was:<sess-[alph]a>   at FavoriteOrderAndClickBindingTest.kt:99
favoritePageOrderStaysStableWhenRunningStatusChanges
  AssertionError: 收藏页必须保持加入次序（alpha 后收藏，应在 zeta 之上），不得因 zeta 运行中而前插
  expected:<[sess-alpha, sess-zeta]> but was:<[sess-zeta, sess-alpha]>
```

转绿产物 `green.xml` timestamp `10:35:58.845Z`（红 10:31 → 绿 10:35，时序合理，不是先绿后补红）。
失败信息里的实际值（`was: sess-alpha` / `was: [sess-zeta, sess-alpha]`）**正是旧代码
`sortSessions + zip` 会产生的结果**——红是真红，不是构造出来的假红。
棘轮：`wiki.log` 新增=0、`smell.log` 新增=0，EXIT:0，均为原文落盘。

## 3. 修法红线：是身份绑定，不是关排序遮掩 ✅

红线要点是「关掉收藏页排序」这一步本身会让 zip 顺带对齐，从而**掩盖**绑定错位。逐条核：

- **收藏页点击**：`byId = rows.associateBy { it.ref }`，点击 `byId[item.id]`。
  `FavoriteRow.toSessionItem()`（`DesignListMapping.kt:43`）的 `id = ref`，
  ⇒ 键是身份不是下标，**即使将来收藏页再引入任何重排，绑定仍然正确**。这是真修，不是遮掩。
- **收藏页不变序**：`sortSessions(` 在 main 侧全仓只剩三处调用——`WorkspaceScreen.kt:207`、
  `L2SessionList.kt:63`、`SessionScreen.kt:233`，**全部是会话页/浮层（088 合法重排）**，
  收藏页零调用。序 = `FavoriteBook.rows()` 的 `sortedByDescending { addedAt }`，即加入序。
- **会话页仍旧对**：`L2SessionList` 保留 `sortSessions` 重排 **且** `byId = sessions.associateBy { it.ref }`；
  `WorkspaceScreen` 点击 `onSessionClick = { item -> onOpenSession(item.id, ...) }`（展示项 id = ref）；
  浮层 `SessionScreen` `byRef[item.id]`。三处都是「重排照旧 + 身份回源」，
  ⇒ **没有靠关排序来换取正确性**。
- 红测第一条（次序稳定）用 `mutableStateOf` 在运行/空闲状态**翻转后再断言序不变**，
  只关排序而不改绑定的写法过不了第二条，只改绑定不关排序的写法过不了第一条——两条互补，堵住了单边糊弄。

## 4. 我扣下来的两点（不影响判决，请 leader 收进验收格）

**(a) 会话页那半条红测是同义反复，覆盖力弱于它看起来的样子。**
`clickRowN...` 后半段直接调 `SessionListScreen(onSessionClick = { item -> openedSession.add(item.id) })`，
断言 `openedSession[i] == displayed[i].id`——点击回调里记的就是 `item.id`，
**无论生产代码 `L2SessionList` / `WorkspaceScreen` 怎么写，这半段都会绿**。
它验的是设计包的 row tag 与视觉序，不是生产侧的点击回源。
会话页「点击按身份」我是**靠代码走查确认的**（第 3 节三处 `associateBy(ref)` / `item.id`），不是靠这条测试确认的。
⇒ 不构成 refutes（结论仍成立），但这条红测**不能**被当成会话页的回归防线；
真要防会话页倒退，得让测试穿过 `L2SessionList`/`WorkspaceScreen` 本身。

**(b) 空 ref 的 legacy 行：新旧行为不同，当前不可达。**
`toSessionItem()` 在 `ref` 为空时把 id 兜底成 `"legacy-$addedAt-..."`，而新 `byId` 以 `ref` 为键，
⇒ 这类行的点击与**取消收藏**都会 `?: return`（旧 zip 写法则按下标能命中，取消收藏可用）。
唯一生产调用点 `ThreePane.kt:228` 喂的是 `FavoriteBook.rows()`，其内 `store.load().filter { it.ref.isNotEmpty() }`
已把空 ref 过滤掉 ⇒ **今天这条路径不可达，不是活缺陷**。
但 `ifEmpty { "legacy-..." }` 兜底与 `associateBy { it.ref }` 已经互相矛盾，
将来谁放宽了那个 filter 就会静默丢掉取消收藏的能力。建议后续把键统一成 `rows.associateBy { it.toSessionItem().id }`。

## 5. 我没有做、因而没有背书的部分

- **没跑测试**。我的写权限只在 `.worktrees/hl1.rv.fav/.team/nodes/hl1-fav-rv/`，
  跑 gradle 会往施工席 worktree 写构建产物 ⇒ 越权，不做。
  「两红转两绿 + 全量 `:app:testDebugUnitTest` BUILD SUCCESSFUL」我采信的是落盘的 XML 与棘轮日志原文，
  全量绿那句**只有说明里的文本、没有机器产物**，属于未独立复核项。
- **没上模拟器**。收藏页点行是不是真的开对会话，仍待验收格按眼见为实铁律做实测截图。
  本判决只覆盖「代码层面修法正确 + 先验红证据成立」。

---

## 附：rev25 二次派单复核（2026-08-21 晚）

同一格在账本 revision 25 被再次派单。**被审对象与 rev23 那次逐字节相同，判决不变，仍为 supports。**
不是复用上次结论，是重新取了一遍状态：

```
$ git rev-list --left-right --count main...pr/fav-order-binding
5	0                       ← main 又前进 1 个 commit；分支仍零自有 commit
$ git rev-parse pr/fav-order-binding
ce72d6c52...              ← 与 rev23 时同一个 sha，未动
$ git log main..pr/fav-order-binding --stat
（空）
$ git -C .worktrees/hl1.fav status -sb
## pr/fav-order-binding
 M SessionScreen.kt / FavoriteList.kt / L2SessionList.kt / WorkspaceScreen.kt
 ?? FavoriteOrderAndClickBindingTest.kt
$ git -C .worktrees/hl1.fav diff | shasum   → a90b3a97fb41…   ← 与上轮同 hash
```

说明.md（mtime 18:36）与四个证据产物（prior-red.xml 18:31 / prior-red.xml 落盘 18:32 /
wiki.log 18:34 / smell.log 18:35 / green.xml 18:36）**mtime 全部未变** ⇒ 施工席这一轮没有新动作。

⇒ 第 0 节那条风险**升级**：距上次判决已过去一轮派单周期，修复**仍然只存在于未提交工作树**，
且期间 main 已前进。这格再怎么重派也不会变绿——**缺的不是评审，是一次 commit。**
若 leader 的本意是「让评审席催出 commit」，那是派错了席：本席硬约束禁跑 git commit / push。
建议下一轮直接派施工席落 commit，或由 leader 自己提交后再叫评审复核 diff。
