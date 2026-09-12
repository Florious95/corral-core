# Frozen before candidate performance measurement

Baseline: serve 655bd4441b57563d39457954420ae3557dc28ac4, Darwin arm64 Go 1.27.1, accepted nodeprobe e5667b9e. Raw evidence: baseline-perf/*/raw.json. Same fixture sockets retained. Each scenario has 25 explicit L1 and 25 L2 requests, A fixed at 2 panes. Separate: 17 sockets/34 panes; shared: 1 socket/66 panes, including 32 non-Agent cat panes. Provider-shaped compiled terminal fixtures are synthetic workloads, not model sessions; tmux, nodeprobe, daemon, WebSocket are real and unmodified.

Baseline p50/p95/max milliseconds:

| Scenario | L1 | L2 |
|---|---|---|
| small | 225.6 / 298.2 / 839.5 | 222.1 / 245.9 / 273.1 |
| separate | 1999.7 / 3422.6 / 5329.4 | 1981.0 / 3347.7 / 3838.5 |
| shared | 117.6 / 127.9 / 232.9 | 113.0 / 123.5 / 162.9 |

Acceptance thresholds (not to be relaxed after candidate data):
- All scenarios: L1 p95 <=750ms, max <=1500ms; L2 p95 <=500ms, max <=1000ms. Separate L1/L2 p95 must improve at least 70% against baseline.
- Shared L2 p95 <= max(250ms, 2 * small L2 p95); correct A count fixed at 2, unrelated non-Agent panes excluded. This determines whether a nodeprobe cross-repo change is needed.
- New same-socket/new-socket A and new workspace: visible in L1 and L2 within 4 seconds; click to actual terminal snapshot with fixture marker <=1500ms after row visible.
- Known cold route must enter a real terminal within 1500ms while an unrelated socket is blocked. Healthy routed A L2 <=1000ms under one unrelated slow socket.
- Slow target/worker saturation: explicit error by 13s from admission, never authoritative empty. Epoch/delete/coalescing race tests must pass.
- Record CPU/RSS and processing counts without a fabricated baseline cap; CPU/RSS/child counts, phases and App UI are separate evidence. Baseline script records daemon CPU/RSS only; child counts and internal sampling phase timing still require supplementary observation. Initial baseline compilation may have overlapped early small samples; do not relabel this as an identical-load causal isolation proof. Final same-environment comparison must preserve this limitation or add a controlled supplemental baseline.
- App: actual refresh/switch/favorite/reconnect/recreation/empty-table behavior on isolated emulator5596 and ADB5068, exact PR98 APK. UI latency is measured separately from WS latency; cannot treat a list response as terminal acceptance.
