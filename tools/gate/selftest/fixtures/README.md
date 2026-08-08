# selftest fixtures —— 手工构造，验证不得自证

本目录所有 `*.json` 均为**手工编写**的静态夹具（模拟各套件运行器的原始结果），
绝不使用 gate 自身生成的内容，否则自测即自证（知识基底 §4「验证不得自证」）。

- `ratchet_down/server.json`  —— cases=10，配初始基线 13：棘轮下行必须红。
- `suite_fail/app.json`       —— cases=5 但有用例失败：套件失败必须整体红。
- `empty_scan/server.json`    —— cases=0：server 面（min_cases=1）空扫描必须红。
- `green/{server,app,archwiki}.json` —— 全绿 + archwiki 缺席跳过路径。
- `ratchet_up/server.json`    —— cases=7，配初始基线 5：上行自动更新基线。

`gate.py selftest` 把每个 fixture 目录复制进临时 rundir、配临时基线后调用
`run_finalize` 断言退出码与基线副作用；三条红测额外要求 issues 非空。
