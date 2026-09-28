# 用户缺陷清单真实状态（2026-08-12 v5 退役时）

> 权威来源：`.team/nodes/librarian-intake/draft-20260812.md` 原始反馈
> v5 APK（有回归不可用）：`~/Desktop/agentmirror-v5-20260812-1530.apk`
> v5 app 侧改动未提交，需先 `git checkout -- app/app/src/main/` 回退

## 待修复（从未真正修复）

| 编号 | 描述 | 说明 |
|------|------|------|
| D-22 | 图片上传永远失败（HTTP 401） | 从未验证 |
| D-23 | 侧滑返回直接退出 App | v5 只拦了返回键，侧滑手势未拦截 |
| D-26 | Agent 工作状态检测准确率低 | 用户报三次失败，服务端修了但效果不足 |
| D-29 | 捏合缩放时闪烁重绘 | 从未验证 |
| D-32 | 返回跳级（3→1） | v5 加了二级导航但 QA FAIL |
| D-35 | bypass 符号**缺省（显示为空）** | **从未修复，v5 错误标记为已修**。⚠️ 本文原写「透明红框」系错误描述，与不可变真相源 `requirement-wiki/raw/046` 原文「状态栏 bypass permissions 前的符号显示为空」不符；交接文档 §3/§4 同错。这是字形/渲染回退问题，**不是配色或透明度问题**，按「红框」改方向即错。2026-08-12 leader 撞库更正 |
| D-36 | 向上滑无法查看历史 | **从未修复，v5 错误标记为已修** |

## v5 改了且 QA PASS（回退后丢失，需重做）

| 编号 | 描述 | v5 改动 |
|------|------|---------|
| D-28 | 捏合缩放右侧溢出 | TermSurfaceView canvas clipRect |
| D-31 | 缩放不持久化 | CellSizeStore 集成到 TermSurfaceView |
| D-38 | 后台返回显示半截 | TermSurfaceView onWindowVisibilityChanged |

## v5 改了但未生效

| 编号 | 描述 | 说明 |
|------|------|------|
| D-21 | 退出不恢复终端尺寸 | server 代码正确有测试，daemon 未重编 |

## v5 引入的回归

| 描述 | 说明 |
|------|------|
| 输入框闪烁重绘 | v2/v4 无此问题，v5 TermSurfaceView 三处改动叠加引入 |

## 已提交已验证（在 git HEAD 中，非 v5 改动）

| 编号 | 描述 | commit 关键词 |
|------|------|---------------|
| D-20 | 键盘遮挡 | ui-redesign "IME 空洞根治"（v5 回退后需重新验证是否仍好） |
| D-24 | 会话别名 | server listing.go WindowName |
| D-25 | TS authkey | feat-ts-wire |
| D-27 | 终端刷新方向 | server no-op resize skip |
| D-30 | 上传填输入框 | upload 不触碰 textFieldValue |
| D-34 | 字体堆叠 | fix-term-glyph-render GlyphFallbackPolicy |
| D-37 | 键条连按 | feat-input-keys-app KeyBar+InputKey |

## 修复流程（CLAUDE.md 已写入）

每个缺陷必须：
1. 模拟器复现，截图留证，**看到问题才开工**
2. basegen 编闭包
3. 三席并行（审查+测试+开发）
4. 红测全绿 + archwiki --strict-t3 PASS
5. 模拟器实测：复现步骤重走，问题消失 + **不倒退**
6. **看到修复才收工**，截图是唯一证据
7. 才打包下一个
