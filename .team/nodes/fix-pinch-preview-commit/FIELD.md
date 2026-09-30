# 现场基 · fix-pinch-preview-commit（捏合预览+松手生效）

## 用户裁定原文（raw/041，IMMUTABLE，早已给出、从未实现）

```
捏合过程中只做**本地视觉缩放**（Canvas scale），手指松开后才算最终 rows/cols 发一次 resize。
即「捏合时预览，松手时生效」。
```

taskbook `fix-terminal-resize-cluster` 把该族裁定写全：
**「仅首次进入 CLI 时 resize 一次，此后键盘/输入框不触发 resize；
捏合缩放松手后发一次 resize（过程中只做本地视觉缩放）；退出时恢复原始尺寸。」**

三句分别对应：① 首帧一次（保留）② 键盘不触发（**已于 fix-ime-no-resize 兑现**）
③ 捏合松手才发（**本任务**）。退出恢复尺寸是 D-21，另行排期。

## ⭐ 用户 2026-08-12 实证了根本病（本轮最重要的定性）

> 「在局域网下我**手指捏合**，整个界面重绘重排可能也就 **0.5 秒以内**。
>  但是我在 **TS 下就会出现频繁的闪烁**。」

同一现象在另一条缺陷上重复出现：

> 「不在 TS 下，我给你发消息，那个从上往下的重复的问题**可能就只会持续 1 秒**。」

**共同的根本病**：客户端会触发需要服务端往返的整屏重建，**痛苦程度正比于 RTT**。

- 捏合：过程中每帧都发 resize → 服务端每次重排回一次整屏 → 捏合一次连发数十轮
  → LAN 下 0.5 秒糊过去，TS 下就是连续闪烁
- 局域网**掩盖**了这个病，而**用户主场景是 TS**（原话：「最主要的场景还是 TS」）

**因此本任务的验收必须在高延迟条件下成立，不得用局域网下「看不出来」当作修好。**
建议红测直接断言「捏合过程中 resize 帧数 == 0」——这个判据与网络快慢无关，最硬。

## ⚠️ 历史教训：持久化功能没错，错在时机

v5 曾用 `CellSizeStore` 做字号持久化，引入了输入框闪烁回归。
根因（已立账 `.team/evidence/rootcause-flicker-v5.json`）：

> D-31 在新 View 尚未布局时，用 retained presenter 的旧 viewport 叠加持久化的新字格尺寸
> 同步重算，沿 `termview → session` 反向边发出错误 resize（观测值 rows/cols=(17,57)）。

**结论：不要因为怕闪就放弃持久化。** 正确做法是把持久化字号的应用**推迟到新 View 布局之后**。
守门探针 `app/app/src/test/kotlin/dev/agentmirror/app/termview/TermSurfaceSessionBindingRegressionTest.kt`
专防此事，**重做必须保持它绿——它绿就证明没把 v5 的毒带回来**。

`CellSizeStore.kt` 的 v5 实现仍在归档分支 `v5-failed@2874c54`，可作参考，
但**不要整文件捞回**，那正是当初被判为禁区的文件之一。

## 开源对照（已调研，docs/oss-terminal-solutions.md）

- **resize 锚定**：herdr 在 resize 前记录 offset、之后恢复 —— 防止重排后视口跳位
- **reflow**：wezterm `rewrap_lines` 模型
- **字号持久化**：ghostty config 模型（本仓库**全仓库无字号持久化**）
- 许可证：herdr / ghostty / wezterm 均 Apache-2.0 或 MIT，**可引用**

## 不得破坏

- **IME resize 抑制**（今晚已收口，锚点 738b503c3）：首帧 seed 一次后，
  IME/输入框挤压只更新 visibleRows 不再 emit。捏合改动不得让它回退——
  探针 `TermViewImeResizePresenterProbeTest` 里有一条守卫
  `pinchFontChangeStillRequestsResize`，**注意它断言的是「捏合仍会请求 resize」**，
  本任务改成「松手才请求」后需同步调整该守卫的语义，但**不得直接删掉它**——
  它防的是「用永不发 resize 来糊弄」。
- **D-20 已修**：IME 弹起时末行可见
- 两道强制回归门保持绿
- R3 基准：CJK + Powerline 渲染正常

## 收工门

模拟器手势注入本轮已被实证不可信（捏合报「无反应」实为可用）。
因此**必须做成 JVM 可重复红测**：喂多指 MotionEvent 序列，
断言过程中 resize 帧数为 0、松手后恰好 1 次、且字号被持久化。
可复用已有的 `TermSurfacePinchGestureTest`（合成真双指序列，已验证能驱动 ScaleGestureDetector）。
最终由用户在真机 + TS 下确认。
