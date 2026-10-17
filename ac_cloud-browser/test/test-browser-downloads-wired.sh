#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ Download manager + BitTorrent: wired end to end                   ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# The engines are unit-tested (DownloadEngineTest, TorrentMagnetTest, TorrentSessionTest). This holds
# the WIRING those tests cannot see: a WebView download and a long-press "Download link" reach the
# download manager with the page's cookies, agent and referer; a magnet: link is intercepted in
# shouldOverrideUrlLoading BEFORE the nav policy can block it; a .torrent download becomes a torrent;
# the torrent engine is the one the manifest names and runs in the same dataSync service; magnets and
# .torrent files from other apps are accepted. Matched in COMMENT-STRIPPED Kotlin.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import os, sys
sys.path.insert(0, ".")
from browser_tester import LIB, APP, kotlin, main

def check(root, ok):
    src = kotlin(root, LIB, APP)
    g = lambda n: src.get(os.path.join(LIB, n + ".kt"), "")
    frag, dl = g("BrowserHostFragment"), g("BrowserDownloads")
    q = g("download/DlQueue")
    bridge = src.get(os.path.join(APP, "torrent", "LtTorrentBridge.kt"), "")
    act = src.get(os.path.join(APP, "MainActivity.kt"), "")
    man = open(os.path.join(root, "ac_cloud-browser/app/src/main/AndroidManifest.xml"), encoding="utf-8").read()
    gradle = open(os.path.join(root, "ac_cloud-browser/app/build.gradle"), encoding="utf-8").read()

    sol = frag[frag.find("override fun shouldOverrideUrlLoading"):]
    m, p = sol.find("DlMagnet.isMagnet(target) && addMagnet(target)"), sol.find("BrowserNavPolicy.decide(target)")
    ok(0 <= m < p, "a magnet link is taken before the nav policy can block it")
    lis = frag[frag.find("setDownloadListener {"):]
    t, d = lis.find("downloadTorrent(dlUrl, ua, disposition, mime)"), lis.find("download(dlUrl, ua, disposition, mime)")
    ok(0 <= t < d, "a .torrent download becomes a torrent, anything else downloads")
    ok("DlMagnet.isTorrentFile(url, mime, disposition)" in frag, "the .torrent test covers name, MIME type and Content-Disposition")
    ok("cookiesOf(webView).getCookie(url)" in frag[frag.find("private fun downloadTorrent"):], "a .torrent is fetched with the tab's cookies")
    ok("downloads.start(url, ua, webView?.url, disposition, mime" in frag, "a download passes the page as referer")
    ok("DlQueue.get(app).add(url, userAgent, referer, cookieHeader(url)" in dl, "the managed path carries cookie, agent and referer")
    ok("Action.DOWNLOAD_LINK -> toast(\"Downloading ${download(link" in frag, "long-press Download link uses the same path")
    ok("DownloadsScreen(" in frag and "if (downloads.managed)" in frag, "the Downloads page is the download manager's when it runs")
    ok("fun torrentUpdate(" in q and "DlTorrentBridge.load(app)" in q, "the queue runs torrents through the bridge")
    ok('android:value="com.diegonmarcos.cloudbrowser.torrent.LtTorrentBridge"' in man and "class LtTorrentBridge(context: Context) : DlTorrentBridge" in bridge,
       "the manifest names the libtorrent bridge, with the constructor load() calls")
    ok("saveResumeData(TorrentHandle.SAVE_INFO_DICT)" in bridge and "writeResumeDataBuf" in bridge and "read_resume_data_ex" in bridge,
       "torrents write and re-read resume data")
    for f in ("setEnableDht(true)", "setEnableLsd(true)", "enable_incoming_utp", "enable_outgoing_utp"):
        ok(f in bridge, "the session turns on %s" % f)
    for f in ("SEQUENTIAL_DOWNLOAD", "prioritizeFiles(", "DlSeedRule.reached(", "uploadRateLimit("):
        ok(f in bridge, "the torrent engine supports %s" % f)
    ok('android:scheme="magnet"' in man and 'android:mimeType="application/x-bittorrent"' in man, "magnets and .torrent files from other apps are accepted")
    ok("TorrentIntents.consume(this, intent)" in act, "MainActivity hands them to the queue")
    ok('android:foregroundServiceType="dataSync"' in man, "the one download service is dataSync")
    for abi in ("libtorrent4j-android-arm64", "libtorrent4j-android-x86_64"):
        ok("'org.libtorrent4j:%s:" % abi in gradle, "the native libtorrent ships for %s" % abi)

FRAG = LIB + "/BrowserHostFragment.kt"
MAN = "ac_cloud-browser/app/src/main/AndroidManifest.xml"
main("downloads wiring", check, [
    ("the magnet intercept is dropped", FRAG, "DlMagnet.isMagnet(target) && addMagnet(target)", "false", "magnet link is taken"),
    ("torrent downloads go to HTTP", FRAG, "if (!downloadTorrent(dlUrl, ua, disposition, mime)) {", "if (true) {", ".torrent download becomes"),
    ("the referer is lost", FRAG, "downloads.start(url, ua, webView?.url, disposition, mime", "downloads.start(url, ua, null, disposition, mime", "referer"),
    ("the bridge is not named", MAN, "com.diegonmarcos.cloudbrowser.torrent.LtTorrentBridge", "com.example.None", "names the libtorrent bridge"),
    ("the service loses its type", MAN, 'android:foregroundServiceType="dataSync"', 'android:foregroundServiceType="specialUse"', "dataSync"),
])
PYEOF
