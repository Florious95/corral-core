# successor9 SDK-root selector bootstrap 结果

## 结论

bootstrap 已闭合 diagnosis 的 root-selection 固定点：selector 不按来源优先级抢占，而是汇合 env/root-local/sdkmanager-derived 候选，canonical + inode 去重，并对固定 image metadata、同 root adb/emulator/avdmanager 与 `sdkmanager --sdk_root` exact package 独立复核；只有唯一 valid root 才原子生成目标单行 0600、未跟踪 `app/local.properties`。source-local 缺 package.xml 而 sdkmanager root 完整的诊断形状已机械通过。

目标策略四态已冻结：tracked、symlink/non-regular、未知/重复键或 target 转义为 exit 1；零/多 valid root、工具/读写/timeout 不足为 exit 2；无效 source 不覆盖唯一 valid root。所有候选值、sdk.dir 与路径都不进入 selector 或 regression 输出。

未来 apparatus 入口先 strict envcheck，再 selector，再把值只作为环境注入原 owned-emulator；原命令会再次 strict envcheck，保留唯一 PID+serial、有限清理、非 owned 不 kill。successor8 三 consume 与 apparatus→verify→user-gate→migrate→measure→final 只在任务书冻结，本轮没有生成或启动 final successor9 ledger。

## 产物

- `.team/ledgers/acceptance/baseline-bundle-successor9-sdk-selector.py`
- `.team/ledgers/acceptance/baseline-bundle-successor9-sdk-selector.sh`
- `.team/ledgers/acceptance/baseline-bundle-successor9-sdk-regression.sh`
- `.team/ledgers/acceptance/baseline-bundle-successor9-owned-emulator.sh`
- `.team/nodes/spec-sol/baseline-bundle-successor9/任务书.md`
- `.team/nodes/spec-sol/baseline-bundle-successor9/bootstrap-syntax.log`
- `.team/nodes/spec-sol/baseline-bundle-successor9/bootstrap-regression.log`
- `.team/nodes/spec-sol/baseline-bundle-successor9/bootstrap-structure.log`

## Fresh 机械结果

- `sh -n`：3 个 POSIX sh 脚本通过。
- `shellcheck -s sh`：3 个脚本通过，无输出。
- Python byte compile：selector helper 通过，cache 仅在本格 tmp。
- regression：同 root/inode、错误 source root、双 valid 歧义、sdk.dir 转义、合法两行 target 收敛、未知键、tracked/symlink/missing target、sdkmanager 错 package、值零泄露全部通过。
- bootstrap structure：strict envcheck → selector → owned 顺序、production test-variable fail-closed、successor8 三 consume/完整后链冻结、真实 target 只读结构（regular、non-symlink、untracked、comment+sdk.dir 两行）与 final ledger absent 全部通过。
- 未运行 production selector，未改真实 `app/local.properties`；未启动 adb/emulator/qemu/账本；未改 App/server；未 commit。

verdict: pass
