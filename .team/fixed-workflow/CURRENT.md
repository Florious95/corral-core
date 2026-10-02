# 当前推进任务看板

### 核心铁律（已彻底执行，永久生效）
- 🔴 **【高阶模型已全部关闭且严禁再用】**：
  * `astra` / `pi-astra` / `code-auditor` / `fable` / `gemini-auditor`：**全部已关闭（STOPPED）**
- 用户特派 Claude Opus 5.5 单轮专家席位：
  * **席位**：`opus`（`claude-opus-5-5`）
  * **纪律**：**单轮任务圆满交付，用户真机实测很满意，后续绝不向其发送任何消息！**
- 构建与实测闭环 100% 由基础模型 Luna (GPT-6 Luna) 执行：
  * 开发席位：`developer`（Luna `openai-codex/gpt-6-luna`，`effort: max`）
  * 构建与测试调研席位：`tester`（Luna `openai-codex/gpt-6-luna`，`effort: max`）
  * 端侧实机测试席位：`app-tester`（Luna `openai-codex/gpt-6-luna`，`effort: max`）

---

### 全局基线状态（全量新特性与无参数通知身份自动推导已合流，全新终极黄金基线！）
- **全新终极黄金基线**：**Commit `008c90187311d0a51c91104e12c1451f28b7e732`**（`main` 主线）
- **提交说明**：`docs(contracts): add 02-desktop-mouse-wheel-and-raw-input-contract`（包含 `d4181a3ed fix(notify): infer sender identity from the linked pane` 与 `5530953e3`）
- **核心能力与新特性全景**：
  1. **极简通知体系与身份全自动推导**：
     - 彻底消除 `corral-notify` 的 `--agent-name` 参数，调用极致精简；
     - 服务端根据关联的 tmux 会话与 pane 标题，自动解析发信者身份（Leader 会话自动推导为 `Leader`，各执行角色自动推导为角色名）；
     - 规范化 macOS `/private/tmp` 路径别名，确保会话 Ref 100% 精确匹配；
     - 全局 Skill `corral-notify` 已在全环境（`~/.agents/skills/`、`~/.pi/agent/skills/`、`~/.claude/skills/`）安装生效；
  2. **生产环境 9900 服务端平滑热更完毕**：
     - 包含 whoami 200、nodeprobe 工作状态实时感知（15 工作区 65 会话，working_count 正常统计）、4 项 Agent 启动器（`+ Agent` 按钮可用）、快照鼠标前缀与桌面端原子滚轮防碎割；
     - 挂载通知管道 `/Users/alauda/Library/Application Support/agentmirror/notify.sock`；
  3. **Android 客户端（用户手机已实测验收通过）**：
     - 手机在后台实时收到系统通知大横幅（Heads-up）；
     - 消息中心全新精致 UI（消除“任/A”大白块首字，展示真实 Agent 品牌徽章与工作区路径，全文无截断展开）；
     - 点击卡片任意位置一键秒级跳转直达对应 Agent 终端 CLI；
     - 输入框展开无重叠动效、快捷命令「同步全部」、前后台即刻重连与 15s WebSocket Ping 低代价保活 100% 完好。
- **权威交付包信息（全量实测 PASS）**：
  * 本地路径：`/Users/alauda/Downloads/corral-app-final-5530953.apk`
  * 115 专属提取码：**`e5dc26mq9oorkzw2p`**
  * 115 通用覆盖包：**`biq1a94zjkpy183ar`**
  * 精确 SHA-256：`c982bcec68e0aec9a090b7959031d80a057dac51316eb97798d8492e085295b1`
  * 大小：45,231,629 bytes

---

### 攻坚任务全量闭环总览

| 序列 | 任务代号 | 任务定义与核心成效 | 最终闭环状态 |
|:---:|---|---|:---:|
| **1** | **设置界面重构<br>+ 快捷命令二级页** | **主页 iOS 风格 5 大玻璃卡片分组 + 快捷命令单行收拢独立子页**<br>（解决长列表臃肿，Provider 筛选胶囊，液态玻璃编辑面板） | 🏆 **用户真机实测很满意，已合入 main**<br>全新黄金基线 Commit `24098b930c74`！ |
| **2** | **极速排布 + 跟手滑动<br>(双轮驱动核心战役)** | **Warm Subscribe 秒开 + 尾沿补发/OverScroller 惯性**<br>（指点即开秒定，手指滑动看历史丝滑跟手，指哪到哪） | 🏆 **实测 100% PASS，已合入 main**<br>已稳固合入主线 |
| **3** | **Issue #32** | **软键盘升降时 CLI 界面实时同步提升与贴合回落**<br>（消灭滞后排布与收回中间大片留白） | 🏆 **实测 100% PASS，已合入 main**<br>底锚定同帧绘制，直随 live IME 曲线，零留白，文字物理锁定 |
| **4** | **Issue #27** | **一级列表从二级返回保留滚动锚点**<br>（解决进二级返回后强行跳回顶部的问题） | 🏆 **实测 100% PASS，已合入 main**<br>ViewModel 物理保活，二级返回锚点稳定保留，绝不跳顶 |
| **5** | **Issue #41** | **深色模式下顶部系统状态栏白底突兀缺陷**<br>（将刺眼的白底黑字改为图 1 示例的纯黑沉浸白字） | 🏆 **实测 100% PASS，已合入 main**<br>真实交互终端页状态栏彻底变为沉浸 `#070B14`，系统图标纯白高亮，完全对齐图 1 |
| **6** | **Issue #31** | **二级会话收藏平滑滑动置顶动效**<br>（去除丑陋大星星与摇晃弹簧，纯净滑动置顶） | 🏆 **实测 100% PASS，已合入 main**<br>卡片顺畅滑到位移首位，冷启持久化验证，无大星星与弹簧 |
| **7** | **Issue #35** | **一级工作区长按置顶功能生效与 Liquid Glass 菜单**<br>（功能真实生效，菜单与深浅主题严格一致） | 🏆 **实测 100% PASS，已合入 main**<br>深浅自适应 Liquid Glass 面板，平滑置顶首位，force-stop 冷启持久化，取消置顶归位 |
| **8** | **Issue #21** | **前台会话终端完全假死 / 停止响应**<br>（手势可用但文字停止更新、无法看历史） | 🏆 **实测 100% PASS，已合入 main**<br>Reflow Barrier 超时强制解封看门狗，TermSurfaceView 绘制子线程自愈，防死锁 |

---
- **纪律**：纯编排调度，禁止亲力亲为、禁止读业务代码、禁止查看终端、纯消息驱动、严禁轮询。
