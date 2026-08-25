# successor6 谱系收口结果

已仅更新 `.team/ledgers/src/baseline-bundle-successor6-v1.py` 的 immutable provenance，并重新编译覆盖 `.team/ledgers/baseline-bundle-successor6-v1.json`。九格十边、required/mechanical、四态、1.10、真机 gate 与迁移前置均未改变；旧 final 编译快照和本次候选在把 provenance 归一为同一占位符后 byte-identical。

## 固定谱系

- 当前 HEAD 与新 pin 均为 `548572dfd7d8ee2e3f602a274268e8bd881ef8b2`，祖先检查 exit0。
- 九个 task 的 `resources.provenance.revision` 均精确为该完整 SHA；源与 compiled ledger 中旧 `fdf7f64970351d51e616491850e2c49d03d24b22` 已机械证明 absent。
- successor6 final 的 5 个 structure/test/probe/verify/final wrapper、4 个 bootstrap impl/deep/projection 文件、3 个 bootstrap/control fixture、5 份 successor6 taskbook 共 17 路径均经 `git cat-file -e 548572dfd:<path>` exit0。
- compiled ledger 的 14 个 unique ScriptRef 均可由同一 commit 取回，无缺失。

## Fresh 验证

- `final2-compile-schema-byte.log`：ledgerdsl 0.1.1 compile/recompile exit0，byte cmp exit0，jsonschema PASS，DSL preflight PASS，Python byte compile exit0。
- `final2-preflight-dry-run.log`：`ledger-run --preflight` exit0，`ledger-run --dry-run` exit0；revision 1，首 frontier 仅 `t.baseline-bundle.repro`。
- `final2-provenance-catfile-wt.log`：全部 task pin 精确，17 个 final/bootstrap 路径与 14 个 ScriptRef 均从 `548572dfd` 可取回；`wt-maple-core`、`wt-indigo-tests`、`wt-falcon-review` 的磁盘与 git worktree metadata 均 absent；lease/PID absent。
- `final2-semantic-snapshot.log`：旧 fdf7 编译快照与新 5485 编译账本的 provenance-normalized SHA-256 同为 `9bf2389d8c90b370cee6b4de9105977ef0c6e53b102f8f9924cab424bca6603f`，证明本轮未改任务图或判据语义。

未运行 `ledger-run --drive/--once`，未创建 worktree，未改任务书/判据/App/server，未提交或迁移旧账本。

verdict: pass
