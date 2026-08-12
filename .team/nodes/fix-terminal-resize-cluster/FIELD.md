# 现场基 · fix-terminal-resize-cluster

## 前序修复的问题
fix-ime-resize 加了 geometryLocked 锁但做过头了：
1. 键盘弹出时内容不上推（D-20）——锁住后 imePadding 的视觉位移也被影响
2. 退出会话不恢复尺寸（D-21）——onCleared 的恢复逻辑可能没生效
3. 捏合缩放后右侧溢出（D-28）——锁可能把 onZoom 的 resize 也挡了
4. 捏合过程中闪烁（D-29）——每次手指移动都发 resize

## 正确行为（用户裁定 2026-08-12）
- 首次进入会话：resize 一次适配手机
- 键盘弹出/收起：rows/cols 不变，但**视口要上推**（像聊天软件）
- 捏合缩放过程中：只做本地视觉缩放（Canvas/View transform），**松手后**才重算 rows/cols 发一次 resize
- 退出会话：resize 恢复主机终端原始大小

## 关键实现点
1. **视口上推**：imePadding 只作用在外层容器（推输入条），终端 View 的测量高度不受键盘影响
2. **捏合防抖**：onZoom 的连续调用只更新本地 scale factor，ACTION_UP/MotionEvent.ACTION_POINTER_UP 时才算最终 cellWidth/cellHeight 并发 resize
3. **退出恢复**：ViewModel.onCleared 或 DisposableEffect cleanup 时发 resize(INITIAL_ROWS, INITIAL_COLS)
4. **geometryLocked 重新设计**：不是简单布尔锁，应该区分"谁触发的 size change"

## 已有代码位置
- TermViewPresenter.kt:69 geometryLocked / lockedViewportWidthPx
- TermViewPresenter.kt:157 onViewportSizeChanged
- TermViewPresenter.kt:164 onZoom
- TermViewPresenter.kt:181 recalculate
- SessionScreen.kt:225 imePadding
- SessionViewModel.kt:68 resize callback
