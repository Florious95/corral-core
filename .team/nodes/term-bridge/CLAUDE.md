# 知识基底 · term-bridge（系统编译产物）

## 0. 任务（taskbook.yaml#term-bridge）
- 目标：单 pane 终端桥：首帧快照、增量字节流、整条输入注入（带回执）、resize、scrollback 分页。
- 验收（exit 0 = 过）：`bash -lc 'cd /Volumes/nvme/Projects/远程Agent安卓/server && go test ./internal/bridge/...'`
- 写范围：`server/internal/bridge/`。红线：注入必须有可判定回执；对目标 pane 只做镜像与注入，绝不改其运行状态（不 kill、不 detach 他人客户端）。

## 1. 架构基（每项能力的 tmux 机制）
- **快照**：`tmux -S <sock> capture-pane -e -p -t <pane>`（-e 保留颜色转义；含当前屏全部行）。
- **增量流**：`pipe-pane -o -t <pane> 'cat >> <fifo>'`——服务端建 FIFO，读端消费字节流；取消订阅时 `pipe-pane -t <pane>`（无参即关闭）。注意 pipe-pane 只捕获**新输出**，所以订阅顺序必须：先开 pipe-pane，再抓快照，快照行与流的接缝由客户端渲染层天然处理（快照=全屏重绘）。同 pane 重复订阅要幂等。
- **输入注入**：`send-keys -t <pane> -l -- <text>` 发字面文本 + `send-keys -t <pane> Enter`。回执=命令 exit code + pane 存在性预检；pane 已死返回明确错误（003 发送必达）。多行文本用 `load-buffer`/`paste-buffer -t` 更稳。
- **resize**：`set-option -w -t <win> window-size latest` + 通过一个受控 attach 客户端传尺寸，或 3.2+ 直接 `resize-window -t <win> -x <cols> -y <rows>`（优先后者，实测为准）；恢复策略：产品语义是"谁最近操作听谁的"（需求 005），本层只提供 resize 原语。
- **scrollback**：`capture-pane -e -p -t <pane> -S <start> -E <end>`（行号相对当前屏为负）。分页参数由上层传入。
- 所有 tmux 调用统一走一个 exec 封装（socket 路径+超时+错误分类：pane 不存在/服务死/超时三类可判定错误）。

## 2. 现场基
- tmux 3.6a（resize-window 可用）。测试铁律与 discovery 任务相同：**独立 socket**（`TMUX_TMPDIR`=t.TempDir()+`-L test-bridge-...`），测试内自起 server 自清理，`env -u TMUX`，绝不触碰真实 socket（防杀真团队，绝对红线）。
- 集成测试形状：起隔离 tmux + 跑一个可预测输出的程序（如 `cat` 或 shell echo 循环）→ 断言快照含已打印内容、注入 "hello\n" 后流中出现回显、resize 后 `display -p '#{pane_width}'` 变化、scrollback 取到滚出屏幕的行。

## 3. 需求基（指针）
1. requirement-base/entries/003-对话体验四标准.md（输入范式+回执）
2. requirement-base/entries/006-秒开与本地滚动.md（快照/流/scrollback 语义）
3. requirement-base/entries/005-自适应-让CLI自己重画.md（resize 语义）

## 4. 经验基
- 红测先行；阳性对照必配（"流里没新字节"可能是 pipe 没接上）。
- FIFO 读端要处理 pipe-pane 进程退出；所有阻塞读带超时。
- 测试净化前缀 + `-timeout 120s`。注释红线照旧。

## 5. 沉淀区（唯一允许你追加写入的区域）
