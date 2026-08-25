# successor9 retained WT 只读快进预检

## 范围

目标是 registered worktree `wt-maple-core`，bootstrap commit 为 `0fdee1072`。
本次只读核验；未读取 APK 字节，未 merge、reset、clean、启动设备或改账本。

## 预检结果

- Git worktree registration：`registered=true`，目标目录存在且 `HEAD` 可解析。
- `HEAD` 是 `0fdee1072` 的祖先：`true`；因此 ff-only 可行：`true`。
- bootstrap commit 可解析：`true`。
- 目标当前 dirty/untracked 条目数：`288`；bootstrap 未来更新路径数：`55`。
- dirty/untracked 与 `HEAD..0fdee1072` 更新路径的层级交集：`false`；路径零交集：
  `true`。未输出这些路径名，避免把任何 APK 内容带入报告。

## 历史资产保持

- 当前 workspace 保留 successor6 verify 资产，以及 successor8 apparatus diagnosis 的
  `VERDICT.md` 与 `INSTALLED-IMAGES.md`。
- retained `wt-maple-core` 内 successor6 verify 资产仍在；successor8 diagnosis 属于
  workspace-local 历史记录，不是本次 ff 更新的产品路径。
- successor8/历史 apparatus 的 `APPARATUS.json` 仍缺失（当前 workspace 与 retained WT
  均不存在）；没有把该缺失伪装成设备证据，也没有生成候选文件。

## 判定边界

祖先关系、工作树注册、路径隔离和历史缺失事实均闭合，故可安全提供 ff-only 继续
命令。ff 后仍须分别执行 selector regression 与 retained continuity；这两条命令本次
均未执行，其中 continuity 会读取既有归档摘要/哈希，故本预检不以其自报替代执行证据。

verdict: pass
