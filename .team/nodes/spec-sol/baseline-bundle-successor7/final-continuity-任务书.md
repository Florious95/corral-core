# successor7 continuity command 任务书

目标：在 retained `wt-maple-core` 上只读复核 successor6 四格与 baseline bundle 未被空洗，为 apparatus 建立前置；不重放旧格、不起设备。

输入：`3528c2ad5`、bootstrap `da46a6b2b`、连续性 commit `0df3562b7`、successor6 compiled ledger、retained manifest 与 primary/backup archive。

执行/交付：由 command executor 精确运行 `/bin/sh .team/ledgers/acceptance/baseline-bundle-successor7-continuity.sh`，cwd=`wt-maple-core`；不写文件，命令 rc 就是交付。

验收：四格 state/required succeeded、verify planned、registered WT/HEAD ancestry、manifest 与双归档摘要/sealed/regular/独立 inode 全绿为0；有效身份或摘要矛盾为1；Git object/WT/资产缺失为2。不得 checkout/reset/restore/commit，不启动 adb/qemu/emulator。
