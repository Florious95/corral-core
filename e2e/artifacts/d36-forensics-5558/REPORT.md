# D-36 上滑失效 · 5558（1260x2800 真机几何）复核结果

commit: `270d2f4e4`
设备: emulator-5558（**1260x2800，与用户真机 Vivo 300 Pro 同几何**）
拉满的变量：
- **历史规模**：host tmux history-limit=2000（`tmux show -g history-limit` 实测），本轮填到 3200
  行触发主动淘汰（`capture-pane -S -2000` 验证最老可见行已从 D36B-LOAD-1 前移到 D36B-LOAD-1199，
  确认 history-limit 淘汰机制在本轮测试期间是**活跃**的，不是理论上限）
- **首次进入的"大量从上往下加载"**：截图 `03~05` 系列确认进会话时确实经历了一段加载态才稳定
  显示内容，与用户描述的现象匹配
- **真实 Claude Code**：`claude` CLI 交互，非 bash

方法与工具同 `e2e/artifacts/d36-forensics/REPORT.md`（emulator-5554 那份）：
`forensicsSnapshot()` + `D36ForensicsBridge`/`MainActivity` 临时广播桥（`FORENSICS-TEMP` 标记，
收工已撤），两组信号（内部状态 + 截图）。

## 四步结果

| 步骤 | 动作 | scrollbackSize | maxTop | topLine | isFollowingBottom | 画面动了？ |
|---|---|---|---|---|---|---|
| 1 | 进会话（history 已在淘汰态），加载完 | 405 | 405 | null | true | — |
| 2 | 上滑一次 | 405 | **405** | 342 | false | ✅ 截图`10`：D36B-LOAD-3037起+Claude欢迎屏，"回到底部"出现 |
| 3 | 发消息给真实claude（60行响应），等完（"Baked for 5s"） | 405 | 405 | null | true | — |
| 4 | 再上滑一次 | 405 | **405** | 338 | false | ✅ 截图`14`：D36B-LOAD-3033起+LINE-23，"回到底部"出现 |

**第 4 步 `maxTop`（405）与第 2 步（405）完全相同，两步画面都真实移动了。**

## 与 5554 那轮的横向对比

| | emulator-5554 | emulator-5558 |
|---|---|---|
| 几何 | 1080x2400（非真机） | 1260x2800（真机几何） |
| 历史规模 | 332 行（远低于 history-limit） | 405（**本地client端**scrollback；host端已填到3200行、
主动淘汰已激活） |
| 上滑第2/4步 maxTop | 332 vs 332（一致） | 405 vs 405（一致） |
| 上滑是否有效 | 两次都有效 | 两次都有效 |

**两台 AVD、两种历史规模条件（远低于限 vs 已触发淘汰）、两次独立复测，结果一致：都没有复现
"发消息后不能上滑"。**

## 结论

geometry 假说和 history-limit 假说都没能复现用户现象。按方案判据表，两轮都落在
"`maxTop` 仍 > 0 且画面动了 → 用户现象可能已随某修复消失"这一档。

**但"两轮干净环境都没复现"不等于"缺陷不存在"**——按 leader 排出的差异清单，剩下没拉满的变量是：
1. **真手指 vs 注入事件**（`adb shell input swipe` 生成的是合成 MotionEvent，今晚捏合那边已经
   证明"UiAutomation 注入的事件在语义上可能和真手指不完全等价"这件事是真实存在的，不是假设）
2. **长时间运行的会话状态**（用户会话跑了数小时、经历多次 resize/进出/网络抖动；我的两轮都是
   全新会话，没有这种历史包袱）

这两条我这边的模拟器测试已经摸不到——需要用户在他自己正在使用的会话上配合一次带取证的复现
（装同一个 `forensicsSnapshot()` 只读钩子，请他在"滑不动"那一刻触发一次广播读数）。

**没有修改任何生产代码。**

## 取证桥撤除确认

```
$ grep -rn "D36ForensicsBridge\|DUMP_FORENSICS" app/app/src/main/java/
(无输出)
$ git diff --stat -- app/app/src/main/java/dev/agentmirror/app/termview/TermViewPresenter.kt app/app/src/main/java/dev/agentmirror/app/MainActivity.kt
(empty)
```

## 证据文件

- `logcat.txt` —— 完整 D36Forensics 日志
- `03-loading-t0.png` ~ `05-loading-t2.png` —— 首次进入时"大量从上往下加载"过程截图
- `09-checkpoint1-claude.png` ~ `14-checkpoint4.png` —— 四步序列（真实 Claude Code）截图
