# 高延迟条件下的过渡态实测报告

- 日期：2026-08-13
- 设备：`emulator-5558`（`agentmirror_geo_1260x2800`，几何标定 AVD，长期保留）
- APK/daemon 构建自 HEAD `c84b73950371a2bc8c5bf0029ebbfcf7fbc6a217`
- 隔离 tmux + 隔离 daemon（`AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS` 收窄），App 正常配对

## 一、注延迟：自证生效（两个数字）

**「用 emulator 网络限速」这条路走不通，如实说明后换了自建方案。**

### 排除的路径

1. `emulator console` 的 `network delay <ms>` 命令：能设置成功（`network status` 回显已变），
   但**对 `10.0.2.2`（App 实际使用的 host-alias 网关）与真实 LAN IP 均无效**——TCP round-trip
   注延迟前后一致（用 guest 侧 `date +%s%N` 包住 `nc` 连接精确量得，均在同一量级，无系统性差异）。
   这是该 AVD 网络拓扑的已知限制：`network delay` 只影响模拟蜂窝数据链路，不影响 host-alias
   NAT 快速通道；且本沙箱环境无真实公网出口（`ping 8.8.8.8` 挂起无响应），无法用真实外网验证
   限速器本身是否在其设计场景下有效——**如实说明，未回避**。
2. macOS 原生 `dnctl`/`pfctl`（dummynet）：命令存在，但需要 `sudo`（交互式密码），
   本席无法提供，**判为不可用，未强行绕过**。

### 采用的路径：自建用户态 TCP 延迟代理

`delay_proxy.py`——纯 Python asyncio TCP 中继，监听 daemon 前面，双向转发时各 sleep 固定
延迟（不需要 root/sudo）。App 连接地址从直连 daemon 改为连接代理，代理再转发到真实 daemon。

**自证数字（注入前 / 后）**：
1. 代理机制自证（host 侧 python 直连量，控制变量）：
   直连 echo 服务器 **5.3ms** → 经过 200ms-each-way 代理 **403.6ms**
   （精确匹配预期 2×200ms=400ms）
2. 端到端真实链路自证（guest 侧，通过 App 实际使用的路径 `10.0.2.2:<代理端口>`）：
   App 从点「连接」到 daemon 记录 `listing: first snapshot`（真实鉴权+首次快照完成）耗时
   **864ms**——与「约 2 个 400ms 往返」量级吻合（鉴权 1 次 + 首次 listing 1 次）。

**判定：注入生效，取 200ms each-way（约 400ms RTT）作为本轮延迟基准。**

## 二、假说七 A（捏合过渡态右缘越界）：**未测成，非未复现**

### 关键区分：不是"复现不到"，是"触发不了"

按序列填满内容（150 行、每行~123 字符含 `|END` 尾标记，接近 126 列满宽）、进会话确认基线
（`rightMarginPx=51`，`01-app-baseline.png`）后，尝试注入双指捏合手势触发过渡态测量，
**发现该 AVD 上原始 `/dev/input/eventN` sendevent 注入完全不被 Android 输入管线识别**——
不止双指捏合，**连单指 raw sendevent 都不触发**（对照实验：`adb shell input tap` 在完全相同坐标
能唤起 IME，同坐标的原始 `sendevent` 序列不能）。已用 `getevent -l` 确认内核确实收到了注入的
原始事件（设备节点层面注入无误），问题出在 InputReader/InputDispatcher 这一层未把它识别为
有效触摸——与 `test-pinch-harness` 任务记录的已知风险同源（"模拟器手势注入不可靠"），
但这次连单指都受累，比此前记录的更严重。

**尝试过的修复**：`adb root` 解除设备节点权限、清空所有 slot 强制释放残留触摸状态、
放慢注入节奏（每步间插 50ms sleep）——均未解决。

**如实报告：不是"捏合了但没复现越界"，是"这台 AVD 上目前没有可用手段真正捏合"。**
高延迟本身与本次测不成无关——这是触摸注入基础设施的缺口，需要另案解决（例如走 Android
`UiAutomation.injectInputEvent` 的 instrumented 路径，`test-pinch-harness` 已有该路径的
部分基础设施但也记录为 RED 未解决）。

## 三、发消息整屏刷（高延迟下）：**已测，非目检，机器眼判定**

方法：填满内容基线上，输入新消息 → 点「发送」→ **立即连续截图**（改用**顺序**截图避免
并发截图导致的传输层数据错位/PNG 损坏——首次尝试的高频并发截图产生了 ffmpeg 判为损坏的帧，
`sips` 未查出但 ffmpeg 解码器判定 "chunk too big"，已改为顺序截图规避，12 帧覆盖约 2.9 秒，
帧率降至约 4fps 换取可靠性）。

`machine_eye/time.js` 判定：

```json
{
  "movementPattern": "SCROLL_DOWN",
  "reflowSignal": false,
  "nonZeroDiffFrames": [1, 2, 7, 8, 9]
}
```

**关键现象：差分不是连续发生的，分两波，中间有明显静默间隔**：
- 第一波（帧 1→2，约 t=3ms→388ms）：本地乐观更新（输入框清空、可能的即时回显）
- **静默期**（帧 3→4→5→6，约 t=672ms～1437ms，约 **765ms 无任何视觉变化**）
- 第二波（帧 7→8→9，约 t=1666ms→2180ms）：真正的服务端确认/回显到达，触发第二次可见更新

两波之间的静默期（~765ms）与本轮注入的 200ms-each-way（约 400ms 单趟、约 2 趟往返≈800ms）
量级吻合，**是高延迟把"发消息"从 LAN 下的单次快速更新拉伸成了两段式、中间有感知停顿的更新**。

**形态本身（SCROLL_DOWN，非 FULL_REFLOW）**：`areaRatio` 每帧均 <8.4%（未达 `FULL_REFLOW`
判定的 >50% 阈值），差分区域顶到底连续（`topRatio<0.1` 且 `bottomRatio>0.9`）——机器眼判定
**这是合法的"内容追加→视口平移"形态，不是整屏重排 bug**。但**被延迟拉长的两段式更新时序**
本身，是用户口中"TS 下更明显"的一个可能来源（哪怕形态正确，被拉长到近 3 秒内分两次更新，
仍会被用户感知为"卡顿/半天没反应/又跳了一下"）。

右缘复核（附带）：全部 12 帧 `rightMarginPx` 稳定为 51px（1 帧 `bottomMarginPx=7` 系单帧噪声，
其余为 0），**未见越界或截断**。

## 四、捏合闪烁（高延迟下）：**未测**

同样受限于二、所述的触摸注入基础设施缺口，本项未能执行。

## 五、纪律核对

- 隔离环境全程；未碰宿主真实 tmux/daemon；App 正常配对未手改 SharedPreferences。
- 产品代码零改动；未切分支、未 commit、未 push；token 未出现在任何输出。
- 未因超时而降低延迟去凑判据——延迟全程保持 200ms-each-way，未调整。
- AVD `agentmirror_geo_1260x2800` 保留；隔离 daemon/proxy/tmux 已清理。
