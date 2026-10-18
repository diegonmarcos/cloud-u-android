#!/usr/bin/env python3
"""One-source guard for Cloud Store > Access > Identity (run by test-store-identity-page.sh).
  usage: store-identity-guard.py <repo-root> <I5|I6>
  I5 shared-uid groups are declared ONLY in aa_cloud-superapp/data/fleet-identity.json (plus the
     file+key it names as a group's uid owner): no other JSON carries a shared_user_id/sharedUserId
     key, and no Kotlin/Java source outside tests quotes a declared shared uid value.
     (Manifests carry the platform's literal; test-fleet-shared-uid.sh checks them against the declaration.)
  I6 the Access tab list is declared once, in ac_cloud-store/build.json::ui access.pages:
     AccessPage.kt's PAGE_* ids equal those ids, and no Kotlin file of the app lists the tabs."""
import json, os, re, sys

R, WHICH = sys.argv[1], sys.argv[2]
SKIP = ('.git', 'node_modules', 'build', '.gradle')
FI = 'aa_cloud-superapp/data/fleet-identity.json'
bad = []


def files(root, exts):
    for dp, dn, fn in os.walk(root):
        dn[:] = [d for d in dn if d not in SKIP]
        for f in fn:
            if f.endswith(exts):
                yield os.path.join(dp, f)


def code(path):
    t = open(path, errors='replace').read()
    t = re.sub(r'/\*.*?\*/', '', t, flags=re.S)
    return re.sub(r'//[^\n]*', '', t)


def has_key(o):
    if isinstance(o, dict):
        return any(k in ('shared_user_id', 'sharedUserId') or has_key(v) for k, v in o.items())
    if isinstance(o, list):
        return any(has_key(v) for v in o)
    return False


if WHICH == 'I5':
    owners, values = {FI}, set()
    for g in json.load(open(os.path.join(R, FI)))['groups']:
        v = g.get('shared_user_id')
        if isinstance(v, dict):
            owners.add(v['from'])
            d = json.load(open(os.path.join(R, v['from'])))
            for k in v['key'].split('.'):
                d = d[k]
            values.add(d)
        elif v:
            values.add(v)
    for p in files(R, ('.json',)):
        rel = os.path.relpath(p, R)
        if rel in owners:
            continue
        try:
            d = json.load(open(p))
        except Exception:
            continue
        if has_key(d):
            bad.append(rel + ' declares a shared uid outside ' + FI)
    for p in files(R, ('.kt', '.java')):
        rel = os.path.relpath(p, R)
        if '/test/' in '/' + rel or '/androidTest/' in rel:
            continue
        t = code(p)
        bad += [rel + ' quotes the shared uid ' + v for v in values if '"' + v + '"' in t]
elif WHICH == 'I6':
    S = os.path.join(R, 'ac_cloud-store')
    sec = next(s for s in json.load(open(os.path.join(S, 'build.json')))['ui']['sections'] if s['id'] == 'access')
    decl = [p['id'] for p in sec['pages']]
    kt = code(os.path.join(S, 'app/src/main/java/com/diegonmarcos/cloudstore/AccessPage.kt'))
    consts = re.findall(r'const val PAGE_\w+ = "([^"]+)"', kt)
    if sorted(consts) != sorted(decl):
        bad.append('AccessPage PAGE_* %s != build.json access pages %s' % (consts, decl))
    lst = re.compile(r'(?:listOf|arrayOf|setOf)\(([^)]*)\)')
    for p in files(os.path.join(S, 'app/src/main'), ('.kt',)):
        for m in lst.finditer(code(p)):
            hits = [i for i in decl if '"%s"' % i in m.group(1)] + re.findall(r'PAGE_\w+', m.group(1))
            if len(hits) >= 3:
                bad.append(os.path.basename(p) + ' lists the Access tabs: ' + m.group(0)[:60])
else:
    sys.exit(2)

print('\n'.join(bad))
sys.exit(1 if bad else 0)
