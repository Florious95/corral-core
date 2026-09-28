# 知识基底 · ws-api（系统编译产物）

## 0. 任务（taskbook.yaml#ws-api）
- 目标：按协议契约实现服务端 WS API 与图片上传端点，接线 discovery 与 bridge；断连重连后重新订阅即重放快照。
- 验收（exit 0 = 过）：`bash -lc 'cd /Volumes/nvme/Projects/远程Agent安卓/server && go test ./internal/api/...'`
- 写范围：`server/internal/api/`、`server/cmd/`（接线启动）。红线：协议行为以 docs/protocol.md 为准，一帧不差；不改 protocol/discovery/bridge 包（发现问题报 leader）。

## 1. 架构基（组装已交付的三个包）
- **协议权威 docs/protocol.md v1（定稿）**：生命周期 §3、帧表 §4.1、聚合已在 listing 内、二进制帧 §6（scrollback 12 字节区间头）、错误枚举 §7、/upload §8、安全 §9（token 不落日志）。
- 已交付依赖（先读各包 doc.go 与导出 API）：
  - `internal/protocol`：帧类型+编解码（MarshalFrame/UnmarshalFrame/EncodeBinary/DecodeBinary），直接用，不重复造。
  - `internal/discovery`：`Discover(ctx)` 全 socket 扫描→两级模型快照（无缓存语义）。
  - `internal/bridge`：pane 快照/FIFO 流/注入回执/resize/scrollback（裸 pane id 精检；错误三分类）。其节点沉淀 `.team/nodes/term-bridge/CLAUDE.md` §5 必读（sun_path 104 字节坑等）。
- 装配设计：
  - WS 库：gorilla/websocket 或 coder/websocket（均 Apache/BSD/MIT 兼容），自选并在 doc 注明理由。
  - 会话簿：连接级订阅表（ref→bridge 实例）；连接关闭=全退订（协议 §3）；服务端不存客户端状态（004）。
  - listing/list_delta：定期 Discover（间隔可配，默认 2s）+ 与上次快照 diff → delta 推送；seq 单调；ref 生成：socket+pane_id 稳定映射。
  - agent 状态：`internal/agentstate` 由并行席位施工中——**你只留接口位**（`StateProvider` 接口，默认实现恒返 unknown），unknown 是一等公民（008），接线归后续；不要等它。
  - auth：配对 token 校验的钩子接接口（`TokenValidator`），先用 config 静态 token 实现；pairing-security 任务后续替换。token 不回显不落日志（§9）。
  - /upload：multipart 落盘 `~/Downloads/agentmirror-uploads/`（可配），返回绝对路径 JSON（§8）；大小上限可配默认 20MiB。
- 测试面：httptest + 真实 WS 客户端连接；tmux 集成场景用隔离 socket（bridge 测试铁律同款）；协议一致性直接复用 protocol 包 golden 夹具构造帧。

## 2. 现场基
- go1.26.1；tmux 3.6a。隔离 socket 用**短路径**（sun_path 104 字节上限，term-bridge 实测坑）。
- 净化前缀照旧；`-timeout 120s`。

## 3. 需求基（指针）
1. docs/protocol.md（协议正文，唯一权威）
2. requirement-base/entries/003-对话体验四标准.md、004-后台策略-无状态免疫.md（重连语义）
3. requirement-base/entries/012-工作区聚合状态规则.md（聚合在服务端算——listing 组装时执行）

## 4. 经验基
- 红测先行：未认证操作→unauthorized、订阅不存在 ref→session_not_found、input 必回 ack、重复订阅幂等重放快照、delta seq 单调、upload 返回路径存在于磁盘，各一条。
- 静默失效猎杀：每个 C→S 帧都有可判定结果（ack/error/数据），无声吞帧是最高罪。
- 注释红线照旧。

## 5. 沉淀区（唯一允许你追加写入的区域）
