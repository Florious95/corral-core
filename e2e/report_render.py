#!/usr/bin/env python3
# report_render.py — 从 e2e/artifacts/ 的 metrics JSON + 层状态渲染 e2e/report.md。
#
# 只读真实数字（metrics-layer1.json / metrics-layer3.json / layer2 状态），
# 不手写声称。层红时把失败现场文件路径也列入，供检修。
import json, os, sys

ART = sys.argv[1]
PASS = sys.argv[2] == "1"

def load(name):
    p = os.path.join(ART, name)
    if os.path.exists(p):
        try:
            return json.load(open(p))
        except Exception:
            return None
    return None

m1 = load("metrics-layer1.json") or {}
m3 = load("metrics-layer3.json") or {}

# layer2 状态：读 layer2.sh 落盘的结果 JSON（未运行 = 文件不存在 → 未运行）。
l2 = load("layer2.json")
if l2 is None:
    l2_pass = None  # 未运行
else:
    l2_pass = bool(l2.get("pass"))

ff = m1.get("first_frame") or {}
aging = m3.get("aging") or {}

lines = []
lines.append("# E2E 验收报告")
lines.append("")
lines.append(f"- 日期：{__import__('datetime').datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
lines.append(f"- 结论：**{'PASS' if PASS else 'FAIL'}**")
lines.append("")
lines.append("## 层1 协议链路（真实 tmux + 真实 Claude CLI + agentmirrord + WS）")
lines.append("")
lines.append(f"- layer1_pass: `{m1.get('layer1_pass')}`")
lines.append(f"- 首帧延迟（006 <200ms）：样本 {ff.get('count', 0)}，min {ff.get('min_ms','-')} ms，"
             f"avg {ff.get('avg_ms','-')} ms，p50 {ff.get('p50_ms','-')} ms，p90 {ff.get('p90_ms','-')} ms")
if ff.get("samples"):
    lines.append(f"- 样本分布：{', '.join(f'{s:.1f}' for s in ff['samples'])} ms")
lines.append("")
lines.append("## 层2 安卓模拟器 smoke")
lines.append("")
lines.append(f"- layer2_pass: `{str(l2_pass).lower()}`")
if not l2_pass:
    files = [f for f in os.listdir(ART) if os.path.isfile(os.path.join(ART, f)) and f.startswith("layer2")]
    lines.append(f"- 失败现场：{', '.join(files)}")
lines.append("")
lines.append("## 层3 老化（004/013：20 轮杀-恢复 / 20 轮断连-重连）")
lines.append("")
lines.append(f"- layer3_pass: `{m3.get('layer3_pass')}`")
lines.append(f"- daemon 重启轮：ok {aging.get('restart_rounds_ok', 0)} / fail {aging.get('restart_rounds_fail', 0)}")
lines.append(f"- 连接断连轮：ok {aging.get('reconnect_rounds_ok', 0)} / fail {aging.get('reconnect_rounds_fail', 0)}")
lines.append("")
lines.append("## 验收命令")
lines.append("")
lines.append("```")
lines.append("bash -lc 'bash e2e/run.sh'")
lines.append("```")

print("\n".join(lines))
