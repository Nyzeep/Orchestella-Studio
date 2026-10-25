#!/usr/bin/env python3
"""Independent AXP1 + distcc v1 client for the PVM-03 compile service.

Why a second implementation: the C++ tests and the C++ server share a codec,
so a mirrored bug would pass both. This client is written from the Java
Axp1Connection layout and the distcc protocol-1 document, not from the server
code, and it replays the byte dump a real distcc 3.4 client produced
(runtime/fastlane-distcc/src/test/resources/distcc/request-v1.hex).

Usage:
  axp1_distcc_client.py --port 12345 --token <32 hex> \
      --request request-v1.hex --out unit.o

Prints one `key=value` line per fact on stdout. Exit codes:
  0 exchange completed and the response parsed
  1 transport/protocol failure
  2 usage error
"""

from __future__ import annotations

import argparse
import socket
import struct
import sys
from pathlib import Path

MAGIC = b"AXP1"
VERSION = 1
TYPE_HELLO = 1
TYPE_HELLO_ACK = 2
TYPE_DATA = 3
TYPE_EOF = 4
TYPE_RST = 5
TYPE_PING = 6
TYPE_PONG = 7

ROLE_HOST = 1
ROLE_GUEST = 2
MAX_FRAME = 1 << 20

ERRORS = {
    1: "VERSION_MISMATCH",
    2: "AUTH_FAILED",
    3: "FRAME_TOO_LARGE",
    4: "PROTOCOL_ERROR",
    5: "TIMEOUT",
    6: "CLOSED",
}


def encode_frame(frame_type: int, payload: bytes) -> bytes:
    return MAGIC + bytes([VERSION, frame_type]) + struct.pack(">HI", 0, len(payload)) + payload


def read_exact(sock: socket.socket, size: int) -> bytes:
    chunks = bytearray()
    while len(chunks) < size:
        chunk = sock.recv(size - len(chunks))
        if not chunk:
            raise ConnectionError(f"short read: wanted {size}, got {len(chunks)}")
        chunks.extend(chunk)
    return bytes(chunks)


def read_frame(sock: socket.socket) -> tuple[int, bytes]:
    header = read_exact(sock, 12)
    if header[:4] != MAGIC:
        raise ValueError(f"bad magic: {header[:4]!r}")
    if header[4] != VERSION:
        raise ValueError(f"bad version: {header[4]}")
    frame_type = header[5]
    (length,) = struct.unpack(">I", header[8:12])
    return frame_type, read_exact(sock, length) if length else b""


def caps_block(max_frame: int = MAX_FRAME) -> bytes:
    # count=2, id 1 (distcc, 1 byte), id 2 (max_frame, 4 bytes BE)
    return (
        struct.pack(">H", 2)
        + bytes([1]) + struct.pack(">H", 1) + b"\x01"
        + bytes([2]) + struct.pack(">H", 4) + struct.pack(">I", max_frame)
    )


def hello_payload(token: str) -> bytes:
    nonce = bytes(range(16))
    return (
        bytes([ROLE_HOST, len(nonce)])
        + nonce
        + bytes([len(token)])
        + token.encode("ascii")
        + caps_block()
    )


def load_request(path: Path) -> bytes:
    text = "".join(path.read_text(encoding="ascii").split())
    return bytes.fromhex(text)


def build_request(argv: list[str], doti: bytes) -> bytes:
    """Builds a DIST/ARGC/ARGV*/DOTI stream (protocol-1 token layout)."""
    out = bytearray(b"DIST" + b"%08x" % 1)
    out += b"ARGC" + b"%08x" % len(argv)
    for argument in argv:
        raw = argument.encode("utf-8")
        out += b"ARGV" + b"%08x" % len(raw) + raw
    out += b"DOTI" + b"%08x" % len(doti) + doti
    return bytes(out)


def decode_distcc_packets(payload: bytes) -> dict[str, bytes]:
    """Parses DIST/DONE-style packets: 4-byte token + 8 hex digits + body."""
    packets: dict[str, bytes] = {}
    offset = 0
    while offset < len(payload):
        if offset + 12 > len(payload):
            raise ValueError("truncated packet header")
        token = payload[offset:offset + 4].decode("ascii")
        value = int(payload[offset + 4:offset + 12], 16)
        offset += 12
        if token in ("DIST", "DONE", "STAT", "ARGC"):
            packets[token] = struct.pack(">I", value)
            continue
        if offset + value > len(payload):
            raise ValueError(f"truncated body for {token}")
        packets[token] = payload[offset:offset + value]
        offset += value
    return packets


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--token", required=True)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--request", type=Path, help="distcc v1 request hex dump")
    source.add_argument("--arg", action="append",
                        help="one argv element for a synthesised request (repeatable; "
                             "keeps flags such as -Xclang out of argparse)")
    parser.add_argument("--doti-text", default="int probe(void) { return 0; }\n")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--timeout", type=float, default=20.0)
    args = parser.parse_args(argv)

    if len(args.token) != 32:
        print("error=token must be 32 hex characters")
        return 2

    if args.request is not None:
        request = load_request(args.request)
    else:
        if not args.arg:
            print("error=--arg needs at least one element")
            return 2
        request = build_request(args.arg, args.doti_text.encode("utf-8"))
    try:
        with socket.create_connection((args.host, args.port), timeout=args.timeout) as sock:
            sock.sendall(encode_frame(TYPE_HELLO, hello_payload(args.token)))
            frame_type, payload = read_frame(sock)
            if frame_type == TYPE_RST:
                code = struct.unpack(">H", payload[:2])[0] if len(payload) >= 2 else 0
                print(f"rst={ERRORS.get(code, code)}")
                return 1
            if frame_type != TYPE_HELLO_ACK:
                print(f"error=expected HELLO_ACK got {frame_type}")
                return 1
            if payload[0] != ROLE_GUEST:
                print(f"error=peer role {payload[0]} is not GUEST")
                return 1
            print("handshake=OK")

            stream = bytearray()
            for offset in range(0, len(request), MAX_FRAME):
                stream += encode_frame(TYPE_DATA, request[offset:offset + MAX_FRAME])
            stream += encode_frame(TYPE_EOF, b"")
            sock.sendall(stream)

            response = bytearray()
            while True:
                frame_type, payload = read_frame(sock)
                if frame_type == TYPE_DATA:
                    response += payload
                    continue
                if frame_type == TYPE_PING:
                    sock.sendall(encode_frame(TYPE_PONG, payload))
                    continue
                if frame_type == TYPE_EOF:
                    break
                if frame_type == TYPE_RST:
                    code = struct.unpack(">H", payload[:2])[0] if len(payload) >= 2 else 0
                    print(f"rst={ERRORS.get(code, code)}")
                    return 1
                print(f"error=unexpected frame type {frame_type}")
                return 1
    except (OSError, ValueError, ConnectionError) as failure:
        print(f"error={failure}")
        return 1

    try:
        packets = decode_distcc_packets(bytes(response))
    except ValueError as failure:
        print(f"error={failure}")
        return 1

    for token in ("DONE", "STAT", "SERR", "SOUT", "DOTO"):
        if token not in packets:
            print(f"missing={token}")
            return 1

    (stat,) = struct.unpack(">I", packets["STAT"])
    print(f"stat={stat}")
    print(f"done={struct.unpack('>I', packets['DONE'])[0]}")
    print(f"serr={packets['SERR'].decode('utf-8', 'replace').strip()}")
    print(f"serr_bytes={len(packets['SERR'])}")
    print(f"sout_bytes={len(packets['SOUT'])}")
    print(f"object_bytes={len(packets['DOTO'])}")
    args.out.write_bytes(packets["DOTO"])
    print("result=OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
