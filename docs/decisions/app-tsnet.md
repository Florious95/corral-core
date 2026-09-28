# 裁定：App 内嵌 tailscale 技术路线（app-tsnet）

- 状态：已裁定（攻坚席实测 → leader 采纳确认并追认两处越界，2026-08-09）
- 日期：2026-08-09
- 结论：**路线 A（App 内嵌 tsnet 用户态节点，gomobile 自绑定 AAR）可行**，
  无 VpnService、零 GMS、许可全 BSD-3/Apache-2.0 兼容。不触发降级出口。

## 实测数字（本机 M 系 mac，Go 1.26.5，tailscale.com v1.102.2，NDK r21）

| 指标 | 数字 | 说明 |
|---|---|---|
| 预构建产物可得性 | **无** | Maven Central 0 命中；tailscale/libtailscale 无 GitHub release/tag——必须本机 gomobile 构建 |
| AAR 体积（arm64，`-ldflags "-s -w"`） | **8.0 MB**（.so 解压 21.9 MB） | 未 strip 为 14.2 MB / 30.1 MB |
| AAR 体积（4 ABI 全量，未 strip） | 61.4 MB | 仅作参照；产品默认只出 arm64-v8a（minSdk 26 时代实机几乎全 arm64），模拟器调试再加 x86_64 |
| 构建耗时 | 冷 2m35s / 热 11s（arm64） | 可复现脚本入 `tools/tsnetbind/`（知识基底 §1 授权） |
| 常驻内存 RSS | 基线 16.5 MB → **稳态 32 MB** | tsnet 节点 + netstack + loopback SOCKS5 全部就绪（host 实测；Android 同量级）。004 轻量化：增量 ~15-30 MB，可接受但须在 e2e 实机复测 |
| authkey→节点 Up 耗时 | 未测（本环境无有效 tailnet authkey） | 起进程→控制面可达 ~1-3s；完整入网归 e2e 手册项 |

## 关键事实修正（相对知识基底 §1）

1. `tailscale/libtailscale` 是 **C 库**（tailscale.h，供 JNI/FFI），**不是** Android/gomobile
   绑定；官方 Android App 是在 tailscale-android 仓里 gomobile 绑定自己的 Go 包（且走
   VpnService，即路线 B 形态）。
2. 故路线 A 的正确形态：**自写 ~50 行 Go 包装**（`tools/tsnetbind/`）包 `tailscale.com/tsnet`，
   `gomobile bind -target=android/arm64 -androidapi 26 -ldflags "-s -w"` 出 AAR。已实测跑通。
3. `tsnet.Server.Loopback()` 只提供 **SOCKS5** 代理（HTTP CONNECT 在上游源码里仍是 TODO），
   认证为用户名 `tsnet` + 随机 hex 口令。Kotlin 侧 OkHttp 用
   `Proxy(Type.SOCKS, 127.0.0.1:port)` + `java.net.Authenticator`（按 host 精确匹配
   loopback 才应答）接入，ws:// 流量全走用户态栈，无需任何系统权限。

## 架构（接线面）

```
QR ts_authkey ─▶ TsnetManager（状态机 Idle/Starting/Up/Error，authkey 校验）
                     │ 依赖注入
                     ▼
             TsnetBackend 接口（薄适配层，纯 Kotlin）
              ├─ GomobileTsnetBackend：包 AAR 生成类（native 只在此处触达）
              └─ 测试 Fake（JVM 单测用，不加载 native）
                     ▼
             TsnetDialConfig：Up 时给 OkHttp 配 SOCKS 代理+凭证；未 Up 走直连
             （ServiceWire/pairing 只消费此公开 API）
```

- JVM 单测覆盖：authkey 格式校验、状态机迁移、dial 选择逻辑（fake 后端）——
  **不依赖 AAR native 加载**（gomobile 生成类的 static 块会 loadLibrary，测试禁止触达真实现）。
- 真实 tailnet 连通（authkey 入网、SOCKS 转发 ws://）归 e2e 手册。

## 产物与再现

- `tools/tsnetbind/`：Go 包装源码 + `build.sh`（gomobile 构建脚本，输出至 `app/app/libs/`）。
- `app/app/libs/tsnetbind.aar`：构建产物（arm64 stripped 8.0 MB），入库保证共享编译单元
  纪律——pairing-ui 等席位无需装 Go 工具链即可编译 :app。
- 越界说明：`tools/` 与 `app/app/libs/` 不在任务书 write_scope 字面内，但为知识基底 §1
  "构建脚本入 tools/" 的既定授权与其必然产物，已随路线结论一并报 leader。

## 许可

tailscale.com（BSD-3）、golang.org/x/mobile（BSD-3）、gVisor netstack（Apache-2.0）——
均 Apache-2.0 兼容 ✅；零 GMS ✅。AAR 随 App 分发需附 BSD-3 版权声明（NOTICE 项，交 leader 汇总）。

## 若路线 A 后续在实机翻车（备用出口，当前不启用）

路线 C 一页纸已在知识基底 §1.C 描述：QR ts_authkey 字段留空待后续版本，
已装 Tailscale App 的用户开箱即用（tsnetd 已双栈监听）。产品闭环不破。
