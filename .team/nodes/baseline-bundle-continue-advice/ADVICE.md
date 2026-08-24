# baseline-bundle-v1 合法继续路径

## 结论

当前这条账本没有可合法执行的继续动作，结论为 `blocked`。用户最新裁定只解除“框架出问题就冻结所有产品工作”的停工语义；它没有授权手写 live JSON、清洗失败 attempt、绕过 `plan/apply`、绕过判据，或人工 `team-agent send` 代替账本派单。因此可以继续其它已有合法入口的产品工作，但不能伪造 baseline-bundle 的下一格。

## 当前事实与阻塞点

- live 账本是 `.team/ledgers/baseline-bundle-v1.json`，`ledger_id=ledger.baseline-bundle.v1`、`revision=1`；`t.baseline-bundle.repro` 是 `failed_retryable`，保留一个失败 attempt。
- 现行创作源是 `.team/ledgers/src/baseline-bundle-v1.py`。`test`、`probe`、`impl` 的 `Task.parallel="baseline-bundle-wave"` 同时被 v0.1.1 DSL 模型和同代 schema 接受。
- `ledger-run --preflight --json <live>` 已通过；`ledger-run --dry-run --json <live>` 退出 0，但 repro 因失败冻结而不可派，下游因 `requires_success` 被排除。
- `ledgerdsl.plan(source, live)` 在拆 `t.baseline-bundle.impl` 时因 `parallel` 未进入 `field_ownership()` 的创作面而退出 2；已有 P0 证据为 `.team/artifacts/ledger-p0-ledgerdsl-plan-rejects-parallel-20260824.md`。该次 `apply` 未执行，live 与旧 attempt 未改变。
- `ledger-run --help` 的 `--resident` 只是长期前台驱动模式；`team-agent --help` 没有 managed/background task submit API。现有 `.team/watchdog-supervisor.sh` 只守护 watchdog，且其文档入口含 `setsid nohup ... &`，不是 driver 提交器。
- 既有 driver 的 `ppid=1` 只证明进程后来脱离了调用者，不证明 Pi harness 有合法的受管提交入口；不能据此复制其启动方式。

## 最小恢复路径（仅在缺口补齐后执行；本轮不执行）

### 1. 先保留现场

保留上述 live、`failed_retryable` attempt、P0 计划失败日志及当前 source。不得手写或覆盖 `.team/ledgers/baseline-bundle-v1.json`，不得删除 attempt，不能用 `abandon_state` 逃过语义变更，也不能手工制造新 case。

### 2. 等框架方发布两个必要能力

这不是本席修改框架，而是恢复前置：

1. ledgerdsl 的 `field_ownership()` 必须把模型/emit/schema 已承认的 `Task.parallel` 纳入创作面，同时继续拒绝拼写错误的未知字段；修复后要有 plan/apply 回归。
2. 当前 Pi 必须暴露真实 managed-task submit API，至少接收 `cwd`、前台 `argv`、stdout/stderr 路径，并返回可管理句柄或 PID。可替代地，提供已注册、受管的本机服务入口；普通 bash 后台不算。

### 3. 生成 candidate，过门，再做受管 surgery

框架修复安装到实际量具后，候选只由现有 DSL 编译生成，不手写 JSON：

```sh
set -eu
WS=/Volumes/nvme/Projects/远程Agent安卓
DSL="$WS/.team/ledgers/src/baseline-bundle-v1.py"
CAND="$WS/.team/nodes/spec-sol/baseline-bundle-repro-fix/baseline-bundle-v1.candidate.json"
PYTHONPATH=/Users/alauda/.agents/skills/ledger-orchestration/reference/ledgerdsl-0.1.1 \
  /usr/bin/python3 "$DSL" > "$CAND"
ledger-run --preflight --json "$CAND"
```

然后用发布后的同一 DSL 量具对真实 live 生成计划（此命令只读）：

```sh
PYTHONPATH=/Users/alauda/.agents/skills/ledger-orchestration/reference/ledgerdsl-0.1.1 \
  /usr/bin/python3 - <<'PY'
import contextlib, io, runpy
from ledgerdsl import plan
with contextlib.redirect_stdout(io.StringIO()):
    source = runpy.run_path(".team/ledgers/src/baseline-bundle-v1.py")["ledger"]
print(plan(source, ".team/ledgers/baseline-bundle-v1.json").render())
PY
```

计划必须明确：合法 `parallel` 不再被报未知；repro 的旧运行事实会被安全失效/归档；后继 revision 为 2；没有无关任务的语义漂移。leader 审核计划后，且确认 live 无 lease、driver 已由受管入口停妥，才可调用 DSL 的 `apply(source, ".team/ledgers/baseline-bundle-v1.json")`。apply 后重新运行：

```sh
ledger-run --preflight --json .team/ledgers/baseline-bundle-v1.json
ledger-run --dry-run --json .team/ledgers/baseline-bundle-v1.json
```

只接受 revision 前进、旧事实有 surgery 侧车、frontier/excluded 形状与依赖一致的结果；否则停下并记录 `blocked`/`needs_reconcile`，不重派。

### 4. 只用受管入口提交 resident

上述门全过后，受管提交器的 payload 才能是：

```text
cwd=/Volumes/nvme/Projects/远程Agent安卓
argv=[ledger-run,--resident,--json,/Volumes/nvme/Projects/远程Agent安卓/.team/ledgers/baseline-bundle-v1.json]
stdout/stderr=/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/_driver/baseline-bundle-v1.out
```

child 必须以前台 argv 运行；提交器返回可管理 PID/句柄后，才写 `.team/nodes/_driver/baseline-bundle-v1.pid` 的 `pid=<pid>`。日志必须直接落在该路径，或在同一文件系统以硬链落到该路径，不能软链。

随后仅做既定只读核验：PID file 对应 PID、`ps -o pid,ppid,etime,stat,comm` 的 `comm=ledger-run`、`lsof` 的 cwd 精确为仓根；日志出现首格 `dispatch-landed`；Pi 私有 socket 上的 nodeprobe 将 `sampler-test-luna2` 判为 `working`。不读取 argv/凭据，不碰真实 tmux。只有这四项齐全才算首格真的启动。

## 纪律冲突的处理

若旧文件仍把“框架故障后停止一切产品工作”写成硬规则，应按用户最新裁定改读为“报告交割后，继续其它有合法执行入口的工作”。但“不得绕判据/不得手写 live JSON/不得人肉派产品格”和“resident 必须受管提交”没有被覆盖，不能用“继续”作为例外。当前 baseline-bundle 同时缺少合法 surgery（`plan` 被拒）和受管 resident submit，所以本链仍必须停在 `blocked`；这不是把全局产品工作停下。

## 本轮未做

未修改框架、live ledger、source、attempt 或判据；未调用 `apply`；未启动或停止任何 driver；未使用 `&`、`nohup`、自 daemonize 或真实 tmux；未人工 `team-agent send` 产品格；未读取 argv、凭据或生产日志。

verdict: blocked
