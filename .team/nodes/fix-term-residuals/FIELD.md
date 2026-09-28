# 现场基 · fix-term-residuals（leader+w-term-debt 取证素材，2026-08-09）

## 实锤证据
- 首行裁切：.team/evidence/fix-term-render-debt/A-snapshot-align.png 顶部 ALIGN_A 行被切半——w-term-debt 定位 translate(0,-lineHeightPx) 固定偏移（termview 绘制原点）。
- resize 残影：SIGWINCH 后 CLI 重排，但服务端不补快照——客户端旧提示符残影叠新画面（w-term-debt 实测观察）。方案自由度：服务端 resize 后补发快照帧，或客户端 resize 后主动重订阅拉快照——协议改动最小者优先，docs/protocol.md 若需改先契约后码。

## leader 裁定
- 两条一案同席；红测先行（首行：首行完整可见断言/夹具渲染网格 y=0 行；resize：resize 后快照重放断言——e2e 层1 或 api 集成测试）。
- 交件附修后 A 图同机位截图对照。
