#!/usr/bin/env python3
"""
Orchestella Studio Virtio-Serial Micro-Relay (Guest Side Python 3 implementation)
Bridges local 127.0.0.1:8080 proxy requests over /dev/virtio-ports/orchestella.proxy (or /dev/vport0p2).
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
    "/dev/virtio-ports/orchestella.proxy",
    "/dev/orchestella-proxy",
    "/dev/vport0p2",
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
                    print(f"[orchestella-proxy-relay.py] Connected to proxy port: {path}")
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

class ProxyRelay:
    def __init__(self, serial_fd):
        self.serial_fd = serial_fd
        self.channels = {}
        self.lock = threading.Lock()
        self.serial_write_lock = threading.Lock()
        self.next_cid = 1
        self.running = True

    def send_frame(self, cid, cmd, payload=b""):
        header = struct.pack("!IBI", cid, cmd, len(payload))
        with self.serial_write_lock:
            return write_exact(self.serial_fd, header + payload)

    def close_channel(self, cid):
        with self.lock:
            sock = self.channels.pop(cid, None)
        if sock:
            try:
                sock.shutdown(socket.SHUT_RDWR)
                sock.close()
            except OSError:
                pass

    def run_serial_reader(self):
        while self.running:
            header = read_exact(self.serial_fd, 9)
            if not header:
                print("[orchestella-proxy-relay.py] Serial reader EOF", file=sys.stderr)
                break
            cid, cmd, length = struct.unpack("!IBI", header)
            payload = b""
            if length > 0:
                payload = read_exact(self.serial_fd, length)
                if payload is None:
                    break

            if cmd in (CMD_CLOSE, CMD_RESET):
                self.close_channel(cid)
            elif cmd == CMD_DATA:
                with self.lock:
                    sock = self.channels.get(cid)
                if sock:
                    try:
                        sock.sendall(payload)
                    except OSError:
                        self.close_channel(cid)
                        self.send_frame(cid, CMD_CLOSE)

    def handle_client(self, client_sock, cid):
        try:
            while self.running:
                data = client_sock.recv(16384)
                if not data:
                    break
                if not self.send_frame(cid, CMD_DATA, data):
                    break
        except OSError:
            pass
        finally:
            self.send_frame(cid, CMD_CLOSE)
            self.close_channel(cid)

    def run_tcp_server(self):
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(("127.0.0.1", PROXY_PORT))
        server.listen(128)
        print(f"[orchestella-proxy-relay.py] Listening on 127.0.0.1:{PROXY_PORT}")

        while self.running:
            try:
                client_sock, _ = server.accept()
                with self.lock:
                    cid = self.next_cid
                    self.next_cid = (self.next_cid + 1) & 0x7FFFFFFF
                    if self.next_cid == 0:
                        self.next_cid = 1
                    self.channels[cid] = client_sock
                t = threading.Thread(target=self.handle_client, args=(client_sock, cid), daemon=True)
                t.start()
            except OSError:
                break

def main():
    serial_fd = open_proxy_device()
    if serial_fd is None:
        print("[orchestella-proxy-relay.py] Error: Virtio-serial proxy port not found", file=sys.stderr)
        sys.exit(1)

    relay = ProxyRelay(serial_fd)
    reader_thread = threading.Thread(target=relay.run_serial_reader, daemon=True)
    reader_thread.start()

    try:
        relay.run_tcp_server()
    except KeyboardInterrupt:
        pass
    finally:
        relay.running = False

if __name__ == "__main__":
    main()
