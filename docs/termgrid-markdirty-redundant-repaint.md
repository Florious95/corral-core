# TerminalGrid.write 无条件 markDirty —— 冗余重绘源（单独立案说明）

> 状态：**发现待立信**（leader msg_3d5b1fdafd20：值得修，但单独立案，不许挂在 fix-input-send-fullrepaint 上）。
> 日期：2026-08-13。本文件仅写 docs/，不改产品代码。

## 现象

`TerminalGrid.write`（`app/terminal/src/main/kotlin/dev/agentmirror/terminal/TerminalGrid.kt:44-75`）
在**每次写入码点**时无条件 `markDirty(cursorY)`（第 58、67 行），**没有「新内容与旧内容相同则跳过」的短路**。

## 后果

同一行内容被**原样重写一遍**（例如：pty 回显波与后续服务端 delta 波对同一行写出相同字节、或 CLI
在清屏重建后把与之前相同的行重新画一遍）时，内核仍然标脏 → 即使采用脏行级渲染，也会**白白触发
这些行的重绘**。这是「同内容冗余重绘」的来源之一，独立于 fix-input-send-fullrepaint 的整屏重绘。

## 为什么值得修但单独立案

- **值得修**：内容相同却标脏 = 无意义的绘制工作；在脏行级渲染落地后，冗余重绘的工作量按脏行计
  （不再是整屏），但每帧仍多画了没变化的行。对低端设备/高帧率场景是真实浪费。
- **单独立案**：修它需要在内核 `TerminalGrid.write` 加「新旧 Cell 相等」判定（`Cell.equals`）。
  这改变内核语义（内容相同不再触发 damage），**可能影响依赖「写即标脏」的既有行为**（如光标移动
  后重画、样式变化等边界），需要独立红测覆盖，不能混进 fix-input-send-fullrepaint 的收口。

## 修法方向（供立案参考）

`TerminalGrid.write` 在 `markDirty(cursorY)` 前比较 `row[cursorX] == newCell`（`Cell` 为 data class，
`equals` 含 text/style/width），相同则跳过标脏。注意：
- `style`（颜色/属性）变化即使 text 相同也必须标脏（视觉变了）；
- 宽字符主格/续格配对、组合字符并入前格等路径各自独立判定；
- 需红测：同内容重写不标脏；异内容/异样式标脏；光标移动后同内容重画仍标脏（如果有依赖）。

## 与 fix-input-send-fullrepaint 的关系

不相关。fix-input-send-fullrepaint 是「整屏被重写时的中间态抑制 + 脏行级重绘」；
本条是「同内容重写的冗余标脏」。两者独立，本条修复后能降低 delta 波的冗余绘制，但不解决
整屏 recap 的中间态观感。
