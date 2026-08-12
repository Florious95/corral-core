# 现场基 · web-client

## 协议权威
docs/protocol.md（397 行，完整 WebSocket 协议 v1 规范）

## 需要实现的帧类型
### 控制帧（JSON text message）
- C→S: auth, list, subscribe, unsubscribe, input, scrollback, resize
- S→C: auth_ack, listing, list_delta, input_ack, error

### 二进制帧（binary message）
- S→C: snapshot（全屏快照）, delta（增量字节流）, scrollback（历史页）
- 帧头格式见 protocol.md §6.1

## 技术选型
- xterm.js（MIT，终端渲染）— 直接 write(data) 即可渲染 VT/ANSI
- 原生 WebSocket API
- 纯 HTML/CSS/JS，不需要 React/Vue（保持简单）
- 二进制帧用 DataView/ArrayBuffer 解析

## UI 三页
1. 配对页：ws 地址 + token 输入 + 连接按钮
2. 工作区列表页：两级分组（workspace → session），显示状态徽章
3. 会话页：xterm.js 终端 + 输入框 + 特殊键条（Esc/Ctrl-C/Tab/方向键）

## 目录结构建议
web/
├── index.html          # 入口
├── css/style.css       # 样式
├── js/
│   ├── protocol.js     # WebSocket 协议实现（帧编解码）
│   ├── terminal.js     # xterm.js 封装
│   ├── app.js          # UI 逻辑（路由、页面切换）
│   └── binary.js       # 二进制帧解析
├── package.json        # xterm.js 依赖
└── README.md

## 安全
- token 不落日志不上屏明文（输入框 type=password）
- auth 帧的 token 只上行不回显

## 参考
- 安卓端 App 的 UI 截图在 e2e/artifacts/stage4-execution/A*.png
- 服务端协议实现在 server/internal/protocol/（Go 参考）
- 安卓端协议实现在 app/app/src/main/java/dev/agentmirror/app/conn/（Kotlin 参考）
