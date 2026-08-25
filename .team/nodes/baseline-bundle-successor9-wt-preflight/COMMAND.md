# successor9 retained WT 受控继续命令

以下命令仅在 `VERDICT.md` 的 pass 预检成立后、由有权限的受管流程按顺序执行；本次
没有执行它们。

1. 只允许 fast-forward 目标 retained WT：

   ```sh
   git -C /Volumes/nvme/Projects/远程Agent安卓/.worktrees/wt-maple-core merge --ff-only 0fdee1072
   ```

2. 在已快进的同一 WT 运行 successor9 SDK selector regression：

   ```sh
   cd /Volumes/nvme/Projects/远程Agent安卓/.worktrees/wt-maple-core
   sh .team/ledgers/acceptance/baseline-bundle-successor9-sdk-regression.sh
   ```

3. 继续只读验证 retained successor6 四格与同 WT bundle continuity：

   ```sh
   cd /Volumes/nvme/Projects/远程Agent安卓/.worktrees/wt-maple-core
   sh .team/ledgers/acceptance/baseline-bundle-successor7-continuity.sh
   ```

这些命令不包含 reset、clean、强制覆盖或设备启动；selector regression 只使用其
node-local 临时夹具并自清理，continuity 只消费既有证据。任何非零或 exit 2 都应保留
原现场并按四态判定，不得把缺失 APPARATUS 补写为通过。

verdict: pass
