#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #1115 — Cloud Drive is a SOURCE in the system file picker                ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY. A web page's file input and any app's ACTION_OPEN_DOCUMENT / GET_CONTENT list only
# apps that publish a DocumentsProvider or answer GET_CONTENT/PICK. Drive had neither, so it
# was never offered. This pins the manifest declaration (the JVM suite DriveDocumentsProviderTest
# pins the behaviour, test-drive-documents-picker.yml the real system picker):
#   P1 a provider: exported, grantUriPermissions, MANAGE_DOCUMENTS, DOCUMENTS_PROVIDER action
#   P2 GET_CONTENT and PICK filters for */* (GET_CONTENT with OPENABLE)
#   P3 the provider is read-only (no write mode, no create/delete override)
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
M="$ROOT/ac_cloud-drive/app/src/main/AndroidManifest.xml"
K="$ROOT/ac_cloud-drive/app/src/main/java/com/diegonmarcos/clouddrive/documents/DriveDocumentsProvider.kt"
fails=0; ok(){ echo "  PASS  $1"; }; bad(){ echo "  FAIL  $1"; fails=$((fails+1)); }
python3 - "$M" <<'PY' && ok "P1/P2 manifest: provider + GET_CONTENT/PICK filters" || bad "manifest declaration incomplete"
import sys, xml.etree.ElementTree as ET
A = "{http://schemas.android.com/apk/res/android}"
app = ET.parse(sys.argv[1]).getroot().find("application")
p = [e for e in app.iter("provider") if e.get(A + "name") == ".documents.DriveDocumentsProvider"]
assert p, "no DriveDocumentsProvider"
p = p[0]
assert p.get(A + "exported") == "true", "not exported"
assert p.get(A + "grantUriPermissions") == "true", "no grantUriPermissions"
assert p.get(A + "permission") == "android.permission.MANAGE_DOCUMENTS", "not guarded by MANAGE_DOCUMENTS"
assert any(a.get(A + "name") == "android.content.action.DOCUMENTS_PROVIDER" for a in p.iter("action")), "no DOCUMENTS_PROVIDER action"
act = [e for e in app.iter("activity") if e.get(A + "name") == ".documents.PickerActivity"][0]
assert act.get(A + "exported") == "true"
seen = {}
for f in act.iter("intent-filter"):
    acts = {a.get(A + "name") for a in f.iter("action")}
    mimes = {d.get(A + "mimeType") for d in f.iter("data")}
    cats = {c.get(A + "name") for c in f.iter("category")}
    assert "android.intent.category.DEFAULT" in cats
    for a in acts: seen[a] = (mimes, cats)
for a in ("android.intent.action.GET_CONTENT", "android.intent.action.PICK"):
    assert a in seen and "*/*" in seen[a][0], a + " */* filter missing"
assert "android.intent.category.OPENABLE" in seen["android.intent.action.GET_CONTENT"][1], "GET_CONTENT without OPENABLE"
PY
grep -Eq "override fun (createDocument|deleteDocument|renameDocument)" "$K" && bad "provider writes" || ok "P3 provider has no mutation override"
grep -q "read-only" "$K" && ok "P3 write modes refused" || bad "no read-only guard"
[ $fails -eq 0 ] && echo "test-drive-documents-provider: all checks passed" || { echo "test-drive-documents-provider: $fails FAILED"; exit 1; }
