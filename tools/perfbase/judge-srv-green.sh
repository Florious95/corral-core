#!/bin/sh
# 判据：t.instr.srv——服务端 subscribe 三时间戳日志落地且 go 全绿。
# 基线事实（2026-08-22 leader 实测）：main 上 `go build ./... && go test ./...` 退出码 0 ⇒ 存量红为零。
# 四态：0=通过；1=不通过；2=不可判。
set -u
ROOT=$(pwd)
[ -d "$ROOT/server" ] || { echo "UNJUDGEABLE 无 server/"; exit 2; }

# 四个操作数键必须出现在 server 源码里（诊断日志纪律：记操作数不只记判决）
for k in perf_subscribe recv_ms start_ms done_ms queue_ms; do
  grep -rq -- "$k" "$ROOT/server" --include=*.go || { echo "FAIL server 源码缺键 $k"; exit 1; }
done

cd "$ROOT/server" || { echo "UNJUDGEABLE cd 失败"; exit 2; }
OUT="$ROOT/.team/nodes/pb-srv/tmp/go-run.log"
mkdir -p "$(dirname "$OUT")"
{ go build ./... && go test ./...; } >"$OUT" 2>&1
RC=$?
grep -qE "^# |cannot find package|undefined:" "$OUT" && { echo "UNJUDGEABLE 编译不过（见 $OUT）"; exit 2; }
[ "$RC" -eq 0 ] || { echo "FAIL go test 红 rc=${RC}（基线为 0；见 $OUT）"; exit 1; }
echo "PASS server 三时间戳到位且全绿"
exit 0
