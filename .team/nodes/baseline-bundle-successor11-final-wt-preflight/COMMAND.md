# successor11 final post-commit 受控继续命令

仅在 VERDICT pass 后由受管流程按顺序执行；本次不执行 merge、ledger 或设备动作。

1. retained WT 只允许 fast-forward 到 final commit：

   ```sh
   git -C /Volumes/nvme/Projects/远程Agent安卓/.worktrees/wt-maple-core merge --ff-only 7e8c93bda
   ```

2. 使用固定 command-compatible binary 做 fixed-pair preflight/dry-run；此处及以下
   structure、consume、verify regression 均必须在主仓 root
   `/Volumes/nvme/Projects/远程Agent安卓` 执行，不能在 retained WT cwd 执行：

   ```sh
   cd /Volumes/nvme/Projects/远程Agent安卓
   /Volumes/nvme/cargo-target-w7-builder-b/debug/ledger-run --preflight --json .team/ledgers/baseline-bundle-successor11-v1.json
   /Volumes/nvme/cargo-target-w7-builder-b/debug/ledger-run --dry-run --json .team/ledgers/baseline-bundle-successor11-v1.json
   ```

3. 运行 successor11 structure gate：

   ```sh
   cd /Volumes/nvme/Projects/远程Agent安卓
   sh .team/ledgers/acceptance/baseline-bundle-successor11-structure.sh
   ```

4. 只读消费 successor10 四个 succeeded command 格（四条分别执行）：

   ```sh
   cd /Volumes/nvme/Projects/远程Agent安卓
   sh .team/ledgers/acceptance/baseline-bundle-successor11-consume.sh continuity
   sh .team/ledgers/acceptance/baseline-bundle-successor11-consume.sh apparatus-test
   sh .team/ledgers/acceptance/baseline-bundle-successor11-consume.sh apparatus-probe
   sh .team/ledgers/acceptance/baseline-bundle-successor11-consume.sh apparatus
   ```

5. 运行 successor11 verify regression（纯文件夹具）与 retained WT continuity；前者在主仓
   root，后者才在 retained WT cwd：

   ```sh
   cd /Volumes/nvme/Projects/远程Agent安卓
   sh .team/ledgers/acceptance/baseline-bundle-successor11-verify-regression.sh
   cd /Volumes/nvme/Projects/远程Agent安卓/.worktrees/wt-maple-core
   sh .team/ledgers/acceptance/baseline-bundle-successor7-continuity.sh
   ```

cwd 回归记录：此前将 fixed-pair/structure/consume 误放在 retained WT cwd，
`wrong-cwd=2`（各命令 exit 2）；改为上述主仓 root 后 `corrected=0`。
successor7-continuity 保持 retained WT cwd，已验证 `rc0`。

任一命令非零或 exit 2 都须保留现场并按四态处理；不得启动 production ledger、verify
或设备命令，不得读取 APK 字节，也不得改写旧 successor10 `unjudgeable` 历史。

verdict: pass
