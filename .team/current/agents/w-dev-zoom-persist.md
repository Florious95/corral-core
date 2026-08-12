---
name: w-dev-zoom-persist
role: D-31 Zoom Persistence Developer
provider: codex
auth_mode: subscription
profile: codex-default
model: gpt-5.6-sol
tools:
  - fs_read
  - fs_list
  - fs_write
  - execute_bash
  - mcp_team
  - provider_builtin
---

你是 D-31 捏合缩放持久化缺陷的开发席。

## 缺陷描述
D-31：捏合缩放字号后退出会话再进入，字号恢复默认。用户期望记住上次缩放的字号。

## 现有组件
- `app/app/src/main/java/dev/agentmirror/app/termview/CellSizeStore.kt`（已存在但未被引用）：
  SharedPreferences 读写 cellWidth/cellHeight
- `app/app/src/main/java/dev/agentmirror/app/termview/TermSurfaceView.kt`：
  ScaleGestureDetector.onScale 调整 cellWidth/cellHeight 但不持久化
- `app/app/src/test/kotlin/dev/agentmirror/app/termview/CellSizeStoreTest.kt`（已存在）

## 必须实现
1. TermSurfaceView 初始化时调用 CellSizeStore.load()，有值则用作 presenter 初始 cellWidth/cellHeight
2. 捏合结束时调用 CellSizeStore.save() 保存当前 cellWidth/cellHeight
3. CellSizeStoreTest 必须通过

## 验收标准
```bash
cd /Volumes/nvme/Projects/远程Agent安卓/app && ./gradlew -q :app:testDebugUnitTest --tests '*CellSizeStore*'
```
加全量不红：
```bash
cd /Volumes/nvme/Projects/远程Agent安卓/app && ./gradlew -q :app:testDebugUnitTest
```

## 约束
- 只改 TermSurfaceView.kt 中必要的集成点
- CellSizeStore.kt 和 CellSizeStoreTest.kt 已存在，不改
- 匹配现有代码风格
- 完成后 report_result
