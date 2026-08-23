# scan-filter 独立终审

## 审查范围

审查对象是 wt-scan-impl 的：

- `server/internal/discovery/scan.go`
- `server/internal/discovery/scan_filter_test.go`
- `server/internal/discovery/scan_test.go`
- `server/internal/discovery/doc.go`
- `.team/nodes/scan-filter-impl/IMPL.md`

本席未改实现、测试、旧判据或生产面；测试只使用仓内 node-local fixture、UNIX
listener 和 PATH 前置 spy，不启动真实 tmux、用户会话或生产 daemon。

## 机械门重跑

```text
cd server && go test -count=1 ./internal/discovery
exit 0
ok  github.com/agentmirror/agentmirror/internal/discovery
```

```text
sh .team/ledgers/acceptance/scan-filter.sh
exit 0
```

聚焦测试实际运行并输出固定证据：

```text
SCAN_FILTER_EVIDENCE default_list_panes=1 tmux_env_list_panes=1 ta_list_panes=0 isolated_list_panes=0 unknown_list_panes=0 other_uid_list_panes=0 user_agents_found=2 spy_argv_recorded=true
SCAN_FILTER_CLASSIFICATION_EVIDENCE ta=skip isolated=skip unknown=skip other_uid=skip path_operand=true classification_operand=true
PASS scan-filter: user discovery preserved; forbidden list-panes calls are zero
```

测试后 `.team/nodes/scan-filter-impl/tmp/` 无残留；`git diff --check` 通过。

架构维基命令也重跑了，但返回 `exit 1`，原因是既有跨 app/server 的 T3-1/T3-2/
T3-3/T3-4 违规（与本次 scan-filter 改动无关，且 IMPL.md 已如实记录）；本席
不把该既有门包装成通过，也不改它来凑绿。

## 独立破坏齿

选取离禁止外部调用最近的齿：临时将 `classifySocket` 对 basename `ta-*` 的
结果改为 allow。变异通过 Go `-overlay` 指向 verify 目录临时副本执行，没有
修改 wt-scan-impl 中的实现文件。

```text
go test -count=1 -overlay <verify-temp>/overlay.json \
  -run '^TestDiscoverScanFilterCommandBoundary$' -v ./internal/discovery
exit 1
scan_filter_test.go:153: ta list-panes calls = 1, want 0
```

该变异的失败具体命中禁止 socket 的 spy argv：

```text
tmux -S tmux-501/ta-private list-panes -a -F <paneFormat>
```

失败发生在 `ta` 调用计数断言；此前两个允许 socket 的 Model/Agent 阳性断言
已通过，故不是“关闭全部扫描”的假红。随后删除 overlay 临时副本，重新运行
`sh .team/ledgers/acceptance/scan-filter.sh` 得到 `exit 0`，固定证据恢复为
`ta_list_panes=0`，证明“改坏必红、还原必绿、用户阳性臂仍在”。

## 结论

实现满足本任务的独立行为边界：目录级其它 uid 前置跳过，候选级 ta/隔离/
unknown 在 `probeSocket`/`scanServer` 前 fail-closed，当前 uid `default` 与
`TMUX` 第一段自定义 socket 均被发现，分类日志具备 path、classification、
action 操作数。选定破坏齿实际返回 `exit 1`，还原后机械门返回 `exit 0`。

未验证项仅为既有 archwiki T3 门的全仓缺口；本任务未要求也未授权扩大修改范围。

verdict: pass
