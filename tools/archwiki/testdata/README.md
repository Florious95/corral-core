# testdata — 判据红测 fixture

本目录为 `build_wiki.py --check` 各判据的红测 fixture（准入纪律：**写不出红测的
判据不准入**）。全部 fixture **手工构造**，绝不用生成器自身产出（验证不得自证）。

| fixture | 判据 | 期望 |
|---|---|---|
| `cycle/` | T1-1 internal 包环依赖 | `--check` 必须红（exit 1） |
| `missingdoc/` | T1-2 包缺 doc 注释 | `--check` 必须红（exit 1） |
| `empty/` | 空扫描红线 | 空扫描视为失败（exit 2） |
| `missingdoc-symbol/` | T3-1 符号级 doc 覆盖 | `--check --strict-t3` 必须红（Go+Kotlin 各一漏） |
| `documented-symbol/` | T3-1 阳性对照 | `--check --strict-t3` 必须绿 |
| `lying-ref/` | T3-2 引用真实性 | `--check --strict-t3` 必须红（谎称符号/路径/flag，**覆盖全部注释形态**：KDoc + 函数体内普通 // + KDoc 内 flag） |
| `truthful-ref/` | T3-2 阳性对照 | `--check --strict-t3` 必须绿（同形状全真实） |
| `pkg-filter/` | `--pkg` 单包硬判 | 指向 dirty 包红、clean 包绿；报告模式不改退出码 |
| `contract-incomplete/` | T3-3 契约标签完备（arch-criteria-t3-contract） | `--check --strict-t3` 必须红（Go 缺 @err/@inv、Kotlin 缺 @post）——**那个 0 自证用的必红 fixture**，与扫真仓库同一代码路径 |
| `contract-multi/` | T3-3 同文件多 @contract 回归红测（返工 #1） | `--check --strict-t3` 必须红（Go 的 Half 缺 @err/@inv、Kotlin 的 HalfClient 缺 @post——前一个完整 @contract 不得掩盖后一个残缺） |
| `contract-complete/` | T3-3 阳性对照 | `--check --strict-t3` 必须绿（四标签齐全，含显式 none） |
| `consumes-drift/` | T3-4 跨层声明一致 | `--check --strict-t3` 必须红（声明了没 import + import 了没声明，Go/Kotlin 双侧） |
| `consumes-consistent/` | T3-4 阳性对照 | `--check --strict-t3` 必须绿（@consumes 与 import 图一致） |
| `consumes-main/` | T3-4 命令包盲区（package main 特判） | 必红 `cmd/redcmd`（import 了未声明）；必绿 `cmd/agentmirrord`（声明与 import 一致，**修复前红、修复后绿**——`_declared_consumes` 必须读到命令包声明）；防串味 `mixed.go`（命令包目录混入声明别的包名的文件，其 @consumes 不得被误归属，守卫必须保住） |

运行方式见 `tools/archwiki/test_check.py`。
