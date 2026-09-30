# 现场基 · test-pinch-harness（leader 手填取证素材）

## 本任务的由来：用户的一句话

> 「这个东西模拟器测不了吗？假如说没有找到好的办法去测的话，那以后这个东西回归了，它也发现不了。」

leader 原本打算把捏合判定甩给用户手机手测。用户否掉了，理由是对的：
**手测只能回答「现在坏没坏」，回答不了「以后坏了谁来发现」。**
多点触控目前在本工程三层测试流水线（Web → 模拟器 → 手测）里是盲区，
D-28 / D-29 / D-31 / D-34 四条缺陷全在这个盲区里。
**本任务先补测试能力，再谈修复。能力比这一次的修复更值钱。**

## 已知硬事实（base-v2-gate 实测，2026-08-12）

证据：`e2e/artifacts/baseline-v2/`（R5 项）+ `.team/evidence/base-v2-gate.json` 的 `blocking_finding`

- 向 `/dev/input/event1` 注入**真实双指**捏合后：
  - 前后截图 PNG SHA-256 **完全相同**（`7638c3…c7d`）
  - **未生成 `cell_size.xml`**（即 D-31 的 CellSizeStore 从未被写入过）
- 取证席未冒充通过，明确标注「仅记录现状，不作通过判定」——这个诚实要保持。

## 两种读法（本任务要分辨的就是这个）

| 读法 | 含义 | 后果 |
|---|---|---|
| 一：功能真坏 | v2 的捏合缩放本身不工作 | 这是一条**从未立案的独立缺陷**，且是 D-28/D-29/D-31/D-34 的**共同上游**，必须先修它 |
| 二：注入不足 | adb/sendevent 注入未构成 App 可识别的 `ScaleGestureDetector` 手势 | 取证手段问题，换注入方式即可，四条缺陷各自独立 |

**分辨方法（不需要真机）**：在代码层合成一对真正的多指 `MotionEvent` 序列
（ACTION_DOWN → ACTION_POINTER_DOWN → 多次 ACTION_MOVE 改变两指间距 →
ACTION_POINTER_UP → ACTION_UP，pointerCount=2，坐标真实变化），
喂给 `TermSurfaceView.onTouchEvent`，观察：

- `ScaleGestureDetector.onScale` / `onScaleEnd` 有没有被触发
- `presenter.onFontSizeChanged` 有没有被调用、cellWidth/cellHeight 有没有变
- （v2 基线上 `CellSizeStore` 尚不存在——它在 `v5-failed` 分支，主干已回退）

**通了 ⇒ 读法二**（App 逻辑没问题，注入姿势不对）
**不通 ⇒ 读法一**（捏合真坏，且这个测试当场就是它的红测）

## 期望的产出形态（两层，都要）

1. **JVM / Robolectric 层**：多指 MotionEvent 合成 + 断言手势链被触发。
   这是**长期回归守门**——以后谁改坏捏合，这一层立刻红。快、稳、无需设备。
2. **Instrumented（androidTest，跑在模拟器上）**：用 `UiAutomation.injectInputEvent`
   直接注入多指 `MotionEvent`，做真端到端。这一层证明「真设备事件通路」也是通的。

第 1 层是必须的（回归守门）；第 2 层若受环境限制做不成，如实说明卡在哪，不要硬凑。

## 边界

- **本任务不修捏合缺陷**，只补测试能力 + 给判定。
  判定出来后由 leader 决定下一步派谁修。
- 写盘范围：`app/app/src/test/`、`app/app/src/androidTest/`、`e2e/artifacts/pinch-harness/`。
  **不改产品代码**。若发现必须改产品代码才能测（例如接缝不存在），
  停下来报告「需要什么接缝、为什么」，由 leader 定夺，不要自行动手改 main。
- 强制回归门：`app/app/src/test/kotlin/dev/agentmirror/app/termview/TermSurfaceSessionBindingRegressionTest.kt`
  必须保持绿。
- 当前主干含 D-35 形近等价映射修复（未提交），不要碰它。

## 相关代码位置（架构基会给完整闭包，这里只给起点）

- `TermSurfaceView.kt`：`ScaleGestureDetector` 接线、`onTouchEvent`、`onScaleEnd`
- `TermViewPresenter`：`onFontSizeChanged` / 捏合行列数换算（纯 JVM 状态机，单测应打在它上）
- 架构卡原文：termview 职责「捏合行列数换算」明确在 presenter 侧
