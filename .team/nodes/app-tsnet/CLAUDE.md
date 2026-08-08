# 知识基底 · app-tsnet（系统编译产物）——契约级攻坚任务（Fable 5 席）

## 0. 任务（taskbook.yaml#app-tsnet，contention: contract）
- 目标：App 内嵌 tailscale（007/011 裁定）：用户填 TS token（authkey）即入网，与配对流程打通（QR 的 ts_authkey 预留字段接活）。**若技术评估后风险过高，出一页纸降级方案 send 给 leader 裁定**——这是合法出口，不是失败。
- 验收（exit 0 = 过）：`bash -lc 'cd /Volumes/nvme/Projects/远程Agent安卓/app && ./gradlew -q :app:testDebugUnitTest --tests "*Tsnet*"'`
- 写范围：`app/app/src/main/java/**/tsnet/`、`app/app/src/test/`、`app/app/build.gradle.kts`（仅加依赖）、`docs/decisions/app-tsnet.md`（评估/降级裁定文档）。红线：依赖许可必须 Apache-2.0 兼容（tailscale 系 BSD-3 ✅）；零 GMS；conn/pairing 等包只消费公开 API。

## 1. 架构基（先评估，后施工，评估文档落 docs/decisions/）
- 候选路线（按侵入性排序，你实测后定）：
  A. **libtailscale AAR**（github.com/tailscale/libtailscale，官方 Android 绑定，BSD-3）：gomobile 构建产物；确认是否有可用预构建 artifact，无则本机 gomobile 构建（Go 1.26 已装）并把构建脚本入 tools/。App 内起 tsnet 用户态节点（无 VPN 权限方案优先——tsnet 用户态 socks/direct dial，而非 VpnService 全局接管）。关键 API 面：用 authkey 起节点 + 提供 dial 能力给 OkHttp（自定义 SocketFactory/Proxy 走 tsnet 用户态栈）。
  B. **VpnService 全局模式**（官方 Tailscale App 的形态）：侵入大、与用户已装 Tailscale App 冲突，**不推荐**，除非 A 实测不可行。
  C. **降级方案**（A/B 均不可行时的一页纸）：QR ts_authkey 字段留待后续版本，文档化"已装 Tailscale App 的用户开箱即用（服务端 tsnetd 已在 tailnet 监听，手机在 tailnet 里直接 ws://<tailnet-ip> 配对）"——产品闭环不破，仅"App 内免装 TS"延后。
- 接线面：TsnetManager（authkey 起停/状态流）+ 给 ServiceWire 注入自定义 dial（OkHttp SocketFactory）；pairing-ui 的 TS token 输入入口已留（其交件后接线，若未交件先做自身模块+测试）。
- 测试面：纯 JVM 可测部分=状态机/authkey 校验/dial 选择逻辑（fake 后端）；真实 tailnet 连通归 e2e 手册。JVM 测试**不依赖 AAR 原生库加载**（架构上把 native 绑定隔离在薄适配层后）。

## 2. 现场基
- 服务端 tsnetd 已交付（tailnet+LAN 双栈，authkey 经 config）；QR JSON 含空 ts_authkey 字段（pairing 交付）。
- **共享编译单元纪律**：pairing-ui 席位并行施工 :app——每次落盘保持整模块可编译+依赖可解析（camera-bom 幻觉版本刚教训过：写依赖前实测 maven 404 与否）。
- 构建 `bash -lc`；compileSdk 36；Kotlin 2.2.0。

## 3. 需求基（指针）
1. requirement-base/entries/007-联网模型-tsnet与扫码.md（"App 内嵌 tsnet 已定"的裁定原文与理由——痛点二：不叠 App）
2. requirement-base/entries/011-技术路线裁定.md（联网行）
3. requirement-base/entries/010-最终验收与运行方式.md（生产级标准；降级出口的合法性）

## 4. 经验基
- 你是攻坚席：**禁止杂活**——只做本任务评估与 tsnet 模块；相邻问题报 leader。
- 评估要实测不要文献综述：libtailscale 能不能构建、AAR 多大、authkey 起节点耗时/内存，跑出数字再下结论（004 轻量化是硬约束：AAR 体积与常驻内存要写进评估文档）。
- 红测先行；注释红线；净化前缀照旧。

## 5. 沉淀区（唯一允许你追加写入的区域）
