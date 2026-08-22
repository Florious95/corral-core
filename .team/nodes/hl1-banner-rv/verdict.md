VERDICT: supports

# t.banner.rv · 异源评审（只读）

评审席：hl1-judge-ui（Claude 订阅 / Opus 5，与实现席 grok 异源）
被审：分支 `pr/banner-humanize`，封版 commit **411d5ded2**
本席工作目录：`.worktrees/hl1.rv.ui`；未改任何产品代码，未 commit/push。

```
git log --oneline main..pr/banner-humanize
  411d5ded2 [pr/banner-humanize] 席位交付封版（leader 代提交）
git diff --stat main...pr/banner-humanize
  WorkspaceViewModel.kt +24/-5 | L2QuietBannerHumanizeTest.kt +122（新）
  说明.md +80 | tmp/{kin-grep,prior-red.log,prior-red.xml,unit-green.log,unit-green.xml} +282
  8 files changed, 503 insertions(+), 5 deletions(-)
```

**先说一句：这一格的证据链是我审的这几格里最扎实的。** 理由见 §1。

## 1. 🔴 先验红：这次是**真探针**，不是文档存在性闸

前三格（t.uiplus / t.uicmd / t.uiicon）的先验红都是「说明.md 还没写」的同义反复闸，我每次都要单独加保留。
**本格不是**：

```
L2QuietBannerHumanizeTest > quietBanner_omitsDebugOperandsAndHostPath FAILED
    java.lang.AssertionError: 横幅不得含 ms（，got=二级状态已停更 1374029ms（last_at=1000000
    now=2374029 workspace=/Volumes/nvme/Projects/远程Agent安卓）
2 tests completed, 1 failed
```

我核了原文 `.team/nodes/hl1-banner/tmp/prior-red.log`（127 行）：是**真实 gradle 输出**——
36 个 task 逐条 executed、`FAILURE: Build failed with an exception.`、
`Execution failed for task ':app:testDebugUnitTest'`、`BUILD FAILED in 12s`，
report 路径指向被审 worktree。不是手打的片段。

**关键在于这条红的内容**：断言失败信息里**原样带出了缺陷本体**
（`1374029ms（last_at=… now=… workspace=/Volumes/…）`），与用户截图原文一致。
也就是说这条红**证明了缺陷存在**，而不只是证明「产物还没写」。
转绿输出（`tmp/unit-green.{log,xml}`，`tests=2 failures=0`）同样有原文。

这是本轮唯一一格「先验红」四个字名副其实的。**判据这一关，过得干净。**

## 2. 文案人话 ✅

```kotlin
private fun humanizeLevel2QuietBanner(quietForMs: Long): String {
    val minutes = (quietForMs + 30_000L) / 60_000L
    return if (minutes < 1L) "状态已不到 1 分钟未更新，正在重连"
           else "状态已 $minutes 分钟未更新，正在重连"
}
```

- 我验了算术：`(1374029 + 30000) / 60000 = 23`，与说明和单测断言的「23 分钟」一致，
  也与用户截图那个 `1374029ms` 对得上。四舍五入是真四舍五入（+30s 再整除），不是截断。
- 不足 1 分钟走「不到 1 分钟」，不会出现「状态已 0 分钟未更新」这种蠢话。
- 边界：`quietFor` 为负（时钟回拨 / now < last_at）时 `stale=false`，不出横幅，不会印出负数分钟。
- 「正在重连」这句是**对用户有用的信息**（告诉他系统在自愈、不用操作），不是单纯把黑话删掉。

## 3. 调试参数进 DiagLog、操作数齐全 ✅（有一条覆盖面保留）

```kotlin
DiagLog.record("level2",
    "checkLevel2Quiet src=quiet-timeout " +
    "quiet_for_ms=$quietFor timeout_ms=$quietTimeoutMs " +
    "last_at=$lastLevel2AtMs now=$now workspace=$ws shown=$stale")
```

对照本工程「诊断日志纪律」逐条打分：

| 纪律要求 | 本次 | 判 |
|---|---|---|
| 参与比较的**两边**原始数值都记 | `quiet_for_ms` **与** `timeout_ms` 都在 | ✅ |
| 再记**结论** | `shown=$stale` | ✅ |
| 记**触发来源** | `src=quiet-timeout` | ✅ |
| 光看日志能分「该做没做」还是「做了做错」 | 见下 | ⚠️ |

**保留（不阻塞，但正是这条纪律当初立起来的那个场景）**：
`DiagLog.record` 被放在 `if (current.banner != banner)` **里面**，且函数开头有两条**静默 early return**：

```kotlin
val ws = subscribedWorkspace ?: return      // ← 无日志
if (lastLevel2AtMs == 0L) return            // ← 无日志
```

后果：用户报「停更了但没出横幅」时，导出的日志里**这四种情况长得一模一样（都是没有记录）**——
① `checkLevel2Quiet` 压根没被调用；② `subscribedWorkspace` 为 null 提前返回；
③ `lastLevel2AtMs == 0` 提前返回；④ 算了但 `stale=false`。
这正是纪律原文那句「一个分支没走进去时，日志里只写『未触发』等于什么都没说」。

**为什么仍判过**：leader 给我的判据是「调试参数进 DiagLog（操作数齐全）」——
**已记的那条，操作数是齐的**，比工程里绝大多数记录点都齐。early return 无仪表是**既有代码的**结构，
本次改动没有让它变差。建议后续格给两条 early return 各补一行（`src=quiet-timeout skip=no-workspace` /
`skip=no-frame-yet`），成本一行，收益是这个函数从此不会再有沉默分支。

## 4. 主机路径不上屏 ✅（两路证据）

- **代码面**：横幅串里已无 `$ws`；`workspace=$ws` 只出现在 `DiagLog.record` 那一行
  （我自己 grep 复核过，见 §5）。DiagLog 是用户自己导出给自己看的诊断产物，主机路径留在那里是对的。
- **测试面**：单测不只断言「不含那个 ws 字面量」，还上了一条**正则**
  `Regex("""(?<![A-Za-z0-9_])(/[A-Za-z0-9._-]+)""")` 断言横幅里**任何**以 `/` 开头的路径都不许有。
  这条比字面量断言强——换个工作区路径它照样能拦住。**是真棘轮，不是恒真断言。**

## 5. 同族清单是不是真 grep 的 ✅（我自己重跑了一遍 grep 复核）

`tmp/kin-grep.txt` 确实随代码封版进了 commit，内容是真 grep 输出（带文件:行号）。
**但我没有只看他的 grep —— 我在被审 worktree 里自己又 grep 了一遍**：

```
grep -rn 'ms（\|last_at=\|now=\|epoch' app/app/src/main --include=*.kt | grep -v DiagLog
  → WorkspaceViewModel.kt:492  （就是新加的那条 DiagLog，被 grep -v 漏网因为同行有 last_at=）
  → FavoriteRecord.kt:38       （KDoc 注释里的「epoch ms」，非 UI 文案）
grep -rn '\$ws\b|workspace=\$|\$cwd' … | 排除 DiagLog/testTag
  → 命中全部落在 DiagLog.record（WorkspaceViewModel 431/437/492/763/943、FavoriteBook 57/89/116）
```

**结论：说明第 6 条（「其余 main 源集无第二处把 ms（/last_at=/now=/主机路径拼进 UI 文案」）成立**，
我独立验证过，不是采信他的自报。

清单本身的成色，逐条给：

| # | 说明的定性 | 我的复核 |
|---|---|---|
| 1 已修 | `checkLevel2Quiet` | ✅ 属实 |
| 2 **同族、本轮未改** | `onLocalDecodeError` → `banner = "二级帧解码失败 code=$code reason=$message"` | ⚠️ **属实且仍在发货**，见下 |
| 3 非用户面 | `applyLevel2` 等 DiagLog | ✅ 属实，不上屏 |
| 4 非同族 | PairingScreen 配对地址 | ✅ 我看了，是连接 URL 不是主机 cwd；但 **kin-grep.txt 里没有对应行**，属无 grep 背书的断言（我另行确认了它无害） |
| 5 非同族 | SessionSwitchSheet 展示名 | ✅ 同上，属实但同样无 grep 背书 |
| 6 无第二处 | — | ✅ **我自己 grep 复核通过** |

### ⚠️ 第 2 条值得 leader 单独定夺：同一块像素上还有一个调试串

```kotlin
override fun onLocalDecodeError(code: FrameError, message: String) {
    DiagLog.record("level2", "decode failed code=$code reason=$message")
    if (subscribedWorkspace != null) {
        _level2.update { it.copy(banner = "二级帧解码失败 code=$code reason=$message") }
    }
}
```

我核了渲染通道：`banner` 字段由 `WorkspaceScreen.kt:193` 与 `L2SessionList.kt:80`
的 **同一个 `testTag("l2-stale-banner")`** 渲染。也就是说**用户看到的是同一条横幅**，
只是写入者有两个：一个已经说人话了，另一个仍然会打出 `code=… reason=…`。

按本工程「修 bug = 根因不是症状」那条（*共享函数里一个守卫，小于每个调用方各加一个；
只补工单点名的那条路径，兄弟调用方还是坏*），严格讲这次修的是 `banner` 的**一个写入者**，
不是**那块像素**。`decode failed` 这条虽然没有 epoch/毫秒/主机路径（危害小得多），
但它仍是裸调试键上屏。

**我不因此扣分**，理由有二：① 实现席**主动写进了清单并说明「未顺手人话化，避免扩范围」**——
这是申报而不是隐瞒，正是我希望看到的做法；② 扩范围要不要做是 leader 的排产权，不是实现席该自作主张的。
但请**明确排一格或明确记为已知**，不要让它停在「有人提过」的状态。

## 6. 我没做的事（诚实边界）

- **没有独立跑 `:app:testDebugUnitTest`**：跑它会在被审 worktree 落 build 产物，超出我被允许写入的路径。
  `A-banner-suite` exit 0、`xmls=161 tests=697 failures=0` 采信实现席自报（其 prior-red / unit-green 原文我核过是真 gradle 输出）。
- **没有在模拟器/真机上看横幅**：本格无截图，也没要求截图。文案正确性我是从**代码 + 单测断言 + 算术**三路推的，
  没有像素证据。对这个改动我认为够（纯字符串生成，单测直接断言成品串），但如实记下。

## 结论

先验红是**真缺陷探针**且有真实 gradle 原文（本轮唯一一格）；文案人话且算术正确、边界不蠢；
调试操作数齐全并含比较两边与结论；主机路径既从代码里去掉、又被一条**正则**棘轮钉住；
同族清单是真 grep，且**我自己重跑复核通过**。

**VERDICT: supports**

带走两条（都不阻塞 land）：
① `onLocalDecodeError` 仍往同一块 `l2-stale-banner` 写 `code=/reason=` 裸调试串 —— 实现席已申报，请 leader 定夺排产；
② `checkLevel2Quiet` 两条 early return 无仪表，导致「没出横幅」时四种成因在日志里长得一样 —— 建议后续格各补一行。
