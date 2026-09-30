# P0：ledger-run --drive 进入「判据 acceptance」后挂死，零 CPU、零子进程、无超时

- 日期：2026-08-19
- 报告方：远程Agent安卓 leader（账本编排框架的使用者）
- 账本：`.team/ledgers/refresh-v1.json`（ledger.refresh.v1，revision 2）

## 一、现象

驱动器进程存活但**完全不动**：

- 最后一条日志 `01:07:47Z 判据 acceptance | task=t.srv 在跑 required=["A-s-test","A-s-suite","A-s-doc"]`
- 观测时刻 `01:54:44Z` —— **47 分钟没有任何后续输出**
- 进程仍在（`ps -o pid=,etime=,stat=,time=,comm= -p 86766` → `01:00:46 SN 0:01.13 ledger-run`）：
  **存活 60 分钟累计 CPU 仅 1.13 秒**
- 全机**没有任何 go 进程**（`ps -o comm=` 过滤 go/compile 零命中）⇒ 判据的子进程根本没起来，
  或起来后已消失而驱动器没有察觉
- 账本文件 mtime 停在 09:00（本地时区），日志 mtime 09:07，之后再无写入
- `A-s-suite` 的 `time_budget_seconds` 是 1800（30 分钟），**已超 17 分钟仍未触发任何超时处理**

## 二、日志与量具身份

量具：

```
ls -la $(which ledger-run)
-rwxr-xr-x  1 alauda  staff  13547664  8月 18 19:51 /Users/alauda/.cargo/bin/ledger-run
md5 = 5e17f4ab1d6f11001f46ee7e9f08ea8f
```

日志（`.team/ledgers/rf-drive.log` 末 6 行，mtime 2026-08-19 09:07 本地）：

```
[01:00:37Z] 派单 dispatch | task=t.srv 收件人=…::grok-l2/dev-server
[01:00:37Z] 投递回执 send-ok | case_id=ledger_refresh_v1__t.srv__r2 message_id=msg_0f4e7a639c48
[01:00:37Z] 派单落地 dispatch-landed | 席位 dev-server 的收件箱已多出这条派单
[01:00:37Z] 等待 wait | 在等的 key（case_id）=ledger_refresh_v1__t.srv__r2 team=grok-l2 预算=950s
[01:07:47Z] 已唤醒 wait-signaled | task=t.srv 等到结果了
[01:07:47Z] 判据 acceptance | task=t.srv 在跑 required=["A-s-test", "A-s-suite", "A-s-doc"]
   ← 此后 47 分钟无任何输出
```

## 三、复现 / 先查自己的结果

**已排除我方**：把它声称在跑的判据手动跑一遍，全部正常且快：

```
cwd=/Volumes/nvme/Projects/远程Agent安卓/server
go test ./... -count=1 -timeout 240s     # 无任何 FAIL，约 20 秒
```

⇒ 判据本身既不失败也不挂。挂的是驱动器对判据的执行/收敛环节。

三条判据（均在账本 `tasks.t.srv.acceptance.mechanical`）：

- `A-s-test`  `sh -c "cd <repo>/server && go test ./... -run TestRefreshOnOpen -count=1"` budget 950
- `A-s-suite` `sh -c "cd <repo>/server && go test ./... -count=1"` budget 1800
- `A-s-doc`   `test -s <repo>/.team/nodes/rf-srv/说明.md` budget 950

## 四、原因分析及其边界

**查到这里为止的事实**：

1. 驱动器进入判据阶段后零 CPU、零子进程、零输出。
2. 判据本身可独立跑通。
3. `time_budget_seconds` 到期没有产生任何可见行为 —— **超时机制在这条路径上没有生效**，
   否则 1800 秒前就该收敛。

**从这里开始是推测，未验证**：

- 本机同时存在另外两个 `ledger-run`（pid 15711 存活 57 分钟、pid 31906 存活 12 小时，
  分别属于本机其它工作区）。若判据执行路径上有跨进程共享资源（如 `team.db` 的 SQLite 写锁），
  三者互等会形成零 CPU 的挂死，且**与「判据在跑」的日志表现完全一致**。
  这条我**没有验证**，只是它符合「零 CPU + 无子进程 + 无超时」这组特征。
- 也可能是判据子进程的 spawn 或其 stdout 管道读取阻塞在一个没有超时的 `wait` 上。

**请求**：无论根因是哪个，`time_budget_seconds` 到期必须产生**可见结果**
（收敛为失败 / 记一条超时日志 / 杀掉子进程）。当前形态下，超时预算形同虚设，
而「驱动器挂死」和「判据在长时间正常运行」在日志上完全同形 —— 使用方无法区分。

## 五、我方处置

按 2026-08-18 用户裁定「投完报告就继续，不停工」：kill 该驱动器并重启同一账本，任务继续。

---

## 复发记录 #2（2026-08-19 14:45 CST / 06:45Z）

同一形状再次命中，**不另发信**（同一缺陷，避免占用跨 agent 往返配额）。

- 量具身份：`/Users/alauda/.cargo/bin/ledger-run` mtime `8月 19 10:35` md5 `e3b6683af465b13f4fbade6927decbb0`
- 账本：`.team/ledgers/refresh-v1.json` revision=5，task=`t.srv`
- 日志冻结点：`[2026-08-19T05:46:35Z] 判据 acceptance | task=t.srv 在跑 required=["A-s-test","A-s-suite","A-s-doc"]` —— 此后 **59 分钟零新行**
- 进程：`ps -o pid,ppid,etime,stat,comm` → 存活，`STAT=SN`，`%CPU=0.0`，累计 `TIME=0:01.92`
- **子进程数 = 0**（`pgrep -P <pid>` 返回空）——判据本该在跑 `go test`，却没有任何子进程
- 对照组：同一时刻另一驱动器（v72-v1）处于 `等待 wait` 阶段，同样零子进程但日志仍按预算推进 ⇒ **挂死只发生在 acceptance 阶段**

与首报的差异：本次 `A-s-suite` 是 `go test ./... -count=1`（分钟级），首报是同类长命令。
⇒ 复现条件收窄为：**acceptance 里存在分钟级机械命令时，驱动器在收集其输出处挂死**。

## 复发记录 #3（06:53–07:22Z）＋ **复现条件收窄到单变量**

同一台机、同一二进制、同一批「判据输出重定向」绕法，**两个驱动器并排跑**，结果相反：

| 驱动器 | 进 acceptance 的**前一步** | 结果 |
|---|---|---|
| v72-v1（pid 79278） | `已唤醒 wait-signaled \| 等到结果了` | ✅ 判据整套跑完（6 条），正常写回并推进下一格 |
| refresh-v1（pid 79279） | `无信号 wait-no-signal \| 预算内没等到这把 key 的结果` | 🔴 挂死 13 分钟，零子进程，**第一条判据的日志文件从未被创建** |

🔴 **收窄结论**：挂死不是「判据命令太慢」，而是 **`wait-no-signal` 超时路径进入 acceptance 时挂死**。
`wait-signaled` 路径进入 acceptance 完全正常。今日三次挂死（09:55 首报、06:45 复发 #2、本次 #3）
**全部**发生在 `wait-no-signal` 之后；无一例发生在 `wait-signaled` 之后。

证据：`A-s-test` 的重定向目标 `.team/acclogs/A-s-test.log` **不存在**（同批绕法下 v72 的
`A-id-go.log` / `A-om-go.log` 都正常写出）⇒ 连 shell 都没被 fork 出来 ⇒ **挂点在派生子进程之前**，
不在收集输出处。这与首报「`read_to_string` 收不到 EOF」的推测**不一致**，请以本条为准。

⛔ 判断边界：我没读你们源码，以上全是外部观察。
