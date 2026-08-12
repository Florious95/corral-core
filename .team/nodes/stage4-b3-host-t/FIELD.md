# 现场基 · stage4-b3-host-t

## 执行权威
唯一权威文档：`docs/stage4-execution-plan.md`（已读入）。

## 你的 6 条用例
C4 token 轮换文档化、C5(H) 锁中文 README 明示、D1 静默经济、D2 进程卫生、D3 资源有界、D4 可达性常识。

## 隔离铁律
- 自建 `TMUX_TMPDIR=/tmp/st4-b3-$$/tmux` + daemon 端口 19983
- `AGENTMIRROR_STATE_DIR=/tmp/st4-b3-$$/state`
- 测试一律 `env -u TEAM_AGENT_*`
- **绝不触碰**生产 daemon（pid 3393，:9900）与用户真实 tmux
- 收尾 `lsof -i :19983` + 进程表自证零残留

## 阳性对照（必做）
- T 对账：capture-pane 后断言输出非空且含预期文本；capture 为空 = harness
- 上传/日志用 `test -s` + 内容 grep 断言非空存在
- D2 单实例：二启断言显式失败可见

## daemon 构建与启动
```bash
cd /Volumes/nvme/Projects/远程Agent安卓/server
env -u TEAM_AGENT_* go build -o /tmp/st4-b3-$$/agentmirrord ./cmd/agentmirrord
/tmp/st4-b3-$$/agentmirrord -listen 0.0.0.0:19983 -upload-dir /tmp/st4-b3-$$/uploads
```

## 失败四归因（013）
product / harness / baseline / flaky —— 归因拿不准写 harness 倾向并留证。

## 安全
- 配对 token 不落日志不上屏明文
- TS authkey 不落日志不上屏不入截图
- 取证产物含密钥字段必须脱敏
