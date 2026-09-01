# Go one-shot attribution and repair

The frozen base and status focused runs were each executed once in isolated worktrees. Base `4605951e` and status `22bc9e69` both passed; all five frozen coordinates share `api_tmux_test.go` blob `6785b9ca4db2e6e6928c0a3da81e646c9421002c`. The initial compose full gate had passed while independent acceptance later failed at `read control (draining)`, proving order-dependent synchronization rather than an owning-coordinate behavior split.

Root cause: phase 2 called `waitForMirror`, which destructively skipped an interleaved `InputAck`, then attempted a second read. The first repair reused `waitAckAndMirror`; its preserved red at candidate `50e3798ce…` exposed the deeper queued predecessor case: the helper accepted phase-1 `ReqID=1` while waiting for `ReqID=2`.

Final minimal repair is test-only under `server/internal/api/**`:

- `waitAckAndMirror` marks an ack seen only when `ack.ReqID == reqID`;
- `TestWaitAckAndMirrorIgnoresEarlierRequestAck` sends ack 1 plus the target mirror, proves the waiter has not completed, then sends ack 2 and proves completion;
- no production API/tmux code, timeout, no-Enter assertion, or error assertion changed.

The sole authorized superseding four-package gate ran at `d9a55311982b414680a5d3b1177cdd6813c3cb78` and passed all four packages with `-count=1`. Final code candidate `ac84f22e…` has the identical server tree `54d28cf738b6b0dd9650b162f2d926b44980ec6b`; its only later change removes two Android-test imports. Original red logs remain preserved.
