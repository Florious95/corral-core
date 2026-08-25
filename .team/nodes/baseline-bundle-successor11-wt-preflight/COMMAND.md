# successor11 retained WT 受控继续命令

仅在 VERDICT pass 后由受管流程按顺序执行；本次没有执行 merge 或以下命令。

1. retained WT 只允许 fast-forward 到 successor11 bootstrap：

   ```sh
   git -C /Volumes/nvme/Projects/远程Agent安卓/.worktrees/wt-maple-core merge --ff-only ebd0dc5c2
   ```

2. 运行 successor11 verify regression（纯文件夹具，不调用 live adb/emulator/qemu）：

   ```sh
   cd /Volumes/nvme/Projects/远程Agent安卓/.worktrees/wt-maple-core
   sh .team/ledgers/acceptance/baseline-bundle-successor11-verify-regression.sh
   ```

3. 运行 retained successor6 四格 continuity：

   ```sh
   cd /Volumes/nvme/Projects/远程Agent安卓/.worktrees/wt-maple-core
   sh .team/ledgers/acceptance/baseline-bundle-successor7-continuity.sh
   ```

任一命令非零或 exit 2 都须保留现场并按四态处理；不得启动 production verify/apparatus，
不得读取 APK 字节，不得把旧 successor10 `unjudgeable` verdict 改写成新证据。

verdict: pass
