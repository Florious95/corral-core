# 现场基 · fix-scrollback-history-d36

## ⚠️ 先读这段：本缺陷的定义被改过两次，前四轮都是按错误定义修的

### 用户 2026-08-12 最终定义（原话，以此为准）

> 「我重新说，**向上滑动就是完全失效的**。我只有捏合缩放成比较小的情况下，
>  它才能向上滑。也就是说，**它就加载了最小字体的一页**。
>  然后你把它放大之后，才有了向上滑动的效果。」

**含义：App 从头到尾只有一屏内容。**
- 字体小 → 一屏塞得下 → 完全滑不动
- 字体大 → 同样这一屏塞不下 → 看起来「能滑」，但滑的还是同一屏，不是历史

**即：本地 scrollback 缓冲根本没有在累积，服务端历史也从未拉取。**

### 用户此前的表述（已被上面推翻，勿再采信）

> 「往上滑能看得到它的历史，但这个历史仅限于我打开这个窗口积攒的历史。」

leader 据此立案为「本地滚动通、只是不拉服务端历史」——**这个定义是错的**。
用户随即更正。**四轮修不对，根因之一就是定义一直没对齐。**

### 用户要的最终形态

> 「我可以往上滑看到所有历史，**就像我在 CLI 通过移动鼠标滚轮往上滑一样**。」

即 tmux pane 的完整 scrollback，不是本次会话的增量。

## 已知代码事实（leader 已查，不必重复）

管道齐备，**所以这不是「没实现」，是行为不对**：

- `app/.../conn/ConnectionManager.kt:321` `fun scrollback(ref, fromLine, count): Boolean`
  —— 可发 ScrollbackFrame，req_id 单调递增
- `app/.../session/SessionViewModel.kt:333` —— 已有调用点
- `app/.../termview/TermViewPresenter.kt:114-115` ——
  `logicalCount = emulator.scrollback.size + emulator.rows`，本地滚动只改视口顶行（零网络）
- 服务端与协议均支持：二进制 kind=3，payload 头 12 字节（req_id / from_line / line_count，大端）

**要查清的是**：
1. 本地 `emulator.scrollback` 到底有没有在累积？（用户现象指向「一直是空的」）
2. `SessionViewModel:333` 的调用触发条件是什么？实际会不会被触发？
3. 拉回的历史有没有真正并入本地缓冲、并可被继续向上滚动到？
4. 分页是否收敛（不重复拉同一页、不无限拉）？

## 对照标准

`web/js/scrollback.js` 是同协议、同 daemon 的另一实现（xterm.js，MIT），
`study-web-terminal-model` 任务正在产出模型差异清单。**优先参考它，不要自己发明模型。**

## 不得破坏

- 强制回归门 `TermSurfaceSessionBindingRegressionTest`、`TermSurfacePinchGestureTest` 保持绿
- 主干含今晚多条已锚定改动（D-35 / D-22 / D-23-32 / IME resize 抑制），只动 write_scope 内文件
- **注意与 IME resize 抑制的交互**：`onViewportSizeChanged` 现在首帧后不再 emit resize，
  若 scrollback 依赖 rows/cols 变化触发，需确认不受影响

## 收工门

**模拟器手势注入已被实证不可信**（本轮已两次给出与真机相反的结论：
捏合报「无反应」实为可用、上滑报「画面完全不动」实为部分可滚）。
因此本任务的滚动行为**必须做成 JVM 可重复红测**（喂 MotionEvent 序列 + 断言 scrollback 请求
与缓冲并入），不得只靠模拟器截图。最终由用户在真机上确认。
