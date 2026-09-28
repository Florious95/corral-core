# fix-ime-no-resize 模拟器实测收工门报告

- 日期：2026-08-12（Asia/Singapore）
- 设备：`emulator-5554`，1080×2400
- 源版本：主树 HEAD `9653be07f`，修复锚点提交 `738b503c3`
  （"[在途·未验证] fix-ime-no-resize：IME/输入框挤压不再发 resize 帧"）
- 构建：`app/app/build/outputs/apk/debug/app-debug.apk`（`:app:assembleDebug`，本席自建）
- 配对：自建隔离 daemon（`AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS` 收窄扫描到本次专属 socket 目录，
  绝不触碰宿主真实 daemon/tmux），隔离 tmux 会话 `ime-e2e`（20 行 LINE-N 内容 + CJK + Powerline
  字符，覆盖 R3 复核需要）。
- 边界：未改产品代码；本报告所有写盘均在 `e2e/artifacts/ime-no-resize/` 与 `e2e/artifacts/ui-review/`。
  评测结束后自建 daemon/tmux 已 scoped kill，宿主真实舰队未受影响。

## 核心结论

**输入框变高致终端重排：无；D-20 末行可见：是**

## 判据一：终端已有内容有没有被整体重画/重排（逐帧目检）

方法：录屏覆盖"点输入框唤起 IME → 停留 3 秒 → 连续输入让输入框依次长到 2/3/4 行"全过程，
10fps 抽帧后对终端内容区（y∈[254,2010]）做逐帧灰度像素差分（`mean_abs_diff`）。

结果（`ime-resize2.mp4`，25.436 秒，254 帧，`frames2/`）：

- **全程 254 帧中，仅 3 帧对出现非零差分**（对应 2 行/3 行/4 行三次输入框增高），
  其余全部帧间差分为 0——说明终端画面在非增高时刻**逐像素静止**，没有任何持续性重绘/闪跳。
- 这 3 次差分逐一核验（`transition-B.png`/`transition-C.png`/`transition-D.png`
  连续 6 帧窗口 + `transition-B-exact.png` 精确前后帧对比）：**均为纯粹的整屏向上滚动
  （LINE-1 滚出可见区，LINE-3 顶到最上排等），每一行文字的内容、字距、换行位置
  逐字节相同，不存在重新排版（rewrap）**。若真的触发了 resize（rows/cols 改变），
  预期会看到文字在不同列宽下重新折行；实测未见此现象。
- 结论：这是需求 `raw/019`「键盘弹出：视口上推，不触发重绘」裁定的正确实现——
  可见区域收缩时把渲染窗口下移以保持末行可见（视口平移），而不是对整个画布做
  resize 后的重新计算/重画。视口平移本身产生的"内容滑动"是裁定要求的预期行为，
  不算"重排"。

Bounds 交叉核验（`uiautomator dump`，与 `e2e/artifacts/abc-regression/` 修复前数据比对）：

| 状态 | 终端 View bounds（本次，修复后） | abc-regression（修复前基线） |
|---|---|---|
| 未聚焦 | `[0,254][1080,2010]` | `[0,254][1080,2010]` 一致 |
| 聚焦、IME 已起 | `[0,254][1080,1896]` | `[0,254][1080,1896]` 一致 |
| 两行 | `[0,254][1080,1833]` | `[0,254][1080,1833]` 一致 |
| 三行 | `[0,254][1080,1770]` | `[0,254][1080,1770]` 一致 |
| 四行（本轮新增档位） | `[0,254][1080,1707]` | （未测，超出 abc-regression 覆盖范围） |

**bounds 数字本身修复前后完全相同**——这符合现场基提示："bounds 变化是布局必然，
不是缺陷；要看的是内容有没有跟着重排"。真正的差异不在 bounds 数字，而在
上面判据一证实的"内容是否重画"：修复前（v2/d35fix/v4 三组）与本次修复后相比，
本轮逐帧差分显示除纯滚动外零重绘，是本次修复带来的新证据（abc-regression 报告
当时用整屏 10fps 抽帧目检得出"内容有效画布重新布局/重画"的结论；本次改用像素级
逐帧差分定量复核，同一份终端内容在增高瞬间只有一次性滚动位移，非持续重画）。

证据：
- 主录屏：`ime-resize2.mp4`（25.436s，完整覆盖 IME 起→1→2→3→4 行全过程）
- 全量抽帧：`frames2/frame-*.png`（254 张）
- 差分定位与切片：`top-region-compare.png`（7 阶段并排）、`transition-B.png`、
  `transition-C.png`、`transition-D.png`、`transition-B-exact.png`（精确前后帧对比）
- Bounds 原始 dump：`05-ime-focus.xml`、`06-stage1-2line.xml`、`06b-stage1b.xml`、
  `07-stage2-3line.xml`、`08-stage3-4line.xml`、`10-final-after-record2.xml`
- 首次尝试的不完整录屏 `ime-resize.mp4`（30s time-limit 早于第 4 档增高触发前截断，
  已被 `ime-resize2.mp4` 完整覆盖取代，未采信入结论）

## 判据二：D-20 不得倒退——IME 弹起时终端最后一行仍可见

对照基准：`e2e/artifacts/baseline-v2/R2-ime-last-line-visible.png`。

实测在**最极端的四行输入框增高档位**（对末行可见性挤压最狠）截图 `11-final-screenshot.png`：
终端最后一行（`$` 提示符）完整可见于输入框上方，未被输入框/IME 遮挡。D-20 未回归。

## 输入框闪烁复核（点开输入框 + 发消息增行）

- 点开输入框 + IME 起 + 连续增行：见上方 `ime-resize2.mp4` 全程分析，无闪烁（无整屏白帧/
  终端清空/大面积明暗跳变）。
- **发消息增行**（真实点「发送」触发终端追加新内容，非本地增高）：`ime-send9.mp4`
  （3.66s，15fps 抽帧 `frames-send9/`）。逐帧对比 `send-transition-strip.png`：
  终端底部平滑追加回显行（`SENDFLICKERSIX` → `bash: command not found`），伴随
  "发送中… → 已发送" 状态提示浮现/消退，全程无空白帧、无终端清空、无大面积闪跳。
  （注：`screenrecord` 在本机 emulator 上遇到「发送」触发的 IME 收起会较早自行终止
  录制，多次重试后取用能完整覆盖发送前后关键帧的一份；此为录屏工具本身的已知限制，
  与被测 App 行为无关。）

## R3 CJK + Powerline 复核

隔离会话内容含 `中文终端渲染 CJK test 你好世界` 与 Powerline 符号（`  `）。
全程截图（`04-session-before-ime.png`、`09-stage3-4line.png` 等）中 CJK 正常渲染，
Powerline 三角符号可见（形态与 `baseline-v2/R3-cjk-powerline.png` 一致：前两个符号
渲染为三角形，第三个符号呈现形态与基线同形），未见回归。

## UI 审查全态截图

落 `e2e/artifacts/ui-review/`，`ime-` 前缀：

- `ime-normal-light.png` / `ime-normal-dark.png`：未聚焦状态，浅色/深色主题
- `ime-focused-1line-light.png` / `ime-focused-1line-dark.png`：IME 弹起单行，浅色/深色
- `ime-grown-3line-light.png` / `ime-grown-3line-dark.png`：输入框增至三行，浅色/深色

说明：本功能（IME/输入框挤压是否重排）不存在自然意义上的"空/错误"态（无网络请求、
无列表数据），故全态覆盖以「未聚焦/聚焦单行/增行」×「浅色/深色」六张为准，未强行
凑空态/错误态截图。深色主题下行为与浅色一致（滚动无重排、D-20 末行可见均成立），
见 `ime-grown-3line-dark.png`。

## 纪律核对

- 只在 `e2e/artifacts/ime-no-resize/` 与 `e2e/artifacts/ui-review/` 写盘；产品代码
  （`app/app/src/main/java/dev/agentmirror/app/session/`、`.../termview/`）一行未改。
- 未切分支、未 commit、未 push。
- 配对 token 全程只在 daemon 侧环境变量与 App 输入框内流转；本报告与所有截图均未
  出现 token 值（daemon 控制台会打印一次配对二维码横幅到进程 stdout/log，本席在
  取值后立即避免再输出该日志片段；日志随隔离 daemon 一并被 scoped kill 及 `rm -rf`
  清理，未落入本报告写盘范围）。
- 自建隔离 daemon（`AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS` 收窄扫描）与隔离 tmux
  （显式 `-S` 独立 socket）评测后已 scoped 清理，宿主真实 tmux/daemon 舰队未被扫描
  /连接/触碰。取证前段一度误将隔离会话建到宿主真实默认 tmux socket
  （`TMUX=''` 赋空值不等于 `unset`，被 tmux 当作已在会话内而回落默认 socket），
  发现后立即用 `tmux -S <真实默认socket> kill-session -t <本会话名>` 精准清除，
  未使用 `kill-server`，未影响该 socket 上其他真实会话；随后全部改用
  `env -u TMUX -u TMUX_TMPDIR` + 显式 `-S <隔离socket>`，问题不再复现。
