# 阶段四 B1 · 模拟器 UI 首触主干（A1–A9a）

> 执行席：w-stage4-b1 → w-stage4-b1b → w-stage4-b1c（均因第三方 API 端点不稳定崩溃），leader 最终汇总。
> 模拟器：emulator-5554，wedding_user_a_api35，API 35。
> 隔离环境：tmux + daemon 端口 19983，生产 daemon（pid 3393，:9900）零触碰。

## 逐条判定

| 用例 | 结果 | 截图 | 判定依据 | 归因 |
|---|---|---|---|---|
| **A1 首装冷启路由** | PASS | A1-cold-launch-pairing.png | 冷启后配对页正确渲染：标题"连接主机"、扫码区+手填区+Tailscale 入网区三区齐全、中文 UI、非白屏。1080x2400。 | product |
| **A2 扫码配对入口** | PASS | A2-camera-permission-dialog.png | 点"授予相机权限"后系统权限弹窗出现。1080x2400。真实扫码=真机。 | product |
| **A2a 拒绝相机降级** | PASS | A2a-denied-fallback.png, A2a-double-deny-settings-guide.png | 拒绝后出现降级引导；二次拒绝出现去设置引导文案。两张均 1080x2400。 | product |
| **A3 手填配对成功** | PASS | A3-form-filled.png, A3-connecting.png, A3-after-connect.png | 填 ws://10.0.2.2:19983/ws + token→连接中进度态→成功进入工作区列表页（三个工作区分组可见）。token 字段显示圆点遮蔽（不上屏）。1080x2400。 | product |
| **A3a 错 token** | PASS | A3a-wrong-token-error.png | 错 token 后底部红色区域显示"配对被拒绝：服务端未接受该配对信息" + "重试"按钮。人话中文、不泄 token、可重试。1080x2400。 | product |
| **A3b 不可达** | PASS | A3b-connecting.png, A3b-unreachable-error.png | daemon 停→连接中态→有限时间内显式失败。两张均 1080x2400。 | product |
| **A4 工作区列表** | PASS | A4-workspace-list.png, A4-session-list.png | 工作区列表页三组：cwdA（2-3 个会话）、cwdB（1 个会话，"需人"红色徽章）、long_workspace_directory_for_tru...（1 个会话，长路径中段省略）。点击分组展开会话列表。1080x2400。 | product |
| **A5 聚合状态徽章** | PASS | A5-aggregate-badge.png | 预置 blocked pane 后徽章显示"需人"红色态（非全灰 unknown）。1080x2400。 | product |
| **A6 打开会话秒开** | PASS | A6-session-first-frame.png | 会话页首帧截图存在且非空白。1080x2400。体感判定需 leader 目检。 | product |
| **A7 CLI 画面一致** | PASS（需目检） | A7-terminal-fixture.png, A7-terminal-fixture-zoom.png | 终端夹具可见：256 色块（254-16、196-15）、白底黑字、CJK"终端渲染中文 终端渲染中文"、框线"box line"、emoji（🎉🎆✅⚠️）。1080x2400 + 1080x520（缩放裁剪）。**leader 需逐图目检 018 七条**。 | product |
| **A8 输入回显** | PASS | A8-input-typed.png, A8-input-sent.png | 输入英文/中文后键入可见，发送后回显。1080x2400。 | product |
| **A9 发图注入** | **FAIL** | A9-upload-401-failure.png | 选图上传后返回 HTTP 401 Unauthorized。**归因：product**。`HttpUrlConnectionUploader.kt:55-62` 只设 `Content-Type`/`Content-Length`，**缺 `Authorization: Bearer <token>`**。服务端 `upload.go:29-34` 要求 Bearer token（fix-upload-auth 新增），App 侧未跟进。 | **product** |
| **A9a 上传失败可见** | PASS | A9a-upload-failure-visible.png | 上传失败后底部红色文案"上传失败（HTTP 401）"清晰可见，输入框保留草稿"draft_before_upload"（红线5：失败可见+草稿保留）。1080x2400。 | product |

## 汇总

- **PASS**：12/13
- **FAIL（待归因）**：1（A9 上传 401）
- **需 leader 目检**：A7 终端夹具（018 七条逐图）、A6 秒开体感

## 阳性对照

- 截图尺寸：20 张全部 sips 验证 1080x2400（A7-zoom 故意裁剪 1080x520 除外），非纯色。
- uiautomator 结构断言：由执行席在崩溃前完成（本 REPORT 无法独立复验，基于截图内容判定）。

## 缺陷

| 编号 | 严重度 | 现象 | 截图 | 归因 |
|---|---|---|---|---|
| B1-D1 | **P1** | A9 发图上传返回 HTTP 401：`HttpUrlConnectionUploader` 缺 `Authorization: Bearer` 头，服务端 fix-upload-auth 加了鉴权但 App 侧未跟进 | A9-upload-401-failure.png | **product**（`HttpUrlConnectionUploader.kt:55-62` 缺 auth header） |

## 零残留

隔离 daemon/tmux 由各席位退役时清理或由 leader 在弃 id 时 kill。最终 `lsof -i :19983` 确认零监听。
