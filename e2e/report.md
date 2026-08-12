# E2E 验收报告

- 日期：2026-08-12 15:38:48
- 结论：**FAIL**

## 层1 协议链路（真实 tmux + 真实 Claude CLI + agentmirrord + WS）

- layer1_pass: `True`
- 首帧延迟（006 <200ms）：样本 6，min 49.601 ms，avg 54.74604866666667 ms，p50 50.0905 ms，p90 54.761042 ms
- 样本分布：71.4, 54.8, 49.6, 52.8, 49.9, 50.1 ms

## 层2 安卓模拟器 smoke

- layer2_pass: `false`
- 失败现场：layer2.json, layer2.workspace.xml, layer2.pairing.xml, layer2.capture-err.txt, layer2.list.xml, layer2.expected-fail.xml, layer2.pair-form.xml, layer2.fail, layer2.session.xml, layer2.daemon.log, layer2.pane-capture.txt

## 层3 老化（004/013：20 轮杀-恢复 / 20 轮断连-重连）

- layer3_pass: `True`
- daemon 重启轮：ok 20 / fail 0
- 连接断连轮：ok 20 / fail 0

## 验收命令

```
bash -lc 'bash e2e/run.sh'
```
