#!/usr/bin/env python3
"""Minimal UML mconsole client for the U3 capability matrix.

UML's mconsole wire format (arch/um/drivers/mconsole.h) is a fixed
struct mconsole_request followed by a struct mconsole_reply, both native
endianness, not a text protocol.

Usage: mconsole_probe.py --socket PATH [--timeout 5] [--command version]
Exit 0 and print MCONSOLE_OK on a well-formed reply; 1 otherwise.
"""

import argparse
import os
import socket
import struct
import sys
import time

MAGIC = 0xCAFEBABE
VERSION = 2
MAX_DATA = 512

REQUEST = struct.Struct("III%dB" % MAX_DATA)
REPLY = struct.Struct("III%dB" % MAX_DATA)


def probe(socket_path, timeout, command):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not os.path.exists(socket_path):
            time.sleep(0.05)
            continue
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM)
        client_path = "/tmp/uml-mconsole-client-%d.sock" % os.getpid()
        try:
            if os.path.exists(client_path):
                os.unlink(client_path)
            sock.bind(client_path)
        except OSError:
            pass
        sock.settimeout(max(0.2, deadline - time.monotonic()))
        data = command.encode("utf-8")
        request = b"".join([
            struct.pack("III", MAGIC, VERSION, len(data)),
            data,
        ])
        try:
            sock.sendto(request, socket_path)
            reply = sock.recv(4096)
        except OSError as exc:
            sock.close()
            if isinstance(exc, socket.timeout):
                time.sleep(0.05)
                continue
            return 1, "MCONSOLE_FAIL errno=%s" % exc
        finally:
            sock.close()
            try:
                os.unlink(client_path)
            except OSError:
                pass
        if len(reply) < 12:
            return 1, "MCONSOLE_FAIL short-reply=%d" % len(reply)
        err, more, length = struct.unpack("III", reply[:12])
        body = reply[12:12 + min(length, MAX_DATA)]
        if err:
            return 1, "MCONSOLE_FAIL err=%d body=%r" % (err, body)
        return 0, "MCONSOLE_OK more=%d len=%d body=%s" % (
            more, length, body.decode("utf-8", "replace").strip())
    return 1, "MCONSOLE_FAIL timeout"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--socket", required=True)
    parser.add_argument("--timeout", type=float, default=5.0)
    parser.add_argument("--command", default="version")
    args = parser.parse_args(argv)
    rc, message = probe(args.socket, args.timeout, args.command)
    print(message)
    return rc


if __name__ == "__main__":
    sys.exit(main())
