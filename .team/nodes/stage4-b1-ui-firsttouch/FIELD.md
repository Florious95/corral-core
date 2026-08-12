# 现场基 · stage4-b1-ui-firsttouch

## 环境
- 模拟器已运行：emulator-5554，wedding_user_a_api35，API 35
- adb 全路径：`/Users/alauda/Library/Android/sdk/platform-tools/adb`
- APK 构建：`cd /Volumes/nvme/Projects/远程Agent安卓/app && env -u TEAM_AGENT_* ./gradlew :app:assembleDebug`
- APK 位置：`app/app/build/outputs/apk/debug/app-debug.apk`
- 安装：`/Users/alauda/Library/Android/sdk/platform-tools/adb install -r app/app/build/outputs/apk/debug/app-debug.apk`

## 执行权威
唯一权威文档：`docs/stage4-execution-plan.md`（已读入）。

## 你的 13 条用例
A1 首装冷启路由、A2 扫码配对（QR 载荷核对+入口）、A2a 拒绝相机权限降级、
A3 手填配对成功、A3a 错 token、A3b 服务端未起/地址不可达、
A4 工作区列表两级分组、A5 聚合状态徽章、A6 打开会话秒开、
A7 CLI 画面一致、A8 输入回显与回执、A9 发图注入、A9a 上传失败可见。

## 隔离铁律
- 自建 `TMUX_TMPDIR=/tmp/st4-b1-$$/tmux` + daemon 端口 19983
- `AGENTMIRROR_STATE_DIR=/tmp/st4-b1-$$/state`
- daemon 构建：`cd /Volumes/nvme/Projects/远程Agent安卓/server && env -u TEAM_AGENT_* go build -o /tmp/st4-b1-$$/agentmirrord ./cmd/agentmirrord`
- 测试一律 `env -u TEAM_AGENT_*`
- **绝不触碰**生产 daemon（pid 3393，:9900）与用户真实 tmux
- 收尾 lsof + 进程表自证零残留

## 夹具预置（execution-plan §4.2 B1 要求）
- 隔离 tmux：3 session / 2 cwd + 一个 blocked pane + 长路径 session
- 手填配对地址：`ws://10.0.2.2:19983/ws`（10.0.2.2 是模拟器里看宿主的特殊地址）

## 阳性对照（必做）
- U 结构断言：每次 dump 后断言假节点 `dev.agentmirror.app:id/assert_never_exists` 不存在
- S 截图：sips -g pixelWidth -g pixelHeight 断言 1080×2400 非纯色
- T 对账：capture-pane 非空且含预期文本

## 已知不可用面
- 相机：本 AVD 无真实摄像头，真实扫码/拍照不可测 → 真机
- Extended Controls GUI 窗口寻址：已实证不可用，不要用

## uiautomator 用法
```bash
ADB=/Users/alauda/Library/Android/sdk/platform-tools/adb
$ADB shell uiautomator dump /sdcard/u.xml
$ADB shell cat /sdcard/u.xml
```

## 截图命名
`e2e/artifacts/stage4-execution/<case>-<step>.png`

## 安全
- token 不上屏：uiautomator 全树 grep 无 token 明文（协议 §9）
- 配对 token 不落日志不上屏明文
