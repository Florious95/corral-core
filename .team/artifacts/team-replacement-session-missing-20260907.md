# 换席后 coordinator.session_missing（事实报告，P0）

工作区：/Volumes/nvme/Projects/远程Agent安卓；团队 regressions-20260907；量具 team-agent 0.5.72。

用户授权关闭迟迟未完成真实AVD前置的原席并换角色。公开CLI依次执行 remove-agent app-fix-luna --from-spec --confirm --force（removed）、add-agent real-avd-astra --role-file ...（running/coordinator_started=true）、send（msg_f75964dcbbc0 accepted/queued，未宣称已投递）。随后协调器通知 tmux session team-regressions-20260907 is missing，coordinator is stopping，action restart the team or recover the missing tmux session。

status 当时仍列唯一 real-avd-astra running，ready=false/workers_not_spawned；不能从这些字段判断实际判活。leader按公开action执行 restart WORKSPACE --team regressions-20260907 --json，返回 restarted/ok，coordinator PID54239 already_running，自动绑定leader %299。新席随后确认接管并创建 RESULT.md。生产PID92596/9900与自有emulator-5580（adb5038）在恢复前后均保持。

后续一次公开diagnose返回 issues=[]、leader attached，runtime message_count=0/result_count=0；RESULT.md仍为初始接管阶段。尚不能据此归因任务丢失，leader只发一次编排恢复消息msg_487fb4993088要求从已有进度续跑，不重复产品任务。

原因边界：仅确认换席期间会话缺失通知与公开恢复结果，未查引擎/内部状态/原始pane，未做复现阶梯。预期：成功add/send之后的会话可用性应一致；若瞬态必须有可靠恢复和任务连续性，不让产品任务静默停滞。本方已恢复并继续实际工作，不等待框架回复。

对应日志入口（未为框架另采）：.team/logs/events.jsonl。当前任务进度：.team/nodes/real-avd-astra/RESULT.md。

恢复后实际产品推进仍停滞。leader一次approvals核waiting=false、collect无新席结果；用既有已验私有nodeprobe（e5667b9e，部署corpus）核default socket，唯一real-avd-astra/%313只有/bin/sh与sleep，provider=unknown/activity=idle，未识别Codex。start-agent --force返回exit1：same-role cohort duplicate proof failed; real-avd-astra:window=real-avd-astra:live_panes=[%313]，无action。

产品恢复：先add real-avd-astra2（任务未投递时不操作AVD），再remove失效real-avd-astra，最后正式send msg_2c0c0d21ea78。nodeprobe已核新%314 provider=codex/activity=working，进程链含原生Codex。这是正常产品恢复中的客观核验，不是为框架复现。当前结果入口改为 .team/nodes/real-avd-astra2/RESULT.md。未私改框架或操作其他团队。
