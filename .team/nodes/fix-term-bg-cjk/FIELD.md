# 现场基 · fix-term-bg-cjk（leader 取证素材，2026-08-09 21:50 用户真机实拍）

## 实锤证据（user-real-device-sample.png，用户经 App 传图链路上传——该链路本身真机首证）
- 深色默认背景区域：中文/英文/数字全部渲染正常（大段中文交付消息逐字可读）。
- **浅色背景块内（Claude Code recap/引用框，SGR 背景色区域）：CJK 文字大面积重叠、错位、黑块马赛克化，基本不可读**。两处大块浅色区域均如此，稳定复现。
- 键条最右「→」按钮被屏幕右缘裁切一半（次要，同席顺带：键条横向布局溢出）。

## leader 裁定（方向候选，席位取证为准）
- 疑点：带背景色 run 的绘制路径与默认路径不同（背景矩形+文字两遍绘制？），宽字符（CJK 双宽）在背景色 run 中的单元格测量/推进错位——glyph 案改过 drawCentered/GlyphRunBuilder 的区间绘制，背景色路径可能没走同一修复；或反色（reverse video）处理缺陷。
- 复现夹具：tmux 会话里 printf 带 SGR 背景色的中文行（如 \e[47;30m 中文测试\e[0m 与真实 Claude Code recap 输出 capture 字节），模拟器复现后修，修后同机位截图对照。
- 键条裁切：SessionScreen KeyBar Row 布局横向约束（可滚动或 weight 均分），ui-redesign 席的布局，此处最小修。
