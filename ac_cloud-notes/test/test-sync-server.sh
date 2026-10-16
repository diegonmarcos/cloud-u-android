#!/usr/bin/env bash
# ac_cloud-notes tester (Phase 2): the sync server's guarantees are pinned in
# source, the same way the web image's are (test-web-image.sh).
#
#   fail closed      main.mjs builds its verifier from the environment, which throws
#                    when none is configured; the Dockerfile sets no default token
#   refuse at upgrade  server.mjs answers 401 and destroys the socket in onUpgrade
#   no secret baked  nothing in sync-server/ carries a token literal other than tests
#   CI proves it     the sync_server job runs the suite AND the image smoke test
#                    (healthz, 401 on a bare upgrade, refusal to start unconfigured)
#                    before it pushes, and pushes from main only
#
# python3 only; paths below are the only constants.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
import os, re, sys
root = sys.argv[1]
fails = 0
def check(ok, msg):
    global fails
    print(('  PASS  ' if ok else '  FAIL  ') + msg)
    if not ok: fails += 1
rd = lambda *p: open(os.path.join(root, *p), encoding='utf-8').read()

main = rd('sync-server/src/main.mjs')
auth = rd('sync-server/src/auth.mjs')
srv = rd('sync-server/src/server.mjs')
docker = rd('sync-server/Dockerfile')
wf = rd('..', '1_cicd', 'src', 'cicd', 'ship-cloud-notes.yml')

check('verifierFromEnv()' in main, 'main.mjs takes its verifier from the environment')
check("refusing to start" in auth and 'throw new Error' in auth, 'no configured verifier means no server')
check(not re.search(r'SYNC_STATIC_TOKENS\s*=|SYNC_INTROSPECT_URL\s*=', docker), 'the Dockerfile sets no default token or introspection URL')
check(re.search(r'onUpgrade\([^)]*\)\s*\{[^}]*401 Unauthorized[^}]*socket\.destroy', srv, re.S) is not None, 'the upgrade is refused with 401 before any document is touched')
check('onAuthenticate' in srv and 'verify(' in srv, 'the in-band authentication is checked as well')
check("path === '/healthz'" in srv, 'the only unauthenticated HTTP path is /healthz')
check('USER node' in docker, 'the container does not run as root')
check('HEALTHCHECK' in docker, 'the image carries its healthcheck')
check(not re.search(r"['\"][A-Za-z0-9_\-]{24,}['\"]", main + auth + srv), 'no long token-like literal in the server sources')

job = wf[wf.index('  sync_server:'):] if '  sync_server:' in wf else ''
job = job.split('\n  build_and_publish:')[0] if '\n  build_and_publish:' in job else job
check(bool(job), 'ship-cloud-notes.yml declares the sync_server job')
check('npm ci' in job and 'vitest run' in job, 'the job installs from the lockfile and runs the suite')
i_smoke = job.find('Smoke test'); i_push = job.find('uses: ./.github/actions/publish-ghcr')
check(i_smoke != -1 and i_push != -1 and i_smoke < i_push, 'the image smoke test runs BEFORE the push')
check('401' in job[i_smoke:i_push] and 'refusing to start' in job[i_smoke:i_push], 'the smoke test covers the 401 and the fail-closed start')
check("github.ref == 'refs/heads/main'" in job, 'the image is pushed from main only, through the publish-ghcr module')
check('cloud-notes-sync' in job, 'the image is named like the fleet\'s other images (<name>-binaries)')

print('RESULT: %s' % ('RED' if fails else 'GREEN'))
raise SystemExit(1 if fails else 0)
PY
