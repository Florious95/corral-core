# successor7 retained WT 继续命令

预检已通过。以下命令仅供 leader 在确认现场未漂移后执行；本席未执行。命令不
读取或复制 APK 内容，不执行 reset/clean。

    set -eu
    cd /Volumes/nvme/Projects/远程Agent安卓/.worktrees/wt-maple-core
    git merge --ff-only da46a6b2b538faf7954fa4f9af7e8c09a194f45e
    sh .team/ledgers/acceptance/baseline-bundle-successor7-continuity.sh

若现场 dirty/untracked 集合、HEAD 或 registered worktree 状态发生变化，应停止并
重新做本预检，不得将本文件的旧 pass 当作当前授权。
