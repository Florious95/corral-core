# Web 客户端架构

目标是让同一份无框架 HTML/CSS/JavaScript 同时运行在浏览器与 Tauri WebView；业务代码不调用
Tauri API。

- `protocol.js` / `binary.js`：协议边界。严格校验 v1 控制帧和二进制帧，未知字段忽略，终端字节不进入 DOM HTML。
- `client.js`：单 WebSocket 连接和唯一网络状态源。负责 auth、全量/增量列表、输入回执、指数退避重连，以及重连后重放全部订阅。
- `preferences.js`：localStorage 边界。保存最近 URL、token 与主题；读取失败时安全退回空配置/系统主题。
- `app.js`：页面与应用状态。路由保持配对、工作区、会话三层；一个 `SessionPage` 对应一个会话面板，`App.sessions` 保存多个已打开面板并切换标签，而网络仍由同一个 `Client` 多路复用。
- `terminal.js` / `scrollback.js`：每个面板独立持有 xterm、resize 和历史请求状态；隐藏面板不销毁，因此仍接收 delta，重新激活后重新 fit。
- `src-tauri/`：仅提供桌面窗口、构建配置和 WebView 容器。开发时加载 `npm run dev`，生产包加载静态 `web/`；没有桌面专用业务分支。

安全边界：所有用户/daemon 展示文本使用 `textContent` 或转义后写入；token 只用于 auth，不写日志或页面。
为了满足“刷新后自动重连”，token 与 URL 同存 localStorage，因此其机密性等同该站点同源存储；部署时必须避免加载不可信第三方脚本。
