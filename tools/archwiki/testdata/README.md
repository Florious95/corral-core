# testdata — 判据红测 fixture

本目录为 `build_wiki.py --check` 各判据的红测 fixture（准入纪律：**写不出红测的
判据不准入**）。全部 fixture **手工构造**，绝不用生成器自身产出（验证不得自证）。

| fixture | 判据 | 期望 |
|---|---|---|
| `cycle/` | T1-1 internal 包环依赖 | `--check` 必须红（exit 1） |
| `missingdoc/` | T1-2 包缺 doc 注释 | `--check` 必须红（exit 1） |
| `empty/` | 空扫描红线 | 空扫描视为失败（exit 2） |

运行方式见 `tools/archwiki/test_check.py`。
