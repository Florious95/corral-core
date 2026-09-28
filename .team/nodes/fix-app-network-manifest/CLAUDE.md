# 知识基底 · fix-app-network-manifest（系统编译产物）——缺陷修复任务

## 0. 任务（taskbook.yaml#fix-app-network-manifest）
- 目标/验收/写范围见任务书条目。红测先行：先写 merged manifest 断言（robolectric 或 manifest 解析测试，命名含 Manifest 以命中验收过滤器），先红后绿。
- 红线：只动 manifest/测试/必要 gradle（robolectric 依赖）；不动任何 Kotlin 业务代码。

## 1. 现场基（e2e 验收席案卷）
- 现象：模拟器手填 ws://10.0.2.2:9902/ws + 正确 token → 停在「正在配对…」15s 超时；daemon 零连接到达。
- 根因：manifest 缺 INTERNET 权限（debug/release merged 均缺，已复核非增量问题）；且 targetSdk 35 默认禁明文，ws:// 必抛 CleartextNotPermitted。10.0.2.2 ping 通，env 排除。
- **明文策略已裁定（不要改判）**：debug 与 release 均 usesCleartextTraffic=true，注释引需求 007/011（ws:// 是出厂传输；tailnet=WireGuard 加密；LAN 明文属用户自网；TLS 是后续版本议题）。
- 共享编译单元纪律：e2e/fix-bridge 席位在途（不碰 :app），照常落盘自检编译。

## 2. 需求基（指针）
1. requirement-base/entries/007-联网模型-tsnet与扫码.md、011-技术路线裁定.md（明文裁定依据）
2. requirement-base/entries/013-测试体系与回归门禁.md（跨层缺陷为何单测漏掉——写进你的交件 notes）

## 3. 经验基
- 注释红线、净化前缀照旧；交件前全量门自查；robolectric 若引入注意 gradle 依赖实测存在（camera-bom 幻觉版本前车之鉴）。

## 4. 沉淀区（唯一允许你追加写入的区域）
