---
type: technique
slug: unified-session-naming
aliases: [统一会话命名, 会话名称收束, tmux左右名称优先级]
created: 2026-09-13
updated: 2026-09-13
sources:
  - requirement-base/entries/101-统一会话命名与旧实现收束.md
tags: ["#layer/technique", "#domain/corral", "#status/draft"]
importance: 4
status: draft
---

# 统一会话命名算法 v1

## 摘要

**有效非工程左侧名 > 有效非工程右侧分段名 > 工程名 > 未命名会话。** 所有 Provider 共用同一算法，服务端统一计算，客户端只显示结果。

本页是算法唯一现行定义；[需求 101](../../../requirement-base/entries/101-统一会话命名与旧实现收束.md)保存用户出处、旧实现清理表和验收要求，不复制第二份算法。用户明确的是输入来源、过滤无信息名称、左右优先级、工程名最低及取消 Provider 特例；以下分隔、比较、噪声表及缺省行为是本 PR 明示的工程细则，随 PR 评审，不冒称用户逐字指定，也不代表运行时已落地。

## 1. 输入、输出与唯一归属

| 输入 | 意义 | 用途 |
| --- | --- | --- |
| `window_name` | 图中左侧 tmux 窗口名 | 一个完整候选，不拆分 |
| `pane_title` | 图中右侧标题 | 按字段分隔符提取多个候选 |
| `cwd` | 当前会话的工作目录 | 识别工程名及最终兜底 |
| `pane_current_command` | 可选的前台可执行文件名，不是 argv | 仅过滤默认进程名，缺失不阻塞 |

输出 `ResolvedName(value, source)`，source 为 `window`、`title`、`project` 或 `placeholder`。value 写入既有协议 `Session.name`；source 可供内部测试/诊断，不要求新增线上字段。

函数不接收 Provider、状态、原生 session_name 或旧显示名。唯一生产实现归服务端公共模块（例如 `internal/sessionname`），由 discovery→protocol 投影调用；App、Web/桌面消费者及 core 协议模块不复制排序算法。

tmux 的窗口名、pane 标题和窗口标记是不同字段。直接读结构化 `window_name` / `pane_title`，不从截图或整行 choose-tree 文本提取，不将 `0:`、树形箭头、窗口 flags 拼入名字。多 pane 窗口必须取各自 pane 的标题，不能把活动 pane 标题套给其他 pane。接口依据：[tmux 手册 NAMES AND TITLES / FORMATS](https://man.openbsd.org/tmux.1)。

## 2. 统一清理与过滤

所有候选使用同一个 `clean`：将空白控制字符变为空格、去掉其余控制字符、去首尾空白、剥成对外围单/双引号，去掉开头连续的状态装饰 `◐ ◓ ◑ ◒ ✳ ● ○ ◌ ✓ ✔` 及紧随空白；外围引号/前导装饰重复处理到稳定。

保留中文、内部空格、显示大小写和有意义的标点，不翻译、不使用 ASCII 白名单，不截断 `C#`、`C++`、`review!`、`node-proxy`。只做显示清理，不据状态装饰判定 working/idle；原始 Title 不被清理结果覆盖。

比较键 `key(text)` 为 `NFC(clean(text))` 的 Unicode 大小写折叠结果，仅用于完整匹配和候选去重，不改变输出大小写或原始路径的分组/寻址语义。禁止子串、模糊或长度优先匹配。

全局仅一份纯数据噪声表，v1 初始完整标签为：

```text
tmux node nodejs sh bash zsh fish dash ksh csh tcsh
ssh sshd login env sudo π claude_code
```

`π` 来自本轮图 2；`claude_code` 来自历史 076 已确认的默认名回归。对所有输入同样查表，不能先识别 Provider 再选词表、解析器或优先级。后续默认标签如需补充，只改这一份数据并增加正反例，不增加 Provider 代码分支。

候选为空、只剩分隔符/外围引号/窗口标记/上述状态装饰、完整匹配噪声表，或完整匹配已提供的当前前台可执行文件名时无效。可执行文件路径只取末段；缺省 command 不参与比较。过滤候选不能过滤会话，不能用于 Provider 识别或判活。

对旧采集器夹带的装饰，仅在噪声判定的比较副本里去掉外围 `[]` 与尾部 `* ! # ~` 再作完整词匹配，所以 `[tmux]`、`node!`、`node!*` 被排除。这个副本不能作为最终显示值，也不能无条件截掉有效名称末尾标点；`review!` 仍保留。真正的结构化输入应将 flags 单列。

名字恰与默认进程标签相同而没有更多元数据时，无法判断用户意图；不为猜测它而恢复逐 Provider 私有存储读取。

## 3. 候选提取与工程名降级

### 3.1 左右字段

左侧 `clean(window_name)` 作为一个候选，**左侧永不按 `-` 或 `|` 拆分**。

右侧先对完整 `pane_title` 做 clean，再从左到右按以下字段分隔符拆分，每段再次 clean：

- ASCII `|`：两侧有无空格都拆分。
- ASCII `-`：两侧均为空白才拆分；统一空白后 Unicode 空白同样适用。

因此 `π - 任务 - 工程` 是三个字段；`fix-login | my-project` 是两个字段，不能把名字内部连字符误拆。不要自动把 `/`、`:`、中文标点或其他横线加入分隔符。该分隔细则是本 PR 的明示工程选择。

候选记录 `text/source/position`；左侧 position=0，右侧 position 为原始段序号。过滤空段及无信息段后，同级右侧候选取最先出现者，不按长度、字母或 Provider 猜测。

### 3.2 工程识别

工程名 `P` 是 cwd 去尾部分隔符后的最后一个目录段；只做词法处理，不访问文件系统、不运行 git、不扫描其他工程。空 cwd、根目录、`.`、`..` 不产生工程名。路径语义沿用既有采集层已确定的宿主平台，不按 Provider 分支。

P 非空且候选完整匹配 P 或去尾部分隔符后的完整 cwd 时，标为工程候选，**降级而非删除**。该判定先于通用噪声过滤，因此工程真的叫 `node`，没有任务名时仍以 `node` 兜底。`my-project-review` 不是工程 `my-project`，不能因子串重合而降级。

## 4. 排序键与伪代码

每个有效候选的排序键为：

```text
(is_project ? 1 : 0, source == "window" ? 0 : 1, position)
```

取字典序最小者。工程标记先于左右来源比较，所以右侧任务名胜过左侧工程名；有效非工程左侧名则始终胜过右侧名。

按同一比较键去重时，只保留排序键最小的候选。去重范围是**一个会话的候选集合**，不是整个会话列表；同名不同 ref 必须保留为两条会话。胜者为工程候选时，返回目录末段 P 而非完整路径；没有候选时先用 P，P 也为空才用常量 `未命名会话`。

以下是算法规范伪代码，不是产品代码已提交或可直接运行的声明。source 使用字符串常量，不能与工程名变量混淆。

```text
resolve(window_name, pane_title, cwd, current_command=""):
    P = lexical_project_basename(cwd)
    executable = executable_basename(current_command)
    candidates = []
    inputs = [(window_name, "window", 0)]
    inputs += [(part, "title", i)
               for i, part in enumerate(split_title_fields(clean(pane_title)))]

    for raw, source, position in inputs:
        text = clean(raw)
        if text == "": continue
        project_only = (P != "" and matches_project(text, P, cwd))
        if not project_only and is_noise(text, executable): continue
        rank = (1 if project_only else 0,
                0 if source == "window" else 1, position)
        candidates.append({text, source, project_only, rank})

    candidates = deduplicate_by_key_keep_smallest_rank(candidates)
    best = minimum_rank_or_none(candidates)
    if best is not None and not best.project_only:
        return ResolvedName(best.text, best.source)
    if P != "":
        return ResolvedName(P, "project")
    return ResolvedName("未命名会话", "placeholder")
```

不使用 tmux session 的团队总名、Provider 字符串或缓存旧名再兜底。可单次扫描选最小排序键，无需排序全部会话。命名成本随当前字段长度线性增长，不随主机总任务量增长；命名本身零额外进程、零正文/私有索引读取、零输入发送、零写入。

## 5. 机械期望样例

所有 cwd 均为合成路径；空 command 表示缺失，不能因此跳过命名。

| 左侧 | 右侧 | cwd | command | 结果 |
| --- | --- | --- | --- | --- |
| `[tmux]` | `解决中转站的问题` | `/work/多agent协作` | `tmux` | `解决中转站的问题` |
| `zsh` | `π - 多agent开发leader - 多agent协作` | `/work/多agent协作` | `zsh` | `多agent开发leader` |
| `node!` | `多 agent leader \| 多agent协作` | `/work/多agent协作` | `node` | `多 agent leader` |
| `smoke-luna` | `多agent协作` | `/work/多agent协作` | `node` | `smoke-luna` |
| `project` | `reviewer \| project` | `/work/project` | `node` | `reviewer` |
| `reviewer` | `architect \| project` | `/work/project` | `node` | `reviewer` |
| `node` | `project - reviewer` | `/work/project` | `node` | `reviewer` |
| `node` | `reviewer \| architect \| project` | `/work/project` | `node` | `reviewer` |
| `node` | `fix-login \| my-project` | `/work/my-project` | `node` | `fix-login` |
| `node-proxy` | `other \| project` | `/work/project` | `node` | `node-proxy` |
| `claude_code` | `✳ 远控 leader` | `/work/project` | `node` | `远控 leader` |
| `future-cli` | `审查任务 \| project` | `/work/project` | `future-cli` | `审查任务` |
| `node` | `project\|reviewer\|reviewer` | `/work/project` | 空 | `reviewer` |
| `project-review` | `other` | `/work/project` | `node` | `project-review` |
| `node` | 空 | `/work/node` | `node` | `node` |
| `zsh` | `π - project` | `/work/project/` | `zsh` | `project` |
| `review!` | `other` | `/work/project` | `node` | `review!` |
| `C#` | `other` | `/work/project` | `node` | `C#` |
| 空 | 空 | 空 | 空 | `未命名会话` |

还须覆盖控制字符、两侧缺省、重复字段、大小写/Unicode、完整 cwd 匹配、状态装饰变化及窗口 flags 分离。全部样例只改变 Provider 标识时结果必须不变。名字仅包含 `node` 或工程名子串时须有反例防误杀。

## 6. 消费、更新与兼容

```text
现有 tmux 元数据 -> 唯一 resolve -> Session.name
  -> listing / list_delta / Level2
  -> App 列表 / 在线收藏 / 查看菜单 / 当前会话顶栏
```

UI 包装函数最多处理统一空值占位，不再从窗口名、原始标题、原生 session_name 或 Provider 重建名字。Name 不反填结构字段；ref 仍是寻址/收藏/状态关联键。原始 Title 与终端镜像不变。

成功采集元数据后重新计算，经现有快照/推送机制发布变化；没有命名专用轮询或永久缓存。同一 ref 改名不制造 add/remove，不重置会话或收藏。已打开顶栏绑定当前 ref 的实时 name，不能一直保留点击时复制的旧字符串。

采集失败沿用既有保留完整快照/过期提示机制，不能伪装成成功的空名称更新；成功但字段确实为空则重新兜底，不能让旧好名字永久压住新结果。

在线收藏复用 live name；离线收藏只显示最近保存的已解析 name 并标离线。旧离线记录没有该缓存时用统一缺省，下次在线回填，不能按旧 Provider 重算。这个缓存是结果副本，不是新命名入口，不改变 FavoriteKey。

沿用既有线上 name 字段。新 App 可以显示旧服务端已有 name，但不能宣称旧服务端符合新算法，也不能为兼容增加客户端排序。验收须使用配套版本；命名迁移不阻断正常连接或终端镜像。

只存在于 Provider 私有存储、没有反映到 window_name/pane_title 的名字不在输入范围内。明确承认输入边界，不恢复逐 Provider 改名接口或文件读取作为兜底。

## Contradictions

[历史 076 §3a](../../../requirement-base/entries/076-底部标签栏落地后的体验收口.md)要求每家 CLI 单独取名，与新需求冲突；由 [101 的替代范围](../../../requirement-base/entries/101-统一会话命名与旧实现收束.md)及 [R-010](../../../requirement-base/REVISIONS.md)仅在命名范围内显式替代。

[历史 077](../../../requirement-base/entries/077-会话页标题仍用旧名与判据改用UI树.md)要求不同界面共用一个名字，继续有效；共用的是服务端结果，不是旧客户端特例函数。

## Connections

- [需求 101](../../../requirement-base/entries/101-统一会话命名与旧实现收束.md) — 用户出处、现有实现清理清单及运行时验收，双向链接。
- [需求修订记录](../../../requirement-base/REVISIONS.md) — 旧命名规则的定向替代，不改写历史原话。
- [需求维基目录](../index.md) — 现行入口。
