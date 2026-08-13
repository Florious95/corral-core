# 现场基 · fix-cols-grid-convergence（最右列文字被截断）

## 用户报告（第 4 次，历史上从未真正解决）

> 「我现在手机上看到的界面，**最右侧的字它跑到屏幕外**，也就是说被截断，
>  我只能看到一部分……比如说『拿它做 C 组对照』这句，我就只能看到『它』这个字的一半。」

用户同时给出对照：**Mac 上完全正常**，只有手机上被截。

## ⚠️ 本任务开工第一件事：分辨两个互相冲突的根因

两个席位读同一份代码，给出了**不同的根因**。选错一个 = 第五轮白修。
**你的第一产出不是修复，是一个能把两者分开的红测。**

### 假说 A（w-model-study，见 docs/web-vs-android-terminal-model.md）

> 安卓把「上报给服务端的 cols」用**名义字格宽（默认 10px）**计算，
> 而「画布的列推进」用**实测字形宽**计算——两个独立栅格从不收敛。
> 服务端按较宽的 cols 换行，画布按较窄的实测宽推进，最右列必然画到 View 外。

**可证伪预测**：上报的 cols **大于** 画布实际能容纳的列数。

### 假说 B（w-oss-research，见 docs/oss-terminal-solutions.md）

> 根因不在 reflow：内核 `TerminalGrid.write` 已防宽字符占末列。
> 问题在渲染层 `TermSurfaceView.drawLine` / `drawCentered`
> 把**宽字符**画过画布右缘，被 Canvas 裁半。

**可证伪预测**：cols 是对的（cols × 字形宽 ≤ View 宽），但**宽字符（CJK）**
在最后一列起画时，其两倍宽度越过右缘。窄字符（ASCII）则不会被截。

### 两者的判别特征

| | 假说 A | 假说 B |
|---|---|---|
| ASCII 文本会被截吗 | **会**（cols 本身就多） | 不会 |
| 只有 CJK/宽字符被截 | 不一定 | **是** |
| cols × 实测字形宽 vs View 宽 | **> View 宽** | ≤ View 宽 |

用户的例子「『它』这个字的一半」——**是个 CJK 宽字符**，这一点略偏向假说 B，
但**不足以定案**（也可能两者同时存在）。**必须用测试判定，不许靠推理选边。**

## 你的任务顺序（不许颠倒）

1. **先写判别红测**：同时测量「上报 cols」「View 宽 / 实测字形推进宽」「最后一列起画的宽字符是否越界」，
   让测试输出直接指明是 A、是 B、还是两者都有
2. **报告判别结果给 leader**，再动手修
3. 修完后该红测必须转绿

## 开源对照（已调研，可直接用）

`docs/oss-terminal-solutions.md` 指出根治方向：
alacritty / xterm.js 的 reflow 边界模型 **`LEADING_WIDE_CHAR_SPACER`**
—— 宽字符放不下末列时用占位符处理，而不是画出去被裁。
许可证：alacritty (Apache-2.0/MIT)、xterm.js (MIT)，**可引用**。

## 不得破坏

- **R3 基准**：CJK 与 Powerline 渲染正常（`e2e/artifacts/baseline-v2/R3-cjk-powerline.png`）
- **D-35 已修**：形近等价映射 + `'?'` 兜底槽，不得回退
- 强制回归门 `TermSurfaceSessionBindingRegressionTest`、`TermSurfacePinchGestureTest` 保持绿
- 主干含多条已锚定与在途改动，只动 write_scope 内文件

## 收工门

模拟器渲染结论在本轮已多次被真机推翻，**最终由用户在真机确认**。
你要保证的是：判别红测与修复后红测都能在 JVM 上重复运行。
