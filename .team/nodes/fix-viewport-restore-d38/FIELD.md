# 现场基 · fix-viewport-restore-d38（后台返回视口不恢复）

## 用户报告与截图实证（2026-08-12）

> 「切到后台之后，再回到前台，对话界面的输入框会跑到中间去。
>  这个问题**之前已经汇报过了**，要么就是没记，要么就是 compact 之后忘了。」

截图（用户经 App 上传，LAN 路径）所见：
- 终端内容**只占屏幕顶部约 1/4**（内容止于 `bypass permissions on · 1 shell` 一行）
- 其下**一大片空黑**
- 键条（Esc/Ctrl-C/Tab/方向键）与输入框仍在屏幕最底部

即：**终端行数停留在一个更小的旧视口几何上，没有随回前台恢复。**

leader 说明：这条在缺陷清单里记作 D-38「后台返回显示半截」，**记录没丢**，
是 leader 一直没排期。v5 曾改过并 QA PASS，但修复位于 `TermSurfaceView.kt`，
而该文件是回收导航时列为禁区、故意未捞的四个文件之一（它同时含 v5 闪烁回归元凶）。

## ⚠️ leader 今晚引入的风险，必须一并处理

`fix-ime-no-resize`（已收口，锚点 738b503c3）把
`TermViewPresenter.onViewportSizeChanged` 改成：
**首帧 seed 并 emit 一次，此后挤压只更新 visibleRows、不再 emit resize。**

- **修复前**：视口一变就 emit → 后台回来时几何被重算纠正
- **修复后**：不再 emit → **后台期间视口若变过（如 IME 收起），回前台会卡在旧的小几何上**

与用户所见现象一致。**leader 的修复很可能让这条老缺陷变严重了。**

## 根子：一个分不清两种情况的入口

`onViewportSizeChanged` 无法区分：

| 情况 | 正确行为 |
|---|---|
| 输入框 / IME 引起的**临时挤压** | 视口上推，**不重算 rows/cols，不发 resize**（raw/019 裁定） |
| **真实视口变化**（回前台、旋转、分屏、窗口尺寸变更） | **必须重算几何**，并按需发一次 resize |

**「一律 emit」和「一律不 emit」都是错的。** 本任务的核心是给出能区分两者的判据。

这与另外两条缺陷同源（都是「一个入口承担了多种语义」）：
- `fix-pinch-preview-commit`：捏合 vs 挤压（`onFontSizeChanged` vs `onViewportSizeChanged`）
- 本任务：临时挤压 vs 真实视口变化

## 可参考但不得整文件捞回

v5 用 `TermSurfaceView.onWindowVisibilityChanged` 处理回前台（当时 QA PASS），
代码在归档分支 `v5-failed@2874c54`。**可读可借鉴，不得整文件回收**——
该文件同时含 D-31 在绑定期用 stale viewport 发 resize 的闪烁元凶。
守门探针 `TermSurfaceSessionBindingRegressionTest` 必须保持绿。

## 不得破坏（三道探针全部要绿）

- `TermViewImeResizePresenterProbeTest`：IME 挤压不 emit resize（今晚成果，不得回退）
- `TermSurfaceSessionBindingRegressionTest`：绑定期不得用 stale viewport 发 resize
- `TermSurfacePinchGestureTest`：捏合手势接线
- **D-20**：IME 弹起时终端末行可见

## 收工门

模拟器可做后台/前台切换（`input keyevent HOME` + `am start`），
但本轮模拟器结论已多次被真机推翻，**必须先做成 JVM 可重复红测**：
模拟「首帧 → 挤压 → 视口真实变化 → 回前台」序列，
断言几何恢复到与当前 View 尺寸一致、且 resize 帧数符合预期。
最终由用户在真机确认。

---

## ⚠️ 用户更正（2026-08-12 深夜）—— 推翻上面那段 leader 的自责推测

> 「**D-38 这个问题一直都存在。**」

即：本缺陷**在 leader 今晚的 IME 修复之前就存在**，不是被那个改动引入或放大的。

上面「leader 今晚引入的风险」那一节是 leader 的**推测**，被用户的实际观察推翻。
保留该节是为了记录推理过程，但**它不是事实，不要据此定位根因**。

**对排查的影响（重要）**：
- **不要只盯着 `onViewportSizeChanged` 的 emit 抑制看**。那是今晚才有的改动，
  而缺陷比它更早，所以根因至少不完全在那里。
- 应回到更基础的问题：**回前台时，谁负责把终端几何重新对齐到当前 View 尺寸？**
  现在看起来是**没有人负责**——v5 曾用 `TermSurfaceView.onWindowVisibilityChanged` 补这个位
  （当时 QA PASS），而该文件在回收导航时被列为禁区未捞回，所以这个缺口一直空着。
- 今晚的 emit 抑制**可能仍然叠加影响**（让本来能顺带纠正的路径也不纠正了），
  但它不是根因。两者都要处理，主次要分清。

**leader 记录**：把推测写成「我可能弄坏了」而不加验证，同样是一种不精确。
用户一句话就纠正了方向 —— 说明**推测必须标为推测，且必须尽快用事实证伪或证实**。
