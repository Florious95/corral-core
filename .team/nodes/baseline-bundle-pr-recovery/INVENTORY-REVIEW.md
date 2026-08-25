# PR #63 INVENTORY 独立零上下文审查

## 结论

INVENTORY.md 的 PR 分支 diff 只有新增该文件（git diff --name-status main...HEAD 为一项 A，192 行；git diff --check 无输出），没有发现产品文件修改。其本地矩阵也确实含 45 个唯一原始 commit SHA，45 个 SHA 均能解析为 commit 且均为当前 main 祖先；状态表含 46 行，编号唯一覆盖 #17–#62。

但本盘点不能作为 pass，存在可复现的内容错误/证据缺口：

1. INVENTORY.md:14-16 声称 pr/baseline-bundle-successor11 与 main 的 git rev-list --left-right --count 为 0 0。当前只读实测为：

   main                         b982bff3fc8ca64cc822202ec80eeea158484748
   pr/baseline-bundle-successor11 80f6c3ce3966d95b9c1423444d8532976e69ca0b
   git rev-list --left-right --count main...pr/baseline-bundle-successor11
   2 0

   因而该分支是 main 的祖先但不是同一指针，0 0 断言为假。

2. successor11 矩阵行（INVENTORY.md:60）写作 .team/nodes/baseline-bundle-successor11-bootstrap-review/VERDICT.md，但原 SHA ebd0dc5c2 的真实路径是 .team/nodes/spec-sol/baseline-bundle-successor11-bootstrap-review/VERDICT.md。按盘点所写路径对原 SHA 做 git show 会得到 path 不存在；带 spec-sol/ 的真实路径存在且末行 verdict: pass。这会使按盘点路径复核的审计者误判资产缺失。

3. 远端字段没有被本次复核独立取得。对 gh pr list --repo Florious95/corral-core --state all --limit 100 的只读请求失败于 GitHub GraphQL proxyconnect tcp: TLS handshake timeout。因此 #17–#62 的 state、mergedAt、head ref、title 只能确认“盘点表逐行这样写了”，不能确认“GitHub 当前事实就是这样”。本地 S6-FDF7-REVIEW.md 也记录了同类 gh api/PR 页面读取失败，故不能用盘点自身循环证明这 46 个远端字段。

## 逐项核验

### #17–#62 数量与 45/45 映射

- 状态表结构：46 个唯一编号，范围 #17–#62；每行自报 MERGED 且带时间戳。
- 关键点矩阵：45 个唯一 SHA，均为 commit、均可由当前 main 到达。
- 覆盖补表：#17–#60 为 44 个映射；fdf7f6497 另列为 #61 HTTP 504 后的审计补救；#62 明确标为独立 review，不计入 45。这个计数/分类在本地文本层面自洽。
- 远端合并事实、合并时间、head/title 仍为 unjudgeable，因为没有独立 GitHub 返回值或导出的原始快照。#62 的本地分支 pr/baseline-bundle-s6-fdf7-review 确实指向本地 b982bff3，其提交标题为 docs: record successor6 PR recovery review；这证明本地 review commit，不证明 GitHub PR #62 的当前状态或时间。

### baseline 抽样

原 SHA 9468854e1f1a1fdef50edb859d9d309461a597d8 存在，父为 a538117cc2e9832c88754ccfa9d6f9becb6a91b0；原提交新增 43 个文件，全部在 .team/，包括盘点列出的 baseline ledger、source、acceptance、RESULT.md、日志和 PRELAUNCH-VERDICT.md。两个结果文档按原 SHA 读取的末行都是 verdict: pass。该样本支持“原始 baseline 资产与回退边界是审计/判据资产，不是产品实现”的本地事实，但不补足远端 PR 元数据。

### successor6 抽样与 #61 HTTP 504

原 SHA fdf7f64970351d51e616491850e2c49d03d24b22 存在，新增 18 个文件、933 行，路径均在 .team/ledgers/、.team/nodes/ 与固定 fixture；列出的 projection/deep 脚本、两个 fixture、bootstrap 结果和 bootstrap review 均存在，两个结果末行均为 verdict: pass，没有 app/server/web/test 产品路径。

本地 S6-FDF7-PR-AUDIT.md 与 S6-FDF7-REVIEW.md 都记录：过滤分支已推送，但原 gh pr create 遇 HTTP 504，后续 audit 只新增审计文档、不回退/删除原提交。可验证的本地 recovery ref 为 recovery/bundle-s6-fdf7f6-audit（c489012b...），其父为 80f6c3ce...，只新增一份 audit 文档；#62 本地 review commit 紧随其后。这个 provenance 与“#61 是补救、#62 是独立 review”的叙述相符，但 #61/#62 的远端 state/mergedAt/head/title 仍未能独立取回。

### successor11 抽样与回退边界

原 SHA ebd0dc5c285ee65244824b99db6667a1bc569c83 存在，新增 successor11 verify 脚本、fixture、bootstrap 结果、review 与日志，均为 .team/资产；真实 review 路径带 spec-sol/，且结果末行均为 verdict: pass。3597b8232、7e8c93bda、49250115a、80f6c3ce3 依次存在并形成 successor11 的本地提交链，7e8c93bda 的 final RESULT.md 末行也是 verdict: pass。

这些提交的本地 diff 都是账本、判据、日志、任务书或 review 证据；没有证据表明可以通过 revert 清洗旧 attempt/红态。因此“旧状态不可擦除、后续另建新提交”的回退边界方向正确；但应先修正上述 WT 指针与 review 路径文字。

## 工具与运输边界

- 当前只读事实与 INVENTORY.md:161-162 一致：python3 -c 'import git_filter_repo' 返回 ModuleNotFoundError，git-filter-repo 可执行文件不在 PATH。
- uv 可执行文件存在（/Users/alauda/.local/bin/uv），但仓内没有独立的 uv 执行日志可证明历史恢复实际使用了 git-filter-repo==2.47.0；INVENTORY.md:167-173 是声明/命令记录，不是该次命令的原始执行证据。
- “不系统安装”与当前 import/命令事实相符；“不使用裸推”需要缩窄措辞。tools/mirror-pr.sh:38 明确执行 git push --force origin "$BR"，:47 又执行 git push --force -u origin main。这可以是受管 wrapper 内的 push，而非人工绕过 wrapper 的裸推，但不能字面写成“无 git push”。
- INVENTORY.md:176-180 关于仓内没有 vendored git_filter_repo 与不能用 filter-branch 等价替代，和当前仓内脚本/依赖检查相符；不等于证明了所有历史运输动作都没有其他 push 路径。

## 需要返修的最小项

1. 将 pr/baseline-bundle-successor11 的关系改为当前可复核的 2 0（或重新取得与声明一致的 ref 后附原始命令输出）。
2. 将 successor11 bootstrap review 路径补全为 .team/nodes/spec-sol/baseline-bundle-successor11-bootstrap-review/。
3. 将工具边界改写为“未使用人工/绕过受管入口的裸推；受管 mirror-pr.sh 内部执行 git push --force”，并附固定版本 uv 的原始执行日志或把该点标为未证实。
4. 网络恢复后重新读取 #17–#62 的 GitHub state、mergedAt、head、title 原始 JSON；在此之前不得把状态表当作独立远端证据。

verdict: refutes
