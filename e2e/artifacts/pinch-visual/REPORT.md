# 捏合修复视觉验收（用 pinch-out，emulator-5554）

对照组 commit: `d53aba161`（`920fd7a16` 的父提交，即 fix-pinch-preview-commit 落地前）
修复组 commit: `270d2f4e4`（当前 HEAD，含 `920fd7a16` 捏合预览/提交 + 后续所有改动）

手势: 同一份 `/data/local/tmp/pinch_out.sh`（raw sendevent，两指从 span≈80px 张开到 span≈400px，
中心 (540,1200)，屏幕 1080x2400），两组测试逐字节相同，唯一变量是 APK。

方法: 主机侧 `tmux display -p "#{pane_width}x#{pane_height}"` 每 50ms 轮询一次贯穿整个手势
（不依赖应用内埋点，直接量协议层落地的 resize 效果）；同时 `adb shell screenrecord` 录屏，
`ffmpeg` 抽帧至 10fps，机器眼 `time.js analyzeSequence` 判差分形态（非目检）。

## 数字 1：手势期间主机 pane 尺寸变化次数

| | 序列 | 变化次数（不含基线） |
|---|---|---|
| **修复前**（失败态基准） | 108x87 → 98x76 → 90x67 → 83x67 → 77x60 | **4** |
| **修复后** | 108x87 → 83x67 → 77x60 | **2** |

**判据：修复后必须是 1（松手那次）。实测 2，不达标。**

修复前是逐帧发 resize 的典型形态（4 个中间态，from N-per-frame）；修复后确实大幅收敛
（4→2），但不是「预览零 resize + 松手一次」的目标形态——手势中途仍出现了一次额外的 pane 尺寸
落地（108x87→83x67），松手时才到最终值 77x60。**这次手势里 onScaleEnd 之外至少还有一次
resize 落到了主机侧。**

## 数字 2：闪烁（机器眼帧差分形态）

| | 非零差分帧 | movementPattern | 差分区域占比 |
|---|---|---|---|
| 修复前 | 5 帧（10fps 序列第29-33帧） | MIXED | 7.5%-8.8% |
| 修复后 | 6 帧（第29,31-35帧，第30帧骤降到0.56%后又回升） | MIXED | 0.6%-9.4%（第32帧骤降） |

两组都判为 `MIXED`（非纯滚动、非纯底部追加，含多帧大面积变化），差分区域占比量级相近。
修复后第30/32帧出现两次骤降到接近0的间隙，形状上像「两段独立的重排各自收尾」，与数字1的
「两次尺寸落地」吻合——但**非零差分帧总数没有随「resize从4次降到2次」等比例下降**，视觉层面
的改善不如主机侧 resize 次数改善明显。

## 结论

**不达标。** 数字1的「必须是1」判据未通过（实测2）。这是相对于修复前的真实改善（4→2，
resize次数减半），但没有达到 commit message 声称的「N次降到1次」。数字2显示视觉重排帧数
两组接近，未观测到闪烁被显著抑制的独立证据。

建议 w-dev-pinch（若存在此席位）或原改动作者复核 `onScaleEnd`/`onPinchCommit` 路径是否存在
「多指抬起分两阶段触发」的情况——两次尺寸落地里前一次（83x67）是否对应某个中间 ACTION_POINTER_UP
先行触发了一次提交，值得用真实 MotionEvent 序列（而非 raw sendevent）交叉验证。

## 证据文件

- `before-panesize.log` / `after-panesize.log` —— 50ms 轮询的完整时间戳+尺寸序列
- `pinch_before.mp4` / `pinch_after.mp4` —— 原始录屏
- `frames-before/` / `frames-after/` —— 10fps 抽帧
- `02-pane-before.png` / `03-pane-after.png` —— 两组进入会话后的基线截图
