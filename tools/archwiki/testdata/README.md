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

运行方式见 `tools/archwiki/test_check.py`。
