#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #863 — a bootstrap re-extract over a dirty usr-staging succeeds          ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# A37 (Android 16): an interrupted extract left files in files/usr-staging; the
# next attempt died in Os.symlink with EEXIST, after $PREFIX had already been
# wiped, so usr/ stayed empty and the Nix terminal was unusable.
#
#   B  the REAL BootstrapStaging (+ BoundedRecursiveDelete) on a plain JVM: a
#      re-extract replayed over a dirty staging dir (stale files, a read-only
#      dir, a stale link, a same link, a file where a link goes) ends with the
#      new tree in usr/; a failure before the swap keeps the old usr/; the
#      error markdown is capped.
#   M  mutants (no staging wipe; Os.symlink-style create; no cap) must FAIL.
#   W  wiring: TermuxInstaller uses wipe/placeSymlink/swap/bound, no raw
#      Os.symlink, no up-front $PREFIX delete.
set -u
DIR="$(cd "$(dirname "$0")/.." && pwd)"
exec python3 - "$DIR" <<'PY'
import os, sys, shutil, subprocess, tempfile, re
DIR = sys.argv[1]
BS = "app/src/main/java/com/termux/app/BootstrapStaging.java"
DEL = "termux-shared/src/main/java/com/termux/shared/file/BoundedRecursiveDelete.java"
INST = "app/src/main/java/com/termux/app/TermuxInstaller.java"
fails = []

HARNESS = r'''
package com.termux.app;
import java.nio.file.*; import java.util.*;
public class Harness {
  static int fails = 0;
  static void check(boolean c, String m) { System.out.println((c ? "  ok   B " : "  FAIL B ") + m); if (!c) fails++; }
  static void write(Path p, String s) throws Exception { Files.createDirectories(p.getParent()); Files.write(p, s.getBytes()); }
  // what installBootstrap does: wipe staging, extract files, place links, swap
  static void install(Path files, String ver, boolean failBeforeSwap) throws Exception {
    Path st = files.resolve("usr-staging"), usr = files.resolve("usr"), old = files.resolve("usr-old");
    BootstrapStaging.wipe(st); BootstrapStaging.wipe(old);
    Files.createDirectories(st);
    write(st.resolve("bin/login"), ver); write(st.resolve("etc/version"), ver);
    BootstrapStaging.placeSymlink("login", st.resolve("bin/sh"));
    BootstrapStaging.placeSymlink("../etc/version", st.resolve("bin/v"));
    BootstrapStaging.placeSymlink("/nix/store/x", st.resolve("lib/nix"));
    if (failBeforeSwap) throw new java.io.IOException("simulated failure");
    BootstrapStaging.swap(st, usr, old);
  }
  public static void main(String[] a) throws Exception {
    Path files = Paths.get(a[0]);
    install(files, "v1", false);
    // dirty staging left by an interrupted attempt
    Path st = files.resolve("usr-staging");
    write(st.resolve("stale/junk"), "x");
    Files.createSymbolicLink(Files.createDirectories(st.resolve("bin")).resolve("sh"), Paths.get("login"));  // same link
    Files.createSymbolicLink(st.resolve("bin/v"), Paths.get("elsewhere"));                                  // stale link
    write(st.resolve("lib/nix/inside"), "a dir where a link goes");                                       // dir at link path
    Path ro = Files.createDirectories(st.resolve("nix/store/ro")); write(ro.resolve("f"), "x");
    ro.toFile().setWritable(false, false);
    try { install(files, "v2", true); } catch (java.io.IOException e) { }
    check(new String(Files.readAllBytes(files.resolve("usr/bin/login"))).equals("v1"), "a failure before the swap keeps the old usr/");
    write(st.resolve("bin/v2-leftover"), "x");
    String err = null;
    try { install(files, "v2", false); } catch (Throwable t) { err = t.toString(); }
    check(err == null, "re-extract over a dirty usr-staging succeeds" + (err == null ? "" : " (" + err + ")"));
    Path usr = files.resolve("usr");
    check(Files.exists(usr.resolve("bin/login")) && new String(Files.readAllBytes(usr.resolve("bin/login"))).equals("v2"), "usr/bin/login is the new bootstrap");
    check(Files.isSymbolicLink(usr.resolve("bin/v")) && Files.readSymbolicLink(usr.resolve("bin/v")).toString().equals("../etc/version"), "a stale link is replaced");
    check(Files.isSymbolicLink(usr.resolve("lib/nix")), "a dir at a link path is replaced by the link");
    check(!Files.exists(usr.resolve("stale")) && !Files.exists(usr.resolve("bin/v2-leftover")), "no leftover from the dirty staging reaches usr/");
    check(!Files.exists(st) && !Files.exists(files.resolve("usr-old")), "no staging or usr-old left behind");
    StringBuilder sb = new StringBuilder(); for (int i = 0; i < 200000; i++) sb.append("at x.y.Z(Z.java:1)\n");
    String b = BootstrapStaging.bound(sb.toString());
    check(b.length() <= BootstrapStaging.MAX_ERROR_CHARS + BootstrapStaging.TRUNCATED.length(), "error markdown is capped (" + b.length() + " chars)");
    check(BootstrapStaging.bound("small").equals("small"), "small markdown unchanged");
    // placeSymlink on its own, over every kind of existing path (Os.symlink threw EEXIST here)
    Path d = Files.createDirectories(files.resolve("direct"));
    String perr = null;
    try {
      BootstrapStaging.placeSymlink("t1", d.resolve("same")); BootstrapStaging.placeSymlink("t1", d.resolve("same"));
      Files.createSymbolicLink(d.resolve("other"), Paths.get("old")); BootstrapStaging.placeSymlink("t2", d.resolve("other"));
      write(d.resolve("file"), "x"); BootstrapStaging.placeSymlink("t3", d.resolve("file"));
    } catch (Throwable t) { perr = t.toString(); }
    check(perr == null && Files.readSymbolicLink(d.resolve("other")).toString().equals("t2") && Files.readSymbolicLink(d.resolve("file")).toString().equals("t3"),
        "placeSymlink over a same link / other link / file never EEXISTs" + (perr == null ? "" : " (" + perr + ")"));
    // a swap whose staging->usr rename fails puts the old usr back
    Path f2 = Files.createDirectories(files.resolve("swapfail"));
    write(f2.resolve("usr/bin/login"), "keep");
    try { BootstrapStaging.swap(f2.resolve("missing-staging"), f2.resolve("usr"), f2.resolve("usr-old")); } catch (java.io.IOException e) { }
    check(Files.exists(f2.resolve("usr/bin/login")), "a failed staging -> usr rename restores the old usr/");
    // wipeQuiet (the port of the termux terminal's wipe_rootfs): a read-only store tree goes, and a path it cannot
    // empty is COUNTED, never thrown -- a stale leftover costs one log line, not the start.
    Path q = Files.createDirectories(files.resolve("quiet"));
    write(q.resolve("nix/store/abc-pkg/bin/tool"), "x"); write(q.resolve("keep.me"), "x");
    q.resolve("nix/store/abc-pkg/bin").toFile().setWritable(false, false);
    q.resolve("nix/store/abc-pkg").toFile().setWritable(false, false);
    String werr = null; int wleft = -1;
    try { wleft = BootstrapStaging.wipeQuiet(q); } catch (Throwable t) { werr = t.toString(); }
    check(werr == null && wleft == 0 && Files.isDirectory(q) && !Files.exists(q.resolve("nix")) && !Files.exists(q.resolve("keep.me")),
        "wipeQuiet empties a tree with read-only store dirs and keeps the directory" + (werr == null ? "" : " (" + werr + ")"));
    Path afile = files.resolve("not-a-dir"); write(afile, "x");
    werr = null; wleft = -1;
    try { wleft = BootstrapStaging.wipeQuiet(afile); } catch (Throwable t) { werr = t.toString(); }
    check(werr == null && wleft > 0, "wipeQuiet on a path it cannot empty reports the leftover and never throws" + (werr == null ? "" : " (" + werr + ")"));
    check(BootstrapStaging.wipeQuiet(files.resolve("does-not-exist")) == 0, "wipeQuiet on a missing directory is 0");
    System.exit(fails == 0 ? 0 : 1);
  }
}
'''

def run(src_bs):
    work = tempfile.mkdtemp()
    try:
        for src, rel in ((src_bs, BS), (open(os.path.join(DIR, DEL)).read(), DEL)):
            dst = os.path.join(work, "src", rel.split("java/", 1)[1])
            os.makedirs(os.path.dirname(dst), exist_ok=True); open(dst, "w").write(src)
        open(os.path.join(work, "src/com/termux/app/Harness.java"), "w").write(HARNESS)
        srcs = [os.path.join(dp, f) for dp, _, fs in os.walk(os.path.join(work, "src")) for f in fs]
        c = subprocess.run(["javac", "-d", os.path.join(work, "out")] + srcs, capture_output=True, text=True)
        if c.returncode: return ["B does not compile on a plain JVM:\n" + c.stderr], ""
        fdir = os.path.join(work, "files"); os.mkdir(fdir)
        r = subprocess.run(["java", "-Xmx32m", "-cp", os.path.join(work, "out"), "com.termux.app.Harness", fdir], capture_output=True, text=True, timeout=300)
        bad = [l.strip() for l in r.stdout.splitlines() if l.startswith("  FAIL")]
        if r.returncode and not bad: bad = ["B harness exited %d: %s" % (r.returncode, r.stderr[-1500:])]
        return bad, r.stdout
    finally:
        subprocess.run(["chmod", "-R", "u+rwx", work]); shutil.rmtree(work, ignore_errors=True)

rd = lambda p: open(os.path.join(DIR, p)).read()
print("── #863 bootstrap re-extract assertions [%s] ──" % os.path.basename(DIR))
bad, out = run(rd(BS)); sys.stdout.write(out); fails += bad

src = rd(BS)
mutants = (
    ("no staging wipe", src.replace("BoundedRecursiveDelete.delete(staging);", "")),
    ("symlink not idempotent (Os.symlink-like)", src.replace("if (Files.isSymbolicLink(link)) {", "if (false) {").replace("} else if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {", "} else if (false) {")),
    ("swap does not restore old usr", src.replace("if (hadPrefix) {\n                try", "if (false) {\n                try")),
    ("error markdown uncapped", src.replace("s.length() <= MAX_ERROR_CHARS ?", "true ?")),
    ("wipeQuiet leaves the old entries in place", src.replace("BoundedRecursiveDelete.deleteContents(dir);", "")),
    ("wipeQuiet lets a failure through (fatal again)", src.replace("/* counted below */", "throw new RuntimeException(ignored);")),
    ("wipeQuiet calls an unreadable directory clean", src.replace("        } catch (IOException e) {\n            return 1;", "        } catch (IOException e) {\n            return 0;")),
)
for name, m in mutants:
    if m == src: fails.append("M mutant did not apply: " + name); continue
    mb, _ = run(m)
    print(("  ok   M " if mb else "  FAIL M ") + "mutant caught: " + name)
    if not mb: fails.append("M mutant survived: " + name)

inst = rd(INST)
for label, ok in [
    ("staging and usr-old wiped quietly before extract (parity with the termux wipe_rootfs)",
        "BootstrapStaging.wipeQuiet(TERMUX_STAGING_PREFIX_DIR.toPath())" in inst and "BootstrapStaging.wipeQuiet(PREFIX_OLD_DIR.toPath())" in inst),
    ("a leftover is one warning, never a BootstrapFailure", 'could not be removed; continuing' in inst
        and "Could not wipe the bootstrap staging directory" not in inst),
    ("symlinks placed idempotently, no raw Os.symlink", "BootstrapStaging.placeSymlink(" in inst and "Os.symlink(" not in inst),
    ("staging -> usr by BootstrapStaging.swap, no renameTo", "BootstrapStaging.swap(" in inst and "renameTo(TERMUX_PREFIX_DIR)" not in inst),
    ("$PREFIX not deleted before the extract", 'deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);\n        if (error' not in inst),
    ("failed extract wipes staging", "catch (Throwable t) {\n            try { BootstrapStaging.wipe(" in inst),
    ("every error markdown is bounded", "new BootstrapFailure(Error.getErrorMarkdownString" not in inst and "BootstrapStaging.bound(Logger.getStackTracesMarkdownString" in inst),
]:
    print(("  ok   W " if ok else "  FAIL W ") + label)
    if not ok: fails.append("W " + label)

if fails:
    for f in fails: print("::error::FAIL — " + f.splitlines()[0])
    sys.exit(1)
print("all #863 assertions passed")
PY
