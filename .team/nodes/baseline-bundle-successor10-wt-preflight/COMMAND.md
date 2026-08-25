# successor10 retained WT 受控继续命令

仅在 VERDICT pass 且由受管流程执行；本次不执行 merge 或以下命令。

1. retained WT 只允许 fast-forward 到 successor10 bootstrap：

   ```sh
   git -C /Volumes/nvme/Projects/远程Agent安卓/.worktrees/wt-maple-core merge --ff-only 9ea73dff8
   ```

2. 运行 successor10 fake AVD regression（不调用真实 avdmanager、adb、emulator 或
   qemu）：

   ```sh
   cd /Volumes/nvme/Projects/远程Agent安卓/.worktrees/wt-maple-core
   sh .team/ledgers/acceptance/baseline-bundle-successor10-avd-regression.sh
   ```

3. 运行 successor9 SDK selector regression：

   ```sh
   cd /Volumes/nvme/Projects/远程Agent安卓/.worktrees/wt-maple-core
   sh .team/ledgers/acceptance/baseline-bundle-successor9-sdk-regression.sh
   ```

4. 运行 retained successor6 四格 continuity：

   ```sh
   cd /Volumes/nvme/Projects/远程Agent安卓/.worktrees/wt-maple-core
   sh .team/ledgers/acceptance/baseline-bundle-successor7-continuity.sh
   ```

若任一命令返回非零或 exit 2，保留现场并按四态处理；不得启动 production wrapper，
不得将缺失 APPARATUS.json 补写为通过。

verdict: pass
