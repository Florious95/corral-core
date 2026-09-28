# 现场基 · fix-upload-transport-tsnet

## 用户真机实证（2026-08-12）

- 报告一：「上传图片会失败」
- 报告二（关键补充）：「**失败的模式是 Timeout**」

**Timeout 而非 401，说明 D-22 的 Bearer 头修复是有效的**，请求根本没到服务端，
是连不上，不是被拒。

## leader 已查实的根因

```
app/.../session/HttpUrlConnectionUploader.kt   → grep Proxy/socks/tsnet：零命中，走系统网络栈
app/.../tsnet/TsnetDial.kt:54-55               → proxyFor(state)：状态 Up 时返回 SOCKS Proxy，
                                                  其余 NO_PROXY；另有 isTailnetHost 判定
```

WebSocket 经 tsnet 用户态节点的 SOCKS 代理拨号；上传用 `HttpURLConnection` 系统直连。
**两条通道不同。** daemon 只在 tailnet 可达时，WS 通而 HTTP 上传出不去 → connectTimeout。

## 为什么模拟器实测没抓到

D-22 的模拟器验收用的是**测试席自建隔离 daemon + 10.0.2.2 局域网直连**，全程未经 tsnet。
**我们验的场景和用户用的场景不是同一条路。**
这是本轮又一次「模拟器绿、真机坏」，教训与捏合、上滑同源：
测试环境与真实环境的差异本身就是缺陷藏身处。

## 修法方向（不要自行发明）

上传必须与 WS 使用**同一传输通道**。`TsnetDial.proxyFor(state)` 已存在，直接复用。
需要考虑：
- tsnet Up 且目标是 tailnet host → 走 SOCKS 代理
- tsnet Down 或 LAN 直连 → 保持系统直连（不得破坏当前 LAN 可用路径）
- 状态切换时的取值时机（与 WS 取同一份 state，避免两边判断不一致）

## 不得破坏

- **D-22 已修**：Bearer 头链路（ServiceWire 快照 → SessionRoute → VM → uploader），
  以及二参无认证入口立即 Failure 且零 HTTP 请求的防复发断言
- **D-30 已修**：upload 不触碰 `textFieldValue`
- 强制回归门 `TermSurfaceSessionBindingRegressionTest`、`TermSurfacePinchGestureTest` 保持绿
- 主干含今晚多条已锚定改动，只动 write_scope 内文件

## 收工门

模拟器实测**必须覆盖 tsnet 路径**，不能再只验 LAN 直连——
否则等于重复这次「验了个不相干的场景」。
若隔离环境搭不出 tailnet，如实说明并交 leader 定夺，不要用 LAN 直连冒充通过。

## 用户补充实证（2026-08-12，决定性）

> 「我现在**基于 TS 的 token 以及 TS 的局域网 IP** 在和你沟通，说明这条链路已经走通了。」

即：用户当前正经 tailnet 与 daemon 通信，**WebSocket 完全正常**。
而同一时刻图片上传 timeout。

**这直接坐实根因**：WS 经 tsnet SOCKS 代理可达 tailnet 地址；
`HttpUrlConnectionUploader` 走系统网络栈，够不到同一个 tailnet 地址 → connectTimeout。
两条通道的差异就是缺陷本身，不需要再做假设验证。
