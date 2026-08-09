#!/usr/bin/env python3
"""哑 leader 接收端（2026-08-10，框架队 leader 情报 §4 实证形态：哑程序当节点成立）。

作用：占住 leader 绑定的那个 pane，把框架注入的文本原样落盘，并推醒编排引擎。
**不解析、不判断、不回复**——判断全在 .team/orchestrator.py 里，这里只做管道。

为什么不是 LLM：用户裁定（2026-08-10）——leader 位上坐一个模型只是换岗，不是自动化。
为什么不轮询：工程常识红线 1（静默经济）禁止常驻进程定频派生子进程。本程序阻塞在
stdin.readline() 上，空闲 CPU 不动；引擎阻塞在 fifo 上，被推醒而不是自己醒。

用法（在要绑定为 leader 的 pane 里）：
  team-agent claim-leader --confirm --workspace .   # 先把绑定挂到本 pane
  python3 .team/leader-sink.py                      # 再卡住这个 pane
"""
import os, sys, time

WS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INBOX = os.path.join(WS, ".team", "leader-inbox.log")
WAKE = os.path.join(WS, ".team", "orch.wake")


def poke():
    """非阻塞推醒引擎；没人等就立刻失败，绝不卡住调用方（框架实测坑：无人等时应 ENXIO 而非阻塞）。"""
    try:
        fd = os.open(WAKE, os.O_WRONLY | os.O_NONBLOCK)
        os.write(fd, b"1")
        os.close(fd)
    except OSError:
        pass


def main():
    print("leader-sink 就位：只落盘+推醒，不解析不回复。inbox=", INBOX, flush=True)
    with open(INBOX, "a", encoding="utf-8") as f:
        for line in sys.stdin:                      # 阻塞在这里，空闲零 CPU
            f.write(f"{time.strftime('%F %T')} {line}")
            f.flush()
            poke()


if __name__ == "__main__":
    main()
