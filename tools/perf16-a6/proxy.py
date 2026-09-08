#!/usr/bin/env python3
import os
import socket
import sys
import threading
import time

upstream_port = int(sys.argv[1])
listen_port = int(sys.argv[2])
release_file = sys.argv[3]
count = 0
count_lock = threading.Lock()

def more(sock, buf):
    chunk = sock.recv(65536)
    return None if not chunk else buf + chunk

def frame(sock, buf):
    while len(buf) < 2:
        buf = more(sock, buf)
        if buf is None:
            return None, buf
    b0, b1 = buf[0], buf[1]
    length = b1 & 0x7f
    header = 2
    if length == 126:
        while len(buf) < 4:
            buf = more(sock, buf)
            if buf is None:
                return None, buf
        length = int.from_bytes(buf[2:4], "big")
        header = 4
    elif length == 127:
        while len(buf) < 10:
            buf = more(sock, buf)
            if buf is None:
                return None, buf
        length = int.from_bytes(buf[2:10], "big")
        header = 10
    if b1 & 0x80:
        header += 4
    total = header + length
    while len(buf) < total:
        buf = more(sock, buf)
        if buf is None:
            return None, buf
    return buf[:total], buf[total:]

def wait_release_then_drain(upstream):
    while not os.path.exists(release_file):
        time.sleep(0.05)
    print("P16_PROXY_RELEASE_AFTER_SERVER_LOSS", flush=True)
    while True:
        data = upstream.recv(65536)
        if not data:
            print("P16_PROXY_UPSTREAM_FIN", flush=True)
            return

def c2u(client, upstream):
    try:
        while True:
            data = client.recv(65536)
            if not data:
                return
            upstream.sendall(data)
    except OSError as error:
        print(f"P16_PROXY_IO_ERROR {type(error).__name__}", flush=True)
        return

def u2c(client, upstream, gate):
    buf = b""
    try:
        while b"\r\n\r\n" not in buf:
            data = upstream.recv(65536)
            if not data:
                return
            buf += data
        end = buf.index(b"\r\n\r\n") + 4
        client.sendall(buf[:end])
        buf = buf[end:]
        fragmented_binary = False
        gated = False
        while True:
            data, buf = frame(upstream, buf)
            if data is None:
                return
            opcode = data[0] & 0x0f
            fin = bool(data[0] & 0x80)
            if not gate or not gated:
                client.sendall(data)
            if gate:
                if opcode == 2:
                    fragmented_binary = not fin
                elif opcode == 0 and fragmented_binary and fin:
                    fragmented_binary = False
                if (opcode == 2 and fin) or (opcode == 0 and fin and not fragmented_binary):
                    gated = True
                    print("P16_PROXY_GATE_AFTER_INITIAL_BINARY", flush=True)
                    wait_release_then_drain(upstream)
                    return
    except OSError as error:
        print(f"P16_PROXY_IO_ERROR {type(error).__name__}", flush=True)
        return

def handle(client, number):
    upstream = None
    try:
        upstream = socket.create_connection(("127.0.0.1", upstream_port), timeout=5)
        upstream.settimeout(None)
        client.settimeout(None)
        gate = number == 1
        down = threading.Thread(target=u2c, args=(client, upstream, gate), daemon=True)
        up = threading.Thread(target=c2u, args=(client, upstream), daemon=True)
        down.start()
        up.start()
        down.join()
    except OSError as error:
        print(f"P16_PROXY_CONNECT_ERROR {type(error).__name__}", flush=True)
    finally:
        for sock in (client, upstream):
            if sock is None:
                continue
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                sock.close()
            except OSError:
                pass

with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("127.0.0.1", listen_port))
    listener.listen(16)
    print("P16_PROXY_READY", flush=True)
    while True:
        client, _ = listener.accept()
        with count_lock:
            count += 1
            number = count
        print(f"P16_PROXY_CONNECTION {number}", flush=True)
        threading.Thread(target=handle, args=(client, number), daemon=True).start()
