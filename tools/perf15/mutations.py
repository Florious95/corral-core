"""Six bounded PERF15 mutations on a disposable exact candidate checkout."""
from pathlib import Path
import sys

mode = sys.argv[1]
root = Path(sys.argv[2])

def replace(path, before, after):
    p = root/path
    source = p.read_text()
    assert source.count(before) == 1, (path, 'mutation target not unique')
    p.write_text(source.replace(before, after))

if mode == 'sync-list':
    replace('internal/api/ws_handler.go', '\tc.s.scans.list(c, l.ReqID)', '\t_ = c.s.scans.wait(c.ctx, false)\n\tc.s.scans.list(c, l.ReqID)')
elif mode == 'sync-level2':
    replace('internal/api/level2.go', '\tc.s.scans.level2(c, epoch)', '\t_ = c.s.scans.wait(c.ctx, false)\n\tc.s.scans.level2(c, epoch)')
elif mode == 'parallel-scan':
    replace('internal/api/scan_coordinator.go', '\t\tq.jobs <- g // capacity 1, only when the preceding worker result was consumed', '\t\tq.jobs <- g // capacity 1, only when the preceding worker result was consumed\n\t\tgo q.scan(q.s.loopCtx, g) // MUTATION: independent concurrent scan')
elif mode == 'split-publication':
    replace('internal/api/scan_coordinator.go', '\ts.snapMu.Lock()\n\tif result.err == nil {', '\tif result.err == nil {\n\t\ts.snapMu.Lock(); s.catalog = result.catalog; s.snapMu.Unlock()\n\t\ttime.Sleep(20*time.Millisecond) // MUTATION: visible catalog before snapshot\n\t}\n\ts.snapMu.Lock()\n\tif result.err == nil {')
elif mode == 'writer-epoch':
    replace('internal/api/ws_conn.go', '\tif m.level2Epoch != 0 && m.level2Epoch != c.currentLevel2Epoch() {\n\t\treturn nil\n\t}', '')
elif mode == 'completion-epoch':
    replace('internal/api/level2.go', '\tif !c.level2On || c.level2Epoch != epoch {', '\tif !c.level2On {')
else:
    raise AssertionError('unknown mutation')
