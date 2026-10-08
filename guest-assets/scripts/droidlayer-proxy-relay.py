#!/usr/bin/env python3
"""
Orchestella Studio Virtio-Serial Micro-Relay (Guest Side Python 3 implementation)
Bridges local 127.0.0.1:8080 proxy requests over /dev/virtio-ports/droidlayer.proxy (or /dev/vport0p2).
"""
import errno
import os
import socket
import struct
import sys
import threading
import time

PROXY_PORT = 8080
DEVICE_CANDIDATES = [
    "/dev/virtio-ports/droidlayer.proxy",
    "/dev/vport0p2",
    "/dev/droidlayer-proxy",
]

CMD_DATA = 1
CMD_CLOSE = 2
CMD_RESET = 3

def open_proxy_device():
    for _ in range(30):
        for path in DEVICE_CANDIDATES:
            if os.path.exists(path):
                try:
                    fd = os.open(path, os.O_RDWR)
                    print(f"[droidlayer-proxy-relay.py] Connected to proxy port: {path}")
                    return fd
                except OSError:
                    pass
        time.sleep(0.2)
    return None

def write_exact(fd, data):
    total = 0
    length = len(data)
    while total < length:
        try:
            n = os.write(fd, data[total:])
            if n <= 0:
                return False
            total += n
        except OSError as e:
            if e.errno in (errno.EINTR, errno.EAGAIN):
                continue
            return False
    return True

def read_exact(fd, length):
    chunks = []
    total = 0
    while total < length:
        try:
            chunk = os.read(fd, length - total)
            if not chunk:
                return None
            chunks.append(chunk)
            total += len(chunk)
        except OSError as e:
            if e.errno in (errno.EINTR, errno.EAGAIN):
                continue
            return None
    return b"".join(chunks)

def main():
    proxy_fd = open_proxy_device()
    if proxy_fd is None:
        print("[droidlayer-proxy-relay.py] Error: Virtio-serial proxy port not found", file=sys.stderr)
        sys.exit(1)

    server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server_sock.bind(("127.0.0.1", PROXY_PORT))
    server_sock.listen(64)
    print(f"[droidlayer-proxy-relay.py] Listening on 127.0.0.1:{PROXY_PORT}")

    write_lock = threading.Lock()
    channels = {}
    channels_lock = threading.Lock()
    channel_counter = 0

    def send_frame(cid, cmd, payload=b""):
        header = struct.pack(">IBI", cid, cmd, len(payload))
        with write_lock:
            if not write_exact(proxy_fd, header):
                return False
            if payload:
                return write_exact(proxy_fd, payload)
        return True

    def serial_reader():
        while True:
            hdr = read_exact(proxy_fd, 9)
            if not hdr:
                print("[droidlayer-proxy-relay.py] Serial reader EOF", file=sys.stderr)
                break
            cid, cmd, length = struct.unpack(">IBI", hdr)
            payload = read_exact(proxy_fd, length) if length > 0 else b""
            if length > 0 and payload is None:
                break

            with channels_lock:
                sock = channels.get(cid)

            if sock:
                if cmd == CMD_DATA and payload:
                    try:
                        sock.sendall(payload)
                    except OSError:
                        pass
                elif cmd in (CMD_CLOSE, CMD_RESET):
                    with channels_lock:
                        channels.pop(cid, None)
                    try:
                        sock.close()
                    except OSError:
                        pass

    t = threading.Thread(target=serial_reader, daemon=True)
    t.start()

    while True:
        client_sock, _ = server_sock.accept()
        client_sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        nonlocal_cid = 0
        with channels_lock:
            channel_counter += 1
            nonlocal_cid = channel_counter
            channels[nonlocal_cid] = client_sock

        def client_reader(cid, csock):
            try:
                while True:
                    data = csock.recv(16384)
                    if not data:
                        send_frame(cid, CMD_CLOSE, b"")
                        break
                    if not send_frame(cid, CMD_DATA, data):
                        break
            except OSError:
                send_frame(cid, CMD_RESET, b"")
            finally:
                with channels_lock:
                    channels.pop(cid, None)
                try:
                    csock.close()
                except OSError:
                    pass

        ct = threading.Thread(target=client_reader, args=(nonlocal_cid, client_sock), daemon=True)
        ct.start()

if __name__ == "__main__":
    main()
