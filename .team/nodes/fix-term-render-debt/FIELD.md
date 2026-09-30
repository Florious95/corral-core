# 现场基 · fix-term-render-debt（leader+w-glyph-f5 取证素材，2026-08-09）

## 实锤证据
- 冻结（缺陷①）：f5 席实测——会话页 clear+printf 注入新内容后画面纹丝不动，swipe 无效，退出重进（重 attach）才见新画面。定位线索：postFrame 帧调度仅在 presenter 注入与 damage 自续两处触发，WS 增量帧到达路径无人调 postFrame。真机用户观感=镜像"死了"。
- 行首漂移（缺陷②）：e2e/artifacts/ui-review/term-glyph-after.png 肉眼可见每行起点=上一行末尾 x 座标（box draw 行、blocks 行、GLYPH_OK、bash 逐行右移）。tmux 侧 capture-pane 文本对齐正常 → 服务端无罪，:terminal feed 链 \n 未带列归零（\r\n vs 裸 \n 的 CR 语义）或网格 cursor 换行处理缺陷。19:52 前席截图同状，非 glyph 改动引入。
- 用户原话锚：「渲染别扭，UI极其简陋」——UI 已由 ui-redesign 案清偿，「渲染别扭」的剩余实体即本案两条。

## leader 裁定
- 两条一案同席收（同域连案，termview+:terminal+session 接线三处都可能涉及）；修①时不许用定时器轮询刷新糊弄（红线：静默经济——空闲零帧循环，数据到达才唤醒）。
- 夹具取真实字节：隔离 tmux 跑 printf 序列 capture 原始输出做 :terminal feed 夹具。
