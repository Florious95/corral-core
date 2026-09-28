# D-38 三版尝试复盘（d38-three-attempts-postmortem）

> 2026-08-13。用户裁定全量回退到 v6（本版不可用，三条倒退：捏合重绘错乱、点输入框闪黑屏、发送消息后重排重绘）。
> 三版代码即将被退掉，本文记录**三版失败的认知过程**——diff 只记录了「改了什么」，
> 记录不了「当时以为病因是什么」。这份文档的寿命比那三版代码长。

---

## 背景：用户现象

「切后台再回前台，终端只占屏幕顶部约 1/4、下方大片空黑」——即终端行数停在更小的旧视口几何上，
未随回前台恢复。用户多次报告，四轮没修好。

w-base-v2 机器眼（bottomMarginPx，非目检）实测序列：
- 基线（未开 IME）108x87，bottomMarginPx=6（健康）
- 切后台→回前台（IME 仍在屏）：108x82 ← 变了（挤压被当真实视口）
- 收起键盘：108x82 卡住，bottomMarginPx=106（≈5 行）

---

## 第一版：height + imeInset（「两值相加」）

### 当时认为的病因
回前台时 View 高被 IME 挤过（adjustResize 下 height = 窗口高 - IME inset），所以
**「当前 View 高 + imeInset」= 扣除 IME 后的稳定窗口高**，把它传给 presenter 的
`onRealViewportChanged`，就不会把挤压值当真实视口。

实现：TermSurfaceView `onWindowVisibilityChanged` 里 `presenter?.onRealViewportChanged(width, height + imeInsetPx)`；
`onApplyWindowInsets` 里实时记录 `imeInsetPx`（View 自己的 insets 的 ime().bottom）。

### 为什么错（验收数字：geometryCorrectionCount=2）
w-base-v2 复测：bottomMarginPx=6（健康）但 **count=2**（对应「回前台」「收键盘」两点）。

**两个值不同源**：`height`（View 当前布局值）与 `imeInset`（insets 回调缓存值）来自不同时刻。
回前台时 insets 可能尚未重新分发（visibility 变化先于 insets），`imeInsetPx` 还是旧值 0 →
`height + 0` = 挤压值 → presenter 收到错几何 emit → 只能靠自愈纠正。

**竞态是结构性的**：加法要求两个值同源，而它们不来自同一时刻——这是「两值相加」方案的固有缺陷。

---

## 第二版：记住 imeBottom==0 时的稳定高（「观测事实」）

### 当时认为的病因
加法有竞态，那就**不加法，记住一个观测事实**：IME 收起（`imeBottom == 0`）时 View 高 = 稳定高，
记录它；回前台 IME 在屏时直接复用。没有加法就没有不同源。

实现：`onApplyWindowInsets` 里 `if (imeBottom == 0 && VISIBLE) { stableHeightPx = height; onRealViewportChanged(...) }`；
回前台复用 stableHeightPx。

### 为什么错（验收数字：geometryCorrectionCount=1）
w-base-v2 复测：**`imeBottom` 几乎永远是 0**（8 次回调里全是 0，另有两个瞬时 1882/126），
而 uiautomator 独立确认 View 确实被挤到 936px。

**`imeBottom == 0` 这个判据从根上不成立**：键盘根本没有覆盖 View，是 Compose 的 imePadding
把布局挤小的——View 从未与键盘重叠，它的 ime inset 报 0 是"正确"的。于是 `imeBottom==0` 时
把**被挤压的高度**记成了稳定高。

**前两版错在同一个前提：以为 View 能从自己的 insets 里看到键盘。它看不到。**

---

## 第三版：Compose 事件源（isImeVisible 布尔）

### 思路
既然 View 看不到键盘，那就**从知道它的那一层拿**：Compose 根读 `WindowInsets.isImeVisible`
（与底部集群 imePadding 同源，已被证实工作），经 `AndroidView.update` → `setImeVisible(bool)`
传给 View。稳定高写入条件：`imeVisibleKnown && !imeVisible`（布尔未到/IME 在屏都不写，防时序）。

实现：TermSurfaceView 加 `setImeVisible`/`imeVisibleKnown`；SessionScreen `val imeVisible = WindowInsets.isImeVisible`
+ `AndroidView(update = { it.setImeVisible(imeVisible) })`。

### 用户实测：点输入框闪黑屏（很可能就是它引入的）
**推测（未证实，代码将被退）**：点输入框 → IME 弹出 → `imeVisible` 变 true → 我的 `onWindowVisibilityChanged`
分支 ③（`imeVisibleKnown && imeVisible` → 不重算）→ 但布局已经把 View 挤小 → **presenter 的 visibleRows
与实际画布尺寸短暂不一致 → 那一帧画出空白/黑屏**。

也可能是 `onSizeChanged` 里新增的 `recordStableHeightIfImeClosed()` 调用点与时序交互——但核心是
**第三版在「IME 弹出」这条链上加了状态与分支，而这条链正是点输入框的路径**。

---

## 贯穿三版的共同错误认知（最重要）

### 单测绿 ≠ 真机对，因为「模拟的时序 ≠ 真实时序」

三版**全部单测绿**（每个都有 RED-STUB 自证判别力、守门探针全绿），但**三版真机验收都不通过**。
根本原因是我自己发现的那句：

> **「Robolectric 无法模拟 Compose insets 分发链，所以我的红测模拟的是理想时序，不是真实环境。」**

第一版红测模拟「imeInsetPx 已设好 + dispatch insets」——理想时序，真实环境 insets 分发时序不同；
第二版红测模拟「imeBottom==0 记稳定高」——但真实环境 imeBottom 恒 0 是「View 看不到键盘」，不是「IME 收起」；
第三版红测模拟「setImeVisible 同步到达」——真实环境 Compose 重组与布局的时序未模拟。

**凡是依赖平台分发链的东西，单测只能证明「如果平台按我们以为的方式工作」，证明不了「平台真的按我们以为的方式工作」。**
这个认知比修好 D-38 本身更有普遍价值——它解释了「为什么单测全绿、真机全错」。

### 第二个认知：真机数字是唯一裁判
第一版 count=2、第二版 count=1、第三版闪黑屏——**都是真机/机器眼暴露的**，单测从未抓到。
geometryCorrectionCount 这个计数器是 leader 设计的「主路径探针」，它诚实报告了主路径三次没稳住。
「只有 bottomMarginPx 好不算修好，count 必须 0」——这条是 D-38 反复失败的真正防线。

### 第三个认知：用户包 ≠ 我们测的包
D-36 回炉中核实：用户装的是 `9653be07f`（无 D-36 修复），w-base-v2 两轮取证跑的是含修复的版本——
「我们验的东西和用户用的东西不是同一件」。这与「模拟时序 ≠ 真实时序」是同一个形状的更深一层：
**连版本都可能是错的**，测对了也没用。

---

## 留给下一个人

1. 这条链（IME 弹出 → 布局挤小 View → presenter visibleRows 与画布尺寸不一致）的**真根因还没找到**，
   D-38 三版都没证明有效。回退到 v6 后，重新从「用户现象 → 机器眼数字 → 复现序列」开始。
2. **先确认用户包版本再谈复现**——v6 就是当前基线，别再测错对象。
3. 黑屏那一帧的取证：机器眼「整片同色低方差」就是存活判据要抓的形态，语料 = 点输入框场景。
4. 单测仍是必要的（锚行为、防回归），但**平台分发链相关的验收必须靠真机**。
