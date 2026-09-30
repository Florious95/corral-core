# t.scan-filter.impl implementation

## 基线红

按任务书先执行：

```text
python3 tools/basegen.py scan-filter
exit 1: task scan-filter not found in taskbook.yaml
```

随后只加入 `server/internal/discovery/scan_filter_test.go` 并运行：

```text
cd server && SCAN_FILTER_FIXTURE_ROOT="$PWD/.team/nodes/scan-filter-impl/tmp" \
  go test -count=1 -run '^TestDiscoverScanFilterCommandBoundary$' -v ./internal/discovery
exit 1: ta list-panes calls = 1, want 0
```

该红测由真实 UNIX listener + PATH 前置 tmux spy 驱动；失败证据中的 spy
argv 同时出现了 `default`、`focus`、`e2e-isolated`、`mystery`、`ta-private`
和其它 uid 目录，证明旧实现是在分类前调用 `list-panes`。

## 实现

- `server/internal/discovery/scan.go:35-105` 增加目录级与候选级纯路径分类。
  其它 uid 的 `tmux-<uid>` 目录在 `ReadDir` 前跳过；`ta-*`、`test-*`、
  `e2e-*`、隔离祖先、仓内 `.team/nodes/*/tmp/` 与未知文件名 fail-closed。
  仅当前 uid 的 `default` 和 TMUX 第一段清理后的精确路径允许继续。
- `server/internal/discovery/scan.go:142-169` 将分类作为 `probeSocket` /
  `scanServer` 前的硬闸；每项日志带 `socket`/`path`、`classification`、
  `action`，允许外部调用另记录完整 `list-panes` argv。
- `server/internal/discovery/scan_filter_test.go:19-201` 实现精确测试名
  `TestDiscoverScanFilterCommandBoundary`，使用仓内 node-local tmp、相对
  短 socket 路径、自建 UNIX listener 与不转发系统 tmux 的 spy，验证两个
  用户 Agent、四类禁止/未知零调用、分类日志和 Socket 身份。
- `server/internal/discovery/scan_test.go:86-95,380` 仅将既有隔离夹具的
  socket 名称调整为允许的 `default`/TMUX `focus`，保留原发现、聚合、取消、
  死 socket 与 TTL 回归覆盖。
- `server/internal/discovery/doc.go:5-17` 同步扫描边界外骨骼说明。

## 验证

```text
cd server && go test -count=1 ./internal/discovery
exit 0

sh .team/ledgers/acceptance/scan-filter.sh
exit 0
SCAN_FILTER_EVIDENCE default_list_panes=1 tmux_env_list_panes=1 ta_list_panes=0 isolated_list_panes=0 unknown_list_panes=0 other_uid_list_panes=0 user_agents_found=2 spy_argv_recorded=true
SCAN_FILTER_CLASSIFICATION_EVIDENCE ta=skip isolated=skip unknown=skip other_uid=skip path_operand=true classification_operand=true
```

## 未验证项与边界

- `python3 tools/archwiki/build_wiki.py --check --strict-t3` exit 1；报告了
  既有跨 app/server 的 T3-1/T3-2/T3-3/T3-4 违规。本格未改判据、产品码或
  非任务书路径，故不将该门写成通过。
- 未启动真实 tmux、真实会话或生产 daemon；未读取凭据/生产日志；未宣称
  性能优化成功。提交、merge、push 与独立判者破坏齿未在实现席执行。
