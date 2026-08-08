# 修订记录（结论被推翻不回改条目，在此登记）

| 编号 | 日期 | 被修订认知 | 修正后口径 | 出处 |
|---|---|---|---|---|
| R-001 | 2026-08-09 | leader 曾把 cwd 与 session 名当作同层的"二选一分组键"；且曾假设 MVP 不做状态解析 | cwd 与 session 是两级结构（见 002）；产品是生产级、状态解析必做（见 008） | 用户第二/三轮纠正 |
| R-002 | 2026-08-09 | 任务书与 011 曾假设 ConnectBot 终端核为 Apache-2.0，改造可行 | w-term-core 核验实为 JTA 血统 GPLv2/LGPL-2.1，改造路线出局；终端内核裁定=自研最小 VT 引擎（纯 Kotlin/JVM），裁定文档 docs/decisions/term-core.md | w-term-core 核验 + leader 裁定 |
