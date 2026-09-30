# D-36 上滑失效 · 仪表化取证方案（交 w-base-v2 执行）

> 状态：**待 leader 批准**。本方案只描述取证，不含修法。
> 用户复现步骤（2026-08-13，四轮以来第一次精确）：「第一次进入 cli 大量从上往下加载，可以向上滑；但只要发一条消息，就不能向上滑了。」

## 为什么仪表化而非继续猜

已排除（只读查证 + 既有实测）：
- `replaySnapshot` 的 `main.reset()` **不清 scrollback**（TerminalGrid.kt:259）——leader 首要假说证伪；
- 发消息（sendKey/sendDraft）**不触发 resize**，服务端 handleInput 也不补发快照；
- 唯一清 scrollback 路径是 **ED3**，但 w-base-v2 实测 Claude Code 发消息 **ED2/ED3 均为 0 命中**。

「发消息 → 本地历史被抹」方向基本排干净。剩下的两种可能**修法毫不相干**，必须用一次取证分开：

| 可能 | 表现 | 方向 |
|---|---|---|
| A. 数据塌了 | 第 4 步 `maxTop` 塌到 0 | 历史没了 / 计数塌了 → 查数据路径 |
| B. 手势/渲染问题 | 第 4 步 `maxTop` 仍 > 0 但画面不动 | 不是数据问题 → 查手势/渲染路径 |

## 要采集的数字（每步都打）

| 数字 | 来源 | 说明 |
|---|---|---|
| `scrollbackSize` | `emulator.scrollback.size` | 本地历史行数 |
| `logicalCount` | `scrollbackSize + emulator.rows` | 渲染坐标空间上界 |
| `visibleRows` | `window.last - window.first + 1`（或 presenter 内部 `visibleRowsOverride`） | 可见行数 |
| `maxTop` | `logicalCount - visibleRows`（钳 ≥ 0） | 上滑空间；**判据核心** |
| `topLine` | presenter 内部 `topLine`（null=跟随，非 null=锁定） | 视口顶行 |
| `isFollowingBottom` | `topLine == null` | 跟随/锁定态 |

## 采集步骤（严格按用户描述）

| 步骤 | 动作 | 采集 |
|---|---|---|
| 1 | 进会话，等「大量从上往下加载」结束（约 2s 静默） | 上述全部数字 |
| 2 | **上滑一次**（用户说此时能滑） | 上述全部 + **画面是否移动**（取一次窗口截图对比 or presenter.window 前后差） |
| 3 | **发一条消息**（触发 50 行长回复），等响应结束 | 上述全部 |
| 4 | **再上滑一次**（用户说此时不能滑） | 上述全部 + **画面是否移动** |

## 判据

**第 4 步的 `maxTop` 与第 2 步相比：**
- `maxTop` **塌到 0** → 可能 A（历史/计数塌了）→ 报：塌的瞬间是哪个事件触发的（增量/快照/补页？）
- `maxTop` **仍 > 0 但画面不动** → 可能 B（手势/渲染）→ 报：`topLine` 是否变了（若没变=手势没进 onScrollBy；若变了但没重画=渲染）
- `maxTop` 仍 > 0 且画面动了 → **用户现象可能已随某修复消失**，报当前行为供复核

## 取证钩子怎么取数字

presenter 这些量是内部状态，现有公开接口只有 `window`/`isFollowingBottom`。方案：

**加一个只读取证快照接口**（标注取证用，收工时按需保留或移除）：
```kotlin
/** 取证快照（D-36 仪表化，w-base-v2 用；只读，不改任何状态）。 */
data class ForensicsSnapshot(
    val scrollbackSize: Int,
    val logicalCount: Int,
    val visibleRows: Int,
    val maxTop: Int,
    val topLine: Int?,   // null = 跟随
    val isFollowingBottom: Boolean,
)

fun forensicsSnapshot(): ForensicsSnapshot {
    val height = visibleRows
    val maxTop = (logicalCount - height).coerceAtLeast(0)
    return ForensicsSnapshot(
        scrollbackSize = emulator.scrollback.size,
        logicalCount = logicalCount,
        visibleRows = height,
        maxTop = maxTop,
        topLine = topLine,
        isFollowingBottom = topLine == null,
    )
}
```
全部是 getter 组合，零副作用。**不产生 resize、不碰 frame 循环、不动几何**——纯读取，可安全在任意线程调。

调用方（w-base-v2 用 adb/uiautomator 或临时日志）在四个步骤点各打一次 JSON。

## 待批项

1. 取证钩子 `forensicsSnapshot()` 是否按此实现（或你有更干净的取法）；
2. 「画面是否移动」用 presenter.window 前后差是否够（还是需要窗口截图）；
3. 采集到数字后，是临时日志（收工移除）还是保留取证接口（无害只读）。

批了交 w-base-v2 执行。数字回来再谈修法。

---

## 更新（2026-08-13）——四条证据排除「发消息抹历史」，取证重点转向「滚动为什么不动」

新增第四个独立证据，与既有三个共同收敛：

| # | 证据 | 结论 |
|---|---|---|
| 1 | `replaySnapshot` 的 `main.reset()` 不清 scrollback（TerminalGrid.kt:259） | 快照重放不清历史 |
| 2 | 发消息（sendKey/sendDraft）不触发 resize，handleInput 不补快照 | 发消息不触发重放 |
| 3 | w-base-v2 实测 Claude Code 发消息 ED2/ED3 均 0 命中 | CLI 发消息不清屏 |
| 4 | **隔离 tmux 实测：history_size 静置 58 → 发消息后 62（增长正常）** | 发消息不丢历史 |

**四条一致指向：「发消息抹掉本地历史」这个方向是错的。**

**收敛**：用户的「发一条消息就不能上滑」**不是历史没了，而是滚动本身被什么挡住了**。

**取证重点调整**：
- `maxTop` 和 `topLine` 现在比 `scrollbackSize` 更关键——它们回答「滚动为什么不动」：
  - `maxTop` 仍 > 0 但画面不动 → 手势/渲染问题（onScrollBy 没被调 / 调了没重画）；
  - `topLine` 卡在某值不变 → 视口被锁死（跟随态没恢复 / 锁定历史没解锁）。
- `scrollbackSize` 降级为辅助（确认历史还在，区分「数据没了」vs「滚动被挡」）。

**后续**：`#{history_size}` 修法（免全量 capture 测历史量）立项待 D-36 根因出来一起动（活跃缺陷路径不做改动）。
