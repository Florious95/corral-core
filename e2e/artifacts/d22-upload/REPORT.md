# D-22 模拟器真实上传验收

模拟器真实上传：成功。

- 设备：`emulator-5554`
- APK：`app/app/build/outputs/apk/debug/app-debug.apk`
- APK SHA-256：`b311ede7f66ef9ae6301783c32936bddd2d1db1c21757e5e109dfbc35e2de526`
- 配对：卸载旧包清态后，通过 App 正常手填连接流程配对到测试席自建隔离 daemon；未手改 SharedPreferences，未触碰生产 daemon。
- 工作区：`/private/tmp/agentmirror-d22-upload/cwd`
- 会话：隔离 tmux 中的 `zsh` 会话。
- 上传动作：点击“＋”→“从相册选择”→系统 Photo Picker 选择真实 PNG。
- 结果：未出现 401；主机隔离上传目录收到 PNG；输入文件与收到文件 SHA-256 同为 `2106e27bafac07bc2585383b3578021f8938119e50836e8a3aa7d22736e97ec6`。
- raw/003 注入语义：成功。上传完成后，输入框自动注入主机侧路径 `/tmp/agentmirror-d22-upload/uploads/upload-20260812T125101-1000000214.png`。

## 证据

- 上传前会话页：`e2e/artifacts/d22-upload/01-session-before-upload-current.png`
- 系统 Photo Picker UI：`e2e/artifacts/d22-upload/document-picker.xml`
- 上传后截图（路径可见）：`e2e/artifacts/d22-upload/02-after-upload.png`
- 上传后 UI 树（路径为 EditText 文本）：`e2e/artifacts/d22-upload/after-upload.xml`
- 主机收到的原文件副本：`e2e/artifacts/d22-upload/received-upload.png`
- 隔离与清理记录：`e2e/artifacts/d22-upload/isolation.txt`、`e2e/artifacts/d22-upload/isolation-cleanup.txt`

证据截图避开配对页与凭据；报告不记录 token 值。
