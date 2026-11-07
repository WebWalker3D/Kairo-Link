#!/usr/bin/env python3
"""Measure a running Kairo-Link from a workstation, through the body phone.

    adb -s BODY forward tcp:18120 tcp:8120
    python3 scripts/linktest.py --port 18120

Port 8120 on the body is the diagnostic proxy: it always uses the BLE relay
(never the body's own Wi-Fi) and also reaches the relay's built-in test
targets, which never touch the internet. Note the adb hop itself adds a few
milliseconds of latency to every figure.
"""
from __future__ import annotations

import argparse
import socket
import ssl
import statistics
import struct
import time


def tunnel(port: int, target: str) -> socket.socket:
    s = socket.create_connection(("127.0.0.1", port), timeout=60)
    s.sendall(f"CONNECT {target} HTTP/1.1\r\nHost: {target}\r\n\r\n".encode())
    head = b""
    while not head.endswith(b"\r\n\r\n"):
        chunk = s.recv(1)
        if not chunk:
            raise ConnectionError("proxy closed during CONNECT")
        head += chunk
    status = head.split(b"\r\n", 1)[0].decode()
    if " 200 " not in status:
        raise ConnectionError(status)
    s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    return s


def recv_all(s: socket.socket) -> int:
    total = 0
    while chunk := s.recv(65536):
        total += len(chunk)
    return total


def rtt(port: int, rounds: int) -> list[float]:
    s = tunnel(port, "echo.kairo-link.diag:1")
    out = []
    for _ in range(rounds):
        t = time.perf_counter()
        s.sendall(b"k")
        if s.recv(1) != b"k":
            raise ConnectionError("echo mismatch")
        out.append((time.perf_counter() - t) * 1000)
        time.sleep(0.05)
    s.close()
    return out


def download(port: int, size: int) -> float:
    t = time.perf_counter()
    s = tunnel(port, f"source-{size}.kairo-link.diag:1")
    got = recv_all(s)
    s.close()
    if got != size:
        raise ConnectionError(f"expected {size} bytes, got {got}")
    return time.perf_counter() - t


def upload(port: int, size: int) -> float:
    t = time.perf_counter()
    s = tunnel(port, f"sink-{size}.kairo-link.diag:1")
    s.sendall(b"\x00" * size)
    answer = b""
    while len(answer) < 8 and (chunk := s.recv(8 - len(answer))):
        answer += chunk
    s.close()
    (count,) = struct.unpack(">Q", answer)
    if count != size:
        raise ConnectionError(f"relay counted {count} of {size} bytes")
    return time.perf_counter() - t


def https(port: int, host: str) -> tuple[str, float]:
    t = time.perf_counter()
    raw = tunnel(port, f"{host}:443")
    s = ssl.create_default_context().wrap_socket(raw, server_hostname=host)
    s.sendall(f"GET / HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\nUser-Agent: kairo-link-test\r\n\r\n".encode())
    first = b""
    while b"\r\n" not in first and (chunk := s.recv(1024)):
        first += chunk
    s.close()
    return first.split(b"\r\n", 1)[0].decode(errors="replace"), time.perf_counter() - t


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=18120)
    ap.add_argument("--host", default="connect.kairo.icu")
    ap.add_argument("--rounds", type=int, default=20)
    a = ap.parse_args()

    r = rtt(a.port, a.rounds)
    print(f"round trip     median {statistics.median(r):6.1f} ms   min {min(r):6.1f}   max {max(r):6.1f}   ({a.rounds} pings)")
    for size, label in ((30_000, "30 KB snapshot"), (200_000, "200 KB")):
        d = download(a.port, size)
        print(f"relay -> body  {label:15s} {d:6.2f} s   {size / d / 1024:6.1f} KB/s")
        u = upload(a.port, size)
        print(f"body -> relay  {label:15s} {u:6.2f} s   {size / u / 1024:6.1f} KB/s")
    status, took = https(a.port, a.host)
    print(f"https://{a.host}/  ->  {status}   ({took:.2f} s incl. TLS handshake)")


if __name__ == "__main__":
    main()
