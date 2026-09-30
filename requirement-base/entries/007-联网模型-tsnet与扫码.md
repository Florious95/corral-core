# 007 联网模型：内嵌 tsnet + 扫码配对双轨

- 状态：部分裁定（内嵌 TS 已定；扫码方案的实现路线未定）
- 出处：用户开场陈述 + leader 分析，2026-08-09 对话

## 已定

- "App 里填 Tailscale token"意味着 **App 内嵌 tsnet 库**，App 自己就是一个 TS 节点，
  用户不需要单独装 Tailscale App——否则痛点二（叠 App）没解决。
- 服务端同理可内嵌 tsnet，主机侧甚至不必装 tailscale。

## 理想态（方向已定，路线未定）

电脑上起服务端 → 出二维码 → App 扫码即连，比填 token 更简洁。
kittylitter 的 Alleycat（QR 扫码 P2P 配对、穿 NAT）是体验标杆。

两条实现路线**不互斥，可分层**：
- (a) QR 只是配对信息载体，底下仍是 TS（扫码 = 自动填 authkey 完成组网）
- (b) 自建 P2P 打洞 + 中继（Alleycat 路线），彻底去 TS，但要自己扛 NAT 穿透

## 待定夺

扫码方案走 (a)、(b) 还是先 (a) 后 (b)，属契约级议题。
