# PR73 T-A 交付

状态：`ci_verified_unaccepted`；等待 leader/独立验收席复核，作者不宣称最终组合验收。

在 owning PR73 上撤掉 health 对合法 activity motion 的否决，保留 health 独立元数据轴。当前 PR 仍 OPEN，未 merge/部署/产 APK。

- PR：[Florious95/corral-core#73](https://github.com/Florious95/corral-core/pull/73)
- 当前 head：`cfb8d32a11cd3a97d2a096d57b09d7bc184924d9`
- 当前 source tree：`05fe74dc4996298c5292e49c8a9fda1712b32d1a`
- base：`pr/status-core-nodeprobe-863c` @ `23e0c4f1529b7b51192d6e65ecc62b3b517e2cf5`
- 精确 C73 起点：`1642902e7b9fe002f72a0a16a6963be2ef865141`，tree `650f7ddeca62d2a826dfa5f993691f35a9fc1f35`

## 写界

- `Models.kt`：`sessionRowMotion(activity, isOnline)`；删除 health 形参及完整 health veto。
- `SessionRow.kt`：caller 改为只传已解析 status 与显式 online；health 仍作为 SessionItem 元数据。
- 规则：online + working → Working；online + idle → Idle；online + unknown → None；offline 全 None。
- DTO 畸形 health 仍归 `unknown`，不改变合法 activity；activity/status 冲突 fail-closed 逻辑未改。
- 未改 provider 白名单/特例、布局、图标、节奏、网络订阅、收藏、名称投影及其他席位文件。

## 红绿

- 精确 C73 abnormal 红：CI run `34089083105`，产品未改，具名 matrix test 退出 1，`1 test completed, 1 failed`，失败集为 `ExternalSessionStatusUiTest > fourAxisProjectionUsesOnlyOnlineAndActivity`；41 actionable tasks executed。见 `RED-REPORT.md`。
- 修复提交：`c33317d7846b6fafc73718b5397780de3e7185c1`。
- 当前绿：CI run `34090682711`，Gradle 退出 0，`BUILD SUCCESSFUL`，41 actionable tasks executed，JUnit 20/0/0/0（tests/failures/errors/skipped）。见 `GREEN-REPORT.md`。
- 命令、退出码、执行数、日志 SHA、未执行项均在 `A73-RECEIPT.json`。

## 覆盖与边界

- 完整 online × activity(working/idle/unknown) × health(normal/unknown/abnormal) 矩阵通过。
- DTO→`toL2Entry`→`toSessionItem`→真实 Compose `SessionRow` 的 abnormal-health working row 同 ref 且连续帧变化；idle 静止、unknown 安静。
- Compose 证据是 hosted JVM/Robolectric 测试，不冒充 AVD/真机/最终 APK。最终 Grok 循环、组合 APK 和跨面收藏验收留给独立席位。
- 本机未运行 Gradle/Go/Rust；Grok 20 GiB 门未满足，未启动同步/构建。未读凭据、真实会话、生产日志或真实 socket。

## 架构/CI

- basegen 已执行；改动目标包 ArchWiki strict-T3：`dev.agentmirror.app.ui.model`、`dev.agentmirror.app`（UI 子包）均 exit 0。
- 精确 C73 根包检查的 5 条 T3-4 未声明 `@consumes` 为基线既有漂移，未扩大 T-A 写界；详见 `ARCH-RECEIPT.md`。
- 新增的 `.github/workflows/pr73-activity.yml` 仅使用标准 hosted runner、只读 contents 权限、临时公开 debug keystore、Gradle `--rerun-tasks`，无 `pull_request_target`/secrets/自建编排。

附属证据：`RED-REPORT.md`、`GREEN-REPORT.md`、`MATRIX-EVIDENCE.md`、`UI-EVIDENCE.md`、`ARCH-RECEIPT.md`、`REMOTE-CLEANUP-RECEIPT.md`。
