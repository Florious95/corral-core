# Leader 阶段二裁定与施工准入（2026-09-07）

输入：Astra SYNTHESIS.md / PR-TASKS.md（res_efac02b5f5e9），Luna REPRO.md及evidence（res_efaad1b46ae8），当前GOAL与安全覆盖。阶段二不是五项全链通过。

## 接受的归责与路线

- S2/S3/N2 的交付回归链：PR86 从旧main基座出发→093aae8抽取→f629补信封→33b4重钉并运行。保留已验前台身份差量，撤回旧基座产物进入消费链的选择；不能只说“PR86没有删除Pi所以无责”。
- T-N 由基本Pi/Luna在core#86 owning分支合入精确C70完整源码，再按当前skill修真正命中的安全/四轴边界，不直接装863c。PR86相对新base C70的作者差量仅nodeprobe声明范围；C70祖先内容作为依赖引入，不借merge额外修改App/serve。
- T-A 在core#73撤掉完整health→activity veto及caller无效参数，不能继续扩health允许表；保留在线/离线、图标、节奏、三面收藏。
- T-S 等精确N收据后更新serve#6，组合保留#7/#11/#12；不得填未来hash或直接发布NOT-PRODUCT手验叠包。
- T-NAME 采用Codex官方完整terminal title作为显示标签（不是解析纯thread名），Pi独立官方session_name保真；未知不拿node/编号/cwd伪名。它与Pi缺channel的上游回退分开。专门命名PR对必须先有实际Codex标题已正确而旧消费错误的受控红，不能用Pi替代。

## 已有红证据与不采纳的夸大

- Codex：旧f629/817/5ee已有10帧逐帧红及idle/未知控制；作者开工仍须绑定自己引用的实际证据/产物哈希。
- Pi：合成Node事件驱动了extension的start/name/change/challenge/settled/shutdown，只是channel层；“缺channel应unknown/unknown”是契约预期，未经过实际probe消费，不当该条绿。
- shell：自有物理隔离socket两个普通shell不进旧probe输出；这不是daemon authenticated listing对比、不是多socket活Agent/退出残留全套验收。
- N1：真实Codex0.153.4 /rename与OSC完整标题已经观察到两个不同中文名；但旧消费name=node仅手写合成，未执行旧转换，消费红仍待补。修订res_f368be95e0b0已恢复完整title、明确消费NOT_RUN及活身份unknown；新增窄PID来自另一轮自有夹具，不能由残留标题推断活身份。实际旧converter测试准备已派msg_852e4009fc80，资源放行前不执行构建。
- S1：正式596 APK在自有API35 AVD上真实投影红已执行，leader核52帧XML、normal/unknown截图与APK哈希：working/normal动、working/unknown及abnormal不动；idle/offline空行/恢复/重订阅控制有观察。不是当前C73二进制红；当前C73允许unknown但仍abnormal veto仅源码已核，T-A必须在精确C73先执行具名abnormal红再改。
- 旧f629源会capture-pane，summary里的 raw_pane_output_read=false 不作为旧binary无副作用证明；只承认其使用范围是自有合成socket。G-SAFE必须实际调用trace+拒绝反控。

## 当前执行

1. 新 `probe-fix-luna`：T-N，先补自身缺失具名红/安全反控，再修、远端构建、更新PR86、交N收据。不得自验最终全链。
2. `ui-repro-luna`：正式596 App投影红已接受；修订res_f368be95e0b0已核版本/完整title/unknown/NOT_RUN边界。后续msg_852e4009fc80只准备精确S-RUN旧converter测试并修报告漏一位的hash，不重跑52帧，不改产品。
3. 新 `app-fix-luna` 准入T-A准备：正式596 UI红已经到位，但精确C73 targeted abnormal红必须先于产品修改。当前远端空间门未过，不启动新sync/build；只冻结/读代码/准备测试，放行后先红再修。T-S等N；T-NAME等真正消费红与T-A/T-S模块写锁释放。T-N独立继续。
4. 后续独立Pi/Luna验收冻结N/S/A实际产物：真实CLI→probe→serve→WS→UI、≤5s、名字/动态/重订阅、已验行为与G-SAFE。未验组合不得部署或merge。

心跳仍卸载且wrapper拒绝运行；不准旧probe在default/共享/真实socket自检。安全新产物验过才恢复。没有生产操作、没有merge授权；现有全局二进制与用户Pi不动。
