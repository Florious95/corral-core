# T-A 架构与静态检查收据

## 影响闭包

- 命令：`python3 tools/basegen.py session-ui --pkgs dev.agentmirror.app`
- 结果：`cards=2 fwd=5 rev=1 refs=[] field=no librarian=no`
- 基底产物：`tmp-basegen-session-ui.md`，SHA256 `83b691144975c2a768ef4a5612bdd41003549eaa2a197279c4e3d207a948e049`

## ArchWiki

- 精确 C73 基线根包命令：`python3 tools/archwiki/build_wiki.py --root tmp/arch-baseline --check --go-source --strict-t3 --pkg dev.agentmirror.app`，exit `1`。
  T1-1/T1-2/T3-1/T3-2/T3-3 通过；T3-4 的 5 条未声明 `@consumes` 是基线已有的 `perf`、`tsnet`、`ui.components`、`ui.model`、`ui.screens` 导入漂移，本席未扩大写界修复。
- 本次修改直接涉及的 UI/model 包：
  - `python3 tools/archwiki/build_wiki.py --check --go-source --strict-t3 --pkg dev.agentmirror.app.ui.model`，exit `0`。
  - `python3 tools/archwiki/build_wiki.py --check --go-source --strict-t3 --pkg dev.agentmirror.app.ui`，exit `0`。
- `dev.agentmirror.app.ui.components` 与 `dev.agentmirror.app.workspace` 的包级检查仍有基线既有文档/声明违规；本次仅改 `SessionRow` caller，不改无关 CommonUi/L2 架构声明。
- 修改产品注释保留 `@contract` 外骨骼；`Models.kt` 的 T3-1/T3-3/T3-4 目标包判定通过。
- 原始输出：`tmp-archwiki-baseline-root.out`、`tmp-archwiki-final.out`、`tmp-archwiki-baseline-model.out`、`tmp-archwiki-baseline-ui.out`。

## 执行边界

- 本机未执行 Gradle/Go/Rust；未启动 Grok Bot（20 GiB 门未满足）。
- GitHub hosted runner 承担唯一 Gradle 执行，命令与收据见 `GREEN-REPORT.md`、`A73-RECEIPT.json`。
