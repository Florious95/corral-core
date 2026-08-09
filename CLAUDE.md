# 远程Agent安卓（暂名）——工程编排约定

手机远程操控主机 tmux 中大量 Agent CLI 的开源产品（Apache 2.0）。
产品需求的唯一权威是 `requirement-base/`（先撞库再问用户）；任务状态的唯一权威是
`taskbook.yaml` + `.team/evidence/`。本工程按 taskbook-orchestration skill 运行。

## 目录地图

- `requirement-base/` — 需求维基（INDEX 索引 / entries 条目 / REVISIONS 修订记录），只增不改
- `taskbook.yaml` — 任务书（五栏+争议度）
- `agents/` — 席位角色文件（retired/ 为退役归档）
- `.team/evidence/` — 任务证据 JSON（状态唯一来源）
- 产品代码目录在架构裁定后建立

## 席位与模型

- teammate 一律第三方 API（compatible_api profile）；难点模块可开 Fable 5 短命席位（一次性，交件即退役）
- 密钥只存在于 `.team/current/profiles/*.env`，**任何席位（含 leader）禁止读其原文**；
  诊断只用 `team-agent profile show <name> --workspace . --json`
- **席位恢复纪律**（A-24 实证，2026-08-09）：席位恢复失败达 2 轮（自动恢复/start-agent/reset 任意组合）
  即弃 id——remove 归档后换**处女 id** add-agent 重建带案重派，不再消耗轮次；死 id 的 runtime 残留
  （provider-config/env/events）保留供框架取证。停摆检测与自动探针由 `.team/watchdog.py` 值守（三条件+预算 2）

## 工程红线

- 代码必须带外骨骼注释（机器可校验标注），架构维基从代码现算，禁止人工另维护架构文档
- halt 是默认：缺字段、判不出 ⇒ 停下问，绝不猜
- 契约级议题（见 requirement-base/INDEX.md 未决议题表）定夺前，相关模块不施工

## 工程常识红线（2026-08-09 用户回炉裁定后增设；所有基底模板继承，验收必查）

任何**面向用户或常驻运行**的交付物，功能验收之外必须自证以下工程卫生——缺一项即不合格，
不因"验收命令绿了"豁免：
1. **静默经济**：常驻进程空闲（零客户端/零订阅）时 CPU 趋近 0、无固定频率的子进程派生；
2. **进程卫生**：单实例守卫；自身与测试脚本退出后零孤儿进程/零残留监听端口；
3. **资源有界**：内存/磁盘（日志、上传目录）增长有界或有轮转说明；
4. **可达性常识**：对外广播的地址/端口必须是对端真实可达的（虚拟网卡/回环/link-local 排除）；
5. **失败可见**：用户任何动作在有限时间内必得可见结果（成功或带原因的失败），静默等待即缺陷。
