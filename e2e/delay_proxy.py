"""userspace TCP delay proxy: paced-read relay, both directions.

Simulates WAN/Tailscale-scale RTT for latency-sensitive e2e tests without root
(macOS dnctl/pfctl need sudo; the Android emulator's `network delay` console
command does not shape traffic to the 10.0.2.2 host-alias or LAN paths the App
actually uses — both were verified ineffective before this was written).

Usage: python3 delay_proxy.py <listen_port> <target_host> <target_port> <delay_ms_each_way>
Point the App's pairing URL at ws://10.0.2.2:<listen_port>/ws instead of the
daemon's real port; the proxy relays to <target_host>:<target_port> (the real
daemon), sleeping <delay_ms_each_way> BEFORE each read (both directions), so a
round trip costs ~2x delay_ms.

Self-proof (validated 2026-08-13): direct-to-echo-server 5.3ms vs through a
200ms-each-way instance 403.6ms, matching the expected 2x200ms exactly.

CORRECTION (2026-08-13, P6): the original version read unbounded
(`reader.read(65536)`) then slept once before writing. That drains the
upstream socket as fast as bytes arrive — the daemon's writes complete
instantly against the proxy's OS-level TCP receive buffer, so the daemon's own
send queue never sees backpressure no matter how long the proxy holds data
before forwarding it on. First P6 run under this design measured
`deltas_dropped=0` at 400ms RTT — a test-apparatus artifact, not evidence
against the drop hypothesis.

First fix attempt (sleep before read instead of before write) broke RTT
accuracy: the sleep then starts at loop-iteration time instead of
data-arrival time, so the two hops' delays overlap instead of stacking —
self-proof measured ~202ms instead of ~400ms for 200ms-each-way. Reverted to
delay-before-write (correct sequential stacking, re-validated below) and
instead capped the per-read chunk size: a burst larger than READ_CHUNK now
needs multiple read+sleep+write cycles to drain, which throttles effective
throughput to ~READ_CHUNK/delay_s bytes/sec.

SECOND correction (2026-08-13, P6): even with that throttle, a real P6 run
against the daemon measured `deltas_dropped=0, queue_peak=1` — the OS-level
TCP receive buffer on the proxy's daemon-facing socket (tens to hundreds of KB
by default on macOS) absorbed the whole burst (an 80-line reply is only a few
KB of terminal escapes) before the throttled reads ever drained it, so the
daemon's write() never blocked. Fixed by shrinking SO_RCVBUF on that socket to
force backpressure to kick in almost immediately regardless of burst size,
instead of relying on the burst being larger than an unknown, OS-dependent
default buffer.
"""
import asyncio, socket, sys

LISTEN_PORT = int(sys.argv[1])
TARGET_HOST = sys.argv[2]
TARGET_PORT = int(sys.argv[3])
DELAY_MS = float(sys.argv[4])
READ_CHUNK = 4096  # caps effective throughput to ~READ_CHUNK/delay_s bytes/sec — real backpressure, not just added latency.
UPSTREAM_RCVBUF = 2048  # shrinks the daemon-facing socket's OS receive buffer so backpressure doesn't depend on burst size vs. an unknown default.

async def pump(reader, writer, delay_s, label):
    try:
        while True:
            data = await reader.read(READ_CHUNK)
            if not data:
                break
            if delay_s > 0:
                await asyncio.sleep(delay_s)
            writer.write(data)
            await writer.drain()
    except (ConnectionResetError, BrokenPipeError):
        pass
    finally:
        writer.close()

async def handle(client_reader, client_writer):
    try:
        upstream_reader, upstream_writer = await asyncio.open_connection(TARGET_HOST, TARGET_PORT)
    except Exception as e:
        print(f"upstream connect failed: {e}", file=sys.stderr)
        client_writer.close()
        return
    upstream_sock = upstream_writer.get_extra_info('socket')
    if upstream_sock is not None:
        upstream_sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, UPSTREAM_RCVBUF)
    delay_s = DELAY_MS / 1000.0
    await asyncio.gather(
        pump(client_reader, upstream_writer, delay_s, "c2s"),
        pump(upstream_reader, client_writer, delay_s, "s2c"),
        return_exceptions=True,
    )

async def main():
    server = await asyncio.start_server(handle, "0.0.0.0", LISTEN_PORT)
    print(f"delay_proxy listening :{LISTEN_PORT} -> {TARGET_HOST}:{TARGET_PORT} delay={DELAY_MS}ms each-way", flush=True)
    async with server:
        await server.serve_forever()

asyncio.run(main())
