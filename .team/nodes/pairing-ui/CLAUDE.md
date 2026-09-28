# 知识基底 · pairing-ui（系统编译产物）

## 0. 任务（taskbook.yaml#pairing-ui）
- 目标：配对 UI：扫码（读服务端 QR 的 JSON `{"v":1,"url":"ws://…/ws","token":"…","ts_authkey":""}`）+ 手填地址/token 兜底 + TS token 填写入口（仅入口占位，接入归 app-tsnet）。配对成功 = 连上服务端 auth 通过 → 存配置 → 进工作区列表。
- 验收（exit 0 = 过）：`bash -lc 'cd /Volumes/nvme/Projects/远程Agent安卓/app && ./gradlew -q :app:testDebugUnitTest --tests "*Pairing*"'`
- 写范围：`app/app/src/main/java/**/pairing/`、`app/app/src/test/`、`app/app/src/main/java/**/service/ServiceWire.kt`（仅接线扩展）、MainActivity/AgentMirrorApp 路由、`app/app/build.gradle.kts`（仅加依赖）、AndroidManifest（仅相机权限）。红线：conn/termview/session/workspace 实现不动；发现缺口报 leader。

## 1. 架构基
- 分层：`PairingViewModel`（纯 JVM：QR JSON 解析/校验、手填表单校验、连接试配对状态机、配置持久化接口）+ Compose 屏（扫码 view + 表单）。单测打在 ViewModel（--tests "*Pairing*"）。
- 扫码选型：**零 GMS 依赖**（008 开源自托管精神 + fg-service 已确立零 Google 服务先例）——ZXing core（Apache-2.0）+ CameraX 分析流，或 journeyapps zxing-android-embedded（Apache-2.0）。禁 ML Kit（闭源 GMS）。
- 配置持久化：SharedPreferences/DataStore 存 {url, token}（token 存储注明明文风险与后续 Keystore 改进 TODO；不打日志）。
- **接线职责（session-ui 沉淀的欠账，本任务清偿）**：配对成功后经 `ServiceWire` 注入：①真实传输工厂（若 conn 层只有测试用假 transport，盘点后报 leader 裁定在哪补真实 OkHttp WebSocket 实现——不要自行越界写 conn/）；②上传 baseUrl（从配对 url 推导 http(s) 基地址供 HttpUrlConnectionUploader）；③`uiConnector` 多槽扇出若仍单槽，属 ServiceWire 接线扩展在你 scope 内。
- 首启路由：无配对配置 → 配对页；有 → 直进工作区列表；配对页可从设置重进（重新配对）。

## 2. 现场基
- 服务端 QR 内容契约与打印实现见 `server/internal/pairing/qr.go`（已交付，JSON 结构以它为准——先读）。
- session-ui 沉淀必读：`.team/nodes/session-ui/CLAUDE.md` §5（ServiceWire 扇出约束、上传 baseUrl 欠账原文）。
- **共享编译单元纪律**：naming 席位并行施工（改 applicationId/build.gradle.kts 单行）——每次落盘保持 :app 可编译；build.gradle.kts 冲突概率低但拉取前先看 git status。
- 构建 `bash -lc`；compileSdk 36。

## 3. 需求基（指针）
1. requirement-base/entries/007-联网模型-tsnet与扫码.md（扫码即连的产品裁定）
2. requirement-base/entries/003-对话体验四标准.md（配对失败必须明确报错——静默失败最高罪）
3. docs/protocol.md §3（auth 握手）、§9（token 纪律：不落日志）

## 4. 经验基
- 红测先行：QR JSON 缺字段/坏版本拒绝、手填非法 url 校验、auth 失败明确报错、配对成功持久化并路由、token 不进日志，各一条。
- 注释红线、净化前缀照旧。

## 5. 沉淀区（唯一允许你追加写入的区域）

- **2026-08-09 pairing-ui 交付**：配对 UI（扫码+手填+TS token 占位）13 测全绿，全量 153 测 0 回归，全量门 594 用例 pass。
  - `PairingViewModel`（纯 JVM）+ `PairingScreen`（Compose：CameraX 分析流 + ZXing 解码 + 手填表单 + TS token 占位禁用入口）+ `PairingRoute`（生产接线）+ `QrPayloadParser`（对齐 server qr.go 契约）+ `PairingConfigStore`（SharedPreferences 存 {url,token}，token 明文风险 TODO 待 Keystore）+ `deriveUploadBase`（ws→http，欠账②清偿）。
  - **传输欠账①清偿（leader 裁定 A）**：service 包新增 `OkHttpWebSocketTransport` 实现 conn 的 `WebSocketTransport` 接口 + `OkHttpTransportFactory` 注入 `ServiceWire.transportFactory`（默认从 Noop 切 OkHttp）；Noop 保留为测试/降级。5 测（MockWebServer）：连接成功/auth 往返/binary 透传/服务端关闭/拨号失败。conn/ 包一行未动。
  - **上传 baseUrl 欠账②清偿**：配对成功后 `ServiceWire.uploadBaseUrl = deriveUploadBase(wsUrl)`，SessionViewModel 上传地址从此不再 null。
  - **uiConnector 单槽现状**：当前仅单屏在屏（SessionRoute 挂一槽），未升级多槽；若未来需多屏同挂再扩展（本轮无需，报 leader 已知）。
  - **首启路由**：无配对配置→配对页（可跳过进空工作区）；有→直进工作区；重配入口从设置进（AgentMirrorApp 的 showPairing 开关）。
  - **试配对架构**：配对用**独立** ConnectionManager（注入 ServiceWire.transportFactory 建），不碰 ServiceWire 常驻 manager；配对成功才 `setConfig`+注入 uploadBaseUrl+切路由。
  - **陷阱记录**：①google maven **无 camera-bom**（1.4.x/1.3.x 全 404，BOM 从未随该系列发布），camera artifact POM 自带同版本互 pin，须显式按版本声明；②MockWebServer 4.12 无 `MockResponse.Builder`/`mockwebserver.WebSocketListener`，用 `MockResponse().withWebSocketUpgrade(okhttp3.WebSocketListener)`；③客户端 close() 后 MockWebServer 服务端必须 onClosing 回 close 完成握手，否则 onClosed 不触发且 tearDown 挂住；④VM 重置/重配对须先置 Idle 再停旧探针，否则旧 stop 的同步 STOPPED 回调误报"拒绝"；⑤naming 席位移行改 build.gradle.kts，落盘依赖后必须 `./gradlew -q :app:compileDebugKotlin` 自检（共享编译单元教训：依赖解析也要过）。
  - **交接缺口（报 leader）**：ServiceWire 的 manager 仅在 `MirrorForegroundService.onCreate` 创建；配对成功注入 setConfig 后需由接线层/App 启动服务（或配对页直接 manager.start）常驻连接才建立——本轮 PairingRoute 只注入配置未启动服务，工作区页的 manager 由 fg-service 首次 onCreate 时建（当前 session-ui 的 SessionRoute 里 `ServiceWire.manager()` + `start()` 兜底）。
