# successor11 retained WT 只读快进预检

## 范围

目标 retained worktree 为 `wt-maple-core`，冻结 bootstrap commit 为 `ebd0dc5c2`。
本次只读；未 merge、reset、clean，未启动设备或账本，未读取或复制 APK 字节。

## Git 预检

- worktree registered：`true`；目标目录存在，HEAD 可解析。
- bootstrap commit 可解析：`true`。
- 当前 HEAD 是 `ebd0dc5c2` 的祖先：`true`。
- ff-only 可行：`true`。
- 当前 dirty/untracked 条目：`359`；bootstrap 更新路径：`30`。
- dirty/untracked 与更新路径的层级交集：`false`；路径零交集：`true`。

所以可以安全给出 fast-forward 命令；本次没有执行 fast-forward。

## 历史资产保持

- successor10 APPARATUS 与 AVD-CREATE 均存在、非空、production、regular、0600，且
  success/created 元数据成立。
- successor6 bundle manifest 存在；primary/backup archive 均为 regular、非 symlink、
  sealed/不可写且 inode 独立。只检查元数据和 inode，没有读取归档字节。
- successor7 permanent fixture 的 manifest 与 contract 均存在且非空。
- successor9 selector Python、shell wrapper、regression 均存在且非空；successor10
  AVD helper 与 fake regression 也均存在且非空。
- 旧 successor10 verify 资产的末行仍为 `verdict: unjudgeable`；没有把旧不可判
  历史改写为通过。

资产身份与隔离边界均闭合，故预检 verdict 为 pass。fake regression 只使用 node-local
夹具；continuity 命令仅供后续受管执行，本次未运行任何命令。

verdict: pass
