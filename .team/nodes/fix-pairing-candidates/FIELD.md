# 现场基 · fix-pairing-candidates（leader 取证素材，2026-08-09）

## 真机现场（二次真机实证）
- 主机双真实网卡（10.20.55.20 / 192.168.31.116），QR 自动主选 10.20.55.20，用户手机 WiFi 在 192.168.31.x——扫码后「配对失败：服务端地址不可达」（截图27 红字+重试按钮，失败可见已生效），但用户只能人肉猜正确地址；leader 被迫 kill daemon 换 -host 重启才连上。
- 结论：多真实网卡下「哪个地址对端可达」机器不可判定，产品必须把候选全集给出来逐试，不许赌单一主选。

## leader 裁定
- 服务端已有全候选计算（fix-qr-host-detect 交付的终端候选清单同源）——QR payload 加可选 candidates 字段（全部候选 ws URL），协议前向兼容不 bump 版本（无 candidates 的旧 QR 行为不变）。
- App：配对失败（不可达/超时）且有 candidates → 自动逐个试探（每个 3s 超时），全败才落手动态并展示候选列表一键重试；手填表单地址支持从候选下拉选。
- 契约先行：docs/protocol.md QR payload 节先改；server pairing 包生成、app pairing 包消费；QR JSON 对齐 server qr.go 现状。
- 注意与刚入库的 fix-reconnect-stale-config 改动合流（pairing 包已被其触碰：PersistentConnection 统一装配入口——你的自动逐试成功后走同一入口启动）。

## 自查判据
- 红测：解析含/不含 candidates 的 payload；失败后逐试首个可达即连；全败候选列表可见可点。
- 模拟器走查：daemon -host 指错误地址但 candidates 含 10.0.2.2 正确项 → App 扫码后自动逐试成功进列表。
