# 手写/脚本拼 JSON 账本痛点清单（远程Agent安卓，实撞坐标全在 hl1-v1，rev1→67 一天内）

坐标底座：.team/ledgers/hl1-v1.json（31 格、67 个 revision、一天）。我们从不手写 JSON——
第一天就放弃了，全部用 python dict 拼（tools/prep_ledger.py + leader 内联 heredoc）。以下痛点
是「即使用脚本拼也躲不掉」的那部分，比语法脆性更深一层。

## 3+5｜无抽象复用 × 路径语义（当日两次事故，同因）
- 想做：每个评审格的产物判据「verdict.md 在哪都认」。
- 被逼：每格手拼 2-3 个 cands 路径，worktree 名靠字符串约定推导。
- 事故①：A-uiplusrv-form 推导出 `.worktrees/hl1.uiplus-rv`（不存在），真实是 `hl1.rv.ui`
  ⇒ 判据红、复位、评审席重派重报一轮。
- 事故②：A-verify2-doc 写 `hl1.verify2`，格子声明的 worktree_id 是 `hl1.verify`——同型第二次。
- 根因：产物落点有「仓根 or 席位 worktree」二义（席位硬约束只许写 worktree），而 JSON 里
  没有「本格 worktree」变量可引用，只能字符串拼。代价：两轮返工 + 一次评审席重复劳动。

## 4｜报错时机：结构预检过 ≠ 语义成立（当日最贵事故）
- 想做：doc 判据断言「席位真交付了」。
- 被逼：JSON 里只能写 argv 的 python -c substring 检查；schema/preflight 全绿。
- 事故：三连击——复现格报 status=norepro、探针格/修复格诚实报 status=blocked，
  substring 判据（只查字段名存在）全部判绿 ⇒ 驱动器带着断裂前提连派三格下游，
  评审席两次审到空分支（自己写下 refutes）。修法是我们后补「status=done 正向哨兵 +
  否定词拦截」进每个 argv 字符串。
- 期望档位：这类「判据对否定式诚实交付恒绿」如果新面能提供 结果枚举/哨兵 一等公民，
  属「被教学」最好；写不出来（编译拒 substring-only 交付判据）更好。

## 6｜意图无处安放 → title 成了万物垃圾场
- 想做：任务书=基底+需求+纪律+第 N 轮补记。
- 被逼：全部塞 title。实测 title 普遍 8-12KB；返修时只能往 title 末尾 append
  「⚠️ 第三轮补记…」（hl1-v1.json 里 t.verify2 的 title 里有**四段**补记）。
- 代价：diff 不可读；席位读到的任务书没有版本边界；评审席引用「说明与任务书一致」时
  无法指认是哪一轮的任务书。

## 7+8｜运行期字段混入 × 打针竞态（当日两类实撞）
- 事故①（丢失更新）：leader python 读-改-写 与驱动器判据写回赛跑，把 t.uicmd 的
  succeeded 盖回 planned ⇒ 席位被重复派单重做一轮。JSON 文件即数据库、无事务。
- 事故②（在途重派）：revision 前进会把**在途格**的 case 重发（r59→r60→r62 三连），
  席位输入框堆积未提交派单。
- 手改复位时要记得 pop 掉 status_record/attempts/rounds 三个引擎字段——漏一个行为不明。
- 期望：运行期字段与作者字段物理分层；或提供「复位格」原语而不是让人裸改 JSON。

## 9｜schema 记忆负担（每次立格都要翻的五条）
fallback 必须非空且角色不得与 owner 重合；roles[].seat 必须独立声明；无路由也要 transitions:[]；
not_applicable 只许 XOR 兄弟；worktree_id 字符集限制。我们的对策是抄上一张账本——
于是「抄错上一张的坑」成了新的坑来源（cands 路径事故①就是抄模板抄出来的）。

## 11｜判据编写细节
- expected_exit_code 只用了 0；unjudgeable=2 从没用起来（不知道哪些判据该声明它——规范
  有概念但书写面没有位置提醒）。
- time_budget 全靠猜（gradle 套件 2400 还是 3000？猜小了红一轮）。
- 判据 shell 是 POSIX sh：bash 进程替换炸过（前日 land-pr `${BR}` 被全角括号吞进变量名）。
- argv 内嵌 python -c 多行脚本：转义没炸是因为我们用 python repr 生成 python——
  等于为了写 JSON 判据先写了一层生成器。这层生成器自己又出过错（need 列表插入位置替换
  substring 时改错判据，所幸 preflight 后人肉 diff 抓到）。

## 12｜其它两条
- 「只重跑判据、不重派席位」无原语：uiplus.rv 的 verdict 已在盘上，判据路径修好后仍必须
  重派评审席让它再 report 一次才能入账——白耗一轮 Opus。
- 基底内联：resources.read_paths 被运输层静默吞（旧 findings F-12），我们只能把 BASE.md
  全文 prepend 进 title——title 膨胀的另一半原因。若新面有「随单附件」一等公民，这个
  绕法可拆。
