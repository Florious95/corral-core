# successor7 retained WT 安全预检

## 范围与安全边界

目标是已注册的 retained wt-maple-core，bootstrap commit 为
da46a6b2b538faf7954fa4f9af7e8c09a194f45e。此次只读核对 Git 元数据、manifest JSON、
报告摘要和归档文件元数据；未读取或复制任何 APK 字节，未输出 SDK 值、私有归档
路径值或其它敏感路径值，未执行 merge/reset/clean。

## 机械结果

| 门 | 结果 |
|---|---|
| registered worktree | pass |
| retained HEAD 可解析 | pass；HEAD 前缀 87ce64f0361a |
| retained HEAD 是 bootstrap 的祖先 | pass |
| bootstrap Git object 可用 | pass |
| dirty/untracked 与 HEAD..da46a6b2b 更新路径交集 | pass；dirty/untracked=288、更新路径=28、交集=0 |
| fast-forward 可行 | pass；git merge-base --is-ancestor HEAD da46… 成功 |
| successor6 四格历史证据 | pass；commit 3528c2ad5 中 revision 5 ledger 身份、required 和四格 succeeded 状态均匹配，verify 保持 planned |
| implementation report | pass；IMPL.md 末行为 implementation: pass |
| manifest | pass；存在且可解析，identity 字段完整 |
| manifest/归档摘要 | pass；manifest 的 artifact SHA 声明与 primary、backup、recovered 三个 archive SHA 声明相等 |
| primary/backup 元数据 | pass；文件存在、regular、非 symlink、无写位，且 inode 独立 |
| APK 字节访问 | 未执行；不以本次重新计算摘要替代已有声明/元数据核验 |

dirty/untracked 集合只用于集合交集计算，没有把路径名写入本报告。归档检查只做
manifest 声明一致性、路径安全、regular/non-symlink、sealed 和 inode 元数据检查；
按用户禁令没有打开 APK 或重新计算 APK digest。

## 结论

所有 successor7 continuity 前置均满足。现有 WT 可以保留并接受 bootstrap 的
fast-forward；路径不相交保证该 fast-forward 不覆盖 retained 的 dirty/untracked
交付。四格 evidence 仍是 successor6 的历史 durable evidence，不被本次预检重放或
改写。

下一步只可按 COMMAND.md 的 ff-only → continuity 顺序执行；本席没有执行该命令。

verdict: pass
