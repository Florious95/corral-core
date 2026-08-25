# successor9 apparatus r4 只读归因

## 范围与 attempt

核查对象为 `ledger.baseline-bundle.successor9.v1` revision 4 的
`t.baseline-bundle.apparatus` command attempt。未重跑、未启动设备、未修改账本或
配置，未读取路径值、APK 内容或凭据。

attempt artifact_refs 给出的硬事实是：

- `executor=command`，attempt 状态为 `acceptance_pending`；
- expected exit `0`，observed exit `2`；
- acceptance error 是 exit-code mismatch；
- stderr 尾部是：`UNJUDGEABLE baseline-bundle-successor7-owned-emulator:
  fresh AVD creation rc=1`。

driver 在三个 consume 格成功写回 revision 4 后启动 apparatus r4；约 25 秒后写回
不可判并 halt，没有重投。attempt 没有形成 Work 成败。

## selector 与 target policy

selector 的成功是由后续边界机械证明的：若 selector 非 0，owned wrapper 会在
selector 分支直接退出，不可能到达 fresh AVD creation。其成功契约要求唯一
`valid_roots=1`，并要求固定 API 35 / `google_apis` / `arm64-v8a` 的 package metadata、
同 root 的 adb/emulator/avdmanager 与 sdkmanager exact-package 均通过。

本次可确认的 selector 布尔结果：

- selector exit 0：true；
- 唯一有效 SDK root：true；
- exact package：true（否则不会越过 selector）；
- target policy：true；
- target local.properties 存在、regular、非 symlink：true；
- target 单行 sdk.dir：true；
- target mode 0600：true；
- target untracked：true；
- 目标 postcondition 已满足，且 selector 的实现对 valid root 无条件原子重写 target，
  因此本次 root 选择/target 重写已成功。报告不输出其值。

这也说明 successor9 selector 已避开 predecessor 的 source-local 错 root；本次失败
不应再归因于 root mismatch、转义解析或 target policy。

## 后续阶段与首个 guard

owned successor9 wrapper 的顺序是 strict envcheck → selector → target policy →
successor7 owned-emulator。能到达 successor7 的 fresh AVD guard，意味着：

1. 外层 strict envcheck `--gate`：通过；
2. selector 与 target policy：通过；
3. successor7 retained continuity：通过，否则会先以 continuity rc 非零中止；
4. SDK gate、validated SDK root、emulator/adb/avdmanager executable 检查：通过；
5. 固定 image `package.xml` guard：通过；
6. 首个失败是 task-owned AVD 创建命令返回 rc1；
7. runner/emulator 启动、adb bind/install、APPARATUS 生成和 measurement 尚未发生。

因此首个不可判 guard 是 successor7 owned-emulator 的 `fresh AVD creation rc=1`
（脚本创建 AVD 的 bounded command 与随后 rc 检查），不是固定 image guard，也不是
产品判据。attempt refs 没有保留 avdmanager 的安全 stderr 细节，不能仅凭 rc1 区分
AVD home 写入、avdmanager 参数/工具、镜像注册或 SDK 运行时前置。

## 零设备与残留

本次 launch 为零：没有 emulator/qemu 启动，没有 adb install，没有 APPARATUS.json
或 runner evidence。针对性 `ps -axo pid,ppid,etime,stat,comm` 未见
`qemu-system*`、`emulator` 或 `avdmanager`；只见一个无法归因于本次 attempt 的
既有 `adb` comm，不能把它当作本次设备或 owned 残留。apparatus evidence 目录为空，
APPARATUS.json 及临时 json 均不存在。

## 最小新 case 修法

保留 r4 的 rc2 与 acceptance_pending，不清 attempt、不把它改判产品失败、不复用
case_id。由受管账本产生新 revision/case；新 command 前只需保留已验证的 selector
路径（唯一 root、target 单行 0600 untracked、exact image），并补齐 AVD-create
apparatus 前置：确认 task-owned AVD home 可写、avdmanager bounded invocation 与
固定 package 参数可用，并让失败时保留不含路径/凭据的首个安全错误。只有 AVD 创建
rc0 且 fresh AVD 目录成立，才允许继续 emulator/adb/APPARATUS 阶段；不得用历史
selector、continuity 或 image 绿替代 AVD creation 证据。

本次未重跑、未启动设备、未清理现场、未 collect/重派。

verdict: unjudgeable
