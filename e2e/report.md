# E2E 验收报告

- 日期：2026-08-09 09:38:04
- 结论：**FAIL**

## 层1 协议链路（真实 tmux + 真实 Claude CLI + agentmirrord + WS）

- layer1_pass: `True`
- 首帧延迟（006 <200ms）：样本 6，min 36.615458 ms，avg 38.56967366666666 ms，p50 38.025583 ms，p90 38.888 ms
- 样本分布：36.6, 37.0, 42.3, 38.9, 38.6, 38.0 ms

## 层2 安卓模拟器 smoke

- layer2_pass: `false`
- 失败现场：layer2.json, layer2.fail, layer2.logcat.txt, layer2.workspace-fail.xml, layer2.daemon.log

## 层3 老化（004/013：20 轮杀-恢复 / 20 轮断连-重连）

- layer3_pass: `False`
- daemon 重启轮：ok 1 / fail 1
- 连接断连轮：ok 20 / fail 0

## 验收命令

```
bash -lc 'bash e2e/run.sh'
```
