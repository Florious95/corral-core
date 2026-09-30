# API 用户场景与性能基线报告

结论：**PASS（hard-gated）**。全部用户动作由 `/ws` 与 `/upload` 程序接口驱动；功能错误、性能超门限、字节丢失、隔离越界或清理残留都会直接失败。

## 场景结果

| 场景 | 结果 | 用户可见断言 |
|---|---|---|
| `pairing_rejection_reason` | PASS | invalid token rejected；reason is non-empty；credential not echoed |
| `pair_and_two_level_listing` | PASS | dial+auth+auth_ack succeeds；workspace/cwd first level present；session/ref second level present；state and aggregate fields are valid |
| `state_field_transition` | PASS | initial state idle；API input changes visible pane state；list_delta carries blocked；workspace aggregate follows blocked |
| `subscribe_snapshot_and_incremental_stream` | PASS | ten non-empty snapshot replies；matching session ref；subscription remains live for deltas |
| `input_ack_and_output_round_trip` | PASS | twenty req_id-correlated input_ack replies；twenty hidden execution markers returned in delta；p50/p95 recorded |
| `special_keys` | PASS | seven independent one-key input frames accepted；each distinct req_id has a successful input_ack；terminal effects are not claimed |
| `multiline_paste` | PASS | one input frame carries embedded newline；one input_ack received；both executed-output markers returned |
| `resize` | PASS | post-resize snapshot is the fact receipt；fresh listing reports 100x40 |
| `large_output_throughput` | PASS | one MiB exact payload received；begin/end markers exclude tty echo；zero lost bytes；throughput recorded |
| `scrollback_pagination` | PASS | ten distinct contiguous req_id-correlated pages；range headers prove no gaps or overlaps；each page has 100 lines and a non-empty payload |
| `upload_authentication` | PASS | missing bearer token rejected with 401 and reason；wrong bearer token rejected with 401 and reason；valid pairing token accepted；credentials not echoed |
| `image_upload_and_path_injection` | PASS | five one-MiB multipart uploads return 200；paths stay inside isolated upload dir；on-disk size exact；returned path injected and visible via WS |
| `disconnect_reconnect_resubscribe` | PASS | ten new WS auth handshakes；same ref resubscribed；current visible marker replayed in every snapshot |
| `failure_reason` | PASS | unknown ref returns session_not_found；human-readable reason is non-empty |

## 性能基线

| 指标 | 样本数 | p50 | p95 | max |
|---|---:|---:|---:|---:|
| 配对（WS dial+auth）到首个 listing | 10 | 0.601 ms | 0.913 ms | 0.962 ms |
| subscribe 到首个 snapshot | 10 | 129.510 ms | 135.906 ms | 136.255 ms |
| input 到执行输出 delta | 20 | 41.327 ms | 49.922 ms | 54.218 ms |
| scrollback 一页 | 10 | 36.868 ms | 39.181 ms | 39.349 ms |
| 1 MiB 上传 | 5 | 1.874 ms | 3.217 ms | 3.510 ms |
| 断线后 auth+续订到 snapshot | 10 | 116.528 ms | 124.088 ms | 126.530 ms |

大输出：期望/实收 `1048576` / `1048576` bytes，丢失 `0`，吞吐 `30160853.417` bytes/s。
上传耗时：p50 `1.874` ms，p95 `3.217` ms。

## 静默经济三态

| 状态 | 窗口 | 平均 CPU | RSS p50 / p95 / max | tmux / ps 派生 | daemon 后代峰值 |
|---|---:|---:|---:|---:|---:|
| `zero_connection` | 600.009s | 0.000% | 18928 / 18928 / 18928 KiB | 0 / 0 | 0 |
| `connected_zero_subscription` | 600.041s | 1.237% | 37424 / 37568 / 37600 KiB | 1935 / 774 | 2 |
| `connected_single_subscription` | 600.044s | 1.222% | 38304 / 39152 / 39152 KiB | 1937 / 774 | 3 |

## 硬数值门限

| 指标 | 实测值 | 比较 | 门限 | 结果 |
|---|---:|:---:|---:|:---:|
| `pair_to_first_listing.p95` | 0.9129499999999999 ms | <= | 5 ms | PASS |
| `subscribe_first_frame.p95` | 135.9058 ms | <= | 400 ms | PASS |
| `output_end_to_end.p95` | 49.92209999999999 ms | <= | 150 ms | PASS |
| `scrollback_page.p95` | 39.1807 ms | <= | 150 ms | PASS |
| `reconnect_recovery.p95` | 124.0883 ms | <= | 400 ms | PASS |
| `zero_connection.cpu.mean_percent` | 0.0 percent_of_one_core | <= | 0.5 percent_of_one_core | PASS |
| `zero_connection.external_process_spawns.total` | 0 processes | == | 0 processes | PASS |
| `zero_connection.daemon_descendants.peak` | 0 processes | == | 0 processes | PASS |
| `connected_zero_subscription.cpu.mean_percent` | 1.2365815953495118 percent_of_one_core | <= | 5 percent_of_one_core | PASS |
| `connected_single_subscription.cpu.mean_percent` | 1.2215761712004134 percent_of_one_core | <= | 5 percent_of_one_core | PASS |
| `connected_single_subscription.daemon_descendants.peak` | 3 processes | <= | 4 processes | PASS |
| `zero_connection.daemon_rss.end_minus_start` | -4320 KiB | <= | 20480 KiB | PASS |
| `connected_zero_subscription.daemon_rss.end_minus_start` | 3984 KiB | <= | 20480 KiB | PASS |
| `connected_single_subscription.daemon_rss.end_minus_start` | 1568 KiB | <= | 20480 KiB | PASS |

## 隔离与清理

- 高端口监听已消失：`True`；tmux socket 已消失：`True`。
- daemon/client/tmux PID 均已退出，runtime/build 临时树均已删除：`True` / `True`。
- 非自有 tmux 目标：`0`；生产 `:9900` 未连接；真实/Team Agent tmux socket 均未扫描或连接，且未 attach/signal。资源采样仅以只读方式在内存中读取全机 PID/PPID 快照，用于筛选隔离 daemon 后代；原表不落盘。
- daemon 与 client 均在 `env -i` 下运行；配对 token 仅经环境变量进入内存，daemon 含 QR 的 stdout 直送 `/dev/null`；未继承 `TS_AUTHKEY`。

## 偏差与未验证清单

- 偏差：Current product has no standalone HTTP pairing/QR endpoint; pairing is tested through the shipped WS auth/auth_ack program interface.
- 偏差：Economy samples use sequential states on one isolated daemon with 10-minute windows.
- 偏差：Wrapper-shaped local shells stand in for agent CLIs; no production daemon, real user tmux, real phone, camera, notification surface, or lock screen is touched.
- 未验证：Physical QR scan and multi-interface reachability
- 未验证：Android workspace/session UI and terminal visual parity
- 未验证：Blocked notification delivery and global notification switch
- 未验证：App process kill restore and lock-screen reconnect
- 未验证：Settings-page single-profile re-pair flow
- 未验证：Chinese-only UI and accessibility content descriptions
- 未验证：Real camera capture; only the host upload/path pipeline is covered
- 未验证：Real Claude Code/Codex multiline bracketed-paste behavior
