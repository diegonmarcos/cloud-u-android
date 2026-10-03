# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-licence-provenance.py ───
#!/usr/bin/env python3
"""
cloud-android-licence-provenance — #805: how much of each in-tree upstream-derived
directory is still upstream's, measured against the pinned upstream revision.

  cloud-android-licence-provenance.py ROOT [ID ...]

Reads ROOT/licenses/upstreams.json, shallow-clones each upstream at its pin
into a temp dir (deleted right after: the disk this runs on is shared), and
classifies every tracked file under the module's path:

  verbatim   its blob is byte-identical to SOME upstream blob (git blob sha)
  modified   it maps to an upstream file (same relative path, else same
             basename with the longest common path suffix — survives the
             package renames every fork here did) and shares significant
             lines with it
  new        no upstream counterpart, or a counterpart it shares no
             significant line with (a rewritten README is not derived)

Lines: non-blank, right-stripped. "derived" lines of a modified file are the
multiset intersection with its upstream counterpart; the rest are "new".
Significant = 12+ chars stripped, so braces and `import` noise cannot make a
rewrite look derived. Limitation: multiset overlap, not a real diff — it
ignores line order, so a reshuffled file reads as derived; good to a few %,
which is the resolution a licensing summary needs. A renamed identifier
(com.termux -> cld.termux) makes that line count as new, so "new" is an UPPER
bound on what the owner authored.

Licensing note (not legal advice): the percentage does not change which
licence applies. A modified upstream file stays under the upstream licence.

Writes ROOT/licenses/provenance.json (summary per module) and
ROOT/licenses/provenance/<id>.tsv (per file). Run by hand when a pin moves;
CI does not clone upstreams.
"""
import collections, datetime, json, os, shutil, subprocess, sys, tempfile

SIG = 12
REPO_KEYS = ("upstream_repo", "repo", "github_mirror", "url")
REV_KEYS = ("pinned_commit", "pinned_revision", "commit", "revision", "pinned_tag", "ref")


def git(*a, cwd=None, check=True):
    return subprocess.run(["git", *a], cwd=cwd, check=check, capture_output=True, text=True).stdout


def resolve_pin(root, m):
    if "pin_from" not in m:
        return m["repo"], m.get("ref", ""), "licenses/upstreams.json"
    path, key = m["pin_from"].split("#", 1)
    o = json.load(open(os.path.join(root, path)))
    # keys contain '-' (forks.media-center) but never '.', so split on '.' is safe
    for k in key.split("."):
        o = o[k]
    repo = next(o[k] for k in REPO_KEYS if o.get(k))
    rev = next((o[k] for k in REV_KEYS if o.get(k)), "")
    return repo, rev, m["pin_from"]


def clone(repo, rev, dest):
    git("init", "-q", dest)
    git("remote", "add", "origin", repo, cwd=dest)
    refs = [rev] if rev else ["HEAD"]
    if rev and len(rev) < 40:  # a tag or a short sha: try the tag namespace first
        refs = [f"refs/tags/{rev}", rev]
    target = "FETCH_HEAD"
    for r in refs:
        if subprocess.run(["git", "fetch", "-q", "--depth", "1", "origin", r], cwd=dest,
                          capture_output=True).returncode == 0:
            break
    else:
        # a short sha cannot be fetched by name: fetch history (treeless) and resolve it
        git("fetch", "-q", "--filter=tree:0", "origin", cwd=dest)
        target = git("rev-parse", rev + "^{commit}", cwd=dest).strip()
    git("checkout", "-q", target, cwd=dest)
    return git("rev-parse", "HEAD", cwd=dest).strip()


def detect_base(repo, ours, subpath, cap=600):
    """No pin recorded: find the upstream commit whose tree shares the most
    byte-identical blobs with ours. Candidates are every tag plus the last
    `cap` commits of the default branch; trees come from a blob-less clone, so
    no file content is downloaded. Limitation: a ceiling of `cap` commits — an
    older vendoring than that window resolves to its nearest tag instead."""
    tmp = tempfile.mkdtemp(prefix="prov-base-")
    try:
        git("clone", "-q", "--bare", "--filter=blob:none", repo, tmp)
        cands = git("for-each-ref", "--format=%(objectname)^{commit}", "refs/tags", cwd=tmp).split()
        cands += git("rev-list", f"--max-count={cap}", "HEAD", cwd=tmp).split()
        mine, best = set(ours.values()), (-1, "")
        for c in dict.fromkeys(cands):
            out = subprocess.run(["git", "ls-tree", "-r", c, "--", subpath or "."], cwd=tmp,
                                 capture_output=True, text=True).stdout
            hit = sum(1 for l in out.splitlines() if l.split()[2] in mine)
            if hit > best[0]:
                best = (hit, git("rev-parse", c, cwd=tmp).strip())
        desc = subprocess.run(["git", "describe", "--tags", "--always", best[1]], cwd=tmp,
                              capture_output=True, text=True).stdout.strip()
        return best[1], desc, best[0]
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def lines(p):
    try:
        with open(p, "rb") as h:
            b = h.read()
    except OSError:
        return None
    if b"\0" in b[:8192]:
        return None  # binary
    return [l.rstrip() for l in b.decode("utf-8", "replace").splitlines() if l.strip()]


def measure(root, m):
    repo, rev, src = resolve_pin(root, m)
    path = m["path"].rstrip("/")
    excl = tuple(e.rstrip("/") + "/" for e in m.get("exclude", []))
    ours = {}
    for l in git("ls-files", "-s", "--", path, cwd=root).splitlines():
        meta, f = l.split("\t", 1)
        if not f.startswith(excl):
            ours[f] = meta.split()[1]
    detected = None
    if m.get("detect_base"):
        rev, desc, hits = detect_base(repo, ours, m.get("subpath", ""))
        detected = {"sha": rev, "describe": desc, "identical_blobs": hits,
                    "_doc": "No pin is recorded in this repo; this is the upstream commit sharing the most "
                            "byte-identical files with the tree (tags + last 600 default-branch commits searched)."}
    tmp = tempfile.mkdtemp(prefix="prov-")
    try:
        sha = clone(repo, rev, tmp)
        sub = m.get("subpath", "")
        up_root = os.path.join(tmp, sub)
        up = {}
        for l in git("ls-tree", "-r", "HEAD", "--", sub or ".", cwd=tmp).splitlines():
            meta, f = l.split("\t", 1)
            up[os.path.relpath(f, sub or ".")] = meta.split()[2]
        blobs = set(up.values())
        by_base = collections.defaultdict(list)
        for f in up:
            by_base[os.path.basename(f)].append(f)

        def counterpart(rel):
            if rel in up:
                return rel
            best, score = None, 0
            for c in by_base.get(os.path.basename(rel), []):
                a, b = rel.split("/")[::-1], c.split("/")[::-1]
                n = next((i for i, (x, y) in enumerate(zip(a, b)) if x != y), min(len(a), len(b)))
                if n > score:
                    best, score = c, n
            return best

        rows, tot, matched_up = [], collections.Counter(), set()
        for f, blob in sorted(ours.items()):
            rel = os.path.relpath(f, path)
            ol = lines(os.path.join(root, f))
            n = len(ol) if ol is not None else 0
            if blob in blobs:
                cls, cp, der = "verbatim", "", n
            else:
                cp = counterpart(rel)
                der = 0
                if cp and ol is not None:
                    ul = lines(os.path.join(up_root, cp)) or []
                    sig_o = collections.Counter(l.strip() for l in ol if len(l.strip()) >= SIG)
                    sig_u = collections.Counter(l.strip() for l in ul if len(l.strip()) >= SIG)
                    if sum((sig_o & sig_u).values()):
                        der = sum((collections.Counter(ol) & collections.Counter(ul)).values())
                cls = "modified" if der else "new"
                if cls == "modified":
                    matched_up.add(cp)
                else:
                    cp = ""
            rows.append((f, cls, cp, der, n - der, "bin" if ol is None else "txt"))
            tot[cls] += 1
            tot["lines_derived"] += der
            tot["lines_new"] += n - der
        vb = {ours[f] for f, c, *_ in rows if c == "verbatim"}
        verbatim_up = {k for k, v in up.items() if v in vb}
        os.makedirs(os.path.join(root, "licenses/provenance"), exist_ok=True)
        with open(os.path.join(root, f"licenses/provenance/{m['id']}.tsv"), "w") as h:
            h.write("path\tclass\tupstream_counterpart\tlines_derived\tlines_new\tkind\n")
            for r in rows:
                h.write("\t".join(map(str, r)) + "\n")
        lt = tot["lines_derived"] + tot["lines_new"]
        first = git("log", "--diff-filter=A", "--format=%h %ad", "--date=short", "--reverse", "--", path,
                    cwd=root).splitlines()
        return {
            "path": path, "upstream": m["upstream"], "upstream_licence": m["licence"],
            "repo": repo, "pin": rev or "(none recorded — measured at upstream HEAD)", "pin_source": src,
            "measured_sha": sha, "detected_base": detected,
            "files": {"total": len(rows), "verbatim": tot["verbatim"], "modified": tot["modified"],
                      "new": tot["new"]},
            "lines": {"total": lt, "upstream_derived": tot["lines_derived"], "new": tot["lines_new"],
                      "pct_upstream_derived": round(100 * tot["lines_derived"] / lt, 1) if lt else None},
            "upstream_files_not_in_tree": len(set(up) - matched_up - verbatim_up),
            "git": {"first_added": first[0] if first else "",
                    "commits_touching": len(git("log", "--format=%h", "--", path, cwd=root).splitlines())},
        }
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main(argv):
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    root = os.path.abspath(argv[1])
    only = set(argv[2:])
    spec = json.load(open(os.path.join(root, "licenses/upstreams.json")))
    out_p = os.path.join(root, "licenses/provenance.json")
    out = json.load(open(out_p)) if os.path.exists(out_p) else {}
    out["_doc"] = ("Generated by 1_cicd/src/scripts/cloud-android-licence-provenance.py from "
                   "licenses/upstreams.json. Per-file classification in licenses/provenance/<id>.tsv. "
                   "See the script docstring for what verbatim/modified/new and derived lines mean. "
                   "Not legal advice: a modified upstream file stays under the upstream licence whatever the %.")
    head = git("rev-parse", "--short=9", "HEAD", cwd=root).strip()
    mods = out.setdefault("modules", {})
    for m in spec["modules"]:
        if only and m["id"] not in only:
            continue
        if m.get("measure") is False:
            repo, rev, src = resolve_pin(root, m)
            mods[m["id"]] = {"path": m["path"], "upstream": m["upstream"], "upstream_licence": m["licence"],
                             "repo": repo, "pin": rev, "pin_source": src, "measured": False,
                             "why": m.get("_doc_measure", "")}
        else:
            print(f"measuring {m['id']} …", file=sys.stderr, flush=True)
            mods[m["id"]] = measure(root, m)
            mods[m["id"]]["measured_on"] = datetime.date.today().isoformat()
            mods[m["id"]]["tree_measured_at"] = head
            print(f"  {m['id']}: {mods[m['id']]['files']} {mods[m['id']]['lines']}", file=sys.stderr, flush=True)
        with open(out_p, "w") as h:  # checkpoint: a later clone failing keeps earlier results
            json.dump(out, h, indent=2, sort_keys=True)
            h.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
