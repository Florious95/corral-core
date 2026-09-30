# t.perf.design：输入透传性能契约

## 状态与适用范围

本契约冻结输入透传改动的性能门。行为基线不是历史 JSON 的数值内容，而是用户裁定通过的稳定 release：

- tag：`baseline-20260822-release`
- 用户真机参考 APK MD5：`0907d6881bb1e034ef33a49f89afaa44`
- APK 大小：`35044459` bytes
- 真机行为基线：蜂窝网络 + 广州中转/DERP 最苛刻路径，打开会话“秒开、没有空白”。

`.team/perf/baseline-20260822.json` 及其同源 null/`INCONCLUSIVE` 历史内容只作无效旧证据，不具备可执行门权威；本契约不修改它们。

## 可执行门

每次候选改动必须在同一时段对同一隔离夹具做 A/B/A/B 交替测量：

- A：从 `baseline-20260822-release` 构建，或使用可核验的精确参考 APK；记录 source revision、APK 路径和 MD5。A 的行为身份必须绑定上述 tag 与参考 MD5。
- B：当前候选构建；记录 source revision、APK 路径和 MD5。
- A/B MD5 必须不同；相同 MD5 直接 `unjudgeable`，不得把同包测两次当对照。
- 每个夹具每个包至少 `n=10` 个完整冷点开样本；每个样本必须保留原始 `PerfTrace` 日志。
- 每批必须记录 load1、free、inactive、free+inactive，以及模拟器/隔离 daemon 身份。
- 极端值不删除；保留在 raw 日志和统计中的 outliers。

夹具固定为：

1. `big_scrollback`
2. `real_claude_idle`
3. `redraw_tui`

每个夹具必须计算以下四段（毫秒，均来自同一 `open_id` 的 `PerfTrace` 单调时间戳）：

1. `tap_to_route_enter = route_enter - tap`
2. `route_enter_to_first_frame = first_frame_recv - route_enter`
3. `first_frame_to_first_draw = first_draw - first_frame_recv`
4. `tap_to_first_draw = first_draw - tap`

每段分别计算 A/B 的 p50、p95；每段必须满足：

```text
B / A <= 1.10
```

缺事件、事件不单调、样本不足、缺 load 或 A/B 身份不能核验，均为 `unjudgeable`，不能包装成通过或失败。

## 仓库现有入口与隔离边界

仓库现有可执行入口已核到：

- `.team/nodes/ca-emu/tmp/setup-fixtures.sh`：创建三个夹具，tmux socket 为 `/tmp/e2e-ca-emu/tmux-<uid>/default`，显式 `tmux -S`、`unset TMUX`，并用 `list-sessions`/pane `comm` 自检；夹具内容为真实隔离目录下的假 `claude` pane。
- `.team/nodes/ca-emu/tmp/coldopen.sh`：对指定夹具执行 `am force-stop`、冷启动、语义 UI 定位，只收 `adb logcat -d -s PerfTrace`，输出 raw 日志和 host-load。
- `.team/nodes/ca-emu/tmp/runab.sh`：三个夹具各 10 次，但固定为每次 A 后 B，不是 A/B/A/B；仅通过 `APK_A`/`APK_B` 环境变量接收路径，不验证 A 是否为稳定 tag/参考 MD5，也不验证 A/B MD5 不同。
- `tools/perfbase/paired.py` 与 `tools/perfbase/judge-perf-ab.sh`：解析/判定只覆盖 `first_draw`、`layout_settled` 两段，不覆盖本契约要求的四段；`judge-perf-ab.sh` 的旧口径也是同批 A 地板，不是稳定 tag A。

上述入口的安全边界可复用：只使用自建隔离 tmux、隔离 daemon/state/端口和自造三个夹具；禁止扫描或点击真实会话，禁止连接/重启生产 daemon `:9900`，禁止读取凭据，禁止把工作目录外的临时件当证据。

## 缺口与阻断

当前树没有一个既有命令同时证明以下全部条件：

1. A/B/A/B 交替顺序；
2. A 精确绑定 `baseline-20260822-release` + `0907d6881bb1e034ef33a49f89afaa44`；
3. A/B MD5 不同的机械守卫；
4. 三夹具四段 raw 样本与 p50/p95 输出。

因此不能猜测、拼接或复用旧采样命令来宣称契约可执行；不能用历史 raw、null JSON 或旧的 A/B 同批结果补齐缺口。后续测量席必须先获得一个明确落盘、可执行的四段 A/B/A/B sampler/parser，并在本契约下复核其隔离和身份守卫。

contract: blocked
