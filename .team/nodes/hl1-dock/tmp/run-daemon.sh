#!/bin/sh
echo "TMUX_TMPDIR=${TMUX_TMPDIR-}" > /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/hl1-dock/tmp/daemon.env.txt
echo "PWD=$PWD" >> /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/hl1-dock/tmp/daemon.env.txt
exec /Volumes/nvme/Projects/远程Agent安卓/e2e/bin/agentmirrord "$@"
