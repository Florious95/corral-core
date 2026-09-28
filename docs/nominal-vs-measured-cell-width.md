# 名义字格宽 vs 实测字形宽：永不收敛的两套栅格（D-31/右列截断根因）

> 日期：2026-08-13。用户报了四次的「最右列文字跑到屏幕外」根因。两次独立发现。
> 这份文档的寿命比任何单次改动都长——用户还会再报右列截断，那时这就是起点。

## 现象

用户反复报「最右列文字跑到屏幕外 / 只显示半个字」。局域网可能不明显，TS/不同字号下更严重。

## 两套栅格

| | 名义 cellWidth | 实测 cellW |
|---|---|---|
| 来源 | `TermViewPresenter.DEFAULT_CELL_WIDTH = 10`（TermViewPresenter.kt:392） | `measureCells()` 的 `fgPaint.measureText("W")`（TermSurfaceView.kt:357-358） |
| 被谁改 | 只有捏合 `onFontSizeChanged`（TermViewPresenter.kt:223） | 每帧 measureCells 局部变量，**从不写回 presenter** |
| 谁用它 | `recomputeGeometry()` 算 cols 上报服务端（viewportWidth / cellWidth） | 渲染时画布列推进（x += cellW） |

**两套栅格从不收敛。** cols 上报用名义 10，绘制推进用实测（如 11px）。若实测 > 名义，上报 cols 偏大 → 服务端按更大列数排内容 → 客户端按实测更窄的列渲染 → 最右列画出视口被裁。

## 它会以哪些形态暴露

1. **右列截断**（用户报了 4 次）：cols 偏大，末列画出画布。
2. **持久化几何不准**（fix-「进会话白推快照」persistGeometry）：persist 写 `cols = viewportWidth / 名义 cellWidth(10)`，而实际渲染是实测 cellW → 持久化值不准，下次 subscribe 用它 → 内容错位。
3. **上报 cols 偏大**：服务端 pane 比客户端能显示的更宽，主机上看 pane 尺寸与内容不符。

## 两次独立发现

1. **w-dev-cols 的横向栅格收敛**（fix-cols-grid-convergence）：把实测 cellW 写回 presenter（`setMeasuredCellWidth`），使 cols 与绘制同源。那版改完引入了别的问题（黑屏/错位），随全量回退一并退掉。
2. **我这次的 persistGeometry**：实现「持久化真实几何」时发现 persist 用名义 cellWidth 算 cols，与渲染的实测 cellW 分歧，导致持久化值不准。

**两次独立走到同一个点**：名义 vs 实测的栅格分歧是根因。

## 修法方向（为什么不是小改动）

**把实测 cellW 写回 presenter**（D-31 根治）：`measureCells()` 算出实测宽后调用 presenter 新方法 `setMeasuredCellWidth(cellW)`，使 `recomputeGeometry` 的 cols 与绘制同源。

**为什么不是小改动**：
- 会触发 cols 变化 → resize → 服务端重排 + 快照重放 → 需要处理「实测值首次回写后的一次 resize」的收敛性（避免回写→resize→再测→再回写的反馈环）。
- w-dev-cols 那版改完引入了别的问题（黑屏/错位），随全量回退退掉——**说明它在当前几何/渲染架构下不是一行能安全落地的**。
- 与 fix-ime-no-resize 的 `viewportSeeded` 首帧 resize 语义交互，需小心不破坏「仅首次 resize」的锚定。
- 需要先量化实测 cellW 与名义 10 的差（真机 8-11px），确认分歧量级，再决定回写粒度。

## 待办（将来用户再报右列截断时从这里开始）

1. 实测 cellW 与名义 10 的差（真机/模拟器量）。
2. 设计 `setMeasuredCellWidth` 的收敛语义（首次回写后幂等，不反馈环）。
3. 与首帧 resize 锚定（viewportSeeded）的交互。
4. 端到端判据：上报 cols == 绘制可容纳列数（floor），末列整格可见。
