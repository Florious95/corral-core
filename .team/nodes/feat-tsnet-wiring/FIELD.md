# 现场基 · feat-tsnet-wiring（leader 取证素材，2026-08-09）

## 立案背景（用户验收红线）
- 用户原话：「我说了是生产级别可用，最关键的TS完全没适配，旁边写的可选，然后写的即将推出。」
- 三痛点之一=单一 App 内嵌 tsnet（用户不装 Tailscale App）；007/011 裁定内嵌路线；010 验收=生产级。
- 死件家族第七例：server/internal/tsnetd 交付在（authkey 时 LAN+tailnet 双栈）；app-tsnet 交付 gomobile 绑定（AAR、SOCKS5 dial、tools/tsnetbind、16 测）；**App 配对页 authkey 输入框死占位（「即将推出」文案）、ConnectionManager 拨号从未走 tsnet**。

## 既有资产坐标（先盘点再接线，勿重复造）
- app tsnet 包：app/…/tsnet/（绑定层交付物，读其 16 测了解已有能力面：节点起停/SOCKS5 端口/状态回调——具体以代码为准）。
- AAR：app-tsnet 交付物（gradle 依赖已挂或在 libs/，核实）。
- 服务端：server/internal/tsnetd（配置分支单测在）；cmd 是否已暴露 authkey flag/env 核实。
- 配对页占位：PairingScreen「Tailscale 入网（可选）」区块（ui-redesign 重画过，逻辑仍死）。
- QR candidates：fix-pairing-candidates 刚交付——tailnet 地址应入候选集（服务端起了 tsnetd 时）。

## leader 裁定
- 拨号路由：运行时判定——目标地址可由 tailnet 承载且本机 tsnet 节点已就绪→SOCKS5；否则直连。不引配置开关（复杂度不值）。
- authkey 只存 EncryptedSharedPreferences 或至少与 token 同级安全存储；绝不进日志（token 同款红线）。
- 真实 tailnet 端到端需用户 authkey——模拟器自验到「SOCKS5 拨号路由选择正确+假 tsnet 接缝全绿」，真机 tailnet 联通显式列未验证清单交用户（016d）。
- UI 文案「即将推出」删除——填 authkey 即生效；连接状态徽标区分 LAN/tailnet（018 状态可视）。
