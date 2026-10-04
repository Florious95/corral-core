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
- **App 客户端黄金基线**：**Commit `9374df29ccb706a7b946b5d2a909f84c811a5efc`**
  * 高级视觉模型 Opus 5.5 全量 30 套主题（60 个深浅色槽）自适应消息气泡重构落地，彻底消灭浅色塌缩与深色死板灰；
  * 保持经典原版底部 Dock UI（用户喜爱且最稳定，彻底废止有争议的重构）；
  * 包含 Issue #47 消息中心一级展示全量通知、二级按当前工作区目录严格过滤与专属未读角标；
  * 包含放宽至 2,000 字符 / 100 行的超长文本自动打包上传为文件与终端安全单引号路径引用（Issue #45 / PR #46）；
  * 包含前台服务后台配置自愈（kill 杀进程后系统自然重启自动拉起长连接，100% 自动连通）；
  * 包含切前台 5 秒防误掐死全套保活（持续高频数据流零断开，永不换代）；
  * 包含常驻通知文案稳定对齐（处于 READY 始终稳定显示“已连接”，无端“正在连接…”闪烁彻底消除）。
- **Go 服务端黄金基线**：**Commit `017836a43093c93f5d555f30e076e293ed2de5cb`**
  * 引入全局原子单调递增 reflow epoch 分配器，彻底消除重订阅时 delta 流被误杀丢弃的 P0 冻屏死锁；
  * 生产环境 9900 全网卡 `0.0.0.0:9900` 监听，PID `86757` 稳定运行；
  * 本地回环与 Tailscale IP（`100.75.207.88`）双通（`/pair/whoami` 200 OK，WebSocket 握手正常）。
- **多端契约完全体合流入库**：
  * [01 号 Agent 任务通知契约（含全自动身份推导与多端适配）](docs/contracts/01-agent-notification-contract.md)
  * [02 号 桌面滚轮与裸输入防碎割契约](docs/contracts/02-desktop-mouse-wheel-and-raw-input-contract.md)
  * [03 号 文件与超长文本上传及终端引用契约](docs/contracts/03-file-upload-and-long-text-reference-contract.md)
  * 主协议 `docs/protocol.md` §8 与 §9 升级校准。
- **跨端完备交接资产包**：`/Volumes/nvme/Projects/远控-ios/android-notifications-and-pipeline-handoff/`（40 文件全量通过对账核验）。

---

### 新任务追踪
- **GitHub Issue #47**：[feat(notify): 一级入口展示全量通知，二级入口按当前工作区目录过滤](https://github.com/Florious95/corral-app/issues/47)
- **目标文档**：`.team/fixed-workflow/MESSAGE-CENTER-LEVEL1-LEVEL2-FILTERING-GOAL.md`

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
