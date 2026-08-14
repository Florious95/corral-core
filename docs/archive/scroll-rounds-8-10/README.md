# 归档：缺陷④「上滑投送到远端」第 8~10 轮（2026-08-14）

**状态：方向错误，全部回退。代码已从工作区移除，此处保留全部内容供后续参考。**
**用户 2026-08-14 裁定：「把上滑修了好多次的改动全部归档，不能让错误方向的修改影响污染后续」。**

---

## 一、推翻这三轮的三个数

leader 在自己（跑着 Claude Code 的）tmux pane 上实测：

```
tmux display-message -p '#{alternate_on} #{history_size} #{mouse_any_flag}'
→ alternate_on = 1      Claude Code 是 alt-screen TUI
→ history_size = 0      它的 pane 在 tmux 里【零行】scrollback
→ mouse_any_flag = 1    它自己【已经开着】鼠标上报
```

对照，一个裸 bash pane：`alt=0  hist=1850  mouse=0`。

**三条结论：**

1. **第 8~10 轮的架构对 Claude Code 结构性无效。**
   我们做的是「服务端自持 scrollOffset + `capture-pane -e -S/-E` 读 tmux scrollback + 推 SNAPSHOT」。
   而 Claude Code 的 pane `history_size = 0` —— 读取器读的是一个**空的历史**。实现再正确也读不出东西。

2. **「Claude Code 属于非 alt-screen 第①档」这个前提从第一天起就是错的。**
   HANDOFF §4.1 那张三档表把 Claude Code 列为「能工作」、把 alt-screen 列为「不支持的边缘情况」。
   实际上 **alt-screen 正是用户唯一在用的场景**，第①档在现实中不存在。

3. **「SGR 鼠标字节注入是死路」这个结论也是错的。**
   2026-08-14 上午的探针测的是 `less` / `vim`，那些进程 `mouse_any_flag = 0`
   —— 没开鼠标上报，收到鼠标字节当然没反应。
   **Claude Code 是 `mouse = 1`，发滚轮字节它会解析。** 鼠标路是活的，而且很可能是唯一对的那条。

---

## 二、根本病因：连续六次「验证的不是真正的被测对象」

这三轮不是修得不够好，是**每一层的验证对象都是替身**：

| # | 我们验的 | 真正的被测对象 |
|---|---|---|
| 1 | `capture-pane`（tmux 视角） | `pipe-pane`（产品推流通道） |
| 2 | 红测手搭的 OkHttpClient 副本 | `OkHttpTransportFactory.create()` |
| 3 | 直接注入 `protocol.ScrollWheel` 帧 | 手势层 |
| 4 | 断言 `<0`，而实际值是 `Int.MIN_VALUE` | 真实 `lineHeightPx` |
| 5 | `adb input swipe` 合成事件 | 真手指 |
| 6 | **裸 shell**（`alt=0 hist=1850`） | **Claude Code**（`alt=1 hist=0`） |

第 6 条废掉的是三轮架构。前五条只废测试。

**通则（已写入下一轮契约）：动手前先证明「我验的东西，就是用户用的东西」。**

---

## 三、这三轮里【仍然成立】的东西（不要连同架构一起丢掉）

1. **`copy-mode` 滚动的结果进不了 `pipe-pane` 推流** —— 字节级实证，post-scroll 恒 0 字节 0 chunk。
   这条是真的，`pipepane_scroll_probe_test.go` 保留在 `tests/`。**任何新方案都不能依赖 copy-mode 把画面送出去。**
2. **`Subscribe` 的契约 `@inv none — 纯镜像，只读 pane 输出流`** —— 客户端画面的唯一来源是 pane 里程序写进 pty 的字节。
3. **手势层符号错**（上滑发正值 = 协议的"向下"）与**手势层丢帧**（不足一行的位移被丢弃、无累加器，
   700px 拖动只送达 3 行）—— 这两个是真 bug，与架构无关，**换方案后仍然要修**。
   补丁在 `diffs/rounds-8-10.patch`，可直接参考。
4. **短距离上滑死区**（模拟器上 150px 以下 0 帧，而 TapSlop 只有 20px）—— 未解释，
   见 taskbook `investigate-short-swipe-deadzone`，且已判明需真手指数据。

---

## 四、⚠️ 部署与源码的分歧（后续第一件事就要处理）

回退发生在**源码**层。而生产 daemon **pid 86755** 跑的是第 8~10 轮编出来的二进制
（sha256 `14001c9d2b754ec964ee7f57aa6988723eba8e1875fd0b62280d5597faa20557`，含 `ScrollState`、
无 `InjectScroll`），**它与回退后的源码不一致**。

- 回退前的二进制已备份：`server/agentmirrord.bak-20260814144430`
- 当前不立即回滚部署的理由：用户此刻正连着它工作，且新二进制对滚动帧只是"不推快照"，不会报错
- **但这正是 2026-08-14 上午 ④ 第一次失败的坑**（跑的是旧二进制、验的是新源码）。
  下一轮开工前必须先把这个分歧消掉，不许带着它施工。

---

## 五、目录内容

```
README.md                              本文
remote-scroll-forward-design-v2.md     第8轮契约方案书 v2.1（无 copy-mode 版）
remote-scroll-pipe-pane-probe.md       根因探针报告（pipe-pane 恒 0 字节，此结论仍成立）
diffs/rounds-8-10.patch                第8~10轮全部已跟踪改动（932 行）
tests/scroll_forward_scenario_test.go  场景红测（判据对，但被测对象是裸 shell，需重写）
tests/pipepane_scroll_probe_test.go    pipe-pane 探针（结论仍成立，可直接复用）
tests/TermSurfaceRemoteScrollSignTest.kt  手势层符号+守恒红测（与架构无关，换方案后仍有效）
```

现场截图证据留在 `e2e/artifacts/scroll-qa-{before,after,v9,v10,frame-probe}/`，未移动。
