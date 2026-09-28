# 现场基 · stage4-b2-ui-backend

## 环境
- 模拟器已运行：emulator-5554，wedding_user_a_api35，API 35
- adb 全路径：`/Users/alauda/Library/Android/sdk/platform-tools/adb`
- APK 需重新构建安装（含 fix-upload-bearer 修复）

## 你的 22 条用例
A10（blocked 通知）、A11（杀 App 恢复）、A12（锁屏重连）、
B1–B8（视觉标准 8 条）、C1（特殊键条七键）、C2（多行粘贴）、C3（重配对入口）、
C5(U 面，各屏中文)、C6(菜单，拍照入口)、D5（失败可见汇总）。
A10a 已随 R-004 撤销，不跑。

## 隔离铁律
同 B1：自建 TMUX_TMPDIR + daemon 端口 19983，绝不触碰生产 daemon。

## B1 已有截图可参考
e2e/artifacts/stage4-execution/ 下已有 A1-A9a 截图（B1 批次产物），你的截图命名避免冲突。

## 关键用例要点
- A10：需先 `pm grant dev.agentmirror.app android.permission.POST_NOTIFICATIONS`，造 blocked，退后台，`cmd statusbar expand-notifications`
- A12：`input keyevent 26` 锁屏 60s → `input keyevent 224` 唤醒 → `input keyevent 82` 或 `wm dismiss-keyguard` 解锁
- B3：`cmd uimode night yes` 深色模式走查，完后 `cmd uimode night no` 复位
- C1：七键逐键验（Esc/Ctrl-C/Tab/↑↓←→），每键 H 对账 pane 效果
- C2：剪贴板三行粘贴，H 断言整段一次注入

## 安全
- token 不上屏不落日志
