# App QA v5 报告

- 日期：2026-08-12（Asia/Singapore）
- 设备：`emulator-5554`
- 包：`dev.agentmirror.app`，`versionName=0.1.0`，`lastUpdateTime=2026-08-12 15:38:27`
- daemon：宿主机 `:9900`，PID `39489`
- 总结：`T1 FAIL`；`T2 PASS`；`T3 PASS`；`T4 PASS`；`T5 BLOCKED`
- 边界：未修改产品代码；token 只用于配对输入，未写入证据或报告。

## T1 — D-23/D-32 返回手势逐级导航：FAIL

配对成功并进入工作区列表后，选择 `alauda` 工作区和 `claude_code` 会话。页面节点逐步确认如下：

1. 工作区列表：`T1-01-workspaces.xml` 含 `工作区`、`alauda`。
2. 会话列表：`T1-02-sessions.xml` 含 `‹ 工作区`、`alauda`、`claude_code`。
3. 会话页：`T1-03-session.xml` 含 `‹ 返回`、`claude_code`、`输入指令…`。
4. 第一次 `KEYCODE_BACK`：`T1-04-back.xml` 已是 `工作区` 一级列表，未回到会话列表。
5. 第二次 `KEYCODE_BACK`：`T1-05-back.xml` 仍是 `工作区` 一级列表。
6. 第三次 `KEYCODE_BACK`：`T1-06-back.xml` 为 `连接主机` 配对页。

实际路径是 `会话页 → 工作区列表 → 工作区列表 → 配对页`，不符合期望的 `会话页 → 会话列表 → 工作区列表 → 配对页`。证据截图：`T1-00-pairing.png`、`T1-01-workspaces.png`、`T1-02-sessions.png`、`T1-03-session.png`、`T1-04-back.png`、`T1-05-back.png`、`T1-06-back.png`。

## T2 — D-38 后台返回完整重绘：PASS

在有真实终端内容的 `远程Agent安卓/claude_code` 会话页取 `T2-01-before.png`，执行 `KEYCODE_HOME`，等待 3 秒，再以 `am start -n dev.agentmirror.app/.MainActivity` 返回。`T2-02-after.png` 中终端从标题栏下方到快捷键栏上方完整填充，未见半高空白或只渲染半截；前后图片均为 `1080×2400`。页面节点仍为会话页，见 `T2-01-before.xml`、`T2-02-after.xml`。

## T3 — D-28 捏合缩放无溢出：PASS

使用 `/dev/input/event1` 注入真实双指多点事件完成放大，SharedPreferences 从无 `cell_size.xml` 变为：

```xml
<int name="cell_height" value="25" />
<int name="cell_width" value="13" />
```

这证明 `ScaleGestureDetector` 实际收到捏合，不是单指 swipe。对比 `T3-01-before.png` 与 `T3-04-after-pinch-large.png`：字号明显放大；终端画布严格裁切在 View/屏幕右边界，没有绘制越过右侧进入相邻 UI 或屏幕外的可见残影。长行在边界处裁切属于异步 resize 重排期间的内容形态，不构成本用例所指的 Canvas 右侧绘制溢出。偏好证据：`T3-01-cell-size-before.xml`、`T3-04-cell-size-after-large.xml`。

## T4 — D-31 缩放持久化：PASS

以 T3 得到的 `13×25` 字格为基线（`T4-01-zoomed-before-exit.png`、`T4-01-cell-size-before-exit.xml`），系统返回退出会话，再重新进入同一 `远程Agent安卓/claude_code` 会话。重入后 `T4-03-cell-size-reentered.xml` 仍精确为 `cell_width=13`、`cell_height=25`，`T4-03-reentered.png` 也保持放大字号，未恢复默认。

## T5 — D-21 退出恢复终端尺寸：BLOCKED

运行中的 daemon 没有包含本轮 D-21 修复：

- daemon PID `39489`，启动时间 `2026-08-12 13:40:03 +0800`
- 运行二进制 `server/agentmirrord` mtime：`2026-08-12 13:40:01 +0800`
- D-21 源码 `server/internal/api/ws_handler.go` mtime：`2026-08-12 15:24:28 +0800`

进程和二进制都早于 D-21 源码修改，说明 daemon 未重编/重启。按测试角色约束，本用例不得在旧 daemon 上判定，故标为 `BLOCKED`。环境截图：`T5-01-blocked-running-old-daemon.png`。

## 结论

已安装 App 的 D-38、D-28、D-31 运行时验收通过；D-23/D-32 逐级返回仍失败；D-21 需先重编并重启 daemon 后复测。
