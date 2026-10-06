# 原生会话运维补齐（Pi / Grok）

本实现继承 74af43788 双主题与 bbdf1cee7 输入/生命周期修复；不另建 Agent、不改变生产端口。它补齐现有 `conversation_v1` 原生桥，通用五 Hook 设计不因 HTTP 下载接口增加第六 Hook。

## 历史 / Resume

手机沿已有 `list_sessions` / `resume_session` command correlation；只选择目录范围内的 opaque session ID，不传主机路径。

- Pi：保持 06 合约的目录/分支读取与原生 `switch_session` 确认。
- Grok：现有 verified ACP PID 的 `session/list {cwd,cursor?}` 分页目录，2 MiB 总目录限额；拒绝缺失身份、重复 ID、重复 cursor。不是猜 ~/.grok 最新文件，官方目录会读取该原生运行时的历史存储。
- Grok 恢复：`session/load {sessionId,cwd,mcpServers:[]}`，不是不重放历史的 `session/resume`。ID 必须在当前 cwd 的官方目录中。
- 忙态不自动中断。明确 force 同意后发 `session/cancel`，等实际 running/queue/prompt 归零才 load；写出 cancel 不等于任务停止。
- 重放使用现有 bounded/coalescing worker；保留 8 MiB / 4000 records 视窗与 truncation 标志，不修改原始主机历史。Grok ACP 未提供的历史消息时间不推测，采用接收时间。
- 原生 event 锁覆盖最终 state 与 history 采样；旧客户端断开，新 stream 含 reset / replay / history_window / state，再交 `{session_id,stream,head_seq,history_truncated,content_clipped}` 回执。手机仍须满足 session / stream / head / Live 屏障。
- 进程 birth 或 session identity 不明、load 超时或确认失败：只关闭桥，不杀主机进程，不猜测恢复成功。

## Grok 用量与上下文

`get_session_stats` 在空闲的当前会话运行两次原生 `session/prompt`：`/context`、`/session-info`，每次最多 5 秒。私有 collector 不向手机伪造 user turn/stream；不是固定频率轮询。官方原生 slash 的输出可能留在它自己的历史/导出中；不能通过篡改主机历史“清理”它。

仅严格识别原生 Context 的 used/limit 同一对和 Turn。示例（实际值因会话而异）：

```json
{"agentProvider":"grok","sessionId":"native-id","source":"native_slash","modelName":"Grok 4.7","turnCount":2,"contextUsage":{"tokens":38865,"contextWindow":256000,"percent":15.181640625}}
```

未报告累计 input/output/cache/cost、分回合 token 消耗时不添加这些字段。手机显示 `—`，未知缓存命中率不能说“尚无输入”。context 占用不是会话累计用量，不通过字符数估 token、不抓屏、不读账号余额。native model 来自当前 ACP metadata。

查询遇 deadline 是 unknown work；关闭桥，不伪造 idle。失败可见，不重复提交模型 prompt。

## 精细压缩

- Pi：现有 `compact {customInstructions?: string}`，手机 8192 bytes 指令上限；保持官方 compaction start/end 与取消/错误结果。
- Grok：native `/compact`；未确认自定义指令 schema，非空指令显式拒绝，不丢弃或猜扩展参数。
- ACK 是 admission，不是压缩结束。真实 prompt callback 才产生 `compaction_end`；deadline 不产生成功 end；取消无 result，保留 aborted。
- Grok slash 完成可以是 native no-op。成功 end 用 `result.commandOnly=true`、有限原生 `summary`，手机说“原生压缩命令已完成”，显示原生反馈，不能仅凭 command completion 宣称上下文减少。
- 不编造 before/after tokens、节省比例或费用；只有可比较原生估算才计算 delta。

## 导出 / 下载 / 系统分享

```text
GET /artifacts/{urlencoded-current-session-id}/export?ref={urlencoded-current-ref}
Authorization: Bearer <paired token>
```

- 使用现有 token validator；query token 不是认证。只认当前 catalog ref 与当前 native session ID，拒绝 cross-ref/过时 SID、路径或 flag 注入。
- Pi：内部 `export_html {outputPath:host_private_path}`，确认返回路径一致；格式 `text/html`，官方会话树 HTML。
- Grok：已验证 native binary 的官方只读 `grok export ID OUTPUT`，当前宿主用户默认历史 store；不是启动新 Agent/模型，也不伪造 ACP typed export。格式 `text/markdown`，不给 `.html` 改后缀假装 HTML。
- 临时私有目录；NOFOLLOW、regular file、非空且最多 64 MiB；前后校验 birth 与 session ID；完成或失败清理。官方生成过程属于原生 exporter，服务端发布/客户端下载明确 capped 64 MiB。
- 最多 4 同时导出；生成 request 40 秒、原生 exporter 30 秒、下载写入期限 60 秒。错误不返回 native stderr/主机路径。
- one-shot 下载，不支持 Range（再次生成不是同一 immutable entity）。`attachment`、`private,no-store`、`nosniff`、sandbox CSP；不是静态目录浏览、公开凭据 URL 或 app WebView。
- Android 同 paired LAN/tsnet SocketFactory 路由，Authorization header，不 redirect / 不 retry；50 秒 call timeout，流式 64 KiB buffer / 64 MiB；私有 cache 最多 4 文件，部分文件失败删除。
- FileProvider 只新增 `cache/session-exports/`；ACTION_SEND + EXTRA_STREAM + ClipData + 临时 read URI grant。系统 chooser 出现不意味着文件已发送给接收方。接收者由用户选择，可保存/打开。
- pending Sheet 只显示真实等待，不伪造百分比；“收起”不假装取消 Agent；再次点击可重开进度。
- HTML/Markdown 可能包含对话、工具输出或敏感信息。原生 HTML base64 不是脱敏，分享说明必须可见。

## 双主题与视觉纠偏

- 三点历史仍第一项，新增导出在用量同组；按实际 Pi/Grok adapter 接线更新现有能力表，未知 provider 不伪装这些能力。
- 彻底删除 BrushedGrain / ImageShader / 横向 streak tile；现代主义纯净不透明平色、0.5dp hairline；无木纹、噪点、拉丝贴图。
- Liquid Glass menu / ModelPicker / Sheet / confirmation reading surface 是 opaque neutral protective surface，阻断底层粉色 Logo 透色。统一 scrim dark .64 / light .48；保持平面 geometry / hairline / ambient shadow，不做 glint/bevel。
- 空闲无新增 timer/subprocess；进度只在真实活动 job 期间。历史 Pending/Running tools 与输入 backpressure / same-ref teardown 不变量不改。

## 验收与诚实边界

Go `-count=1 -race`；Android `--rerun-tasks`；独立原生非空 Grok load / replay / live 连续性、stats 真值、两端 compact feedback、Bearer/ref/SID 防护与 native HTML/MD 内容；模拟器实际点击系统分享及两主题 no-grain/no-bleed 截图。功能 PASS 不冒充性能环境门通过，也不冒充用户最终真机确认。历史终端单测债按同环境基线逐项比较，不能用数量相同掩盖新失败。
