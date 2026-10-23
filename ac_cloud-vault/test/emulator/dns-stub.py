#!/usr/bin/env python3
"""Answers A queries for vaultwarden.app (and subdomains) with 10.0.2.2 (the emulator's view of the CI host, where the
seeded Vaultwarden listens); every other name goes to the host resolver. UDP DNS_BIND:53 (needs root)."""
import os, socket, struct

def name_of(q):
    i, labels = 12, []
    while q[i]:
        labels.append(q[i + 1:i + 1 + q[i]].decode()); i += 1 + q[i]
    return ".".join(labels).lower(), i + 1

s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.bind((os.environ.get("DNS_BIND", "127.0.0.1"), 53))
while True:
    q, addr = s.recvfrom(512)
    try:
        name, end = name_of(q)
        qtype = struct.unpack(">H", q[end:end + 2])[0]
        print("q", name, qtype, flush=True)
        ip = "10.0.2.2" if name == "vaultwarden.app" or name.endswith(".vaultwarden.app") else None
        if ip is None and qtype == 1:
            try:
                ip = socket.gethostbyname(name)
            except OSError:
                ip = None
        head = q[:2] + (b"\x81\x80" if (ip and qtype == 1) else b"\x81\x80") + q[4:6]
        question = q[12:end + 4]
        if ip and qtype == 1:
            ans = b"\xc0\x0c" + struct.pack(">HHIH", 1, 1, 30, 4) + socket.inet_aton(ip)
            s.sendto(head + b"\x00\x01\x00\x00\x00\x00" + question + ans, addr)
        else:
            s.sendto(head + b"\x00\x00\x00\x00\x00\x00" + question, addr)
    except Exception:
        pass
