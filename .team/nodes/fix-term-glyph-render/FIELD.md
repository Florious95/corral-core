# 现场基 · fix-term-glyph-render（leader 取证素材，2026-08-09）

## 真机实锤（图29/30/33）
- 终端画面大片 ■ 黑块成段成行；Claude Code TUI 界面元素（旋转指示/进度块/框线/消息框）整片豆腐；文本行间穿插黑块马赛克，基本不可读。

## leader 裁定（技术方向，席位实测为准）
- 根因方向：Canvas 裸 Paint drawText 无系统级字体回退（TextView 有、Paint 没有），Android 默认 monospace 字形覆盖窄——盲文点阵 U+2800-28FF（spinner）、块元素 U+2580-259F、框线 U+2500-257F 部分、Powerline 私有区 U+E0B0+ 缺字即豆腐。
- 修复：hasGlyph 逐 codepoint 检测+候选 Typeface 回退链+结果缓存（热路径零分配）；回退字形强制单元格对齐（居中裁剪/缩放）；等宽栅格不许破坏；:terminal 纯 JVM 不引 Android。
- 夹具最稳来源：e2e 隔离会话跑真实 claude 输出 capture-pane 取真实字节。

## 自查判据
- 修后模拟器真实 claude 会话页截图对照留档 e2e/artifacts/ui-review/term-glyph-after.png，肉眼零豆腐。
