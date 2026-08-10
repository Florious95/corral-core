# T3 判据报告（自动生成）

> ⚠️ **生成物，勿手改。** 由 `tools/archwiki/build_wiki.py --check --t3-report` 从源码现算生成，重跑无 diff（幂等）。人工改动会被覆盖。

扫描 **18** 个包（Go 9 + Kotlin 9）。

## T3 扫描覆盖（阳性对照：扫描量必须 > 0）

| 项 | 数量 |
|---|---|
| 导出符号索引（Go+Kotlin） | 206 |
| Go CLI flag 索引 | 11 |
| 仓库文件基名索引 | 1714 |
| T3-2 扫描的 Go doc 行 | 2179 |
| T3-2 扫描的 Kotlin KDoc 行 | 3031 |

## T3-1 符号级 doc 覆盖

导出符号缺紧邻 doc/KDoc，共 **1** 条：

| 包 | 语言 | 文件 | 行 | 符号 | 原因 |
|---|---|---|---|---|---|
| dev.agentmirror.app.tsnet | kotlin | app/app/src/main/java/dev/agentmirror/app/tsnet/TsnetInterfaceCodec.kt | 46 | `TsnetInterfaceCodec` | 顶层 public 声明缺紧邻 KDoc |

## T3-2 引用真实性

扫描**全部注释形态**（KDoc/doc 注释 + 函数体内普通 `//` 注释 + `/* */` 块注释 + 行尾注释，Go 与 Kotlin 两侧对齐，含 Kotlin 侧 `--flag` 判定）。

> **诚实边界**：T3-2 只验证**引用形状可判者**——反引号包裹的大写符号、含 `/` 且带已知扩展名的路径、`--flag`。**不验证语义事实**：自然语言断言（如"设置里有重配按钮"）没有可判形状，静态判据解析不出"某组件里有没有某按钮"，这类行为性断言由用例覆盖（如 PairingUxTest 的重配入口可达性断言），不在此列。注释里指认代码实体时务必写成反引号符号或真实路径，让引用变成判据可验的形状。

无违规：注释引用的符号名/仓库文件路径/CLI flag 均真实存在。
