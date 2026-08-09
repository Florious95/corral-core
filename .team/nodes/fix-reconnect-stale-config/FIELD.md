# 现场基 · fix-reconnect-stale-config（leader 取证素材，2026-08-09）

## 真机现场（四次实证）
- 用户真实序列：扫旧码 ws://10.20.55.20:9900/ws（不可达）→ 重扫/手填 ws://192.168.31.116:9900/ws 连接成功进列表 → 锁屏数分钟 → 解锁 → 无限「重连中」，会话全点不开，持续 10+ 分钟。
- **daemon 侧铁证（leader lsof 实测）**：用户手机显示「重连中」的整段时间，daemon 端口 9900 仅 LISTEN、零 ESTABLISHED、零入连尝试——重连请求根本没到达。
- 同会话内点 + 传图：红字「未配置上传地址」（图 30）——uploadBaseUrl 未配。

## leader 裁定（root cause 候选，两条都取证，不许预设）
1. 单例陈旧配置：ServiceWire.manager `!=null` 即复用；若 manager 首建于旧地址时代，setConfig 更新持久层但不更新已存活实例拨号目标 → 重连永远拨旧址。红测：改配置→触发重连→断言拨新址（修前应红）。
2. E2 网络回调缺口（scenario-coverage E2 行）：ConnectivityManager.NetworkCallback 从未注册，锁屏 WiFi 休眠断连→退避爬到长间隔→解锁网络恢复无人打断退避。
- 上传地址与①疑同根：uploadBaseUrl 是 PersistentConnection 启动三件套之一，取证哪条真实路径未走统一入口。
- 修复附带（018 标准5）：重连中 UI 显示当前拨号地址+已试次数。

## 自查走查判据（交件前席位自跑）
- 模拟器复现：连接成功→`adb shell input keyevent 26` 锁屏→60s→解锁→断言 30s 内列表恢复；改地址后重连拨新址日志断言。
