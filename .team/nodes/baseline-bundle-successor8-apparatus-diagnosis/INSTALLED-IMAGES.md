# successor8 apparatus 已安装 system image 只读核验

## 核验边界

本次仅做本机只读枚举；未安装、未联网、未启动 emulator/qemu、未运行 apparatus
command、未修改账本或生产文件，也未读取 SDK 路径、凭据或 APK 内容。

## 量具结果

- 本机架构：`arm64`。
- `sdkmanager --list_installed`：工具存在；过滤后的已安装 system-image package id
  只有：

  `system-images;android-35;google_apis;arm64-v8a`

没有输出 SDK 路径或凭据。未把 PATH 中工具可见性当作 image 安装证据；生产命令仍
必须使用其 validated SDK root 下的同一 package.xml 做可读性核验。

## 与 emu-own 合同对照

现行 `emu-own` 合同要求：

- runner 绑定本次实际启动的 full-path `qemu-system-aarch64` PID；
- 使用绑定 serial `emulator-5554`；
- 顺序为 preflight → launch → bind → adb → measurement，并只清理本次 owned PID；
- image 由 production owned-emulator 固定为 API 35、`google_apis`、`arm64-v8a`。

已安装 package id 与固定 image 完全相同，主机架构也与 aarch64 合同一致。因此存在
等价候选，不需要改用另一 API、image、被测 App 或性能语义；这不是替代语义，而是
固定候选本身已安装。此前 r4 的 `fixed system image unavailable` 仍说明当时
validated SDK root 未证明该 package.xml 可读，不能用本次枚举倒写 r4 证据。

## 最小继续边界

新 apparatus revision/case 仍须在 command 前，仅用不泄露路径的门确认 validated SDK
root 与上述已安装 package 对齐且 package.xml 可读，并先过 strict envcheck 与
continuity；随后才可由受管流程执行原 command。无需安装或联网。

## SDK root mismatch 收敛（追加，只读）

### 来源与实际选择

production owned-emulator 先调用 successor5 SDK gate（`baseline-bundle-successor5-sdk.sh:27-39`）。
当前没有可用的 `ANDROID_SDK_ROOT`/`ANDROID_HOME` 覆盖，因此 gate 的来源是 Git
common root 下的 `app/local.properties`；helper 在
`baseline-bundle-successor5-sdk.py:40-47,75-79` 仅把该值 canonicalize 并确认目录可读，
没有确认固定 system image 元数据。

owned-emulator 随后在 `baseline-bundle-successor7-owned-emulator.sh:225-239` 读取目标
`app/local.properties`。当前目标文件是两行（首行 comment、次行 sdk.dir），不是它
要求的单行描述；本次没有运行 SDK gate 去重写它。此前 r4 command 的固定镜像首个
guard 坐标仍是 `baseline-bundle-successor7-owned-emulator.sh:249`。

### 不泄值比较结果

以下只记录布尔值或来源标签，不记录任一 SDK 路径：

| 比较项 | source-local.properties 选中 candidate | sdkmanager 安装清单 root |
|---|---:|---:|
| `dir_exists` | true | true |
| `package_dir_exists` | true | true |
| `package.xml_regular` | false（不存在） | true |
| package 目录内 `source.properties_regular` | true | true |
| SDK root `source.properties_regular` | false | false |
| package 目录可读/可执行 | true | true |

跨 root 比较：

- `same_inode=false`
- `canonical_equal=false`
- `sdkmanager_exact_package=true`
- sdkmanager root 的 exact package `package.xml` 可读；source candidate 的
  `package.xml` 不存在，故不是“存在但权限拒绝”。
- source candidate 的 sdk.dir 没有反斜杠、转义空格或尾部空白，canonicalize 未改变
  其值；未发现转义解析形状。

### 归因

归因是 root 选择错误：仓内 source `local.properties` 指向一个目录和 image package
目录都存在、但缺少 `package.xml` 的 SDK root，而 sdkmanager 的安装清单在另一
canonical root 中确有 exact package。不是权限问题（选中 root/package 目录可读），
不是转义解析问题，也不是 package.xml 过强假设：sdkmanager root 的同一 exact
package 实际具有 regular `package.xml`。此前 r4 的 `fixed system image unavailable`
因此是选中 root 上的真实前置缺失。

### 最小四态修法与破坏齿

最小修法是让 SDK gate 只产生一个经过校验的 root：在写目标 local.properties 前，
要求 candidate 与 sdkmanager 安装清单 root `canonical_equal`（或同 inode），并同时
要求 exact package、package.xml regular/readable、package 内 source.properties
regular；不满足即在 AVD 创建前返回 rc2。不得换 API/image、改 App 或放宽固定 image
guard。目标描述还必须先恢复为单行 sdk.dir，再供 owned-emulator 读取。

四态保持分离：

- `pass`：root 对齐、metadata 全绿，且后续 owned apparatus 完成；
- `refutes`：root/量具全绿并实际测量后，产品判据被证伪；
- `unjudgeable`：root mismatch、metadata 缺失或权限/工具门失败，映射 command rc2，
  不启动设备；
- `not_applicable`：明确没有合同允许的 image 或收到停止裁定，不产生产品结论。

破坏齿至少应覆盖：把 source 指到“package 目录存在但 package.xml 缺失”的另一 root
必须 rc2 且零 launch；删除/拒读 exact package.xml 必须 rc2；加入转义 sdk.dir 或
第二个 local.properties 行必须在解析门失败；exact package 对齐的 positive control
才允许越过 image guard。上述破坏齿本次只列出，未执行。

本次未安装、未联网、未启动设备、未修改账本或仓内 SDK 配置。

verdict: blocked
