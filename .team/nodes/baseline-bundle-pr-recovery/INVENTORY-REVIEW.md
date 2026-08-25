# PR #63 INVENTORY 二审（独立零上下文）

## 范围与首轮历史

当前 PR head 为 e6d570f2926ebcf569a48d535c2fc14d04af6147。相对 main 的 diff 仅含三项：

- .team/nodes/baseline-bundle-pr-recovery/INVENTORY.md
- .team/nodes/baseline-bundle-pr-recovery/PR-17-62-SNAPSHOT.json
- .team/nodes/baseline-bundle-pr-recovery/INVENTORY-REVIEW.md

没有 merge、push、PR 修改或产品路径变更。本文件保留首轮审查的 refutes 历史：首轮发现 successor11 分支关系误写为 0 0、successor11 bootstrap review 漏写 spec-sol/，以及把受管过滤仓内部 force-push 说成无 git push；同时因无 REST 快照，#17–#62 远端字段当时不可独立确认。

二审不采信作者首轮结论，重新读取当前 head、独立计算 snapshot 摘要、解析全部字段，并重新抽样原始提交。

## 二审结果

### REST snapshot 完整性与 46 条字段

对 PR head 中的 PR-17-62-SNAPSHOT.json 独立计算 SHA-256：

    c7c14cab2a20eea7afadc4ac85bb3f33452031bcd6f8b39dba026b87685f38bf

与 INVENTORY.md 声明完全一致。JSON 的 source 是 GET /repos/Florious95/corral-core/pulls?state=all&per_page=100，range 为 [17, 62]，prs 数量为 46。

逐条解析结果：

- number 唯一覆盖 17..62，无缺号、重复号或越界号；
- 每条恰有 number、state、merged_at、head、title；head 恰有 ref、sha；
- 46 条 raw state 均为 closed，merged_at 均非空，head ref/title 非空；
- 46 个 head sha 均为 40 位小写 hex；
- INVENTORY.md 状态表的 46 个 head、title、MERGED 投影与 snapshot 逐条相等；
- #60、#61、#62 的 merged_at 依次为 2026-08-25T11:55:04Z、2026-08-25T12:04:24Z、2026-08-25T12:07:00Z，时间边界与 #61/#62 晚于 #60 相符。

本次实时 gh api GET 仍因代理 TLS handshake timeout 无法复取 GitHub 页面；因此结论严格限定为“当前 PR 携带的 REST snapshot 完整性、字段一致性和声明投影通过”，不把失败的在线复取伪称为成功。snapshot 已作为本格要求的固定 REST 证据核验。

### 45/45 映射与 #61/#62 角色

独立解析覆盖补表得到 45 个唯一 SHA→PR 映射，PR 号精确为 #17..#61：

- #17..#60：44 个原关键点映射；
- #61：原 fdf7f6497 projection bootstrap 的 HTTP 504 后审计补救；
- #62：不在 45 个关键点映射内，角色为独立 recovery review，head ref 为 pr/baseline-bundle-s6-fdf7-review。

矩阵中的 45 个原始 SHA 全部可解析为 commit，且全部是当前 main 祖先。#62 不被错误计入 45/45。

### 初始 0 0 与 fresh 2 0 边界

当前只读核验：

    main = b982bff3fc8ca64cc822202ec80eeea158484748
    pr/baseline-bundle-successor11 = 80f6c3ce3966d95b9c1423444d8532976e69ca0b
    git rev-list --left-right --count main...pr/baseline-bundle-successor11 = 2 0

提交图显示 c489012b8（#61 audit）直接以 80f6c3ce3 为父，b982bff3（#62 review）直接以 c489012b8 为父。因此初始两指针同为 80f6c3ce3、后续 main 前进恰好两提交而 successor11 ref 保持不动的边界成立；fresh 2 0 不再被误报为 0 0。

### spec-sol 路径

successor11 原 ebd0dc5c2 的 bootstrap review 真实路径为：

    .team/nodes/spec-sol/baseline-bundle-successor11-bootstrap-review/VERDICT.md

当前 INVENTORY.md 已使用该完整路径，且该路径在原 SHA 上存在，末行为 verdict: pass。二审未发现首轮的漏前缀问题仍残留。

### 过滤临时仓与 force-push 语义

当前镜像脚本先创建 /tmp/mirror-pr 临时仓、clone 原仓、在临时仓执行 filter-repo，再添加 corral-core origin；随后脚本内部执行 git push --force。当前 INVENTORY.md 已明确：

- 不做未过滤原仓直推；
- git push --force 是过滤临时仓中的受管发布步骤；
- git-filter-repo 通过 uv 临时注入，不做系统安装。

这与 tools/mirror-pr.sh 的实际顺序一致：脚本源码在过滤完成后的临时仓中执行分支 push，最后再推过滤后的 main；没有把受管过滤发布改称“没有任何 git push”。当前普通 python3 import 仍返回 ModuleNotFoundError，git-filter-repo 可执行文件仍不在 PATH，故“不系统安装”事实相符。固定版本 git-filter-repo==2.47.0 由 snapshot 旁的命令记录声明；本地没有额外执行日志，故不扩张为新的运行事实。

## 三段原始提交抽样

### baseline

9468854e1f1a1fdef50edb859d9d309461a597d8 存在，父为 a538117cc2e9832c88754ccfa9d6f9becb6a91b0。原提交新增 43 个文件且全在 .team/；baseline ledger/source、acceptance、RESULT.md、compile.log、dry-run.log、structure.log 与 PRELAUNCH-VERDICT.md 均能从该 SHA 读取，两个结果文档末行均为 verdict: pass。该段回退边界是判据/账本/证据资产，不涉及产品代码。

### successor6

fdf7f64970351d51e616491850e2c49d03d24b22 存在，新增 18 个文件、933 行，路径全在 .team/ledgers、.team/nodes 与固定 fixture；projection/deep 脚本、两个 fixture、bootstrap 结果和 bootstrap review 均存在，结果末行均为 verdict: pass，没有 app/server/web/test 产品路径。

本地 c489012b8 recovery ref 只新增 S6-FDF7-PR-AUDIT.md，并以 80f6c3ce3 为父；该文档与 S6-FDF7-REVIEW.md 记录原过滤分支已推送、gh pr create 遇 HTTP 504，补救只恢复审计对象，不回退/删除原提交。snapshot 将该对象列为 #61，随后 b982bff3 review 对应 #62；两者角色与时间顺序一致。

### successor11

ebd0dc5c285ee65244824b99db6667a1bc569c83、3597b8232、7e8c93bda、49250115a、80f6c3ce3 均存在并形成连续 successor11 链。原 SHA 的 verify 脚本、fixture、bootstrap 结果、spec-sol review 与日志均存在；7e8c93bda 的 final RESULT.md 末行为 verdict: pass。抽样提交的路径均为 .team/账本、判据、任务书、日志或 review 证据，无产品实现路径；“旧 attempt/红态不可由 revert 清洗、恢复必须另建提交”的回退边界与本地提交类型相符。

## 二审判定

首轮 refutes 的三项具体问题均已由当前 head 修正；snapshot 摘要与声称值相等，46 条字段和表格投影逐条一致，45/45 映射与 #61/#62 角色正确，三段原 SHA/产物/回退边界通过。在线 REST 复取因环境 TLS 超时仍不可用，但这不改变本格对固定 REST snapshot 的独立完整性核验结论。

verdict: pass
