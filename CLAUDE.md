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

## 工程红线

- 代码必须带外骨骼注释（机器可校验标注），架构维基从代码现算，禁止人工另维护架构文档
- halt 是默认：缺字段、判不出 ⇒ 停下问，绝不猜
- 契约级议题（见 requirement-base/INDEX.md 未决议题表）定夺前，相关模块不施工
