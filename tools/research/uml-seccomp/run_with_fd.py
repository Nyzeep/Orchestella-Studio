#!/usr/bin/env python3
"""Run a command with a pre-opened socketpair fd (UML vector transport).

Usage: run_with_fd.py --fd 3 [--type dgram|stream] -- CMD [ARGS...]

Both ends of the socketpair are inherited by the exec'd process.  The vector
fd transport reads/writes raw frames on the fd; a datagram pair is enough to
prove that UML initializes the vec device under the filter (no traffic is
injected in U3).
"""

import argparse
import os
import socket
import sys


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fd", type=int, default=3)
    parser.add_argument("--type", choices=["dgram", "stream"], default="dgram")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    command = args.command
    if command and command[0] == "--":
        command = command[1:]
    if not command:
        parser.error("missing command")

    kind = socket.SOCK_DGRAM if args.type == "dgram" else socket.SOCK_STREAM
    first, second = socket.socketpair(socket.AF_UNIX, kind)
    os.dup2(first.fileno(), args.fd)
    os.set_inheritable(args.fd, True)
    # Keep the peer open so the fd has a valid peer after exec.
    os.set_inheritable(second.fileno(), True)
    os.execvp(command[0], command)


if __name__ == "__main__":
    sys.exit(main())
