# 现场基 · test-automation

## 定位
完全独立的测试工程，不嵌入产品代码。对产品的关系是单向的——测试知道产品，产品不知道测试。

## 核心能力
1. **通用断言库**：WebSocket 帧断言、终端内容断言、UI 元素断言、截图对比
2. **通用测试函数**：连接 daemon 并配对、订阅会话、发送输入并等待回显、截图并验尺寸
3. **环境管理**：隔离 daemon 起停（go build → 高端口）、隔离 tmux 起停、模拟器 APK 安装
4. **用例注册与选择性执行**：按标签/缺陷号/模块选择跑哪些测试
5. **结果持久化**：每次运行的 PASS/FAIL 结果存储，支持趋势对比

## 技术栈
- **Web 端测试**：Playwright（headless Chrome）或 Chrome DevTool MCP
- **安卓端测试**：adb + uiautomator helpers（shell 脚本封装）
- **后端接口测试**：WebSocket 客户端库直连 daemon
- **语言**：Python 或 Node.js（哪个对 Playwright 和 WebSocket 都方便）

## 目录结构建议
test/
├── package.json / requirements.txt
├── framework/
│   ├── ws_client.js        # WebSocket 协议客户端
│   ├── assertions.js       # 通用断言
│   ├── fixtures.js         # 环境管理（daemon/tmux）
│   └── reporter.js         # 结果持久化
├── web/                    # Web 端测试用例
│   ├── pairing.test.js
│   ├── workspace.test.js
│   └── session.test.js
├── android/                # 安卓端测试用例
│   ├── helpers/
│   └── cases/
├── results/                # 运行结果存储
└── README.md

## 测试三层流水线（用户裁定 W-02）
1. Web 端 + Playwright → 拦 80% 问题
2. 安卓模拟器 + adb → 端到端
3. 用户手测 → 最终验收

## 隔离铁律
- 测试一律 env -u TEAM_AGENT_*
- 隔离 daemon 用高端口（≥19983），绝不触碰生产 daemon
- 隔离 tmux 用独立 TMUX_TMPDIR

## 参考
- docs/protocol.md（协议规范）
- server/internal/api/（服务端实现）
- e2e/artifacts/dogfood/TESTPLAN.md（现有 42 条用例清单）
- docs/stage4-execution-plan.md（用例执行方案）
