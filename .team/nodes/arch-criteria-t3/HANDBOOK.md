# HANDBOOK · arch-criteria-t3（阶段一施工分身的操作口径）

> 本手册由 arch-criteria-t3 母席编写，供阶段一 18 个包施工席位（fork-agent 分身）
> 与后续收口方阅读。判据的**机器实现**在 `tools/archwiki/build_wiki.py`；
> 这里写**口径**——判据抓什么、不抓什么、为什么这么切、单包硬判怎么跑、改注释时怎么自检。

---

## 1. 标签集含义（判据识别什么）

### T3-1 符号级 doc 覆盖

非测试导出符号必须有紧邻 doc/KDoc：

- **Go 侧**：顶层导出声明（`_GO_TOP_DECL`：`func/type/var/const X`，X 大写开头），
  紧邻上一行是 `//` 或 `/* */` 注释即达标。`package main` 的 `main` 不算导出符号。
  `_test.go` 一律排除。
- **Kotlin 侧**：顶层 public 声明（`fun/class/interface/object/enum class/data class/
  sealed class/val/var/typealias`，大写开头，**列首无缩进**——缩进即嵌套声明，不算顶层）。
  紧邻判定向上跳过 `@` 注解行（标准顺序：KDoc→注解→声明），遇空行即断。`_kt_exports`
  只用大写开头的，所以 `MainActivity` 这种类、`ANSI_COLORS` 这种属性都算。

### T3-2 引用真实性

**全部注释形态**里提到的三类引用必须在仓库中真实存在。扫描面覆盖
KDoc/doc 注释、函数体内普通 `//` 注释、`/* */` 块注释、行尾注释，
Go 与 Kotlin 两侧对齐（含 Kotlin 侧 `--flag` 判定）。

1. **符号名**：反引号包裹的、单个、大写开头、标识符形状的词。
2. **仓库文件路径**：含 `/` 且以已知扩展名结尾的串。
3. **CLI flag**：`--flag` 形式，Go 与 Kotlin 两侧都判。

---

## 2. 判据边界（为什么这么切——宁可漏也不能吵）

核心原则：**判定保守到不误报为止**。T3-2 抓不抓得住 D-14 那类谎报注释，
取决于误报率能不能压到几乎为零——判据一旦开始误报，收口方就不敢信它，
最后只能像 T1-2 一样被当成摆设。

### 反引号引用（符号）只判这些：
- **单个词**：反引号内容不含空白。`remember`、`releaseManager()` 这类小写开头的
  **不判**（是 Compose API / 方法，不是仓库符号）。`tsnet`、`proj`、`send-keys` 不判。
- **大写开头 + 标识符形状**：`MissingClass`、`GhostHelper` 判；`/home/a/proj`、
  `{"v":1,...}`、`'\''` 这种含空白/引号/斜杠的**不判**。
- **排除协议/工具全大写词**：`POST`、`GET`、`WS`、`URL`、`JSON`、`README` 等
  不判——它们是协议或工具，不是仓库符号。完整清单见 `T3_NONSYMBOL_TOKENS`。
- **后缀处理**：`Foo.bar` 取 `Foo`，`Foo<T>` 取 `Foo`，`Foo()` 去尾 `()`。裸 `Foo` 只查一次。

### 路径引用只判这些：
- 含 `/` 且以已知扩展名结尾（`.go/.kt/.md/.py/.yaml/.yml/.json/.sh/.xml/.txt/
  .toml/.gradle/.kts/.proto/.c/.h/.rs/.js/.ts/.html/.css/.aar`）。
- 前向负断言排除 `//192.168.1.5`、`ws://host/…` 这类 URL/网络串。
- 末尾不接字母数字（排除 `Foo.bar` 里 `.bar` 不是扩展名的情形）。
- 解析：根相对存在、已知前缀拼接存在、或**同名文件在仓库某处存在**（兜底放行
  `pairing/probe.go` 这种省 `server/internal/` 前缀的写法）。

### CLI flag 只判这些：
- 任意注释行里的 `--flag` 形式，flag 名小写开头。Go 与 Kotlin 两侧都判。
- 查表 `collect_go_flags`：所有非测试 `.go` 里的 `fs.String("name",...)` 注册 +
  自动 `-h/--help`。`ts-authkey` 是 env-only，不算 flag。
- **外部工具 flag 白名单**：`T3_EXTERNAL_FLAGS = {"tests"}` 放行 `--tests`（gradle
  验收 flag，不是 daemon CLI）——宁可漏不可吵。

### 外部参考路径白名单
`T3_EXTERNAL_PATH_PREFIXES = ("herdr/", "src/detect/manifests/")` 放行指向仓库之外
的路径（如 adapters.go 合规注记里 herdr 的 `src/detect/manifests/claude.toml`）。
这是"宁可漏不可吵"的明确取舍：外部参考实现不属于本仓库，不验。

### 自然语言普通词一律不判。
"设置里有重配按钮"里的"设置""重配按钮"没有明确形状，不判——这正是 D-14 的教训：
**要抓的是"注释里写了具体符号名/路径/flag，但那个东西不存在"**，不是自然语言语义。
D-14 的谎言是语义断言（"见 WorkspaceScreen 顶栏设置钮"——`WorkspaceScreen` 真实存在，
不存在的是它顶栏上那个按钮），静态判据解析不出"某组件里有没有某按钮"。
这一类由**用例**覆盖（PairingUxTest 的重配入口可达性断言），不要求判据覆盖。

---

## 3. 已知的判定盲区（诚实地告诉你）

- **同一包内的私有符号**不查：T3-2 只查导出符号全集（Go+Kotlin 顶层导出）。
  注释引用同包私有函数不会红。这是"宁可漏"的一部分——私有面变化频繁，误报风险高。
- **跨语言符号**：Go 注释引用 Kotlin 类（或反之）能查到（全集索引跨语言），
  但若目标符号在另一侧是非导出/私有，查不到、不红。
- **自然语言里的假引用**抓不住：注释用自然语言描述一个不存在的功能（"设置里有
  重配按钮"而没写 `SettingsActivity` 这种具体符号），T3-2 抓不到。**这是目标设定的
  诚实边界（leader 2026-08-11 确认）**：T3-2 只验"引用形状可判者"，不验语义事实。
  行为性断言（如"重配入口可达"）由**用例**覆盖，判据不为它设保护网。
  所以阶段一改注释时，凡指认代码实体，务必写成**反引号包裹的大写符号**或
  **真实路径**，让引用变成判据可验的形状。
- **路径只查文件存在**：不查目录、不查 glob；引用真实文件名但错误目录
  （如 docs/x.md 实际在 server/docs/x.md）会被基名兜底放行——不误报优先。
- **小写命名的仓库符号**引用不判（反引号符号需大写开头）：如 `tsnet`/`remember`
  这类小写符号写错无法验。这是"宁可漏"的一部分——小写词在自然语言里太常见，误报面大。

---

## 4. 单包硬判怎么跑（每包 acceptance）

阶段一逐包收口时，每个包席位的 acceptance 是：

```bash
python3 tools/archwiki/build_wiki.py --check --strict-t3 --pkg <该包>
```

- **Go 包**：`--pkg` 用相对包路径键，如 `internal/api`、`internal/bridge`。
- **Kotlin 包**：`--pkg` 用包名键，如 `dev.agentmirror.app.conn`。
- 该命令 exit 0 当且仅当**这一包**的 T3-1 + T3-2 都干净。T1-1/T1-2 仍全局判，
  且这包之外的其他包违规**不影响**本包结果（精确到单包）。
- 注意：`--pkg` 时**不会**自动写 t3-report.md（报告是全体扫描的产物）。

验证单包硬判真的会红：造一个含漏 doc 符号的包，指向它必 exit 1；指向干净包必 exit 0。
（fixture `pkg-filter/` 就是干这个的。）

## 5. 改注释时的自检套路（给施工席）

改任何一个包的注释时，过一遍：

1. **每个新增导出符号**都配上紧邻 doc/KDoc（Go 上一行 `//`；Kotlin KDoc→注解→声明）。
2. **注释里提到别的符号**：写成反引号包裹、大写开头、单个词（`Foo` 或 `Foo.bar`），
   并确认该符号在仓库导出面存在。拿不准就查 `build_wiki.py` 的符号索引输出。
   **函数体/行内的普通注释也一样会被扫**——凡指认代码实体，统一用反引号大写符号
   或真实路径，让判据能验。
3. **注释里提到文件**：写完整相对路径（`server/internal/pairing/qr.go`），
   确认文件真的在。省前缀（`pairing/probe.go`）也放行，但完整路径更稳。
4. **注释里提到 CLI flag**：确认该 flag 在 `server/internal/config/config.go` 注册。
   env-only 的（`TS_AUTHKEY`）写成 `TS_AUTHKEY`（大写全称，不是 flag 形状），
   或用反引号包裹描述性名字。外部工具 flag（gradle `--tests`）在白名单内不误报。
5. **跑自检**：
   ```bash
   python3 tools/archwiki/build_wiki.py --check --strict-t3 --pkg <你的包>
   ```
   exit 0 才算过。想列全部违规：`python3 tools/archwiki/build_wiki.py --check --strict-t3`。
6. **别放宽判据**：真仓库此刻 T3-1 有 1 条（`TsnetInterfaceCodec.kt:46`）、T3-2 有 0 条。
   那是当前真实状态，不是判据的错。改注释把该补的补上，判据自然绿。
7. **别指望判据抓语义谎报**：如果注释想断言"某功能存在/某入口可达"，那属于
   行为性断言，判据不验——要么把它写成可判形状的引用（具体符号/路径/flag），
   要么靠用例覆盖。不要以为 T3-2 PASS 就等于"注释全是真话"。

---

## 6. 常见误报与处理

| 现象 | 原因 | 处理 |
|---|---|---|
| 注释引用了 `remember`/`mutableStateOf` 等 Compose API | 小写开头，非仓库符号 | **不是误报**，判据本来就不判——确认你写的是大写符号 |
| doc 里写了 `POST /upload` | 全大写协议词 | 不判，符合预期 |
| doc 里写了 `--tests "*Pairing*"` | gradle 验收 flag，在 `T3_EXTERNAL_FLAGS` 白名单 | 不判，符合预期（那是 gradle 不是 daemon CLI） |
| 注释写了 `src/detect/manifests/claude.toml`（herdr 合规注记） | 外部参考路径白名单 | 不判，符合预期（指向仓库外） |
| 路径 `//192.168.1.5` 被当路径 | 前向负断言排除 | 不判，符合预期 |
| 引用了同包私有函数 | 不在导出符号索引 | 不红（宁可漏）——如需验，把引用写成导出符号 |
| 报告里某包违规该有却没有 | 先查 `--pkg` 键名对不对（Go 用 `internal/...` 相对路径，不是全路径） | 对不上就是键名错，不是漏扫 |

---

## 7. 三档开关速查

| 开关 | 行为 | 用在哪 |
|---|---|---|
| （默认） | T3 列清单、**不改变退出码**；不写报告（真实仓库） | 常规 `--check` 保持 T1 绿、exit 0 |
| `--strict-t3` | T3 违规计入退出码（exit 1） | 全仓库硬判 / 阶段收口 |
| `--pkg <包名>` | 只扫该包（T1 仍全局） | **每包 acceptance** |
| `--t3-report <路径>` | 写 T3 markdown 报告（真实仓库默认落 `docs/wiki/t3-report.md`） | 阶段收口留痕 |

报告模式的红线提醒：`build_wiki.py --check` 对真实仓库**默认就会打印 T3 FAIL 行**
（真仓库此刻就有 1 条 T3-1 违规），但 exit 0——这是**有意**的分级，不是判据失效。
T1-1/T1-2 必须永远 PASS、exit 0；T3 的硬判只在 `--strict-t3` 时介入。

---

## 8. 判据强度与阶段验收的关系（为什么别手软）

下一阶段刷 18 包注释，注释改动零测试覆盖。T3-1 保证"每个导出符号都有 doc"，
T3-2 保证"注释里写的引用是真的"（**全部注释形态**，Go 与 Kotlin 两侧对齐）。
这两条就是那阶段唯一能自动验收的东西。
**判据弱一分，那阶段就退化一分**——所以判据边界宁可保守在"只抓明确形状"，
也**绝不能为了真仓库好看而放宽**（比如把 T3-2 的 flag 判定砍掉）。
当前真仓库 T3 违规数（T3-1=1, T3-2=0）是现状，收口方补注释后自然清零。

**但判据也有天花板**（leader 2026-08-11 确认）：T3-2 只验"引用形状可判者"，
不验语义事实。D-14 的谎言是自然语言语义断言，静态判据解析不出"某组件里有没有
某按钮"——那一类由**用例**覆盖（PairingUxTest），不是判据的保护网。不要因为
判据抓不住语义谎报就认为它没用：它抓的是**具体引用撒谎**（写了不存在的符号/
路径/flag），这正是注释"引用层面"的契约。施工席只要把指认写成可判形状，
判据就能验。

---

## 9. 阶段二：契约标注写法与 T3-3 / T3-4 自检（arch-criteria-t3-contract 扩充）

阶段二给关键符号补契约。判据两条，**机器实现**在 `build_wiki.py`（复用
`_all_comment_lines` 提取器，不另起炉灶）。这里写**写法口径**——怎么标、判据抓什么、
自检怎么跑。

### 9.1 标签集（docs/next-round-plan-20260810.md §3.1，本工程自定）

| 标签 | 语义 | 写在哪 |
|---|---|---|
| `@contract` | 该符号有契约（前提/后果/错误/不变量） | 符号级 doc/KDoc 注释块 |
| `@pre` / `@post` | 前置条件 / 后置条件 | `@contract` 符号必须带 |
| `@err` / `@inv` | 错误语义 / 不变量 | `@contract` 符号必须带 |
| `@consumes` | 本包消费了哪个包（跨层依赖声明） | **包级** doc/KDoc |
| `@produces` | 本包提供面（预留，当前不判） | 包级 doc/KDoc |

- Go 写在 doc 注释里，Kotlin 写同名 KDoc 标签，两侧判据完全一致。
- `@label` 后可跟 `:` 或空白再写值；值可写任意文本，判据只认**标签在不在**。

### 9.2 T3-3 契约标签完备（抓什么、不抓什么）

**抓**：凡标了 `@contract` 的符号，`@pre` / `@post` / `@err` / `@inv` 四标签必须齐全。
允许显式写 `none`（表示"确无此项"），但不许缺项——缺项即"契约半成品"，
比没有契约更坏，因为读者会以为契约已经定好了。

**不抓（诚实边界，不得暗示它有）**：`@post` 写的内容是不是真的、`@err` 描述的
错误语义对不对——那是**语义事实**，静态判据判不了，那一面由**用例**覆盖。
判据只保证"标签齐了"，不保证"契约内容真"。

写法范例（Go）：
```go
// Div 做整数除法。
// @contract
// @pre b != 0
// @post result * b <= a
// @err 除零错误
// @inv a、b 不变
func Div(a, b int) int { return a / b }

// NoInv 无错误面，显式 none 是合法齐全。
// @contract
// @pre a > 0
// @post result > 0
// @err none
// @inv none
func NoInv(a int) int { return a }
```

写法范例（Kotlin）：
```kotlin
/**
 * 建立会话。
 * @contract
 * @pre host 非空
 * @post state 为 CONNECTED
 * @err none
 * @inv state 属闭集
 */
object Session
```

**判定细节（写了会红/会绿）**：
- 标签行必须与 `@contract` 在**同一符号的注释块**里（Go doc 空行续接也认；Kotlin KDoc
  行首 `* @label` 都归一块）。**契约判定按符号归属，不按文件**——同文件多个 `@contract`
  符号时，每个符号的四标签只在它自己所属的注释块里查，绝不跨块 union。
- `@contract` 单独一行算标签存在；值可空（`@contract` 无值）仍算标了契约。
- 只查四标签**在不在**，值含不含 `none` 不影响判定（none 是写法口径，判据一律放行）。
- **返工 #1 教训（w-t3c-verify 实证）**：Go 侧曾按「文件」union 标签，同文件
  `Discoverer`（四标签齐）＋`scopedDiscoveryDirsEnv`（缺 @err/@inv）时，完整符号的
  @err/@inv 掩盖残缺符号的缺失 → 漏判。判据现按行号相邻分块（`_group_comment_blocks`），
  每个符号的 doc 块被函数/空行天然隔开。施工席同一文件标多个 `@contract` 时，
  **每个符号各自四标签齐**，不要指望前一个补齐后一个。

### 9.3 T3-4 跨层声明一致（抓什么、不抓什么）

**抓两种不一致**：
1. 包级 doc 写了 `@consumes X`，但该包的 import 图里**没有** X —— 声明的依赖不存在；
2. 包**import 了** X 却没写 `@consumes X` —— 架构漂移（代码在用，声明没跟上）。

import 图用 `build_wiki.py` 既有采集结果（`go_pkgs`/`kt_pkgs` 的 `deps`），
**不重新解析**。Go 声明键用相对包路径（`internal/config`），Kotlin 用 fq 包名
（`dev.agentmirror.app.conn`）。

**不抓（诚实边界）**：`@consumes` 写的是不是**业务上真该依赖**——那是设计语义，
静态判据判不了。它只保证"声明与 import 图一致"。

写法范例：
```go
// Package api 是 WS 服务面，消费 discovery/bridge 的扫描与桥接能力。
// @consumes internal/discovery
// @consumes internal/bridge
package api
```

```kotlin
/**
 * conn 连接层。消费 service 的常驻连接与 tsnet 隧道。
 * @consumes dev.agentmirror.app.service
 * @consumes dev.agentmirror.app.tsnet
 */
```

**判定细节**：
- `@consumes` 值取第一个空白分隔 token（容忍行内尾注）；引号/反引号包裹也剥。
- 目标名解析：Go 相对写法精确匹配已知 Go 包键；Kotlin 精确匹配已知 Kotlin 包名。
  写全路径（`server/internal/x` 这种）不认——宁漏不吵，写相对路径或 fq 名即可。
- **反向漂移（import 了没声明）在真仓库当前必然大面积红**：全仓库 29 条 import 边
  一条都没声明。这是阶段二逐包补 `@consumes` 的收口清单，不是判据误报。

### 9.4 阶段二自检套路（给施工席）

补完一个包的契约，跑：

```bash
python3 tools/archwiki/build_wiki.py --check --strict-t3 --pkg <该包>
```

- Go 包：`--pkg` 用相对包路径键，如 `internal/api`、`internal/bridge`。
- Kotlin 包：`--pkg` 用包名键，如 `dev.agentmirror.app.conn`。
- exit 0 当且仅当**这一包**的 T3-1 + T3-2 + T3-3 + T3-4 都干净（T1 仍全局判）。
- 想列全仓库违规：`python3 tools/archwiki/build_wiki.py --check --strict-t3`。

补契约的核对清单：
1. **标了 `@contract` 就补齐四标签**；没错误面写 `@err none`，没不变量写 `@inv none`。
2. **每个 import 的内部包都补一句 `@consumes`**（包级 doc/KDoc，一个目标一行）。
3. 引用别的符号/路径/flag 仍按阶段一规矩写（反引号大写符号、真实路径、真实 flag）——
   T3-2 照常扫，别在补契约时顺手引入假引用。
4. **别指望判据抓契约内容撒谎**：`@post` 写得对不对由用例覆盖（阶段二配套契约用例）。
   判据 PASS 只等于"标签齐 + 声明一致"，不等于"契约内容真"。
5. **白名单纪律**：T3-3/T3-4 没有白名单、没有排除项。不得为了让某包绿而改判据——
   真仓库此刻 T3-4 有 29 条漂移是现状，逐包补 `@consumes` 后自然清零。

### 9.5 三档开关速查（阶段一表新增 T3-3/T3-4 行）

| 开关 | 行为 | 用在哪 |
|---|---|---|
| （默认） | T3 全部列清单、不改变退出码 | 常规 `--check` 保持 T1 绿、exit 0 |
| `--strict-t3` | T3-1..T3-4 违规计入退出码（exit 1） | 全仓库硬判 / 阶段收口 |
| `--pkg <包名>` | 只扫该包（T1 仍全局） | **每包 acceptance** |
| `--t3-report <路径>` | 写 T3 markdown 报告 | 阶段收口留痕（真仓库默认落 `docs/wiki/t3-report.md`） |

报告模式的红线提醒不变：`build_wiki.py --check` 对真仓库默认打印 T3 FAIL 行
（T3-1 1 条、T3-4 29 条），但 exit 0——这是**有意**的分级，不是判据失效。
T1-1/T1-2 必须永远 PASS、exit 0；T3 的硬判只在 `--strict-t3` 时介入。
