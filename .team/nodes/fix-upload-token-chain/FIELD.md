# 现场基 · fix-upload-token-chain（leader 手填取证素材）

## 用户今晚（2026-08-12）真机复现，原话

> 「我现在打算把我看到的 APP 上的图片展示给你看，我会发现上传失败，
>  失败的原因写的是 HTTP 401，这是一个新的改动引发。」

用户所用包：`~/Desktop/agentmirror-d35fix-7c56353.apk`（v2 基线 + 仅 D-35 修复）。

## leader 已查实：这不是新改动引发

用户判断为「新改动引发」，**实测不成立**，两条硬事实：

1. 当前 daemon 仍是 **pid 39489，2026-08-12 13:40 启动的那个二进制**，
   全程未重启、未替换。服务端与用户上午使用 v4 时是同一个进程。
2. 归档分支 `v5-failed` 的 server 全部改动中，
   搜 `401 / Unauthorized / upload / Bearer / token` —— **零命中**。
   即上传鉴权链路从来没有任何人改过。

**结论：这是 D-22 的原形，一条从未修复的老缺陷，不是回归。**
（D-22 在缺陷清单里标注为「从未验证」。）

> 把这条写进现场基是为了防止开发席去追一个不存在的「最近改动」。
> **真正的排查对象是 token 从配置到 HTTP 头的整条传递链，不是最近的 diff。**

## 任务书已给的排查线索（taskbook 原文）

> `fix-upload-bearer` 已在 `HttpUrlConnectionUploader` 加了 Bearer 头参数，
> 但运行时 token 可能没传到。需排查：
> - `ServiceWire.currentConfig()?.token` 是否为 null
> - `SessionRoute` 是否正确传入 `uploadToken`
> - ViewModel 是否下传

即：**头已经加了，缺的是值。** 沿这条链逐段验证，找出断在哪一段。

## 复现环境（已由 leader 核活）

- 模拟器 `emulator-5554` 在线
- daemon pid 39489 监听 `:9900`；模拟器访问宿主机走 `10.0.2.2`
- 配对 token 在 `/Users/alauda/Library/Application Support/agentmirror/token`
- 上传走 multipart HTTP，基地址由 service 装配的 `ServiceWire` 统一注入

## ⚠️ 本任务的安全高危点

排查 token 传递链天然要看 token 有没有传到，**但绝不允许把它打出来**：

- ❌ 禁止 `Log.d(TAG, "token=$token")` 这类
- ❌ 禁止 token 值进入证据文件、report_result 正文、截图
- ✅ 允许打印「是否为 null / 长度 / 来源字段名」这类不泄露内容的判据
- ✅ 单测里用假 token 常量，不读真实 token 文件

工程红线原文：配对 token 与 TS authkey 同级——不落日志、不上屏明文、不入截图。

## 不得破坏的既有状态

- **D-30 已修已提交**：upload 流程不触碰 `textFieldValue`。修 D-22 不得回退这条。
- **D-35 修复在主干未提交**（`termview/` 五个文件的形近等价映射），不要碰它。
- 强制回归门 `app/app/src/test/kotlin/dev/agentmirror/app/termview/TermSurfaceSessionBindingRegressionTest.kt`
  与 `TermSurfacePinchGestureTest.kt` 必须保持绿。

## 眼见为实（收工门）

单测绿不算修好。必须在模拟器上真的传一张图上去、**亲眼看到上传成功**，
并且按 raw/003 的语义确认**主机侧文件路径被注入到输入框**。截图留证。
