# librarian 撞库回执（tsnet/tailscale/authkey/内嵌组网/SOCKS/隧道/密钥，2026-08-09）
【强命中】
- 007：App/服务端内嵌 tsnet，App 自己即 TS 节点，用户无需单独装 Tailscale App
- 011：LAN 直连+内嵌 tailscale；QR 载 服务端地址+配对 token（可选 TS authkey）
【弱命中】
- R-003：QR 选中 TUN（tailscale 虚拟网卡）地址——网卡选择缺陷实证；003 隧道闪断重连恢复；015 内嵌 tsnet 实现存在
【无命中/注意】
- SOCKS/双栈/安全存储/密钥管理均无需求条目——实现自由度在席位，但 authkey 保管按 FIELD 红线（同 token 级）
