#!/usr/bin/env bash
# run.sh — e2e 一键验收入口（知识基底 §1：三层顺序执行）。
#
# 层 1  协议链路层：Go harness（真实 tmux + 真实 Claude CLI + 真实 agentmirrord + WS 全链路）
# 层 2  安卓模拟器 smoke：真实 emulator 冷启动 + 配对链路 + 工作区可达（10.0.2.2）
# 层 3  老化层：20 轮杀服务端重启重放 + 20 轮断连重连快照一致
#
# 用法：
#   bash run.sh            # 无参全跑（层1→层2→层3）
#   bash run.sh --layer 1  # 单跑某层（调试）
#
# 任何一层红 → 整体非零退出。report.md 写实际数字（首帧 ms 分布 / 老化轮次 / 层2 结果）。
# 失败留现场进 e2e/artifacts/。净化前缀照旧：只碰自建隔离 tmux/socket，绝不触碰真实舰队。

set -uo pipefail
E2E_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$E2E_ROOT"
ART="$E2E_ROOT/artifacts"
mkdir -p "$ART"

LAYER=""
[ "${1:-}" = "--layer" ] && LAYER="${2:-}"

# ---- 净化前缀：本验收只用自建隔离 socket；剥离可能继承的 TMUX_TMPDIR/TMUX。 ----
export -n TMUX_TMPDIR 2>/dev/null || true
unset TMUX 2>/dev/null || true

# ---- 1. 构建 daemon 二进制（层1/层3 用；层2 也用同一二进制）。 ----
echo "=== [e2e] build agentmirrord"
( cd "$E2E_ROOT/../server" && go build -o "$E2E_ROOT/bin/agentmirrord" ./cmd/agentmirrord ) \
  || { echo "E2E FAIL: build daemon" | tee "$ART/run.fail"; exit 1; }

run_layer1() {
  echo; echo "########## LAYER 1: 协议链路层 ##########"
  ( cd "$E2E_ROOT/harness" && \
    E2E_DAEMON_BIN="$E2E_ROOT/bin/agentmirrord" \
    E2E_METRICS="$ART/metrics-layer1.json" \
    E2E_ARTIFACTS="$ART" \
    go test -run 'TestLayer1' -count=1 -v -timeout 400s )
}

run_layer2() {
  echo; echo "########## LAYER 2: 安卓模拟器 smoke ##########"
  bash "$E2E_ROOT/layer2.sh"
}

run_layer3() {
  echo; echo "########## LAYER 3: 老化层 ##########"
  ( cd "$E2E_ROOT/harness" && \
    E2E_DAEMON_BIN="$E2E_ROOT/bin/agentmirrord" \
    E2E_METRICS="$ART/metrics-layer3.json" \
    E2E_ARTIFACTS="$ART" \
    go test -run 'TestLayer3Aging' -count=1 -v -timeout 600s )
}

PASS=1
declare -a LAYERS
case "$LAYER" in
  1) LAYERS=(1) ;;
  2) LAYERS=(2) ;;
  3) LAYERS=(3) ;;
  "") LAYERS=(1 2 3) ;;
  *) echo "E2E FAIL: unknown --layer $LAYER (1|2|3)"; exit 2 ;;
esac

for L in "${LAYERS[@]}"; do
  if run_layer$L; then
    echo "== layer$L OK"
  else
    echo "== layer$L FAILED"
    PASS=0
  fi
done

# ---- report.md 渲染（真实数字从 metrics JSON + 各层状态读）。 ----
python3 "$E2E_ROOT/report_render.py" "$ART" "$PASS" > "$E2E_ROOT/report.md" 2>/dev/null \
  || { echo "report render failed" >&2; }

echo; echo "==== e2e verdict: $([ $PASS -eq 1 ] && echo PASS || echo FAIL) ===="
[ $PASS -eq 1 ] && exit 0 || exit 1
