# successor6 bootstrap semantic review

## 判定

`verdict: pass`：bootstrap 阶段的 successor6 内容身份/存储槽位解耦契约成立，且
没有把旧 successor5 的循环路径恒等式误当成真实产品失败。此结论只覆盖 bootstrap
语义与审查 apparatus；不覆盖尚不存在的 successor6 final ledger，也不宣称产品门已
在新 WT 全量通过。

## 独立事实

对冻结 successor5 manifest 做只读重算得到：旧
`.team/private/baseline-vault/{bundle_id}/builds/` 前缀判据 `rc=1`；新 projection
门 `rc=0`，同时给出：

- `SUCCESSOR6_CANONICAL_IDENTITY equal=true`：只从
  `source,runtime,artifact,build,equivalence,implementation` 的 canonical JSON
  字节重算 `bundle_id`，不把存储路径当作恒等式。
- `SUCCESSOR6_SLOT_PROJECTION ... non_circular=true ... independent=true`：恰有两个
  固定、仓内相对、互异且不含 id/PENDING 的槽位，且 `build_root` 独立。
- `SUCCESSOR6_ARCHIVE_PROJECTION content_addressed=true`：archive 仍是 id-scoped
  内容寻址路径，和 build 槽位分开。

manifest 在投影前后 hash 稳定，未发生 TOCTOU 改写。

## 破坏齿与四态

固定 fixture 的 fresh 回归为：合法 `0`；bundle id 篡改 `1`；路径越界 `1`；槽位
改名 `1`；槽位交换 `1`；旧 id-scoped build 路径 `1`；malformed `1`；missing
manifest `2`。路径齿均先重新 canonicalize，因此不会以 stale id 掩盖 slot 判据。
固定 helper、contract、legal fixture 的 digest 均匹配；缺 fixture/helper/contract
应保持 `2`。

## 深门未弱化

successor6 deep gate 与旧 impl gate 均 200 行；除诊断标签外只有一条语义变化：将
独立 build 的安全前缀从循环 `{bundle_id}/builds/` 放宽为仓内 vault 根，由先行
projection gate 精确锁定两个槽位。来源 closure、APK SHA/MD5/size、运行内容、签名/
包名/版本、双份 archive（regular/symlink/inode/seal/retrieve）、A2 双 build/root、
no-cache build、工具与报告 provenance、canonical id、focused mutation 和私有 APK
不入 Git 检查均保留。wrapper 静态核对不调用旧 `baseline-bundle-impl.sh` 或
successor3 impl wrapper，仍调用 successor6 deep、successor3 canonical real fixture
与 controlled bypass，并在各门之间锁 manifest hash。

## 安全与阶段边界

successor5 SDK fallback regression exit 0，structure gate exit 0；SDK 值未输出。
successor6 final DSL/compiled ledger 均不存在，本阶段没有伪造 required-list 或启动
final gate。全量 deep wrapper 未对冻结旧 WT 执行，以保持旧 attempt 只读边界；应在
bootstrap commit 后由 successor6 final 阶段新建 WT 再执行。

详见同目录 `tests.log` 的 fresh 命令和输出。

verdict: pass
