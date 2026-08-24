# successor3 bootstrap semantic verdict

结论：`pass`。

Fresh helper 验证了真实 `baseline_bundle.py retrieve` 入口：最终 projection 取回 exit 0；由最终 `apk_relpath` 之前 projection 计算出的 stale `bundle_id` 对最终 manifest 精确 exit 2，并报告 `manifest bundle_id mismatch`；只改 `implementation.bundle_py.sha256` 的 canonical provenance 伪造同样由真实入口产生该精确底层形状，再由 hardened 齿归类为 exit 1。helper 不重写实现字节，绿色输出实际取回并核对 fixture bytes。

Fresh measure control 保持合法 Git root、非空 raw、n=10、顺序、fixture、身份和真实 runner bytes 不变，仅改变声明的 `runner_sha256`，得到精确 `runner provenance mismatch` exit 1；未借 `repository root mismatch` 或 `empty raw log` 旁路造红。

固定 `control-contract.json` 的精确摘要为 `ffcea3d0d3282618ad91f9db44c7a99616868b6610c88516e022385e59bd3fd9`。缺失与摘要漂移 fresh 均 exit 2；IMPL 末行为 `implementation: unjudgeable` fresh exit 1；`implementation: pass` 但缺 `app/local.properties` fresh exit 2，且未输出配置值、SDK 路径或敏感内容。六个 POSIX shell、Python helper 和 JSON 契约的语法检查均通过。

两阶段设计可执行：阶段一不生成 final DSL/JSON、lease、PID 或 WT；阶段二要求以实际 bootstrap commit SHA 做逐路径 `git cat-file` 与 provenance，并在缺路径/非后代时阻断。固定路径不被 Git ignore，适合提交。任务书明确保留旧历史、真实 verify/final 门、envcheck、无缓存、n>=10、nearest-rank p50/p95、B/A≤1.10、真机 user gate 和迁移前置。test/probe 的 grep gate 明确只检查交付形状，不冒充事实通过。

未执行 final ledger、产品构建、迁移、停 driver 或创建 WT；详见 [tests.log](./tests.log)。

verdict: pass
