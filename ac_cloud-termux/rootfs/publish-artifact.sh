#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #618 — publish ONE big artifact beside the APK, once per content         ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# usage: publish-artifact.sh <file> <repo> <tag> <asset-name> <digest-out> <url-out>
#
# Both terminals baked a ~400 MB root filesystem into their APK as an asset, so
# every one-line app fix shipped a 400 MB update and every phone re-downloaded
# the whole runtime to get it. This puts the big bytes on the app's OWN rolling
# GitHub release — the same channel, repo and token the APK already publishes
# through — and leaves the APK carrying only the digest and the url.
#
# ALREADY-PUBLISHED IS AUTHORITATIVE, and that is the whole idempotence rule:
# neither of these trees is byte-reproducible, so re-uploading a rebuild of the
# same declaration would change the bytes under phones that were told an older
# digest. So when <asset-name> already exists on the release, its published
# digest is read back and OURS IS DISCARDED. A new name is what publishes new
# bytes, and the caller derives the name from the content address of the
# declaration — so a pin bump is a new name and an app-code change is not.
#
# The digest written here is what the APK bakes and what the phone gates its
# fetch on, so it always describes the bytes actually hosted.
set -eu

FILE="${1:?the artifact to publish}"
REPO="${2:?owner/repo}"
TAG="${3:?release tag}"
NAME="${4:?asset name}"
DIGEST_OUT="${5:?where to write the sha256}"
URL_OUT="${6:?where to write the resolved url}"

[ -f "$FILE" ] || { echo "publish-artifact: $FILE does not exist" >&2; exit 1; }
: "${GH_TOKEN:?a release upload needs GH_TOKEN in the environment}"

URL="https://github.com/$REPO/releases/download/$TAG/$NAME"

# A missing release is created rather than reported: the rolling tag is the
# app's stable install URL and the engine creates it too, so the two orders of
# "first APK" and "first artifact" must both work.
if ! gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
    echo "publish-artifact: creating the rolling '$TAG' release on $REPO"
    gh release create "$TAG" --repo "$REPO" --title "$TAG" --notes "Rolling latest." --latest
fi

published="$(gh release view "$TAG" --repo "$REPO" --json assets --jq '.assets[].name')"
if printf '%s\n' "$published" | grep -qx -- "$NAME"; then
    echo "publish-artifact: $NAME is already published — keeping the hosted bytes"
    sha="$(curl -fsSL "$URL.sha256" | tr -d ' \t\r\n')"
    [ "${#sha}" -eq 64 ] || { echo "publish-artifact: $URL.sha256 is not a sha256: '$sha'" >&2; exit 1; }
else
    sha="$(sha256sum "$FILE" | cut -d' ' -f1)"
    staging="$(mktemp -d)"
    trap 'rm -rf "$staging"' EXIT
    # gh names an asset after its file, so the file is staged under the asset name.
    cp "$FILE" "$staging/$NAME"
    printf '%s\n' "$sha" > "$staging/$NAME.sha256"
    echo "publish-artifact: uploading $NAME ($(wc -c < "$FILE") bytes, sha256 $sha)"
    gh release upload "$TAG" "$staging/$NAME" "$staging/$NAME.sha256" --repo "$REPO"
fi

printf '%s\n' "$sha" > "$DIGEST_OUT"
printf '%s\n' "$URL" > "$URL_OUT"
echo "publish-artifact: $URL -> $sha"
