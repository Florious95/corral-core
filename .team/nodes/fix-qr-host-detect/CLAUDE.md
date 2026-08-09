# 知识基底 · fix-qr-host-detect（真机验收缺陷 A 修复）

## 0. 任务（taskbook.yaml#fix-qr-host-detect）
- 目标/验收/写范围见任务书。红测先行：fake 接口表（含 TUN 混入）驱动排序断言，修前红。
- 红线：QR JSON 契约不变（仍单 url）；token 纪律不变。

## 1. 现场基（真机实测，2026-08-09）
- 本机接口实况：en 系有 10.20.55.20 与 192.168.31.116（真实 LAN，手机可达）；另有 169.254.27.197（link-local）与 **198.18.0.1（代理 fake-IP TUN）**。现 PrimaryHost 选了 198.18.0.1 → 真机扫码后不可达。
- 现实排序目标：192.168.31.116 / 10.20.55.20 应排前二（RFC1918 实网卡）；198.18.0.0/15（RFC2544）、169.254/16、utun*/awdl*/llw*/bridge*/tap/tun 一律排除出候选。
- 多候选并存时：QR 用第一名；`PrintOnboarding` 指引列出全部候选的完整 ws URL（用户手填换选）；`-host` flag（进 config，flag+env）显式覆盖一切自动探测。
- 相关代码：server/internal/pairing/probe.go（DetectAddresses/pickPrimary/classifyIP）、cmd 接线、config。probe_test 已有排序契约测试形状可扩展。
- 注意 pairing-security 沉淀（.team/nodes/pairing-security/CLAUDE.md §5）：pickPrimary 只认输入顺序，排序契约在 DetectAddresses——你的修改主战场在后者。

## 2. 需求基（指针）
1. requirement-base/entries/007-联网模型-tsnet与扫码.md（扫码即连的体验承诺——被本缺陷破坏）
2. requirement-base/entries/003-对话体验四标准.md

## 3. 经验基
- 最小修复；注释红线；净化前缀；交件前全量门自查。若动 config 记得同步 cmd 帮助文本。

## 4. 沉淀区（唯一允许你追加写入的区域）
