# D-38 根因探针报告（回炉流程审查席产物）

> 作者：`w-d38-probe`（审查席）  
> 日期：2026-08-14  
> 任务：`fix-viewport-restore-d38-probe`  
> 对应缺陷：D-38「切后台再回前台，输入框跑到屏幕中间（终端只占顶部约 1/4，下方大片空黑）」  
> HEAD 状态：v6 全量回退（提交 `f89d47ec8`）

---

## 1. 探针实际运行结果（眼见为实）

```
测试套件：dev.agentmirror.app.termview.D38ViewportRestoreProbe
测试数量：5
失败数：0
错误数：0
跳过数：0
耗时：0.015s

PROBE_P1_emulatorRowsStuckAtSqueezedValueAfterViewGrows   PASS  0.000s
PROBE_P2_windowCappedByStaleEmulatorRows                  PASS  0.000s
PROBE_P3_foregroundReturnViaSizeChangedCannotRecoverGeometry PASS 0.001s
PROBE_P4_ifFirstFrameIsFullHeightNoDefect                 PASS  0.012s
PROBE_P5_onRealViewportChangedDoesNotExistInV6            PASS  0.001s
```

**全部 5 个探针命中（PASS = 缺陷存在）**。证明诊断正确，诊断见 §2。

探针文件：`app/app/src/test/kotlin/dev/agentmirror/app/termview/D38ViewportRestoreProbe.kt`  
运行命令：`./gradlew :app:testDebugUnitTest --tests "dev.agentmirror.app.termview.D38ViewportRestoreProbe"`  
结果原文 XML：`app/app/build/test-results/testDebugUnitTest/TEST-dev.agentmirror.app.termview.D38ViewportRestoreProbe.xml`

---

## 2. ⚠️ 已闭合根因描述的是 patch 行为，而非 v6 行为

**这是本轮审查席最重要的发现。**

### evidence.json 原文

```json
"summary": "D-38 后台返回底部空黑。根因已闭合：onRealViewportChanged（回前台）在 IME
  仍在屏时重算并上报，把被挤压的几何当成基线；onViewportSizeChanged（IME 收起）按
  fix-ime-no-resize 不再上报 → 挤压值成为永久基线。"
```

### 问题所在

`onRealViewportChanged` 是 **v3 patch 里新增的方法**，在当前 v6 HEAD 的 `TermViewPresenter` 中**根本不存在**。

**探针 P5 实证**（反射检查）：
```
PROBE_P5_onRealViewportChangedDoesNotExistInV6 PASS
信息：v6 TermViewPresenter 无 onRealViewportChanged 方法。
已实际方法列表：[takeDamage, lineCells, beginFrame, onScrollBy, onScrollToBottom,
  onFontSizeChanged, onViewportSizeChanged, ...]
```

### 结论

evidence.json 描述的根因是 **v3 patch 的行为**（patch 里新增了 `onRealViewportChanged`，然后在该方法里把挤压值当基线），而非 v6 代码的根因。

这是**纪律①的第三个实例**：「回退期间立的账，不能按回退前的代码来判」。  
第一个实例：缺陷①图片上传 evidence 记成"已修待验证"，其实修复随 v6 回退消失。  
第二个实例：（由 leader 记录）。  
**第三个实例（本案）**：D-38 的"已闭合根因"描述了 patch 中才有的方法行为。

---

## 3. v6 真正的根因（从代码反推）

### 3.1 关键代码路径

```kotlin
// TermViewPresenter.onViewportSizeChanged（v6 当前代码）
fun onViewportSizeChanged(widthPx: Int, heightPx: Int) {
    viewportWidthPx = widthPx
    viewportHeightPx = heightPx
    if (!viewportSeeded && widthPx > 0 && heightPx > 0) {
        viewportSeeded = true
        recomputeGeometry()   // ← 首帧：seed，emit resize
    }
    // 首帧之后：只更新 visibleRowsOverride，不调 recomputeGeometry()
    val rowsBefore = visibleRows
    updateVisibleRows()
    if (visibleRows != rowsBefore) onFrameRequested?.invoke()
}

// visibleRows getter（v6）
private val visibleRows: Int
    get() {
        val override = visibleRowsOverride ?: return emulator.rows
        return override.coerceIn(1, emulator.rows)  // ← 上限是 emulator.rows！
    }
```

```kotlin
// TermSurfaceView（v6）
override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    presenter?.onViewportSizeChanged(w, h)
    // 无 onWindowVisibilityChanged override（v5 有，被列为禁区未捞回）
}
```

### 3.2 缺陷发生序列

| 步骤 | 事件 | v6 行为 | 应有行为 |
|---|---|---|---|
| 1 | 进入 CLI，IME 已弹起 | `onViewportSizeChanged(1080, 1680)` → seed → `emulator.rows=84` | 同 |
| 2 | IME 收起，View 增长 | `onViewportSizeChanged(1080, 2800)` → `visibleRowsOverride=140`，`emulator.rows`**不变**=84 | `emulator.rows` 应更新到 140 |
| 3 | 切后台 | 无 `onWindowVisibilityChanged` override，无任何动作 | 理想：标记"需要重对齐" |
| 4 | 回前台 | 若 View 尺寸未变，`onSizeChanged` **不被调用**；若被调用也走路径 2（被吞） | **必须** 调 `recomputeGeometry()` 重对齐 |
| 5 | 渲染 | `visibleRows = 140.coerceIn(1, 84) = 84`；window=0..83；View 有 140 行空间但只画 84 行 | visibleRows=140，window=0..139 |

### 3.3 根因精确陈述

**回前台时（乃至任何时刻），在 viewportSeeded=true 之后，没有任何代码路径能调用 `recomputeGeometry()` 来更新 `emulator.rows`**——除了 `onFontSizeChanged`（捏合），而那是字号变化，不是视口变化。

v5 用 `TermSurfaceView.onWindowVisibilityChanged` 补了这个缺口，该文件被列为禁区未捞回，缺口一直空着。`fix-ime-no-resize` 进一步堵死了"顺带纠正"的路径（`onSizeChanged` → `onViewportSizeChanged` 现在被吞掉了）。

### 3.4 数字吻合

实测数字（w-base-v2 机器眼）与 v6 代码预测完全一致：

| 指标 | 代码预测 | 实测值 |
|---|---|---|
| `emulator.rows` | 84（首帧挤压 seed） | 84（实测） |
| `visibleRowsOverride` | 140（IME 收起后） | 140（实测） |
| `visibleRows` | `140.coerceIn(1,84)=84` | 84（实测） |
| 空白行数 | `140-84=56` | 56（实测：1123px/20px≈56） |
| 空白像素 | `56×20=1120px` | `1123px`（用户截图吻合） |

---

## 4. 三版死因复盘（与官方对比）

### 4.1 官方复盘（docs/d38-three-attempts-postmortem.md）对照

| 版本 | 官方描述 | 代码 diff 反推 | 是否一致？ |
|---|---|---|---|
| v1 | 「height + imeInset 两值取自不同时刻，竞态」 | patch 里 `imeInsetPx` 是异步写入的缓存，回调时序不保证 | ✅ 一致 |
| v2 | 「imeBottom 恒为 0，因为 Compose 的 imePadding 作用在兄弟节点上」 | patch 里 `onApplyWindowInsets` 里 `imeBottom` 取 View 自己的 insets，但 View 从未与键盘重叠 → 恒 0 | ✅ 一致 |
| v3 | 「改用 Compose isImeVisible → 引入点输入框闪黑屏」 | patch 的 `onWindowVisibilityChanged` 分支 ③（`imeVisibleKnown && imeVisible` → 不重算）在 IME 弹出路径上加了状态，View 被挤小那帧 presenter.visibleRows 与画布短暂不一致 | ✅ 与 postmortem 推测一致（「很可能就是它引入的」） |

### 4.2 与官方复盘不一致的点

**共性失败原因有一处偏差**：

postmortem 写「Robolectric 无法模拟 Compose insets 分发链 → 三版单测全绿 + 真机全错」。这是正确的诊断。但从 diff 反推，**三版还有另一个共同问题**：它们都试图在 `TermSurfaceView` 里推断 IME 状态，而这本质上是把 Compose 层的知识下沉到 View 层。

更深的失败模式：**三版都加了代码，都在"加法"解决缺口**。v1 加了 `imeInsetPx`，v2 加了 `stableHeightPx` + `imeBottom` 检查，v3 加了 `setImeVisible` + `imeVisibleKnown`。加得越来越复杂，但根因是"缺一个入口"——这是一个**加法不能解决减法问题**的典型案例。

### 4.3 告知未来开发席：已证明走不通的路径

1. **从 View 的 WindowInsets 取 imeBottom**：View 从未与键盘重叠（Compose imePadding 把 Box 挤小，不是键盘压住 View），`imeBottom` 恒为 0（或仅有瞬时噪声）。
2. **height + imeInset 加法**：竞态，两值不同源（visibility 变化先于 insets 分发）。
3. **从 Compose 层推 isImeVisible 经 AndroidView.update 传给 View**：引入 IME 弹出路径上的帧时序问题 → 黑屏闪（v3 死因）。
4. **Robolectric 单测绿 ≠ 真机对**：凡涉及 Compose insets 分发链的行为，单测只能建模理想时序，不代表真实设备。

---

## 5. 给开发席的修复方向

### 5.1 正确判据（FIELD.md 已有结论）

回前台时，谁负责把终端几何重新对齐到当前 View 尺寸？**必须有人负责**。现在没有人。

修复思路（不是实现，由开发席决定）：
- 在 `TermViewPresenter` 加一个与 `onViewportSizeChanged` **正交**的入口（如 `onRealViewportChanged`），语义是"这是真实视口变化，必须重算 rows/cols 并 emit"
- 在 `TermSurfaceView.onWindowVisibilityChanged` VISIBLE 分支调它（v5 已经做过这件事，这次注意不要复制 v5 的黑屏闪逻辑）
- **关键约束**：调用时序必须能区分「IME 在屏（挤压态）」vs「View 真实尺寸」——这就是 v1/v2/v3 没解决的问题。如果能用 Compose 已知的 isImeVisible 来控制"回前台时用哪个高度"，那是可行方向，但触发点必须在 VISIBLE 事件而非 IME 状态变化事件（以免走 v3 的老路）。

### 5.2 探针验收（极性翻转后，永久绿）

2026-08-14 缺陷③修复提交 `3c8e2c2e3` 落地后，探针极性已翻转为**回归闸**（见探针文件头注释）：
- P1/P2/P3/P4 均断言**正确行为**，任何时候跑都应 PASS
- P5 已删除（结论保留在 §2）

```bash
./gradlew :app:testDebugUnitTest --tests "dev.agentmirror.app.termview.D38ViewportRestoreProbe"
```

期望输出（极性翻转后实测，2026-08-14）：

```xml
<testsuite tests="4" failures="0" errors="0">
  <testcase name="P1_emulatorRowsRecoverAfterViewGrows"/>
  <testcase name="P2_windowCoversFullViewportNoBlankRows"/>
  <testcase name="P3_onRealViewportChangedRecoversGeometryOnForegroundReturn"/>
  <testcase name="P4_firstFrameFullHeightNoDefectAndNoRegressionOnIme"/>
</testsuite>
```

失败含义：D-38 很可能重现，见探针文件头注释的排查清单。

---

## 6. 审查结论

| 结论 | 状态 |
|---|---|
| 缺陷在 v6 HEAD 上存在（回炉探针阶段） | ✅ 原 5 探针全部命中（回炉已归档） |
| evidence.json 的根因描述是 patch 行为，非 v6 行为 | ✅ P5 实证（方法不存在）；结论保留在 §2 |
| 三版失败复盘与 postmortem 官方描述基本一致 | ✅ 一致，有一处补充（§4.2） |
| 修复方向：需要"真实视口变化"入口 | ✅ 与 FIELD.md 结论相符 |
| 修复后探针验收（2026-08-14，提交 3c8e2c2e3） | ✅ 4 tests, 0 failures；极性已翻转为回归闸 |
