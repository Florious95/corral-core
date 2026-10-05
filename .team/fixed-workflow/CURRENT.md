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

### 全局黄金基线状态（全量新特性、契约完全体与生产服务已对齐，权威终极黄金基线！）
- **App 客户端黄金基线**：**Commit `c5ea7a04a9e2833cc494b7845040c4931e03914b`**（PR #49）
  * 终端主题选择页（Settings -> Terminal Theme Picker）彻底消除选后自动返回，点击任意主题实时应用并刷新顶部预览，界面保持停留允许无限次对比切换；
  * 高级视觉模型 Opus 5.5 全量 30 套主题（60 个深浅色槽）自适应消息气泡重构落地，彻底消灭浅色塌缩与深色死板灰；
  * 保持经典原版底部 Dock UI（用户喜爱且最稳定，彻底废止有争议的重构）；
  * 包含 Issue #47 消息中心一级展示全量通知、二级按当前工作区目录严格过滤与专属未读角标；
  * 包含放宽至 2,000 字符 / 100 行的超长文本自动打包上传为文件与终端安全单引号路径引用（Issue #45 / PR #46）；
  * 包含前台服务后台配置自愈（kill 杀进程后系统自然重启自动拉起长连接，100% 自动连通）；
  * 包含切前台 5 秒防误掐死全套保活（持续高频数据流零断开，永不换代）；
  * 包含常驻通知文案稳定对齐（处于 READY 始终稳定显示“已连接”，无端“正在连接…”闪烁彻底消除）。
- **新基线 Tag**：**`baseline-20261005-native-gui-v1`**（Commit `2c219b594`，分支 `feat/issue50-native-gui-session-mode`，PR #53）
  * **用户真机实测定调**：“虽然还有优化点，但是它依然是可用的”；
  * **App 客户端**：全套 Opus 5.5 高保真原生对话流（`ConversationScreen`）、逆向布局防震荡、液态玻璃胶囊底栏（`ConversationDock`）、带弹簧阻尼的 `ToolCallCard`、斜杠补全 Sheet、OkLab 30套主题全色阶对比度算法（正文 $\ge 7:1$，次要 $\ge 4.5:1$）、`MainActivity` 原生分流与设置「会话展示方式」持久化；
  * **Go 服务端**：生产 9900 端口已热更上线（**PID `22168`**，二进制 SHA-256 `dfa618a5bef5c7d578452d03e8b009af7e01ae9f0a5155aff3632e0bd6bb7707`）；支持 `conversation_v1` 协议扩展，具备协议投影瘦身引擎，并包含 7 行 `/tmp` ↔ `/private/tmp` 符号链接规范化修复（彻底消灭 5 秒超时假死）。
  * **官方基线 APK**：`corral-app-opus-gui-baseline-8cb4caa.apk`（SHA-256 `95759945433a86d850a1e7c153cef5f5e3e64eaa7bedc04a1a7cc538a311b881`，115 提取码 `d1eni8mrsffzjx2so` / 通用 `biuhwrjodd9f083ar`）。

---

### 新任务追踪
- **GitHub Issue #50（第二阶段：高保真深化与交互完善）**：[feat(session): 原生 GUI 会话模式全量演进（跨主流 Agent 适配与 Codex/Muse 级高保真体验）](https://github.com/Florious95/corral-app/pull/53)
- **权威设计总卷**：`docs/contracts/04-native-gui-conversation-spec.md`
- **视觉意象总卷**：`docs/contracts/05-codex-muse-visual-reference.md`
- **全景攻坚提示词任务书**：`.team/fixed-workflow/OPUS-GUI-SESSION-MODE-COMPREHENSIVE-PROMPT.md`
- **六大深化交付项**：
  1. Skill 调用大段文本折叠卡片化；
  2. 多行文本原子发送（保留 `\n` 不拆包）；
  3. 回到底部按钮与手势滑到底部防抖防闪；
  4. 顶部浮动条切换模型与思维强度（Effort）；
  5. 原生 GUI 与真实 TUI 同 Pane 进程置换（带运行中警告弹窗）；
  6. 双主题体系全量适配：液态玻璃（Liquid Glass）vs 现代主义（Modernism）。

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
