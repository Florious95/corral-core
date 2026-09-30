# 性能基线身份归因（2026-08-23）

## 结论

`status=blocked_contract`。不能启动 fresh 复测：规范基线的“真机 release 身份”和可供 `judge-perf-nonregress.sh` 消费的 JSON 不是同一份可唯一核验的产物。

本轮未启动模拟器、未运行采样入口、未修改产品码、判据或基线文件，也未触碰真实 tmux、真实会话和生产 daemon。

## 1. 基线身份证据与冲突

- `baseline-20260822-release^{commit}` = `26f46642d3960b1bd96a39753b3f25516c5821eb`。
- tag 注释明确声明：真机金标准 release APK MD5 `0907d6881bb1e034ef33a49f89afaa44`（35,044,459 bytes）；模拟器三夹具 `first_draw` 地板为：`real_claude_idle 1450.5/1908.0`、`redraw_tui 1478.0/2280.0`、`big_scrollback 1492.0/1891.8`（p50/p95）。
- 但 tag 指向的 `.team/perf/baseline-20260822.json` blob 与当前文件完全相同（SHA-256 `5b85a383f245ef4c53c0eb8e7f829326b899dcdd`）：`app_sha=fb50a388b`、`apk_md5=d096b250ffe3b6cc4ca62049a2f72ef9`，三夹具 `first_draw` 与 `layout_settled` 均 `n=0,p50=null,p95=null`，文件 verdict 为 `INCONCLUSIVE`。
- 当前“单一事实来源”文档 `docs/基线-20260822-release.md` 又同时指向该 JSON，并写入 tag 注释中的 `0907…` 与有值地板。
- 唯一含有这些有值统计的文件是 `.team/perf/archive/baseline-20260822-fixblank-NOT-this-tree.json`；它明确不是当前树，且身份是 `app_sha=8d811c199`、APK MD5 `f7db6f47d2177234605c04bbb4c711c4`，不能冒充 release 基线。
- 当前工作树候选 release APK MD5 为 `e285492e547b36ed80912b981983ce3c`，代码为 `d65ba733f6aa76b5e624a721b5b394f282040ecf`（dirty），也不是 tag 注释声明的 `0907…` 身份。

因此缺少一项明确裁定：哪一个冻结文件/代码/APK 三元组是可执行性能门的规范基线。未获裁定前不能生成或判 fresh 数据。

## 2. `fixtures=[]` 的直接成因与入口

`tools/perfbase/judge-perf-nonregress.sh` 只读取最新 `baseline-*.json` 和 `recheck-*.json` 的顶层 `fixtures` 字段。当前 `recheck-20260822-capp-ab.json` 没有顶层 `fixtures`，而是把数据嵌在 `round1_n10`、`round2_n20`、`combined_n30_big_scrollback` 下，并声明地板是同批 A 组、不是历史基线。因此脚本执行：

```text
UNJUDGEABLE 夹具集合对不上 基线=['big_scrollback', 'real_claude_idle', 'redraw_tui'] 复测=[]
exit=2
```

仓库既有采样入口已核实为：

- `setup-fixtures.sh`：自建 `/tmp/e2e-ca-emu` socket 与三个夹具 pane；
- `runab.sh` → `coldopen.sh`：三个夹具各 10 次，`force-stop`/冷启动，语义 UI 导航，只收 `adb logcat -d -s PerfTrace`；
- `mkbaseline.py`：生成带顶层 `fixtures` 的 canonical JSON 形状；
- `summarize-ab2.py`：生成当前 `capp-ab` 分层 A/B 形状，正是造成 judge 看见 `fixtures=[]` 的来源。

## 3. 样本、p50/p95 与负载（均为既有文件留痕，非本轮 fresh）

规范 tag 注释声称的三夹具样本数都是 10；但可执行 baseline JSON 中三者都是 `n=0,p50=null,p95=null`。当前 recheck 的嵌套历史数据为 A/B 各 10 次：

| 夹具 | baseline JSON | capp-ab A/B（嵌套、非历史基线） | judge 顶层结果 |
|---|---|---|---|
| `real_claude_idle` | n=0; first_draw/layout p50,p95=null | A n=10: first 161.0/196.7, layout 631.5/649.5；B n=10: 150.5/196.0, 624.0/652.7 | 缺失 |
| `redraw_tui` | n=0; first_draw/layout p50,p95=null | A n=10: first 152.5/182.1, layout 627.5/647.0；B n=10: 166.5/195.9, 633.5/655.2 | 缺失 |
| `big_scrollback` | n=0; first_draw/layout p50,p95=null | round1 A/B n=10；round2 A/B n=20；其分层数值见日志 | 缺失 |

当前只读宿主快照：load averages `5.63, 5.32, 5.79`；free `65.67 MiB`、inactive `8553.39 MiB`、合计 `8619.06 MiB`。上一轮环境闸已 exit 0，但本轮因契约冲突没有重新起环境或采样。

## 4. 命令退出码

完整关键命令与输出见 [`perf-attribution-20260823.log`](perf-attribution-20260823.log)。本轮关键判据：

| 命令 | exit |
|---|---:|
| `git rev-parse baseline-20260822-release^{commit}` | 0 |
| 读取 tag baseline JSON 并解析字段 | 0 |
| 当前 baseline 与 tag blob SHA 对比 | 0 |
| 读取 recheck 顶层/嵌套字段 | 0 |
| `sh tools/perfbase/judge-perf-nonregress.sh` | 2 |
| 当前 APK MD5/尺寸读取 | 0 |

未执行 fresh 采样、未执行本轮 `envcheck --gate`，因为规范基线身份尚未唯一证明；不能把既有嵌套 A/B 数据或归档数据包装成新鲜三夹具复测绿。
