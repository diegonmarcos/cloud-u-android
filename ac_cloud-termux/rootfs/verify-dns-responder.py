#!/usr/bin/env python3
"""verify-rootfs.sh's stand-in for the app's SystemDnsBridge (#741).

Listens on 127.0.0.1:<port> (UDP and TCP, as the bridge does) and answers every
A query with 192.0.2.53 and anything else with an empty NOERROR, so a guest
resolver that reaches it can be told apart from one that reached anything else.
Runs until killed. Usage: verify-dns-responder.py <port>
"""
import socket
import struct
import sys
import threading

PROBE_ADDR = bytes([192, 0, 2, 53])


def answer(q):
    i = 12
    while q[i]:
        i += 1 + q[i]
    end = i + 5
    qtype = struct.unpack(">H", q[i + 1:i + 3])[0]
    rr = struct.pack(">HHHLH", 0xC00C, 1, 1, 60, 4) + PROBE_ADDR if qtype == 1 else b""
    return q[:2] + struct.pack(">HHHHH", 0x8180, 1, 1 if rr else 0, 0, 0) + q[12:end] + rr


def main():
    port = int(sys.argv[1])
    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.bind(("127.0.0.1", port))
    tcp = socket.socket()
    tcp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    tcp.bind(("127.0.0.1", port))
    tcp.listen(8)

    def serve_udp():
        while True:
            q, peer = udp.recvfrom(4096)
            udp.sendto(answer(q), peer)

    threading.Thread(target=serve_udp, daemon=True).start()
    print("ready", flush=True)
    while True:
        c, _ = tcp.accept()
        with c:
            n = struct.unpack(">H", c.recv(2))[0]
            r = answer(c.recv(n))
            c.sendall(struct.pack(">H", len(r)) + r)


if __name__ == "__main__":
    main()
