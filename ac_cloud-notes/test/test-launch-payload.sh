#!/usr/bin/env bash
# ac_cloud-notes tester (#522): the APK THAT SHIPPED can actually start.
#
# THE FAILURE THIS EXISTS FOR. The owner opened Cloud Notes and got
# "An internal error occurred." — twice, #513 and again here. Every gate in the
# ship pipeline was green both times, because every gate looked at the BUILD and
# none looked at the app. #469 closed with "Ship run 35327867569 success, main
# tip 4eca5df295c1 at 18/18" on an app that painted nothing at all.
#
# So this tester asserts against BYTES THAT ARE ON THE OWNER'S PHONE: the
# rolling-release APK named by build.json::release.gh_release, the same file the
# fleet updater installs. Not dist/ (it does not exist when testers run, they run
# BEFORE the toolchain is provisioned), and never a fixture written by hand —
# a tester that parses its own fixture proves the parser works.
#
#   T1  the WebView has a document to load, above the declared byte floor
#   T2  the JS bundle is really there: chunk count and total bytes
#   T3  the Rust document store is packaged, above its byte floor
#   T4  the two payload files FIRST LAUNCH cannot proceed without are present:
#       the nbstore worker (app.tsx opens the local store through it) and the
#       onboarding template (buildShowcaseWorkspace fetches it to create the
#       first workspace). Either one absent and createFirstAppData() rejects,
#       the rejection is swallowed in desktop/pages/index/index.tsx and the
#       owner is shown "An internal error occurred."
#   T5  every uniffi/ffi symbol the committed Kotlin binding DECLARES is really
#       exported by the .so in that APK
#   T6  every uniffi/ffi symbol the committed Kotlin binding CALLS is DECLARED
#       in it — the check that would have caught #469's hand-edit, which cut two
#       lines too many and left new_doc_storage_pool called-but-undeclared plus
#       an orphan "): Pointer" in the interface body
#
# Every floor, glob and path below is DATA (build.json::release.artifact_assert).
# Nothing here is a literal about this app.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_JSON="$ROOT/build.json"
[ -f "$BUILD_JSON" ] || { echo "ERROR: $BUILD_JSON missing" >&2; exit 2; }
command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 required" >&2; exit 2; }
command -v curl    >/dev/null 2>&1 || { echo "ERROR: curl required" >&2; exit 2; }

APK="${CLOUD_NOTES_APK:-}"
if [ -z "$APK" ]; then
  url="$(python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print(d['release']['gh_release'].get('url') or '')" "$BUILD_JSON")"
  if [ -z "$url" ]; then
    repo="$(python3 -c "
import json,sys
d=json.load(open(sys.argv[1]))
r=d['release']['gh_release']
print('https://github.com/diegonmarcos/cloud-u-android/releases/download/%s/%s' % (r['rolling_tag'], r['asset_name']))
" "$BUILD_JSON")"
    url="$repo"
  fi
  APK="$(mktemp -t cloud-notes-XXXXXX.apk)"
  trap 'rm -f "$APK"' EXIT
  echo "  fetching the SHIPPED artifact: $url"
  if ! curl -fsSL --retry 3 --retry-delay 2 -o "$APK" "$url"; then
    # A check that cannot reach its subject must fail, never go quiet.
    echo "  FAIL  cannot download $url — this tester proves nothing without the shipped APK" >&2
    exit 1
  fi
fi

python3 - "$APK" "$BUILD_JSON" "$ROOT" <<'PY'
import fnmatch, json, os, re, struct, sys, zipfile

apk_path, build_json, root = sys.argv[1:4]
cfg = json.load(open(build_json))['release']['artifact_assert']
zf = zipfile.ZipFile(apk_path)
sizes = {i.filename: i.file_size for i in zf.infolist()}

passed = failed = 0
def check(ok, msg):
    global passed, failed
    if ok:
        passed += 1
        print("  PASS  " + msg)
    else:
        failed += 1
        print("  FAIL  " + msg)

print("== T1: the WebView has a document to load ==")
entry, floor = cfg['web_entry'], cfg['web_entry_min_bytes']
got = sizes.get(entry)
check(got is not None and got >= floor,
      "%s is %s bytes, floor %d" % (entry, got, floor))

print("== T2: the JS bundle is really in the APK ==")
chunks = {n: s for n, s in sizes.items()
          if n.startswith(cfg['js_prefix']) and n.endswith('.js')}
total = sum(chunks.values())
check(len(chunks) >= cfg['js_min_chunks'],
      "%s holds %d .js chunks, floor %d" % (cfg['js_prefix'], len(chunks), cfg['js_min_chunks']))
check(total >= cfg['js_min_total_bytes'],
      "%s holds %d bytes of JS, floor %d" % (cfg['js_prefix'], total, cfg['js_min_total_bytes']))

print("== T3: the Rust document store is packaged ==")
for lib in cfg['native_libs']:
    got = sizes.get(lib)
    check(got is not None and got >= cfg['native_lib_min_bytes'],
          "%s is %s bytes, floor %d" % (lib, got, cfg['native_lib_min_bytes']))

print("== T4: the files FIRST LAUNCH cannot proceed without ==")
for glob in cfg.get('required_globs', []):
    hits = [n for n in sizes if fnmatch.fnmatch(n, glob)]
    check(bool(hits) and all(sizes[h] > 0 for h in hits),
          "%s matches %s" % (glob, hits or 'NOTHING — first launch cannot complete'))

print("== T5/T6: the committed UniFFI binding agrees with the shipped .so ==")
def elf_defined_dynsyms(data):
    if data[:4] != b'\x7fELF' or data[4] != 2:
        raise ValueError('not an ELF64 object')
    e_shoff, = struct.unpack_from('<Q', data, 0x28)
    e_shentsize, e_shnum, e_shstrndx = struct.unpack_from('<HHH', data, 0x3a)
    secs = []
    for i in range(e_shnum):
        o = e_shoff + i * e_shentsize
        name, typ, flags, addr, off, size, link, info, align, entsize = \
            struct.unpack_from('<IIQQQQIIQQ', data, o)
        secs.append(dict(name=name, off=off, size=size, link=link, entsize=entsize))
    shstr = secs[e_shstrndx]
    def sec_name(s):
        b = data[shstr['off'] + s['name']:]
        return b[:b.index(b'\0')].decode()
    out = set()
    for s in secs:
        if sec_name(s) != '.dynsym' or not s['entsize']:
            continue
        strtab = secs[s['link']]
        for i in range(s['size'] // s['entsize']):
            o = s['off'] + i * s['entsize']
            st_name, st_info, st_other, st_shndx, st_value, st_size = \
                struct.unpack_from('<IBBHQQ', data, o)
            if st_shndx == 0:
                continue
            b = data[strtab['off'] + st_name:]
            nm = b[:b.index(b'\0')].decode('utf-8', 'replace')
            if nm:
                out.add(nm)
    return out

binding_rel = cfg.get('kotlin_binding')
if not binding_rel:
    check(False, "build.json::release.artifact_assert.kotlin_binding is not declared — T5/T6 have no subject")
else:
    binding_path = os.path.join(root, binding_rel)
    if not os.path.isfile(binding_path):
        check(False, "%s does not exist — the declared binding path resolves to nothing" % binding_rel)
    else:
        src = open(binding_path).read()
        declared = set(re.findall(r'^\s*fun ((?:uniffi|ffi)_\w+)\(', src, re.M))
        called = set(re.findall(r'\.((?:uniffi|ffi)_\w+)\(', src))
        exported = {s for s in elf_defined_dynsyms(zf.read(cfg['native_libs'][0]))
                    if s.startswith(('uniffi_', 'ffi_'))}
        check(bool(declared) and bool(exported),
              "binding declares %d symbols, .so exports %d" % (len(declared), len(exported)))
        missing_in_so = sorted(declared - exported)
        check(not missing_in_so,
              "every declared symbol is exported by the .so%s"
              % ('' if not missing_in_so else ' — ABSENT: %s' % missing_in_so))
        undeclared = sorted(called - declared)
        check(not undeclared,
              "every called symbol is declared in the binding%s"
              % ('' if not undeclared
                 else ' — CALLED BUT NOT DECLARED: %s (this is #469s hand-edit)' % undeclared))

print()
print("  cloud-notes launch-payload tester: %d passed, %d failed" % (passed, failed))
sys.exit(1 if failed else 0)
PY
