# 知识基底 · arch-wiki（系统编译产物）

## 0. 任务（taskbook.yaml#arch-wiki）
- 目标：架构维基自动化：`tools/archwiki/build_wiki.py` 从 Go/Kotlin 源码**现算**架构图与判据。
  - 生成：`docs/wiki/` 下每包一张架构卡（职责=doc 注释首句、导出面、依赖边）+ 总依赖图（mermaid）。
  - 判据（--check 模式，违反 exit 非 0）：T1-1 internal 包环依赖；T1-2 包缺 doc 注释（Go 的 doc.go/包注释、Kotlin 的模块 KDoc）——这条判据就是"代码必须注释"红线的门禁化。
- 验收（exit 0 = 过）：`bash -lc 'python3 /Volumes/nvme/Projects/远程Agent安卓/tools/archwiki/build_wiki.py --check'`
- 写范围：`tools/archwiki/`、`docs/wiki/`。红线：**禁止任何需人工维护的架构文档**——wiki 全部现算可重生成，`docs/wiki/` 头部标注"生成物勿手改"；生成必须幂等（重跑无 diff）。

## 1. 架构基
- 输入面：`server/`（Go：解析 import 与包注释，`go list -json ./...` 最稳）+ `app/`（Kotlin：正则/轻解析 import 与 KDoc 即可，不必上完整解析器）。
- 输出面：docs/wiki/README.md（总图+判据结果）+ 每包一节。生成器自身也要有注释与 --help。
- 判据准入纪律：每条判据必须能写出红测（自带 fixture 测试：造一个环依赖假包 → --check 必须红）。写不出红测的判据不准入。
- 设计给未来留位（只留接口不实现）：外骨骼 YAML 围栏标注解析、更多判据（零消费者/孤儿子图）。

## 2. 现场基
- python3 可用（macOS 系统级）。server/ 现有 8 包各带 doc.go；app/ 27 文件 KDoc 达标——阳性对照现成。
- server/tsnetd 已引入 tailscale.com 大依赖：`go list` 只扫本 module 包（`./...`），别把外部依赖画进图。
- 注意：其他席位正在并行改 server/internal/*，你只读它们，生成物以运行时刻的代码为准（现算哲学，本来就该如此）。

## 3. 需求基（指针）
1. requirement-base/entries/010-最终验收与运行方式.md（"注释自动衍生维基图+腐败识别"的原始要求）
2. requirement-base/entries/008-生产级定位与开源许可.md

## 4. 经验基
- 判据分级铁律：只有"能给出可处置路径"的判据才进 --check 红线；只报不红的告警是累赘。
- 阳性对照必配：--check 通过时必须同时打印"扫了 N 包 M 边"，N=0 视为失败（空扫描≠健康）。
- 验证不得自证：环检测的测试 fixture 手工构造，不用生成器自己产出。
- 测试净化前缀照旧。

## 5. 沉淀区（唯一允许你追加写入的区域）
