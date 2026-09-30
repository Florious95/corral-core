# AgentMirror Web 客户端

浏览器 Web 客户端，通过 WebSocket 连接 `agentmirrord` daemon，镜像 tmux 上运行的
Agent CLI 会话（与安卓 App 同协议、功能对齐）。协议权威：[`docs/protocol.md`](../docs/protocol.md)。

## 快速开始

```bash
cd web
npm install          # 拉取 xterm（MIT），postinstall 把 dist 拷到 vendor/xterm/
npm test             # 协议编解码 + 连接管理单测（对 server 契约夹具断言）
npm run dev          # 浏览器开发模式：http://127.0.0.1:1420
npm run tauri dev    # Tauri v2 桌面开发模式
npm run tauri build  # 当前平台安装包（macOS 为 .dmg）
```

打开后：配对页填 `ws://<host>:9900/ws` + 配对 token → 连接 → 工作区列表 →
点一个会话进入终端镜像。

## 目录结构

```
web/
├── index.html          入口（配对 / 工作区 / 多会话标签）
├── ARCHITECTURE.md     状态边界与桌面集成说明
├── css/style.css       主题与布局
├── js/
│   ├── protocol.js     控制帧编解码（JSON text 消息）
│   ├── binary.js       二进制流帧编解码（snapshot/delta/scrollback）
│   ├── client.js       连接管理（auth/list/subscribe/input/resize/scrollback，
│   │                   重连 + 订阅重放 + listing seq 连续性）
│   ├── terminal.js     xterm.js 封装（fit/resize/snapshot/delta）
│   ├── scrollback.js   历史页拉取与展示
│   ├── preferences.js  配置恢复与主题偏好
│   └── app.js          页面路由与 UI 接线
├── scripts/postinstall.cjs   xterm dist → vendor/ 的复制脚本
├── test/               单测（node --test，对 server testdata 夹具断言）
├── src-tauri/          Tauri v2 桌面壳（业务代码不依赖 Tauri API）
└── vendor/xterm/       xterm dist（npm 装好后由 postinstall 生成，勿手改）
```

## 协议实现要点

- **控制帧** = WS text 消息：`{"v":1,"type":...,"payload":{...}}`。未知字段忽略
  （前向兼容），未知 `type` 报错。`payload` 永不携带终端字节。
- **二进制流帧** = WS binary 消息，布局见 `docs/protocol.md §6.1`：
  `magic "RA" + version + kind + reflen + ref + payload`。kind=3（scrollback）
  的 payload 头部是 12 字节元数据头（req_id / from_line / line_count，均大端）。
- **状态层与镜像层解耦**：状态（working/idle/blocked/done/unknown）只出现在控制帧，
  `aggregate_state` 由服务端权威计算、客户端只渲染；二进制通道只有原始终端字节。
- **重连语义**（requirement 004 无状态铁律）：掉线 → 指数退避重连 → READY 后重新
  `list` + 重放全部活跃 `subscribe`。`list_delta.seq` 不连续于上次见过的值 → 自动
  重新 `list`。
- **输入必达**：`input` 以 `input_ack` 完结；超时/掉线/停连判明确失败，不静默。

## 安全

- token 只在 `auth` 上行，不回显、不落日志；配对页使用密码输入框。
- 为满足刷新后自动恢复，最近的 URL 与 token 保存在浏览器 localStorage。其安全边界等同
  当前站点同源存储：部署时不得加载不可信第三方脚本；点击“断开”会清除已存 token。
- 代码在 `web/` 范围内；本目录不涉及 git 提交。

## 工程卫生

- 空闲（未连接）时不派生进程、无定时轮询；会话页无输出时 xterm 零帧循环。
- 单实例 WebSocket 连接；`disconnect` 显式关闭 socket 并清理所有未决定时器。
- xterm.js（MIT）经 npm 安装、本地静态提供，无 CDN 依赖，许可证 Apache-2.0 兼容。
