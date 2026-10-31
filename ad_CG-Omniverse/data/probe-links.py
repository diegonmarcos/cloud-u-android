#!/usr/bin/env python3
"""Probe every Store world's link and record whether it answers: data/link-status.json (url -> {status, checked}).

  python3 data/probe-links.py [--timeout S]   probe, write data/link-status.json, then run python3 data/gen-store.py

status is `online` (the host answered HTTP with a page), `offline` (the world is gone: HTTP 404/410, a timeout, refused,
no such host) or `unverified` (the probe could not tell: an egress proxy refused or blocked the request, e.g. 403/407
from the proxy or a refused tunnel, a TLS failure, anything unknown). Unverified is never reported as offline. gen-store.py merges
these into store.json; the Store shows Offline and Unverified badges from them. Re-run it to refresh the dates.
"""
import concurrent.futures, datetime, json, os, socket, ssl, sys, urllib.error, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "link-status.json")
UA = "Mozilla/5.0 (Linux; Android 14) CG-Omniverse link check"


GONE = (404, 410)


def classify_http(code):
    """A server answered with [code]: 404/410 mean the world is gone (offline); any other answer means the host is up."""
    return ("offline" if code in GONE else "online"), "HTTP %d" % code


def probe(url, timeout):
    req = urllib.request.Request(url, headers={"User-Agent": UA}, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return classify_http(r.status)
    except urllib.error.HTTPError as e:
        body = (e.read(400) or b"").decode("utf-8", "replace").lower()
        if e.code in (407,) or (e.code in (403, 405) and "proxy" in (body + str(e.headers).lower())):
            return "unverified", "proxy %d" % e.code
        return classify_http(e.code)
    except urllib.error.URLError as e:
        r = e.reason
        text = str(r).lower()
        if "tunnel connection failed" in text or "proxy" in text:
            return "unverified", "proxy: %s" % r
        if isinstance(r, (socket.timeout, TimeoutError)) or "timed out" in text:
            return "offline", "timeout"
        if isinstance(r, socket.gaierror) or "name or service" in text or "nodename" in text:
            return "offline", "no such host"
        if isinstance(r, (ConnectionRefusedError, ConnectionResetError)) or "refused" in text or "reset" in text:
            return "offline", str(r)
        if isinstance(r, ssl.SSLError):
            return "unverified", "tls: %s" % r
        return "unverified", str(r)
    except (socket.timeout, TimeoutError):
        return "offline", "timeout"
    except Exception as e:  # anything else: the probe cannot tell
        return "unverified", "%s: %s" % (type(e).__name__, e)


def main():
    timeout = float(sys.argv[sys.argv.index("--timeout") + 1]) if "--timeout" in sys.argv else 20.0
    store = json.load(open(os.path.join(HERE, "store.json"), encoding="utf-8"))
    urls = [e["url"] for e in store["entries"]]
    today = datetime.date.today().isoformat()
    with concurrent.futures.ThreadPoolExecutor(8) as ex:
        results = dict(zip(urls, ex.map(lambda u: probe(u, timeout), urls)))
    out = {"_doc": "Written by data/probe-links.py - DO NOT EDIT by hand. status online|offline|unverified per Store link, checked = probe date.",
           "links": {u: {"status": s, "checked": today, "detail": d} for u, (s, d) in sorted(results.items())}}
    open(OUT, "w", encoding="utf-8").write(json.dumps(out, indent=2, ensure_ascii=False) + "\n")
    for u, (s, d) in sorted(results.items(), key=lambda x: x[1][0]):
        print("%-8s %-60s %s" % (s, u, d))


if __name__ == "__main__":
    main()
