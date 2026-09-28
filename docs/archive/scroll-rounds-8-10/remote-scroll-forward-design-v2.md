# 远程滚动投送协议设计方案 v2.1（定稿）

> 作者：协议设计席 w-scroll-design  
> 日期：2026-08-14，第 8 轮修订（leader 裁定后定稿）  
> 状态：**等待 leader 最终确认，确认后 dev 开工**  
> 写盘范围：`docs/` only，`app/` 与 `server/` 零改动

---

## 前六轮结论汇总（勿重蹈）

| 轮次 | 做了什么 | 为什么错 |
|---|---|---|
| 1–4 | 修 App 本地缓冲滚动 / 服务端 scrollback 分页坐标 | 用户要的是把滚动手势送到远端，不是修本地 |
| 5 | `send-keys -H` 注入 SGR/X10 字节 | 对 TUI 无效；tmux 鼠标转发走 `-M`，只在真实鼠标事件绑定里生效 |
| 6 | copy-mode + send-keys -X scroll-up | copy-mode 里 tmux 视角变了，但 pipe-pane 只抓运行程序写 PTY 的字节，恒 0 字节→客户端什么都收不到 |
| 7（v2 初稿）| copy-mode + scroll_position + capture-pane -S/-E | **copy-mode 是多余的**（leader 反驳，已认，见下节） |

---

## 答 leader 的挑战：为什么删掉 copy-mode？

**结论：copy-mode 没有必须存在的理由，删掉。**

leader 指出两个实测已证的事实：
- **(C/D)** `capture-pane -S/-E` 在**非 copy-mode 下**可正常读历史范围；
- **(E)** `#{scroll_position}` 在非 copy-mode 下是空串，无法使用。

但若服务端自己记录每个会话的 `scrollOffset`，就完全不需要从 tmux 读偏移量：

```
offset += delta
capture-pane -e -p -S -offset -E (-offset + height - 1)
```

没有任何实测障碍需要 copy-mode 来解决。alt-screen 下 `-S/-E` 读不到 scrollback，但 copy-mode 同样读不到——copy-mode 对此也无能为力。

**保留 copy-mode 的代价（已实际发生）**：
- 用户 pane 被留在 copy-mode（leader 截图确认，当前就已发生）
- 裸 shell 需要"打字脱困"特殊逻辑
- 需要 `pane_mode_changed` 状态帧（协议改动）

**CLAUDE.md 简洁优先：能删的步骤不留。** copy-mode 整块删除。

---

## 实测记录（全部已闭合，原样输出）

### 实测 A：pipe-pane 在 copy-mode 滚动期间捕获了多少字节？

```
=== A: pipe-pane bytes during copy-mode scroll: 0 bytes ===
```
**结论**：pipe-pane 恒 0 字节，结构上不可能到达客户端。

### 实测 B：`capture-pane -e`（无 -S/-E）在 copy-mode 下

```
capture-pane -e top (expect ~0212 if scroll visible): output-line-0262
```
**结论**：`capture-pane -e` 对 copy-mode 滚动视角**盲**，仍返回正常屏幕。

### 实测 C/D：`capture-pane -S/-E` 不在 copy-mode 下

```
capture-pane -S -40 -E -10 (no copy-mode): output-line-0222   ✓
capture-pane -S -40 -E -10 after new output: output-line-0224 ✓（随新输出自动平移）
```
**结论**：`capture-pane -S/-E` **无需 copy-mode**，坐标始终相对当前屏底自动平移。

### 实测 E：`#{scroll_position}` 边界值

| 状态 | `pane_in_mode` | `scroll_position` |
|---|---|---|
| 非 copy-mode | 0 | *(empty)* |
| 刚进 copy-mode，未滚动 | 1 | `0` |
| scroll-up 20 行 | 1 | `20` |
| scroll-up 100 行 | 1 | `100` |
| 滚到历史顶（500 行内容） | 1 | `768`（≈ max_history） |
| 退出 copy-mode 后 | 0 | *(empty)* |

**结论**：非 copy-mode 时空串，不可用。**服务端自持 offset 后此变量完全不需要。**

### 实测 F：坐标公式验证

```
# offset=20, height=40
from=-20, to=19 → first=HIST-0442  预期=0462-20=0442  ✓

# offset=100, height=40
from=-100, to=-61 → first=HIST-0362, last=HIST-0401  ✓
```
**坐标公式** `from = -offset, to = -offset + height - 1` 实测两点验证通过。无需 copy-mode。

---

## 定稿方案：服务端自持 offset + capture-pane -S/-E + SNAPSHOT

### 会话状态（新增一个字段）

```go
// 每个 pane 会话
scrollOffset int32  // 默认 0（看实时）；> 0 = 向上滚了多少行
```

### 数据流

```
App 手势上滑 N 行（deltaLines > 0）
  │
  └─► scroll_wheel{delta = -N} ──────────► handleScrollWheel(ref, delta=-N)
                                                  │
                                            scrollOffset += abs(delta)              ①
                                            clamp to #{history_size}                ②
                                                  │
                                            if #{alternate_on} == "1":              ③
                                              push SNAPSHOT{content="", scroll_offset=-1}
                                              return
                                                  │
                                            from = -scrollOffset
                                            to   = -scrollOffset + paneHeight - 1  ④
                                            content = capture-pane -e -p -S from -E to
                                                  │
                                            push SNAPSHOT{content, scroll_offset=scrollOffset} ⑤
                                                  │
                             (offset > 0 期间：不转发 pipe-pane DELTA 到客户端)      ⑥
                                ◄──────────────────────────────────────────────────

App 收 SNAPSHOT →
  emulator.replaySnapshot(content)          // 整屏替换
  showScrollIndicator(scroll_offset)        // -1 → 显示"不支持"；>0 → 显示行数
```

**向下滑（delta > 0）**：

```
scrollOffset = max(0, scrollOffset - abs(delta))
if scrollOffset == 0:
  content = capture-pane -e -p              // 当前实时屏
  push SNAPSHOT{content, scroll_offset=0}
  resume DELTA forwarding                   // 恢复 pipe-pane 转发
else:
  push SNAPSHOT{content=capture(-S/-E), scroll_offset=scrollOffset}
```

**用户输入（keypress）**：

```
scrollOffset = 0
push SNAPSHOT{content=capture-pane -e -p, scroll_offset=0}
resume DELTA forwarding
```

### 关键决定一览

| 决定 | 理由 |
|---|---|
| **不进 copy-mode** | capture-pane -S/-E 在非 copy-mode 下工作；copy-mode 只增加状态污染 |
| **服务端自持 scrollOffset** | 唯一真相源；不依赖 `#{scroll_position}` |
| **SNAPSHOT + scroll_offset 字段** | 复用已有帧类型；字段提供 (a) 客户端视觉指示 (b) 红测观测量 |
| **offset > 0 期间抑制 DELTA** | 防撕裂；语义完整；协议零新增 |
| **offset 归 0 时推实时 SNAPSHOT** | 对账，确保客户端状态与服务端一致 |
| **alt-screen 检测** | `#{alternate_on}` 读取；scroll_offset=-1，客户端区分"不支持" |

---

## leader 裁定三条（已纳入）

### Q1：pipe-pane 共存规则 → 完整版语义，零新字段

- offset > 0：服务端**直接不发 DELTA**（pipe-pane 输出不转发）
- offset 归 0：推一帧实时 SNAPSHOT + 恢复 DELTA 转发
- 协议零改动，语义完整

### Q2：App 收 SNAPSHOT 后 → 整屏替换，客户端不维护本地偏移

- 滚动位置唯一真相源：服务端 `scrollOffset`
- 客户端不维护本地偏移，避免两套坐标互相污染（前四轮翻车根因）

### Q3：SNAPSHOT 加 scroll_offset 字段 → 加

- `scroll_offset: int32`（0 = 实时；> 0 = 向上滚 N 行；-1 = alt-screen 不支持）
- 用途 (a)：客户端显示滚动指示（替代 copy-mode 气泡）
- 用途 (b)：红测观测量，可直接断言

---

## 分层责任表（定稿）

| 层 | 职责 |
|---|---|
| **App 手势层** | `onScrollWheel(N)` → 发 `scroll_wheel{delta=-N}`；不维护本地 offset；收 SNAPSHOT 调 `emulator.replaySnapshot()` + 展示 scroll_offset 指示 |
| **协议层** | `scroll_wheel`（C→S，已有）；`BinaryKind.SNAPSHOT` + 新增 `scroll_offset int32` 字段 |
| **服务端 handleScrollWheel** | 更新 `scrollOffset`；检测 alt-screen；capture-pane；push SNAPSHOT；offset > 0 抑制 DELTA |
| **服务端 handleInput** | 收到 keypress：`scrollOffset = 0`；推实时 SNAPSHOT；恢复 DELTA 转发 |
| **tmux** | `capture-pane -S/-E`（无需 copy-mode）；`#{history_size}` clamp；`#{alternate_on}` 检测 |

---

## 三档场景行为

| 档位 | 说明 | 效果 |
|---|---|---|
| **① Claude Code（非 alt-screen）** | 主场景 | **有效 ✓**：offset 定位 → capture-pane -S/-E → SNAPSHOT |
| **② alt-screen（vim/less/htop）** | 备用屏，无 scrollback | **明确不支持**：scroll_offset=-1，客户端显示"不支持"，可分辨 ✓ |
| **③ 裸 shell** | 不切备用屏 | **有效 ✓**：同档位① |

---

## 验收判据（leader 指定）

1. **`pipepane_scroll_probe_test.go` 翻转**：post-scroll > 0 字节**且内容是滚动后的视口**（首行 ≠ 正常屏首行），不是简单非零就算过。
2. **w-scroll-test 现在红的三条转绿**，alt-screen 那条合格标准是**客户端能分辨出"不支持"**（scroll_offset=-1），不是"让 vim 也能滚"。
3. **现在绿的（打字脱困）不倒退**：keypress → scrollOffset 归 0 → 实时恢复，由 T-s6 / T-a4 覆盖。

---

## 红测草案（定稿）

### 服务端红测

| 编号 | 测试名 | 自校验前置 | 断言 |
|---|---|---|---|
| T-s1 | 裸 shell + scroll_wheel → SNAPSHOT 含历史内容 | 先写 ≥1 行历史，记屏首行，否则 SKIP | SNAPSHOT.scroll_offset > 0；首行 ≠ 当前屏首行 |
| T-s2 | 坐标公式：offset=N → 首行 = 屏首行 - N | 同 T-s1 | 首行编号差 = offset |
| T-s3 | alt-screen（vim）→ scroll_offset=-1 | 先验 alternate_on=1 | SNAPSHOT.scroll_offset == -1 |
| T-s4 | offset > 0 时 DELTA 不转发 | 进入滚动状态后，进程写新输出 | 新字节不推到客户端连接 |
| T-s5 | offset 归 0 → 推实时 SNAPSHOT + 恢复 DELTA | 先 offset>0，再归 0 | scroll_offset=0 的 SNAPSHOT；之后 DELTA 正常 |
| T-s6 | keypress → offset 归 0 + 实时恢复（打字脱困） | 先 offset>0，再发 keypress | scroll_offset=0 的 SNAPSHOT（不倒退） |
| T-s7 | offset clamp 不超 history_size | history_size=50，发 delta=-200 | offset=50，SNAPSHOT 首行 = 历史最顶行 |
| T-s8 | **漂移行为钉住**（不断言它对，只断言当前实际） | 写 100 行；回滚 offset=50；再写 10 行新输出 | 再次 capture 时首行 = (原屏首行+10)-50（而非原锚点），钉住漂移量=新输出行数；此测试失败即漂移行为已被修复或改变，需重新评估 |

### App 端红测

| 编号 | 测试名 | 断言 |
|---|---|---|
| T-a1 | READY + onScrollWheel → 发 scroll_wheel 帧，delta 符号正确 | 发出且 delta < 0 |
| T-a2 | 收 SNAPSHOT(scroll_offset>0) → emulator 替换 + 指示器显示 | replaySnapshot 调用；UI 展示"滚动 N 行" |
| T-a3 | 收 SNAPSHOT(scroll_offset=-1) → 显示"不支持"，不崩溃 | UI 展示不支持提示 |
| T-a4 | 用户输入 → 最终推 scroll_offset=0 SNAPSHOT（打字脱困，不倒退） | 恢复实时 |

---

## 已知局限

| 局限 | 影响 | 处置 |
|---|---|---|
| **alt-screen（vim/less/htop）** | 无 scrollback，不支持远程滚动 | scroll_offset=-1 返回，客户端明确告知 |
| **RTT 延迟** | 手势到画面 ≥ 1 RTT（~123ms） | offset 更新+capture+push 在单次 handleScrollWheel 内完成，无二次往返 |
| **高速输出期间 offset 相对漂移** | 用户回滚后 Claude Code 继续输出，同样的 offset 指向更新的行，内容跳变（可被用户看见） | **已知局限，非"不是 bug"。** 现在钉住实际行为（T-s8），不断言它对，仅使其可见。修法：滚动开始时记 history_size 作锚点，按锚点算 -S，而非当前屏底。缓期：④ 的一阶问题（内容根本到不了客户端）优先，二阶精度后续迭代。 |
