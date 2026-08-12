# Goal: 三端合一 Web 客户端 — 生产级日常可用

## 背景

agentmirror 是一个手机/桌面远程操控主机 tmux 中 Agent CLI 的产品。当前有：
- **Go daemon**（`server/`）：运行在主机上，通过 WebSocket 协议镜像 tmux pane
- **Android App**（`app/`）：Kotlin + Jetpack Compose，已在真机使用
- **Web 客户端**（`web/`）：原型级，~1600 行 vanilla JS + xterm.js，36/36 测试通过

Web 客户端当前只是测试辅助工具，不是日常可用的生产级客户端。用户希望将其升级为**主力日常使用端**，并且架构上支持**一套代码打包为 Mac 和 Windows 桌面应用**。

## 技术上下文

### 协议
完整协议规范见 `docs/protocol.md`（v1）。核心要点：
- 单条 WebSocket 连接，text 帧（JSON 控制）+ binary 帧（终端字节流）
- 帧类型：auth → list/list_delta → subscribe/unsubscribe → snapshot/delta → input/input_ack → resize → scrollback → error
- 特殊键：input 帧可选 keys 字段（esc/ctrl_c/tab/up/down/left/right）
- 会话按 cwd 聚合为工作区（两级模型）
- Agent 状态五值：working/idle/blocked/done/unknown

### 现有 Web 代码（`web/`）
- `js/protocol.js` — 帧编解码
- `js/binary.js` — 二进制流帧解析
- `js/client.js` — WebSocket 客户端（auth/subscribe/input）
- `js/terminal.js` — xterm.js 封装
- `js/scrollback.js` — 历史滚动
- `js/app.js` — 三页路由 + 接线（配对/工作区/会话）
- `index.html` + `css/style.css` — 单页壳
- `test/` — 36 条 Node.js 测试
- 依赖：`@xterm/xterm 6.0.0`（MIT），`ws`（dev）

### 桌面端架构
推荐 **Tauri v2**（Rust 壳 + 系统 WebView，产物 ~10MB，Apache-2.0 兼容）。当前纯 HTML/JS/CSS 架构天然适合被 Tauri 包装。不选 Electron（产物 ~100MB+）。

## 需求

### R1. Web 端生产级可用
用户日常在浏览器中打开即可操控所有 Agent CLI 会话，体验接近 Android App。

**必须有：**
1. 配对页：地址 + token 输入，连接状态可见（连接中/已连接/失败原因），记住上次配置（localStorage）
2. 工作区列表页：按 cwd 分组，显示会话数、聚合状态徽章（五态颜色语义），实时更新（list_delta）
3. 会话页：
   - xterm.js 终端，接收 snapshot + delta 流
   - 底部输入条 + 发送（input + input_ack 回执可见）
   - 快捷键条（Esc/Ctrl-C/Tab/↑↓←→，走 keys 字段）
   - 向上滚动查看历史（scrollback 分页拉取）
   - 终端 resize（跟随窗口/容器大小变化）
4. 自动重连：断连后指数退避重连，重连成功自动恢复（重新 auth → 重新 subscribe → 新 snapshot）
5. 多会话支持：可以同时打开多个会话标签/面板
6. 深色/浅色主题：跟随系统 + 手动切换
7. 响应式布局：适配桌面宽屏和平板

**不需要：**
- 图片上传（桌面端用剪贴板更自然，后续迭代）
- Tailscale 内嵌（桌面端不需要，直接连局域网/tailnet IP）
- 扫码配对（桌面端用手填）

### R2. 桌面端架构就绪
一套代码可打包为 Mac (.dmg) 和 Windows (.msi/.exe) 桌面应用。

**必须有：**
1. Tauri v2 项目骨架（`web/src-tauri/`）
2. `npm run dev` — 浏览器开发模式
3. `npm run tauri dev` — 桌面开发模式
4. `npm run tauri build` — 产出可分发桌面安装包
5. 桌面端可正常连接 daemon、显示会话、输入交互

**约束：**
- Web 功能代码不依赖 Tauri API（纯浏览器可用）
- Tauri 特有功能（如窗口管理）通过条件检测隔离
- 许可证 Apache-2.0 兼容

### R3. 代码质量
1. 现有 36 条测试保持绿
2. 新增功能有对应测试
3. 无已知的安全漏洞（XSS/注入）
4. 代码有必要的外骨骼注释（@contract 标注）

## 流程

1. **先读后写**：通读 `web/` 全部现有代码和 `docs/protocol.md`，理解已有实现
2. **架构设计**：产出简要架构说明（组件划分、状态管理、路由方案），不超过一页
3. **增量实现**：在现有代码基础上迭代，不重写；按 R1 功能列表逐项实现
4. **Tauri 集成**：功能基本完成后，添加 Tauri 骨架，确认桌面端可运行
5. **测试验证**：每个功能点实现后跑测试，最终全量绿
6. **浏览器实测**：用 Chrome 连接生产 daemon（ws://localhost:9900/ws），实际操控 Agent 会话

## 验收标准

### 功能验收
- [ ] 浏览器打开 → 配对页 → 输入地址+token → 连接成功 → 显示工作区列表
- [ ] 点击会话 → 终端显示 snapshot → 实时 delta 流更新
- [ ] 输入文本 → 发送成功（input_ack ok） → 终端显示响应
- [ ] 快捷键条可用（Esc/Ctrl-C/Tab/方向键）
- [ ] 终端 resize 跟随窗口大小
- [ ] 向上滚动查看历史内容
- [ ] 断连 → 自动重连 → 恢复显示
- [ ] 刷新页面 → 自动重连（配置已记住）
- [ ] 深色/浅色主题切换
- [ ] `npm run tauri dev` 可启动桌面窗口并正常交互
- [ ] `npm run tauri build` 可产出 Mac .dmg

### 技术验收
- [ ] `npm test` 全绿
- [ ] 无 npm audit 高危/严重漏洞
- [ ] 代码无硬编码地址/token
- [ ] xterm.js 许可证 MIT，Tauri 许可证 MIT/Apache-2.0

### 连接信息（实测用）
- daemon 地址：`ws://localhost:9900/ws`
- token 路径：`/Users/alauda/Library/Application Support/agentmirror/token`
- **禁止**将 token 值写入代码或日志
