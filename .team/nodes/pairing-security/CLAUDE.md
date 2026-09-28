# 知识基底 · pairing-security（系统编译产物）

## 0. 任务（taskbook.yaml#pairing-security）
- 目标：配对与鉴权（011 路线 a）：服务端生成配对 token；终端打印二维码（QR 载 服务端地址+token，预留 TS authkey 字段）；WS 握手校验 token；未配对连接一律拒绝。红线：token 不落日志、不回显（协议 §9）。
- 验收（exit 0 = 过）：`bash -lc 'cd /Volumes/nvme/Projects/远程Agent安卓/server && go test ./internal/pairing/...'`
- 写范围：`server/internal/pairing/`、`server/cmd/`（接线打印 QR）。api/config 只消费公开 API；需加法性变更先报 leader。

## 1. 架构基
- 已有接口位（先读代码）：`internal/api` 的 TokenValidator 钩子（ws-api 已实现 config 静态 token 校验）；`internal/config` 已有 Token 字段（flag+env+default）。
- 本任务职责：
  1. **token 生成**：config.Token 为空时启动自动生成（crypto/rand，base32/hex ~128bit），持久化到状态目录（~/.config/agentmirror/token，权限 0600），重启复用；显式配置的 token 优先。
  2. **QR 生成**：终端打印（ANSI 块字符 QR），内容为 JSON：`{"v":1,"url":"ws://<host>:<port>/ws","token":"...","ts_authkey":""}`——host 探测本机 LAN 地址（多网卡列举，tailnet 地址若 tsnetd 启用也列出）；预留 ts_authkey 空字段（app-tsnet 后续用）。
  3. **接线**：cmd/agentmirrord 启动时打印 QR + 明文连接指引（供手填兜底）。
- QR 库选型：Apache-2.0/MIT 兼容（如 github.com/skip2/go-qrcode MIT），终端渲染用半块字符（▀▄█）自绘几十行即可，避免重依赖。
- 安全红线细则：token 打印例外——QR 与启动指引是 token 的**唯一**合法出口（用户配对必须见到它）；除此之外任何日志/错误消息不得含 token；测试断言日志无 token 泄漏。

## 2. 现场基
- ws-api 已交付（17 测）：auth 帧校验/未认证拒绝路径都在，本任务不重复实现校验，只供给 token 源与 QR。
- naming 任务将在你交件后改模块名——你无需关心，但**不要**在代码里硬编码产品名字符串（用 config/常量引用）。

## 3. 需求基（指针）
1. requirement-base/entries/007-联网模型-tsnet与扫码.md（QR 路线 (a) 的裁定与 authkey 分层）
2. requirement-base/entries/011-技术路线裁定.md（联网行）
3. docs/protocol.md §9（安全与日志红线）

## 4. 经验基
- 红测先行：空配置自动生成且持久化复用、显式 token 优先、QR 内容 JSON 往返、日志无 token（阳性对照：故意注入 token 到日志的红测形状）、0600 权限，各一条。
- 静默失效猎杀：LAN 地址探测失败要明确降级提示（打印 localhost 并警告），不静默出错误 QR。
- 注释红线、净化前缀照旧。

## 5. 沉淀区（唯一允许你追加写入的区域）

- 2026-08-09 交件：token 生成/持久化（`EnsureToken`，0600 原子落盘）+ QR（`skip2/go-qrcode` 新直接依赖，ANSI 半块自绘）+ LAN/tailnet 探测（`DetectAddresses`/`PrimaryHost`），cmd 接线打印 QR+指引。13（pairing）+7（cmd）测全绿，`go build ./...`/`go vet`/`go test ./...`/`-race` 全过。
- 坑 1（探测排序）：`pickPrimary` 只认输入顺序，LAN 排序契约在 `DetectAddresses`——测试喂已排序序列。
- 坑 2（降级可测性）：`PrintOnboarding` 探测与渲染耦合则回环-only 降级警告不可注入；拆出 `PrintOnboardingWith(o, addrs, primary, w)` 公开 seam 后，测试可强制 loopback-only 断言 ⚠ 警告。
- 坑 3（token 不进日志）：cmd 启动日志只记 `token_source`（explicit|auto）+ 存储路径，绝不记值；`TestErrorsNeverContainToken` 带阳性对照（注入 token 验证检测器本身能抓到）。
- 外部模块不能 import internal 包：跨模块演示 QR/降级输出需用 seam 或临时测试，不可临时建 external main。
