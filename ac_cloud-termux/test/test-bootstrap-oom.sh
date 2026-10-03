#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #832 — bootstrap re-extract cannot OOM, and deletes a read-only Nix store ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# A37 (Android 16, 256 MB heap): installBootstrap failed deleting the old
# $PREFIX, and formatting that failure OOMed. Guava's deleteRecursively keeps
# one suppressed exception per undeletable file; a Nix store's directories are
# r-x, so every entry failed and the error carried ~10^5 stack traces.
#
#   B  the REAL BoundedRecursiveDelete + BoundedStackTrace compiled on a plain
#      JVM under -Xmx24m: a 100k-entry tree of read-only directories is
#      deleted; a 400k-suppressed exception and a 1000-throwable list format
#      within their caps.
#   M  mutation: the same harness against copies with the writable-fix and the
#      cap removed must FAIL (proves B can see the bug).
#   W  wiring: FileUtils/Logger use them; no Guava recursive delete is left.
#   P  the classes are byte-identical in ac_cloud-termux (same fork pattern).
#
# Runs as an unprivileged user (root ignores directory permissions).
set -u
DIR="$(cd "$(dirname "$0")/.." && pwd)"
exec python3 - "$DIR" <<'PY'
import os, sys, shutil, subprocess, tempfile, re
DIR = sys.argv[1]
SH = "termux-shared/src/main/java/com/termux/shared/"
DEL = SH + "file/BoundedRecursiveDelete.java"
BST = SH + "logger/BoundedStackTrace.java"
fails = []

HARNESS = r'''
import com.termux.shared.file.BoundedRecursiveDelete;
import com.termux.shared.logger.BoundedStackTrace;
import java.nio.file.*; import java.util.*;
public class Harness {
  static int fails = 0;
  static void check(boolean c, String m) { System.out.println((c ? "  ok   B " : "  FAIL B ") + m); if (!c) fails++; }
  public static void main(String[] a) throws Exception {
    Path root = Paths.get(a[0]);
    try { BoundedRecursiveDelete.delete(root); check(!Files.exists(root, LinkOption.NOFOLLOW_LINKS), "100k-entry tree of r-x directories deleted under -Xmx24m"); }
    catch (Throwable t) { check(false, "delete threw " + t.getClass().getName() + " with " + t.getSuppressed().length + " suppressed");
      check(t.getSuppressed().length <= BoundedRecursiveDelete.MAX_FAILURES + 1, "failures kept are capped"); }
    Exception big = new Exception("huge");
    Exception one = new java.io.IOException("cannot delete /nix/store/...: Permission denied");
    for (int i = 0; i < 400000; i++) big.addSuppressed(one);  // 400k suppressed, ~3 MB of refs
    String s = null;
    try { s = BoundedStackTrace.of(big); } catch (OutOfMemoryError e) { check(false, "formatting 400k suppressed: OOM"); }
    if (s != null) check(s.length() <= BoundedStackTrace.MAX_CHARS + BoundedStackTrace.TRUNCATED.length() && s.endsWith(BoundedStackTrace.TRUNCATED),
        "400k-suppressed trace formats within the cap (" + s.length() + " chars)");
    Throwable chain = new Exception("root");
    for (int i = 0; i < 5000; i++) chain = new Exception("level " + i, chain);
    String c = BoundedStackTrace.of(chain);
    check(c.length() <= BoundedStackTrace.MAX_CHARS + BoundedStackTrace.TRUNCATED.length(), "5000-deep cause chain within the cap");
    List<Throwable> many = new ArrayList<>(); for (int i = 0; i < 1000; i++) many.add(big);
    String[] arr = BoundedStackTrace.ofAll(many);
    check(arr.length == BoundedStackTrace.MAX_TRACES + 1 && arr[arr.length-1].contains("984 more"), "1000 throwables -> 16 traces + a count line");
    check(BoundedStackTrace.of(new Exception("small")).startsWith("java.lang.Exception: small"), "a small trace is unchanged");
    System.exit(fails == 0 ? 0 : 1);
  }
}
'''

MAKE_TREE = r'''
import os
def make_tree(root):
    for d in range(2000):
        p = os.path.join(root, "nix/store/%04d-pkg/lib" % d)
        os.makedirs(p)
        for f in range(48):
            open(os.path.join(p, "f%d" % f), "w").close()
        os.symlink("/nonexistent", os.path.join(p, "dangling"))
    for dp, dn, fn in os.walk(root, topdown=False):
        os.chmod(dp, 0o555)

'''
exec(MAKE_TREE)

def run(src_del, src_bst):
    work = tempfile.mkdtemp(); os.chmod(work, 0o777)
    try:
        for src, rel in ((src_del, DEL), (src_bst, BST)):
            dst = os.path.join(work, "src", rel.split("java/", 1)[1])
            os.makedirs(os.path.dirname(dst), exist_ok=True); open(dst, "w").write(src)
        open(os.path.join(work, "src", "Harness.java"), "w").write(HARNESS)
        srcs = [os.path.join(dp, f) for dp, _, fs in os.walk(os.path.join(work, "src")) for f in fs]
        c = subprocess.run(["javac", "-d", os.path.join(work, "out")] + srcs, capture_output=True, text=True)
        if c.returncode: return ["B does not compile on a plain JVM:\n" + c.stderr], ""
        tree = os.path.join(work, "tree"); os.mkdir(tree); os.chmod(work, 0o777)
        pre = []
        if os.geteuid() == 0:
            pre = ["setpriv", "--reuid=65534", "--regid=65534", "--clear-groups"]
            subprocess.run(["chown", "-R", "65534:65534", work])
        if pre: subprocess.run(pre + ["python3", "-c", "import sys;exec(sys.stdin.read())"], input=
            MAKE_TREE + "make_tree(%r)\n" % tree, text=True, check=True)
        else: make_tree(tree)
        r = subprocess.run(pre + ["java", "-Xmx24m", "-cp", os.path.join(work, "out"), "Harness", tree], capture_output=True, text=True, timeout=600)
        out = r.stdout + r.stderr
        bad = [l.strip() for l in r.stdout.splitlines() if l.startswith("  FAIL")]
        if r.returncode and not bad: bad = ["B harness exited %d: %s" % (r.returncode, out[-1500:].replace(os.environ.get("JAVA_TOOL_OPTIONS",""),""))]
        return bad, r.stdout
    finally:
        (print("KEEP", work) if os.environ.get("KEEP") else (subprocess.run(["chmod", "-R", "u+rwx", work]), shutil.rmtree(work, ignore_errors=True)))

rd = lambda p: open(os.path.join(DIR, p)).read()
print("── #832 bootstrap OOM assertions [%s] ──" % os.path.basename(DIR))
bad, out = run(rd(DEL), rd(BST)); sys.stdout.write(out); fails += bad

# M — mutants must be caught
m1 = rd(DEL).replace("makeWritable(dir);\n                return", "return")
m1 = m1.replace("if (parent != null && makeWritable(parent))", "if (false)")
m2 = rd(BST).replace("if (len > room)", "if (false)")
for name, d, b in (("delete without chmod u+w (Guava-like)", m1, rd(BST)), ("trace without a char cap", rd(DEL), m2)):
    assert d != rd(DEL) or b != rd(BST), name
    mb, _ = run(d, b)
    print(("  ok   M " if mb else "  FAIL M ") + "mutant caught: " + name)
    if not mb: fails.append("M mutant survived: " + name)

# W — wiring
fu, lg = rd(SH + "file/FileUtils.java"), rd(SH + "logger/Logger.java")
inst = rd("app/src/main/java/com/termux/app/TermuxInstaller.java")
for label, ok in [
    ("FileUtils.deleteFile uses BoundedRecursiveDelete.delete", "BoundedRecursiveDelete.delete(file.toPath())" in fu),
    ("FileUtils.clearDirectory uses BoundedRecursiveDelete.deleteContents", "BoundedRecursiveDelete.deleteContents(file.toPath())" in fu),
    ("no Guava MoreFiles.delete* call left", not re.search(r"^\s*com\.google\.common\.io\.MoreFiles\.delete", fu, re.M)),
    ("Logger.getStackTraceString is capped", "return BoundedStackTrace.of(throwable);" in lg),
    ("Logger.getStackTracesStringArray is capped", "return BoundedStackTrace.ofAll(throwablesList);" in lg),
    ("bootstrap zip is streamed (ZipInputStream, fixed buffer), never read whole",
     "new ZipInputStream(new FileInputStream(bootstrapZip))" in inst and "final byte[] buffer = new byte[8096];" in inst
     and "readAllBytes" not in inst if "openBootstrapFromLib" in inst else True),  # cld.termux: upstream's small embedded zip
]:
    print(("  ok   W " if ok else "  FAIL W ") + label)
    if not ok: fails.append("W " + label)

# P — sibling fork carries the same classes
sib = os.path.join(os.path.dirname(DIR), "ac_cloud-termux")
if os.path.isdir(sib):
    for rel in (DEL, BST):
        same = os.path.isfile(os.path.join(sib, rel)) and open(os.path.join(sib, rel)).read() == rd(rel)
        print(("  ok   P " if same else "  FAIL P ") + os.path.basename(rel) + " equals ac_cloud-termux's copy")
        if not same: fails.append("P " + rel)

if fails:
    for f in fails: print("::error::FAIL — " + f.splitlines()[0])
    sys.exit(1)
print("all #832 assertions passed")
PY
