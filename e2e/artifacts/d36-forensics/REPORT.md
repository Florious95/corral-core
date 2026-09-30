# D-36 上滑失效 · 仪表化取证结果

commit: `270d2f4e4`（当前 HEAD）
设备: emulator-5554（1080x2400，**非用户真实机型 1260x2800**，见文末待办）
方法: `docs/d36-scrollback-forensics.md` 方案，`forensicsSnapshot()` 只读钩子 + 临时广播桥
（`D36ForensicsBridge` + `MainActivity` 的 `adb shell am broadcast -a dev.agentmirror.app.DUMP_FORENSICS`
触发一次打印；两处改动都标了 `FORENSICS-TEMP` 注释，收工已撤，见文末）。
隔离环境：独立 tmux socket + 独立 daemon 进程 + 独立端口/token，不碰生产 tmux/daemon。

## 一个方法论纠错（诚实记录，别漏）

**第一轮上滑测试用错了手指方向**，两次尝试（`input swipe` 540 1800→540 700，以及加大距离/时长的
重试）画面和内部状态都**完全没变**。没有直接报"复现了"，而是先用系统 Settings App 做了一次注入
层交叉验证（列表页同样的 swipe 命令确认能正常滚动），排除了"adb 注入没到达 App"这个嫌疑，
所以问题只能在方向或手势语义。回读 `TermSurfaceView.kt` 的 `onScroll` 实现注释后确认：
**"手指下拖"才是看更早历史**（`distanceY` 反号语义），我第一轮滑反了。改用手指下拖（540 700→540 1800）
后立刻复现出正确的滚动行为。下面全部数据来自方向修正后的干净四步序列。

## 四步序列（真实 Claude Code，非 bash）

| 步骤 | 动作 | scrollbackSize | logicalCount | visibleRows | **maxTop** | **topLine** | isFollowingBottom | 画面动了？ |
|---|---|---|---|---|---|---|---|---|
| 1 | 进会话，等加载完（bash 填充历史 + `claude` 启动欢迎屏，静置>2s） | 332 | 419 | 87 | 332 | null | true | — |
| 2 | 上滑一次（手指下拖 540,700→540,1800） | 332 | 419 | 87 | 332 | **278** | false | ✅（截图 `14-checkpoint2.png`，内容从 D36-LOAD-159 滚到 132+Claude欢迎屏，"回到底部"按钮出现） |
| 3 | 发一条消息（真实 `claude` CLI，"output 60 lines LINE-1 to LINE-60"），等响应结束（"Cogitated for 5s"） | 332 | 419 | 87 | 332 | null | true | — |
| 4 | 再上滑一次（同样手指下拖） | 332 | 419 | 87 | **332** | **276** | false | ✅（截图 `21-checkpoint4.png`，内容从 D36-LOAD-131 滚到 LINE-14，"回到底部"按钮出现） |

内部状态（logcat 全量）与截图（`10~21` 系列）两组信号均已采集，互相印证。

## 判据结果

**第 4 步 `maxTop`（332）与第 2 步（332）完全相同，且两步画面都真实移动了。**

对照方案判据表：
- ~~`maxTop` 塌到 0~~ → 不是这个
- ~~`maxTop` 仍 > 0 但画面不动~~ → 不是这个
- **`maxTop` 仍 > 0 且画面动了 → 用户现象可能已随某修复消失，报当前行为供复核** ← 命中这条

## 一个观察，不是判据的一部分

第 3 步前后 `scrollbackSize`/`logicalCount` 完全没变（332/419，一位不差）。这**不代表历史没写入**——
第 1 步时内容早已远超一屏（scrollbackSize=332 >> visibleRows=87），说明滚动区早已建立；第 3 步新增的
「消息回显 + 60 行 LINE-N + Claude 状态行」总行数（约 63 行）没有超出当前屏幕底部到已用内容顶部之间
的剩余空白（第 3 步前截图 `19-sent.png` 显示 Claude 欢迎屏下方有大片空白），所以没有触发"内容顶部
被推出可见窗口进入 scrollback"的条件，`scrollbackSize` 未变属于预期几何结果，不是数据异常。

## 结论与建议

**这次干净复测（正确手势方向 + 真实 Claude Code + 双信号）没有复现用户报告的"发消息后不能上滑"。**
`maxTop` 在发消息前后一致为 332，两次上滑内部状态和画面都正确响应。

**没有直接结案，原因是环境差一环**：本次用 emulator-5554（1080x2400），用户真机是 1260x2800
（`agentmirror_geo_1260x2800` / emulator-5558）。今晚已经因为这个几何差栽过一次（D-38 AVD 校准
那次），这次没有先复测同一台就报"修复"过于草率。

**建议**：
1. 若判定"现象已消失"，用 emulator-5558（1260x2800，真机几何）跑同一份四步序列复核一遍，两台
   AVD 结果一致再定案；
2. 若 5558 上复现了、5554 上没复现，说明是几何相关的边界条件（如某个 视口宽度/字号组合下的取整
   误差），需要另开根因调查，不是"现象已消失"。

**没有修改任何生产代码**——本次纯取证，不含修法。

## 取证桥撤除确认

```
$ grep -c "D36ForensicsBridge\|DUMP_FORENSICS" \
    app/app/src/main/java/dev/agentmirror/app/termview/TermViewPresenter.kt \
    app/app/src/main/java/dev/agentmirror/app/MainActivity.kt
0
0
$ git diff --stat -- app/app/src/main/java/dev/agentmirror/app/termview/TermViewPresenter.kt app/app/src/main/java/dev/agentmirror/app/MainActivity.kt
(empty — 两文件均已恢复到取证前状态)
```

## 证据文件

- `logcat.txt` —— 完整 D36Forensics 日志（含第一轮方向错误的失败尝试，未删减）
- `10-before-swipe-correct.png` / `11-after-swipe-correct.png` —— 方向修正后的验证轮（bash 内容）
- `13-checkpoint1.png` ~ `21-checkpoint4.png` —— 正式四步序列（真实 Claude Code）截图
- `08-settings-before.png` / `09-settings-after.png` —— 系统 Settings 列表滚动对照（注入层排查）
