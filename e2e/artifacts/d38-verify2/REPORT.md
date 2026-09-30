# D-38 复测（第二轮，「记住观测到的稳定高」方案）

commit: `a9e9474f3`（w-dev-d38 本轮锚点）
设备: emulator-5554（Pixel-class AVD，1080x2400）
临时插桩: `TermViewPresenter.kt` 的 `geometryCorrectionCount++` 后、`TermSurfaceView.kt` 的
`insetsCallbackCount++` 后各加一行 `Log.d("D38Verify", ...)`，复测后已撤（见文末）。

## 复测动作（干净一轮，View 实例重置后的完整序列）

填满内容(60行) → 进会话记基线 → 点输入框唤起真实 Gboard 键盘 → 切后台(HOME) 停 5s →
`am start` 回前台（确认键盘仍在屏，dumpsys 确认 `mInputShown=true`）→ 收键盘(BACK) → 记录。

## 三个数字

| 指标 | 数值 | 判据 | 结论 |
|---|---|---|---|
| `bottomMarginPx` | **6** | 应 ≈6-9 | ✅ 健康 |
| `geometryCorrectionCount` | **1** | 必须 0 | ❌ **不达标** |
| `insetsCallbackCount` | **8**（本轮清洁窗口内累计） | 非 0 即说明 View 收到了 insets | ✅ **非 0**——「Compose 拦截 insets」假说被排除，View 确实持续收到 `onApplyWindowInsets` 回调 |

机器眼原始输出（`22-final-pane.png`）:
```
bottomMarginPx: 6
contentBounds: top=300 bottom=1993 left=1 right=429
status: OK
```

## 收工判据

**不达标。** `insetsCallbackCount≠0` 排除了"Compose 吞 insets、View 收不到"这条假说——重做方案的
前提（View 能收到 insets）成立。但 `geometryCorrectionCount=1`（回前台瞬间打的日志时间戳
`10:33:10.926`，正是 `am start` 回前台之后）说明主路径仍未稳，自愈分支又被触发了一次。
和上一轮（count=2）性质相同：屏幕视觉正常（bottomMarginPx=6 健康），但计数器暴露了主路径没有
真正稳住。

## 重要复测环境噪声（如实记录，不隐瞒）

本轮复测过程中，**emulator-5554 上的 Gboard 在"数据被清过一次后"出现了间歇性异常**：有时聚焦
输入框只弹出一个左边缘的悬浮小图标条（无完整 QWERTY），`dumpsys input_method` 却仍报告
`mInputShown=true` / `mIsInputViewShown=true`。这不是应用侧问题——反复 `force-stop` Gboard 后
重新聚焦可恢复正常。

更值得注意的技术现象：**`insetsCallbackCount` 的 8 次回调里，`imeBottom` 从未一次报告过接近真实
键盘高度的数值**——要么是 0，要么是两次瞬时的 1882/126（均不匹配任何合理的键盘高度，疑似
insets 动画过渡帧的噪声值，非最终稳定值）。即便 `uiautomator dump` 独立确认 View 当时确实被真实
挤压到了 936px 高（键盘占用约820px），`onApplyWindowInsets` 交给 View 的"最终"值却始终是 0。

**这条我判断不是本次 geometryCorrectionCount=1 的根因**（时间戳对不上：count=1 发生在回前台
时刻，而不是键盘弹出时刻），但它提示：`imeBottom==0` 作为"稳定高观测时机"的判据，在这台模拟器
上并不总能反映真实键盘状态——如果真机上也有类似的 insets 值传递滞后/丢失，"记住观测到的稳定高"
方案的写入时机判据本身可能不可靠。这条留给 w-dev-d38 参考，不构成本次三数判定的一部分（已用
时间戳交叉核对排除其作为本次不达标的直接原因）。

## 插桩撤除确认

```
$ grep -c D38Verify app/app/src/main/java/dev/agentmirror/app/termview/TermViewPresenter.kt \
         app/app/src/main/java/dev/agentmirror/app/termview/TermSurfaceView.kt
0
0
$ git diff --stat -- app/app/src/main/java/dev/agentmirror/app/termview/
(empty — 两个文件均恢复到复测前的（他人在途）状态，无残留)
```

## 证据文件

- `22-final-pane.png` —— 收键盘后终端截图（机器眼判定源）
- `20-repro-foreground.png` —— 回前台后截图
- `logcat.txt` —— 完整 D38Verify 日志（含全部尝试轮次，未删减）
