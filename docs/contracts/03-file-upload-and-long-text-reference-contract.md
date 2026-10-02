# 03 · 文件与超长文本上传、终端路径引用契约

> 状态：权威跨端适配规范；复用现有 HTTP `/upload` 和 WebSocket v1 `input`，不新增帧、能力位或版本。
> **最新用户裁定：仅总长度 ≥2,000 UTF-16 单位或 ≥100 行转文件。单次插入 >100、几百字粘贴及旧4,000门槛均不是当前规则。**
> MUST / MUST NOT / SHOULD 表示必须 / 禁止 / 应当。iOS、Desktop、Web、Android 使用同一判据；跨端指南是待适配要求，不代表这些平台已经实现。

## 0. 契约完全体与职责

| 文档 | 权威职责 |
|---|---|
| [protocol.md](../protocol.md) | 配对、WS v1 信封、ref/订阅、输入回执、二进制镜像与 HTTP 上传 |
| [01 · Agent 通知](01-agent-notification-contract.md) | `notifications_v1` 能力交集、host/stream/通知身份、分页恢复、本地 IPC；不能用通知正文输送文件内容 |
| [02 · 桌面滚轮与裸输入](02-desktop-mouse-wheel-and-raw-input-contract.md) | 滚轮两通道、含 ESC 裸字节原子注入；不得因文本文件功能拆分或停用该路径 |
| 本文 | 通用文件上传、客户端文本分流、路径安全引用、失败保护和跨端验收 |

服务端保存文件并返回宿主路径；**客户端**判定文本阈值、保留草稿、选择上传/输入路线；Agent 自主 `grep`、`head`、`tail` 或分段读取。HTTP200、`input_ack.ok=true`、Agent已读取是三个不同事实。不得默认全量 `cat`、把正文重新灌入终端、加入另一个多模态 API 或要求服务端读取并展开文件。

## 1. HTTP 端点、认证与正文

- `POST /upload`，与 `/ws` 使用同一已配对服务的 host/port；9900只是部署示例，不得硬编码。`ws→http`、`wss→https`；正确保留 IPv6 authority，从当前确认的端点构造 `/upload`，不使用旧地址快照。
- 每次请求 MUST 携带 `Authorization: Bearer <pairing-token>`，与 WS `auth` 使用同一个 `TokenValidator`。不是 URL/query token，不是通知 IPC 权限。不要向其它 authority 转发 Bearer 或盲目跟随跨主机重定向。
- `Content-Type: multipart/form-data; boundary=<boundary>`；客户端规范发送**一个文件段**：

```text
--<boundary>\r\n
Content-Disposition: form-data; name="file"; filename="upload-text-<uuid>.txt"\r\n
Content-Type: text/plain\r\n
\r\n
<exact UTF-8 bytes>\r\n
--<boundary>--\r\n
```

上面 `\r\n` 是 multipart framing，不是给文本正文添加换行。正文 bytes 独立计数、不得被 framing 改写。文件段必须有非空 filename；当前服务端取第一个带 filename 的 part，字段名不是解析条件；跨端统一使用 `file`，不得依赖多文件一次上传。

- 自动文本附件：`upload-text-<uuid>.txt`，MIME `text/plain`，UTF-8 **不加 BOM、不 trim、不补尾部换行、不规范化 Unicode/CRLF**。明确的 Markdown 文件可用 `.md` / `text/markdown`，保留原始 bytes；图片继续使用原 MIME/扩展名。服务端目前不按 MIME/扩展名做图片白名单或文本转码；它保存文件 bytes，而不是验证“这个文件能被哪个 Agent 解码”。
- 文本可直接包装为内存 Data/bytes 的 multipart 文件段，不要求先写客户端临时磁盘文件。拖入真实文件时上传原文件 bytes，不把二进制误解码成文本。
- 网络与大文本编码不得阻塞 UI 主线程；上传中提供轻量状态并阻止重复发送同一捕获草稿。进度百分比不是服务端协议字段，可用本地发送进度或 Loading。

## 2. 成功路径、命名与资源边界

HTTP200，`Content-Type: application/json`：

```json
{"path":"/home/example/Downloads/agentmirror-uploads/upload-20261002T120000-upload-text-01234567-89ab-cdef-0123-456789abcdef.txt"}
```

- `path` 是**服务器宿主文件系统绝对路径**，不是客户端路径、下载 URL 或工作区相对路径；客户端 MUST 使用响应值，不能自行拼接时间戳/目录，也不能对路径做 URL 编码后注入。
- 客户端只命名 `upload-text-<uuid>.txt`；服务端清洗 basename、前置 UTC `upload-YYYYMMDDTHHMMSS-`，冲突可加额外唯一后缀，名称可截断以满足255字节组件限制。完整示例名是结果，不是客户端需再次前置的格式，更不是可依赖的解析 API。
- 默认目录 `$HOME/Downloads/agentmirror-uploads`；daemon 可配置上传目录。按需创建目录权限0700、文件0600，读取者是宿主同一授权用户/Agent，不等于所有用户可读。
- 单文件默认20 MiB（可由服务端配置）；目录常规文件容量默认1 GiB。可测量目录中，服务端串行执行容量检查+写入，越界拒绝507，不自动删旧文件。
- **现有实现边界**：目录读取因权限失败时，服务端记录 `upload_dir_unreadable` 并继续最后可写路径，此时无法执行目录容量测量；不要把1 GiB描述成此异常下仍绝对可执行的保证。其它测量错误拒绝507。
- HTTP响应目前只有 `path`，没有文件hash/size/idempotency-key/下载链接。测试应独立读取宿主 bytes 做hash比对；客户端不得等待不存在的字段。
- 无分片/续传/远程删除/事务回滚 API。重复 POST 可以生成不同文件；断网或用户取消后可能已落盘，不能假定“没收到响应就没文件”。清理既有文件由宿主用户管理。

## 3. 错误与草稿保护

| HTTP | 当前响应 | 客户端处理 |
|---|---|---|
| 401 | JSON `{"code":"unauthorized","reason":"..."}`，`WWW-Authenticate: Bearer` | 保留草稿；提示重新确认配对，禁止无穷重试/回显token |
| 400 | 无效multipart、缺文件、空文件、读取错误通常为文本；无效目录为 JSON `code=upload_dir_invalid` | 显示有界错误，保留草稿；不假定所有400都有JSON |
| 405 | 文本，`Allow: POST` | 修正方法，不丢草稿 |
| 413 | 文本 `file exceeds size limit` | 提示文件上限；不改为将原文塞进WS |
| 507 | JSON `storage_limit_exceeded` / `upload_dir_unavailable` / `upload_dir_unreadable` / `upload_write_failed` + 非空reason | 保留草稿，提示宿主清理/目录权限问题；不能仅解释为单文件太大 |
| 其它非2xx/超时/网络错误/非法成功JSON | 不保证结构 | 明确失败，保留原草稿及期间新编辑，不注入文件路径 |

HTTP错误、响应和日志 MUST NOT 回显token或敏感请求正文；目录故障不得泄漏绝对目录位置。客户端只记录状态/错误类别/有界诊断，不记录整段文本或配对凭据。未知错误不能冒充已上传。

## 4. 统一阈值与编辑时分流

### 4.1 精确计量

```text
fileDraft(text) := UTF16Length(text) >= 2000 OR LineCount(text) >= 100
```

- 长度是 UTF-16 code units：Kotlin `text.length`、Swift `text.utf16.count`、JavaScript `text.length`。**不是**UTF-8字节数或 Swift `String.count` 的字素数。emoji/代理对可能计2单位；上传大小另按UTF-8 bytes计。
- 空文本视为1行；CRLF作为一个换行，单独CR/LF也作为换行，末尾换行增加最后空行。只为计数解析，写入文件时保留原换行。
- 1,999单位且99行：普通文本；恰2,000单位或恰100行：文件。单次粘贴101/200/500单位不足门槛时，MUST 保持普通文本，不允许用“批量来源”或旧>100规则锁成文件。

### 4.2 编辑事件与直通

- 在 Clipboard paste、IME batch/commit、文本drop后的**完整候选文本进入 diff-sync 前**判断。总量达到门槛就不发送该次原文/击键/裸bytes，不先上行再决定上传；输入法组合期仍遵循原有不发送未提交组合文本的规则。
- 不用 `adb input text` 的多个逐键事件冒充单次 Clipboard/IME batch。对未来逐键输入不能预知最终长度：普通输入仍实时，逐键累计达到门槛后才停止新增原文上行。阈值前的已知前缀是允许的历史直通，不是要求回滚过去网络事件。
- 从超长草稿编辑回 <2000且<100行，恢复普通文本路线；**没有低阈值批量latch**。快捷命令/程序化替换遵循同一总量判据，短命令行为不变。
- 关闭inputSync时普通文本本来留本地、发送时一次提交；文件阈值相同。不得为了避免超长原文而把全局普通直通关闭或添加强制防抖延迟。

## 5. 发送事务、路径引用与回执

1. 点击发送捕获不可变文本、host/endpoint、配对身份及目标ref。只在READY且已订阅目标会话时发送；上传中切换主机/ref不能把旧host路径投到新host。不要从新编辑框重新读取并覆盖捕获的内容。
2. 普通文本沿既有输入规则。文件草稿把捕获文本原样UTF-8上传；失败时不发送原文/路径，不清原草稿。没有“上传失败退回终端全量正文”的回退。
3. 成功后校验 `path` 为非空Unix绝对路径且无CR/LF（应拒绝其它终端控制字符）；不能只信任HTTP200。保留路径与目标关联；同一host正常地址更新不改变文件所属host。
4. 若此前存在本客户端已同步的普通前缀，只清理**已知自己的**CLI输入前缀，再放引用；不要按文件全文长度清理，不用Ctrl-C/Ctrl-U/清屏或解析 `[Image #N]` 来猜测并删除其它内容。行尾/单写者前提不成立时应提示而不是破坏他人输入。
5. 安全单引号规则：整个路径外围加 `'`，路径内每个 `'` 替换为 `\'`并不够；必须替换为 **`'\''`**（关闭引号、转义单引号、再开引号）。例：

```text
/home/example/log files/user's.txt
→ '/home/example/log files/user'\''s.txt'
```

不得用原路径拼接shell命令、反引号或 `$(...)`；不要自动 `cat`。该转义是Unix-shell引用约定，目标Agent可自行引用路径检索，不意味着文件应作为可执行脚本运行。

### 5.1 不带图片的提交（推荐同帧一个CR）

`input.text` **非空不会自动Enter**。仅发引号路径会停留在CLI草稿，客户端须明确提交：路径末尾追加一个CR (`\r`)，或路径输入成功后再发裸Enter；二选一，不得重复。

```json
{"v":1,"type":"input","payload":{"req_id":7,"ref":"s1","text":"'/home/example/uploads/upload-text-example.txt'\r"}}
```

### 5.2 同时存在图片

图片仍按既有 `attach_preview.path` 独立预贴；最后一次图片路径放 `input.attachment_path`，`text` 只放文本文件的安全引用，**不再加CR**（服务端此分支负责一次Enter与settle）：

```json
{"v":1,"type":"input","payload":{"req_id":8,"ref":"s1","text":"'/home/example/uploads/upload-text-example.txt'","attachment_path":"/home/example/uploads/upload-image-example.png"}}
```

`.txt/.md` MUST NOT 冒充 `attachment_path` 或调用图片 `attach_preview`，否则会进入图片识别/沉降分支。图片路径不可混在同一次预贴文本中。

### 5.3 成功层级与草稿清除

- `input` 使用新非零req_id、当前已订阅ref；`(text/attachment_path)`、`keys`、`bytes` 三组互斥。JSON由标准serializer转义；路径引用只是其中的字符串，不另造上传WS帧。
- 提交已入发送队列才可清本地框，且只清仍与捕获文本一致的草稿；期间新增 `EDIT...` 不得被成功返回覆盖。上传失败或提交未入队必须保留。
- `input_ack.req_id`对应本次input；`ok=true`仅表示已进入pane，失败/超时须可见，不能宣称Agent已读取。SHOULD 保留已上传路径供明确人工重试；不要盲目自动重传/重复Enter（当前服务端不提供exactly-once输入或上传事务）。

## 6. 多端接入指南

### 6.1 iOS / SwiftUI

- 在SwiftUI输入栏绑定完整文本；需要可靠paste/IME时在UIKit coordinator的编辑回调拿到候选值/提交状态，先判门槛再发按键。使用 `text.utf16.count`，不是 `text.count`；不得把几百字paste当文件。
- 捕获文本快照，异步构造 `Data(text.utf8)` 和multipart；`URLSession`向当前host `/upload` POST、设置boundary和Bearer，处理JSON与文本错误两类响应。大文本编码/网络不在MainActor阻塞。
- MainActor显示Uploading/错误，调用同一WS连接提交§5路径；成功清框须比较当前text与捕获text，失败/取消/期间编辑保留。不要因iOS后台限制杜撰额外HTTP输入接口或声称后台WS永不挂起；通知恢复另遵循01。

### 6.2 Desktop（AppKit / Electron / 其它原生）

- paste/drop得到候选整段文本后先判总量，再决定普通input或文件，不先通过SwiftTerm/raw输入把整个大段发出去。AppKit/Swift同样用UTF-16计数；Electron/JS可用String.length。
- 文本拖入输入栏按同一2000/100行规则。真实文件drop是明确附件上传意图，上传该文件原bytes、保留类型/扩展名；不要把PDF/图片解码成字符串，也不自动读取全文到Agent上下文。
- 现有滚轮/鼠标/快捷键不走文本草稿判定。尤其02要求含ESC的 `input.bytes` 原子注入，不能因新增文件路线合并、拆割或停用滚轮。
- WS输入请求/回执仍走同一发送管理器；文件路径所属host/ref、编辑快照和连接变化由UI事务管理，不新建独立通知/文件WS。

### 6.3 Web

- 使用paste/beforeinput/drop/composition提交入口的候选完整text；普通input继续即时同步，只拦达到总量门槛的编辑。不在用户意图之外读取Clipboard。
- `new Blob([text], {type: 'text/plain'})`/UTF-8 bytes + `FormData`（显式filename）；使用FormData时让浏览器生成boundary，勿手写一个不匹配的Content-Type。
- Bearer/CORS/https等需部署环境明确允许；本契约没有新增CORS或公开下载端点，不保证任意网站可访问宿主。不要把设备的本地文件路径当作返回的host路径。

## 7. 跨端独立验收清单

| 触发 | 独立预期与观察 |
|---|---|
| 逐键短20、真实Clipboard200/500/1999（<100行） | 不上传；开启sync时pane实时出现原文，发送沿原文本路线；关闭sync时发送前无input |
| Clipboard恰2000、5000或100行 | 发送前pane不新增该次原文、无upload；点击发送后只有文件引用，宿主文件bytes与原始UTF-8/SHA完全一致 |
| 1999→2000逐键累计 | 仅已有≤1999前缀历史直通，越界后的唯一marker不得出现；发送清已知前缀，只提交完整文件引用 |
| 2000编辑回500/99行 | 恢复普通text，不残留“>100批量已锁定”的文件路线 |
| CRLF、尾部换行、中文/emoji、路径空格/单引号 | 阈值计量一致；文件不转码/截断/补换行，引用安全，不产生多余Enter |
| 401/413/507/500/断网/非法path | 草稿完整保留、错误可见、不把原文改走WS、不虚报文件路径 |
| 上传中继续编辑、切host/ref、重复发送 | 新稿不丢、不重复上传/Enter、不跨host注入旧路径 |
| 图片 + 文本文件 | 原图片preview/settle不变；txt不preview，仅独立引用；01通知与02裸滚轮仍正常 |

门禁必须区分真实Clipboard/IME事务、逐键ADB装置、HTTP事件、pane原文/路径、实际宿主bytes和画布可见性。编译/单测或“upload events为空”不能替代pane/文件观察；不可判条件如画布加载应单独记录，不能伪装成全场景PASS。

## 8. 参考定位与适配边界

- 服务端：[upload.go](../../server/internal/api/upload.go)、[ws_handler.go](../../server/internal/api/ws_handler.go)、[Input字段](../../server/internal/protocol/frames.go)、[资源默认值](../../server/internal/api/options.go)。文档补充不会自动部署新服务端或改变HTTP存储实现。
- Android参考仓库 `Florious95/corral-app`：`app/.../session/TextFileDraft.kt`、`SessionViewModel.kt`、`SessionScreen.kt`。新总量判据源码冻结 `cae23b0b3f3fe2ea6004450645d7f8efac909bf9`；旧2b6d5的>100规则已废止。该冻结当前为本地验证状态，不能用未更新的远端PR头冒充它。
- 原9914隔离实机的文件bytes/失败保留/图片证据验证上传链路，**不能**直接替代新2000/取消100门槛的客户端复验；也不是本次功能已部署生产9900的证据。iOS/Desktop/Web须执行本节新判据后自行交付，不把指南当实现回执。
