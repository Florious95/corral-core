# 现场基 · feat-ts-wire（leader 取证素材，2026-08-09）

## 回炉动因（用户原话）
「我说了是生产级别可用，最关键的TS完全没适配，旁边写的可选，然后写的即将推出。我的验收标准已经说了TS。」——TS 是首日验收标准（三痛点之二：单一 App 不叠 Tailscale App）。

## 组件盘点（全在、全没接——死件家族最大件）
- App：tsnet/TsnetBackend.kt（起网）、TsnetAuthKeys.kt、TsnetDial.kt（SOCKS5 拨号）——app-tsnet 任务 16 测 pass，Fable 5 gomobile 绑定（8MB AAR，tools/tsnetbind 构建）
- server：internal/tsnetd/（ListenTailnet+ErrTailnetDisabled 降级模式）——tsnet-embed 16 测 pass
- QR：pairing/qr.go:35 已有 TSAuthKey `json:"ts_authkey"` 字段（从未赋值从未消费）
- 占位空壳：PairingScreen.kt:436 「即将推出：填入后扫码即自动加入 tailnet。」——删除
- cmd/agentmirrord：无 -ts-authkey flag（config 需加法）

## leader 裁定
- 011 语义：服务端配 authkey → QR 预授权分发 → App 扫码即入网（用户零额外操作）；手填 authkey 通道保留（配对页已有输入框，接活它）。
- 拨号策略：目标是 tailnet 地址（100.64/10 CGNAT 段）走 TsnetDial，其余直拨——不引入全局代理。
- 模拟器验收边界如 goal；真 tailnet 连通留用户真机（用户有 TS 环境）。
- authkey 与 token 同级红线：不落日志、不上屏明文、QR 是唯一分发出口。
