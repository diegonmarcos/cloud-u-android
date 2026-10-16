#!/usr/bin/env bash
# ac_cloud-notes tester (Phase 1): the gated web image is built from the web
# bundle and the workflow proves it serves, without a build here.
#
#   Dockerfile copies the SAME dist the build step produces and the SAME nginx.conf
#   nginx.conf has the SPA fallback, a healthcheck path and no-store for the shell
#   ship-cloud-notes.yml builds it, smoke-tests it (health, shell, deep link
#   fallback) BEFORE pushing, and pushes only from main
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

docker = rd('deploy/web/Dockerfile')
conf = rd('deploy/web/nginx.conf')
wf = rd('..', '1_cicd', 'src', 'cicd', 'ship-cloud-notes.yml')
web = rd('packages/frontend/apps/web/package.json')

check('COPY packages/frontend/apps/web/dist/' in docker, 'the image copies the web app\'s dist')
check('COPY deploy/web/nginx.conf' in docker, 'the image copies its nginx.conf')
check('"build": "affine bundle"' in web, 'the web app builds with `affine bundle` (what the workflow runs)')
check('try_files $uri $uri/ /index.html' in conf, 'nginx falls back to the app shell for router paths')
check('location = /healthz' in conf and 'return 200' in conf, 'nginx answers a healthcheck path')
check(re.search(r'location = /index\.html \{[^}]*no-store', conf) is not None, 'the app shell is never cached')
check('immutable' in conf, 'hashed assets are cached')

job = wf[wf.index('  web_image:'):] if '  web_image:' in wf else ''
job = job.split('\n  build_and_publish:')[0] if '\n  build_and_publish:' in job else job
check(bool(job), 'ship-cloud-notes.yml declares the web_image job')
check('yarn affine @affine/web build' in job, 'the job builds the web bundle')
check('docker build' in job and 'deploy/web/Dockerfile' in job, 'the job builds the image from deploy/web/Dockerfile')
i_smoke = job.find('/healthz'); i_push = job.find('uses: ./.github/actions/publish-ghcr')
check(i_smoke != -1 and i_push != -1 and i_smoke < i_push, 'the smoke test (healthz) runs BEFORE the push')
check('index.html' in job[i_smoke:i_push] if i_smoke != -1 and i_push != -1 else False, 'the smoke test also fetches the shell and a deep link')
check("github.ref == 'refs/heads/main'" in job, 'the image is pushed from main only, through the publish-ghcr module')
check('cloud-notes-web' in job, 'the image is named like the fleet\'s other web images (<name>-binaries)')

print('RESULT: %s' % ('RED' if fails else 'GREEN'))
raise SystemExit(1 if fails else 0)
PY
