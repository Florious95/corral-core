# FIELD · test-app-dogfood（leader 手填现场基，2026-08-09 23:3x）

## 环境事实

- ADB=`$HOME/Library/Android/sdk/platform-tools/adb`；EMULATOR=`$HOME/Library/Android/sdk/emulator/emulator`
- 唯一 AVD：`wedding_user_a_api35`（API 35）。**当前模拟器未运行**，你自己启动：
  `$EMULATOR -avd wedding_user_a_api35 -no-snapshot -no-audio &`，然后 `adb wait-for-device`，
  轮询 `adb shell getprop sys.boot_completed` 为 1 再操作。
- APK 必须自己重打（现存 21:19 版是旧版，缺 bgcjk 修复）：`cd app && ./gradlew -q :app:assembleDebug`
  → `app/app/build/outputs/apk/debug/app-debug.apk`。
- **并行席 w-ts-wire 正在改 app 源码**（tsnet/pairing/conn/service 域）：编译失败大概率是
  落盘间隙抢跑或半成品——直报 w-ts-wire 点对点（附文件+行号+错误原文），等回执后重试；不经 leader。

## 隔离环境（铁律，违纪今天已有实案）

- 生产 daemon（用户手机正连着）与用户真实 tmux **绝对禁触**。一切自建：
  - 隔离 tmux：`TMUX='' TMUX_TMPDIR=<自建/tmp短路径> tmux -f /dev/null new-session -d -s <名> -c <目录> 'bash --norc'`
    （/tmp 短路径规避 socket 104 字符限）
  - 隔离 daemon：`cd server && go build -o <tmp>/dogfood-daemon ./cmd/agentmirrord`，
    起法 `TMUX='' TMUX_TMPDIR=<同上> AGENTMIRROR_STATE_DIR=<自建> <tmp>/dogfood-daemon -listen 0.0.0.0:<高端口> -upload-dir <自建> -token <自定> &`
    （高端口选 19981+，避开历史 9457/19980）
  - App 内连接地址填 `ws://10.0.2.2:<端口>/ws`（模拟器访问宿主回环）
  - trap 收尾：杀 daemon、tmux kill-server、删临时目录；退出后自证零监听零孤儿。
- 范式脚本全文：本目录 `walkthrough-reference.sh`（已实证跑通的锁屏/断网/暗色走查+uiautomator 定位手法）。

## 测试设计先行（用户裁定，最高优先）

- **用例必须从需求分析推导，不许凭直觉拍场景**。设计输入（按序通读）：
  `requirement-base/INDEX.md` → 全部 entries 条目 → `requirement-base/REVISIONS.md`（被推翻结论）
  → `docs/scenario-coverage.md`（场景总图纸，已有的场景审计基线）→ LIBRARIAN.md 撞库回执。
- 产物 TESTPLAN.md 先落盘再执行：每用例带需求出处（条目编号/场景图纸行号），判定方式三选
  （结构断言 uiautomator dump / 截图目检 / tmux capture 对账）；需求→用例覆盖矩阵必附，
  未覆盖需求（如需真机硬件/需用户 authkey）显式列明原因——静默截断即缺陷。
- 覆盖矩阵之外，允许追加"探索性用例"小节（直觉/边界/破坏性），但必须标注为探索性，
  与需求推导用例分开计数。

## 试用姿势

- 你是用户，不是工程师：执行时按直觉用；卡住/困惑本身就是缺陷（"失败可见"红线）。
- 终端内容要真实：在隔离 tmux 会话里跑 `top`、`vim`、彩色 ANSI 输出脚本、长输出滚动、
  含 CJK+背景色的 printf 夹具（参考 walkthrough-reference.sh 与 bgcjk 案夹具思路：
  `48;5;254;38;5;16` recap 式、`47;30` 白底黑字、默认背景 CJK 对照）。
- 大字号档：`adb shell settings put system font_scale 1.4`（试完恢复 1.0）。
- 传图：会话页「+」→ 相册选图（可先 `adb push` 一张图进模拟器相册，
  `adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://...` 刷新），
  验证上传落盘到你的 -upload-dir 且会话内路径注入可见。
- 每一屏截图：`adb exec-out screencap -p > e2e/artifacts/dogfood/NN-描述.png`，**逐图亲自目检**
  （你有识图能力，这是本席位存在的理由），对照 018 七条+根 CLAUDE.md 工程常识红线五条。

## 已修不复报清单（今天已闭环；**复发才报**，注明"疑似回归"）

锁屏后无限重连｜QR 选中不可达网卡｜传图报"未配置上传地址"｜键盘弹出大空白｜终端 ■ 豆腐块｜
背景色区块 CJK 黑块/重叠｜256 色 recap 文字隐形｜键条「→」右缘裁半｜冷启动永远"连接中"｜
首行裁切｜resize 残影｜画面冻结。

## 在途案（不计新毛病，只记现象）

- feat-ts-wire：TS 组网接线中。配对页 TS 卡/authkey 输入遇异常、tsnet 起网崩溃
  （已知 Android netlink 禁令在修）→ 记录现象+截图，标注"在途案现象"，不列缺陷。

## 报告格式（REPORT.md）

每条缺陷：现象一句话｜截图文件名｜复现步骤（可照做）｜严重度（P0 阻断/P1 影响使用/P2 观感）｜
对照条款（018 第几条 / 工程常识红线第几条 / 纯直觉）。末尾附场景覆盖清单（做了什么没做什么，
未覆盖项写明原因）——静默截断即缺陷。
