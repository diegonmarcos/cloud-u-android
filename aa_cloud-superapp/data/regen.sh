#!/usr/bin/env bash
# Regenerate the three data snapshots the APK ships with:
#
#   mesh.json             — wg0 mesh nodes / peers / transports
#                            (source: cloud/a_solutions/bb-net_wireguard-mesh/
#                             src/data/mesh.json, schema wg-mesh/v1)
#
#   services_public.json  — containers WITH proxy.domain (caddy edge)
#   services_private.json — containers WITHOUT proxy (internal DBs,
#                            queues, MCPs, dev tooling, …)
#                            (source: cloud-data's
#                             _cloud-data-consolidated.json)
#
# Both upstream files are gitignored, so we commit the derived snapshots
# here. Re-run this script any time the upstream changes; the gradle
# build picks the new bytes up automatically on the next APK assembly.
#
# Usage:
#   ./regen.sh                       # auto-discover upstream paths
#   ./regen.sh <consolidated.json> <mesh.json>
#   ./regen.sh --constellation-only  # just constellation-fleet.json (no
#                                     # gitignored upstream files needed —
#                                     # this is what app/build.gradle runs
#                                     # automatically on every build)

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

# ── constellation-fleet.json — the Constellation AppStore's app registry.
# Auto-scanned from each sibling */build.json that declares constellation
# membership in its data (self-registering, DRY),
# in TWO shapes, no hand-maintained list (FIRE 4/6):
#   • top-level apps (browser/vault/wallet/superapp/nav/ide) — identity at
#     .android.application_id + .release.ghcr.image.
#   • fork-apps (dialer/chat/mail/matrix) — the promoted ex-cloud-comms forks,
#     each now its OWN ac_cloud-<id> dir + ship-cloud-<id>.yml CI; identity
#     lives under .forks.<key>. Scanned ONLY for non-top-level dirs, so the IDE
#     hub's own nested forks (editor/files/utils) are never pulled in.
# The cloud-comms HUB is decommissioned (archived to z_archive/ac_cloud-comms),
# so it is no longer scanned and drops out of the fleet automatically.
# Baked into BuildConfig.CONSTELLATION_FLEET_B64 by app/build.gradle. Depends
# ONLY on the sibling repos (always present), so it runs before the cloud-data
# snapshot resolution below.
UNIX="$(cd "$HERE/../.." && pwd)"
regen_constellation() {
    command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required" >&2; return 1; }
    # owner/repo = the monorepo these apps ship from (invariant identity).
    #
    # EVERY ASSET URL BELOW NAMES ITS TAG EXPLICITLY, as
    # <rel>/download/<tag>/<asset> — never <rel>/<tag>/download/<asset>.
    #
    # The two are different GitHub routes and only one of them is stable.
    # `/releases/latest/download/<asset>` is a MAGIC route: GitHub resolves
    # "whichever release is currently latest" and only then looks for the asset
    # on it. Our rolling release is TAGGED the literal word `latest`, so the two
    # coincide right up to the moment any per-app tagged release is published —
    # cloud-comms-mail-*, firestack-aar-*, cloud-unix-termux-boot-v* all are —
    # whereupon the magic route resolves to THAT release, which does not carry
    # our assets, and every install URL in the fleet answers 302 then 404.
    #
    # Measured 2026-09-10: at 11:57:46Z
    # /releases/latest/download/cloud-nixdroid.apk redirected to
    # /releases/download/firestack-aar-20260910.114222/cloud-nixdroid.apk → 404
    # (x-github-request-id CAF4:289D54:E9BFD0:14410E7:6AA29B0B). Three minutes
    # later the same URL served 200. It FLAPS with whatever shipped last, which
    # is why this read for days as an intermittent phone fault rather than as a
    # broken URL, and why Cloud Terminal (Nix) — the one app with no registry
    # package to fall back to — was the one that surfaced it, as a bare 403.
    #
    # `/releases/download/<tag>/<asset>` names the tag and resolves to nothing
    # else, so no later publish can hijack it.
    local rel="https://github.com/diegonmarcos/cloud-u-android/releases"
    local tree="https://github.com/diegonmarcos/cloud-u-android/tree/main"
    local pkg="https://github.com/diegonmarcos/cloud-u-android/pkgs/container"

    # ── Per-ABI release assets: { "<abi>": "<gh_asset>", … } ──────────────────
    # A fleet entry carried ONE flat `asset`/`release_url`, taken from
    # release.gh_release.asset_name — which is the arm64 name, because arm64 is
    # the default publish. The updater's GHCR path was already ABI-aware (the
    # `latest-x86_64` tag comes from these same variants), but the GitHub
    # Release path was not, so an x86_64 device that took the release channel —
    # which is tried FIRST — downloaded the arm64 APK and failed with
    # INSTALL_FAILED_NO_MATCHING_ABIS, or 404ed. Half the ABI dimension was
    # described and the other half was silently wrong.
    #
    # Keyed by supported_abis (falling back to abis, then to the variant id for
    # older entries that only carry one) because supported_abis is EXACTLY the
    # key set AbiUpdateTag matches Build.SUPPORTED_ABIS against for the tag —
    # two lookups keyed differently would be two rules again.
    # `asset` is left untouched: it is the fallback and the dedup key.
    local variant_assets='
        [ (.release.variants // [])[]
          | . as $v
          | ($v.supported_abis // $v.abis // [$v.id])[]?
          | select(type == "string" and . != "")
          | { key: ., value: ($v.gh_asset // "") } ]
        | map(select(.value != "")) | from_entries'

    # Scan EVERY sibling repo's build.json, not just ac_cloud-*/. Membership is a
    # property of the DATA, not of the directory name: a dir self-registers as a
    # top-level app (.android.application_id + .release.ghcr), a multi-lib repo
    # (.lib_apks) or a fork-app (a real .forks.<key> entry), and anything else is
    # skipped. The old ac_cloud-* glob made the prefix load-bearing, so
    # cloud-infra-desktop-termux-boot - named for the host it targets - was invisible to
    # the store no matter what its build.json said. This predicate admits exactly
    # the same repos the glob did, plus any correctly-declared one.
    # id = dir basename sans an ac_cloud- or ac_c3- prefix if it has one.
    # The c3- rebrand renames apps out of the ac_cloud- namespace; without the
    # second strip an app's fleet id changes shape the moment it is renamed
    # (watchdog -> ac_c3-watchdog), which is a gratuitous id change for what is
    # meant to be a directory rename.
    local apps="[]" bj id dir reldir
    # One level deep AND one nested level: ab_cloud-libs-shared holds the two
    # build harnesses (lib-apks, keyboard-engines) as subdirectories since the
    # 2026-08-30 consolidation, and a root-only glob dropped them silently --
    # the fleet fell from 51 entries to 17 with no error, taking every
    # Cloud-Lib-*.apk out of the store. Membership stays a property of the
    # DATA (the jq predicate below), never of the depth or the prefix.
    for bj in "$UNIX"/*/build.json "$UNIX"/*/*/build.json; do
        [ -f "$bj" ] || continue
        jq -e '(.lib_apks != null) or (.forks != null)
               or (.android.application_id != null and .release.ghcr != null)' \
           "$bj" >/dev/null 2>&1 || continue
        dir="$(basename "$(dirname "$bj")")"; id="${dir#ac_cloud-}"; id="${id#ac_c3-}"
        # repo_url must survive nesting. The glob reaches two levels, so a
        # dir can be a_solutions/ae-tool_termux-boot - basename alone built
        # .../tree/main/ae-tool_termux-boot, a 404. Identical to $dir for
        # every top-level app, so nothing else moves.
        reldir="$(realpath --relative-to="$UNIX" "$(dirname "$bj")")"
        if jq -e '.lib_apks.scan' "$bj" >/dev/null 2>&1; then
            # ── Multi-lib repo (ab_cloud-libs-shared/lib-apks) ────────────────────────────────
            # One repo, one APK per library module, so it expands into MANY fleet
            # entries instead of one. The module set is not listed anywhere: it is
            # the same scan+exclude that settings.gradle and build.sh apply, so a
            # new module under ab_cloud-libs-shared/libs/ appears in the Libs tab
            # automatically. Everything else here is derived from the dir name.
            local lroot lscan lrel lpair lexcl lprefix laprefix limgprefix lmod lasset
            lexcl="$(jq -r '(.lib_apks.exclude // {}) | keys[]' "$bj" 2>/dev/null | tr '\n' ' ')"
            lprefix="$(jq -r '.lib_apks.application_id_prefix' "$bj")"
            laprefix="$(jq -r '.lib_apks.asset_prefix' "$bj")"
            limgprefix="$(jq -r '.release.ghcr.image_prefix' "$bj")"
            # lib_apks.scan is a LIST of roots (library modules live beside the
            # app that grew them), so flatten to "<module>|<repo-relative dir>"
            # first: the directory is what repo_url has to point at, and it is
            # no longer always aa_cloud-superapp/libs (consolidated into ab_cloud-libs-shared/libs 2026-08-30).
            for lpair in $(
                for lroot in $(jq -r '.lib_apks.scan | if type == "array" then .[] else . end' "$bj"); do
                    lscan="$(dirname "$bj")/$lroot"
                    [ -d "$lscan" ] || continue
                    lrel="$(realpath --relative-to="$UNIX" "$lscan")"
                    for lmod in $(ls -1 "$lscan" 2>/dev/null); do
                        [ -f "$lscan/$lmod/build.gradle" ] || continue
                        printf '%s|%s\n' "$lmod" "$lrel"
                    done
                done | sort
            ); do
                lmod="${lpair%%|*}"; lrel="${lpair##*|}"
                case " $lexcl " in *" $lmod "*) continue ;; esac
                # Cloud-Lib-Ml-L-Voice-Vosk.apk — each '-' segment capitalised,
                # matching build.sh's asset naming exactly.
                lasset="$laprefix$(echo "$lmod" | awk -F- '{for(i=1;i<=NF;i++){$i=toupper(substr($i,1,1)) substr($i,2)}}1' OFS=-).apk"
                apps="$(jq --argjson acc "$apps" --arg id "lib-$lmod" --arg dir "$dir" \
                           --arg mod "$lmod" --arg asset "$lasset" \
                           --arg pkgid "$lprefix.$(echo "$lmod" | tr -d '-')" \
                           --arg img "$limgprefix$lmod" \
                           --arg rel "$rel" --arg tree "$tree" --arg pkg "$pkg" \
                           --arg libdir "$lrel" '
                    ($rel + "/download/" + (.release.gh_release.rolling_tag // "latest")
                          + "/" + $asset) as $url
                    # First scan root to offer a module wins. The scan roots
                    # OVERLAP: ab_cloud-libs and ab_cloud-libs-shared/lib-apks
                    # both harness the same consolidated ab_cloud-libs-shared/libs,
                    # and the pre-consolidation per-app libs/ trees still exist,
                    # so without this guard every shared module was emitted two
                    # or three times and a plain regen turned 56 entries into
                    # 114. One module is one lib APK is one fleet entry.
                    | if ($acc | any(.id == $id)) then $acc else $acc + [
                              { id: $id,
                                # ONE canonical display name for every lib: the
                                # same cloud-{name} rule #351 applies to apps,
                                # spelled cloud-lib-{name} with consistent dashes
                                # — NOT the old ad-hoc "Lib: {name}" with no
                                # cloud prefix (the #474 naming mess). $mod is
                                # the module dir name (already correctly dashed,
                                # e.g. kde-connect, net-wg, text-tools), so the
                                # canonical label falls out of that ONE
                                # declaration here; a second display-name field
                                # (the #380 mistake) is not the fix, this line is.
                                label: ("cloud-lib-" + $mod),
                                 package: $pkgid,
                                 alt_id: null,
                                 registry: .release.ghcr.registry,
                                 namespace: .release.ghcr.namespace,
                                 image: $img,
                                 tag: (.release.auto_update.tag // "latest"),
                                 asset: $asset,
                                 release_url: $url,
                                 repo_url: ($tree + "/" + $libdir + "/" + $mod),
                                 ghcr_page: ($pkg + "/" + $img),
                                 blocked: false,
                                 kind: "lib" } ] end' "$bj")"
            done
        elif jq -e '.release.ghcr.image and .android.application_id' "$bj" >/dev/null 2>&1; then
            # ── Top-level app (browser/vault/wallet/superapp/nav/ide) ─────────
            # Publishes a rolling `latest` release → stable direct-download URL.
            apps="$(jq --argjson acc "$apps" --arg id "$id" --arg dir "$reldir" \
                       --arg rel "$rel" --arg tree "$tree" --arg pkg "$pkg" '
                ($rel + "/download/" + (.release.gh_release.rolling_tag // "latest")
                      + "/" + .release.gh_release.asset_name) as $url
                | ('"$variant_assets"') as $assets
                | $acc + [ ( { id: $id,
                             label: (.name // $id),
                             package: .android.application_id,
                             alt_id: null,
                             registry: .release.ghcr.registry,
                             namespace: .release.ghcr.namespace,
                             image: .release.ghcr.image,
                             tag: (.release.auto_update.tag // "latest"),
                             asset: .release.gh_release.asset_name,
                             release_url: $url,
                             repo_url: ($tree + "/" + $dir),
                             ghcr_page: ($pkg + "/" + .release.ghcr.image),
                             # AN APP WITH NO BUILD HOST HAS NOTHING TO OFFER.
                             # The fork branch below has always derived `blocked`
                             # from declared data; this branch hardcoded false, so
                             # a top-level app had no way to say "I cannot be
                             # published yet" and the store offered it regardless.
                             # ac_cloud-office is the case that proved it: it
                             # stopped mirroring the official Collabora APK and
                             # became a source build, so its build.json now names
                             # Cloud-Office.apk, com.diegonmarcos.cloudoffice and
                             # the cloud-office GHCR image — none of which exist,
                             # because build.host is null and ship-cloud-office.yml
                             # refuses to run without one. Every field here is
                             # correct and every one of them points at a 404.
                             # Derived, not declared: `build.host` present-but-null
                             # IS the statement "a host is required and there is
                             # none", so no second field can disagree with it.
                             # Fleet.kt::status returns State.Blocked and
                             # StoreCloudFragment hides Install/Direct, so the
                             # row still shows and still opens — it just stops
                             # promising an install that cannot happen.
                             blocked: (((.build // {}) | has("host")) and (.build.host == null)),
                             kind: (.release.kind // "app") }
                             + (if ($assets | length) > 0 then { assets: $assets } else {} end) ) ]' "$bj")"
        elif jq -e '(.forks // {}) | to_entries
                    | map(select(.key != "_doc" and (.value|type=="object")))
                    | length > 0' "$bj" >/dev/null 2>&1; then
            # ── Fork-app (dialer/chat/mail/matrix) ───────────────────────────
            # A standalone ac_cloud-<id> promoted from an ex-cloud-comms fork:
            # identity under .forks.<key> (one real fork per dir). Forks publish
            # --latest=false tagged releases → link the releases page, not a
            # /latest/download. keystore-signed package id is preserved.
            apps="$(jq --argjson acc "$apps" --arg id "$id" --arg dir "$reldir" \
                       --arg rel "$rel" --arg tree "$tree" --arg pkg "$pkg" '
                (.release.ghcr) as $cg
                | ( .forks | to_entries
                    | map(select(.key != "_doc" and (.value|type=="object")))
                    | .[0].value ) as $f
                | ('"$variant_assets"') as $assets
                | $acc + [ ( { id: $id,
                             # .name, the SAME field the top-level branch reads
                             # (#351). Fork-apps used to declare their name as
                             # .forks.<key>.label, a second field for one fact, and
                             # it is where "Cloud Chat (Mattermost)" and
                             # "Messenger (Element)" lived while every top-level
                             # app already said cloud-<x>.
                             label: (.name // $id),
                             package: $f.app_id,
                             alt_id: ($f.alt_id // null),
                             registry: $cg.registry,
                             namespace: $cg.namespace,
                             image: $f.image,
                             tag: "latest",
                             asset: ($f.image + ".apk"),
                             # A DIRECT download URL when the fork publishes to
                             # a rolling tag, the releases page otherwise.
                             #
                             # This branch used to always hand back the page,
                             # on the reasoning that forks publish
                             # --latest=false tagged releases and so have no
                             # stable /latest/download. True of the comms
                             # forks; false of any fork declaring
                             # release.gh_release.rolling_tag, which is exactly
                             # what a stable install URL is.
                             #
                             # The updater does not render a page: ReleaseSource
                             # fetched the HTML, failed to verify it, and fell
                             # through to GHCR — whose digest then did not match
                             # the bytes, so a missing URL surfaced as "digest
                             # mismatch" and pointed at the wrong thing entirely.
                             release_url: (
                               if (.release.gh_release.rolling_tag // "") != ""
                               then $rel + "/download/" + .release.gh_release.rolling_tag
                                    + "/" + ($f.image + ".apk")
                               else $rel end ),
                             repo_url: ($tree + "/" + $dir),
                             ghcr_page: ($pkg + "/" + $f.image),
                             blocked: ($f.blocked_on != null),
                             kind: (.release.kind // "app") }
                             + (if ($assets | length) > 0 then { assets: $assets } else {} end) ) ]' "$bj")"
        fi
    done

    # Cross-repo fleet members. The scan above walks THIS monorepo's siblings,
    # so an app that ships from another repo cannot be discovered no matter how
    # correctly its build.json is declared. Kept as data in
    # aa_cloud-superapp/build.json::constellation.external (FIRE RULE #6) and
    # merged here, with its own release/repo/ghcr URLs pointing at its own repo.
    local selfbj="$UNIX/aa_cloud-superapp/build.json"
    if [ -f "$selfbj" ]; then
        apps="$(jq --argjson acc "$apps" '$acc + (.constellation.external // [])' "$selfbj")"
    fi

    # One asset is one installable APK is one fleet entry. Duplicates reach
    # here two ways: two build.json describing the same APK
    # (ab_cloud-keyboard-libs and ab_cloud-libs-shared/keyboard-engines both
    # publish Cloud-Keyboard-Libs.apk), and a stale .constellation.external
    # member shadowing a scanned entry with a GHCR image that does not exist
    # (cloud-infra-desktop-termux-boot vs the real cloud-unix-termux-boot).
    # Either way the store would list one app twice, and in the second case the
    # duplicate is uninstallable. Scanned entries are appended before external
    # ones, so first-wins keeps the entry backed by a real build.json. Written
    # as a reduce, not unique_by, because unique_by would also re-sort the fleet.
    apps="$(jq -n --argjson apps "$apps" '
        $apps | reduce .[] as $e ([];
            if any(.[]; .asset == $e.asset) then . else . + [$e] end)')"

    # ── Groups: which Constellation tab each entry is drawn in (#343, #405) ──
    # build.json::constellation.groups lists the tabs in render order.
    #
    # An ML entry NAMES ITS OWN TAB (#405): the scheme is
    # ml-{t|l}-{domain}-{name}, so the weight-class segment of an id matching
    # ^lib-ml-([tl])- IS the tab. Everything else takes the group whose
    # default_for_kind is its kind (a lib module defaults to "libs").
    #
    # This replaced a hand-written group_members map of {fleet id -> tab}. That
    # map was a SECOND statement of a fact the name already made: rename or
    # reclassify a module and the two silently disagreed, and nothing could tell
    # which one was right. keyboard-engines was pinned into Lite-ML there purely
    # because the APK bundles the two engines - the owner asked for it back in
    # Libs (#405), and it gets there by saying nothing at all.
    #
    # ONE rule, applied to BOTH apps and catalogue rows, defined once here so the
    # two can never drift. `capture` emits nothing when the id is not an ML name,
    # so `// null` is what turns "not ML" into a value the validator below can
    # see - without it the whole row would silently vanish from the output.
    # StoreCloudFragment renders exactly these groups and names none of them,
    # so a missing or unknown group is a row in no tab at all - every way the
    # data can disagree with itself therefore fails the regen instead of
    # shipping.
    local ml_group='def ml_group($id): ($id | capture("^lib-ml-(?<weight>[tl])-") | "libs-\(.weight)-ml");'
    apps="$(jq --argjson apps "$apps" "$ml_group"'
        ((.constellation.groups // []) | map(select(.default_for_kind != null)
              | { key: .default_for_kind, value: .id }) | from_entries) as $defaults
        | $apps | map(. + { group: (ml_group(.id) // $defaults[.kind]) })' "$selfbj")"
    # Reference rows are resolved here too, by the SAME ml_group, so the
    # catalogue's tab and the catalogue's name are one fact as well. installable
    # is stamped rather than trusted from the data, so no catalogue row can ever
    # claim to be installable.
    local catalogue
    catalogue="$(jq "$ml_group"'
        [ (.constellation.catalogue // [])[]
          | { id, label: (.label // .id), group: (ml_group(.id) // null),
              description: (.description // ""), installable: false } ]' "$selfbj")"
    local group_problems
    group_problems="$(jq -r --argjson apps "$apps" --argjson catalogue "$catalogue" '
        ((.constellation.groups // []) | map(.id)) as $declared
        | ( $apps[] | select(.group == null or (.group | IN($declared[]) | not))
              | "fleet entry \(.id) (kind \(.kind)) is in no declared group: \(.group)" ),
          ( $catalogue[] | select(.group == null or (.group | IN($declared[]) | not))
              | "catalogue row \(.id) is in no declared group: \(.group) - every catalogue row is an ML reference and must be named ml-{t|l}-{domain}-{name} behind the lib- prefix" ),
          ( ($apps | map(.id)) + ($catalogue | map(.id)) | group_by(.)[]
              | select(length > 1)
              | "id \(.[0]) is used more than once across apps and catalogue" )' "$selfbj")"
    if [ -n "$group_problems" ]; then
        printf 'ERROR: constellation groups disagree with the fleet:\n%s\n' "$group_problems" >&2
        return 1
    fi

    # Reference rows go OUT of `apps` on purpose: Fleet.parse reads only `apps`,
    # so the updater, both auto-update workers, PrivilegedGrants and the recovery
    # screens can never be handed an entry with no package. installable:false is
    # stamped here rather than trusted from the data, so no catalogue row can
    # ever claim to be installable.
    #
    # ONE COMPACT ENTRY PER LINE, NOT PRETTY-PRINTED. libs:updater and
    # libs:appstore bake this file's RAW bytes into a BuildConfig String, and
    # javac refuses any constant past 65,535 bytes. Pretty-printed, the 63-entry
    # fleet already baked to 61,368 base64 bytes before the groups existed, so
    # the indentation alone was about to break every build that links the
    # updater. One entry per line keeps a diff readable per entry at compact size.
    jq -r --argjson apps "$apps" --argjson catalogue "$catalogue" '
        # members is the same group stamp read from the tab side, so the page
        # builds its tabs without reading any entry field Fleet.parse owns.
        [ (.constellation.groups // [])[] | .id as $id
            | { id, label: (.label // .id), blurb: (.blurb // ""),
                members: [ ($apps[], $catalogue[]) | select(.group == $id) | .id ] } ] as $groups
        | "{\"version\":1,",
          "\"groups\":[", ($groups | map(tojson) | join(",\n")), "],",
          "\"apps\":[", ($apps | map(tojson) | join(",\n")), "],",
          "\"catalogue\":[", ($catalogue | map(tojson) | join(",\n")), "]}"
        ' "$selfbj" > "$HERE/constellation-fleet.json"
    jq -e '.apps | length > 0' "$HERE/constellation-fleet.json" >/dev/null \
        || { echo "ERROR: constellation-fleet.json is not valid JSON with apps" >&2; return 1; }
    echo "constellation apps: $(jq '.apps | length' "$HERE/constellation-fleet.json")"
}
regen_constellation

[ "${1:-}" = "--constellation-only" ] && exit 0

CONSOLIDATED="${1:-}"
MESH="${2:-}"
LINKTREE="${3:-}"

if [ -z "$CONSOLIDATED" ]; then
    for cand in \
        "$HOME/git/cloud-infra/1_cloud-configs/dist/_cloud-data-consolidated.json" \
        "$HOME/git/cloud-infra/I_cloud-data/_cloud-data-consolidated.json" \
        "$HOME/git/cloud-data/_cloud-data-consolidated.json" \
        "$HOME/git/cloud-infra-desktop/cloud-data/_cloud-data-consolidated.json"; do
        [ -f "$cand" ] && { CONSOLIDATED="$cand"; break; }
    done
fi
if [ -z "$MESH" ]; then
    for cand in \
        "$HOME/git/cloud-infra/1_cloud-configs/dist/mesh-snapshot.json" \
        "$HOME/git/cloud-infra/a_solutions/infra-net_wireguard-mesh/src/code/src/data/mesh.json" \
        "$HOME/git/cloud-data/cloud-data-wg-mesh-snapshot.json"; do
        [ -f "$cand" ] && { MESH="$cand"; break; }
    done
fi
if [ -z "$LINKTREE" ]; then
    for cand in \
        "$HOME/git/front/a-Portals/linktree/src/data/projects.json"; do
        [ -f "$cand" ] && { LINKTREE="$cand"; break; }
    done
fi

: "${CONSOLIDATED:?consolidated.json not found; pass as arg 1}"
: "${MESH:?mesh.json not found; pass as arg 2}"
: "${LINKTREE:?projects.json not found; pass as arg 3}"

command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required" >&2; exit 1; }

echo "→ mesh:         $MESH"
echo "→ consolidated: $CONSOLIDATED"
echo "→ linktree:     $LINKTREE"
echo "→ writing into: $HERE/"

# Mesh is a verbatim snapshot (wg-mesh/v1 schema; the APK parser owns
# the field-set). No transform.
cp "$MESH" "$HERE/mesh.json"

# Linktree projects.json is the source of truth for what shows
# under the Tools aggregator (Apps mode = SUITE+LAB+CIRCUS slides;
# Admin mode = CLOUD slide). Verbatim copy — the APK parser picks
# which slide to render based on build.json::ui.sections[tools].stack_*.
cp "$LINKTREE" "$HERE/linktree.json"

# Public services = containers WITH proxy.domain or proxy.parent_domain.
jq '
[.services | to_entries[] as $s
 | ($s.value.containers // []) | .[]
 | select(type == "object")
 | select(.proxy.domain or .proxy.parent_domain)
 | {
     name: .container_name,
     service: $s.key,
     vm: ($s.value.vm // "—"),
     public_url: (if .proxy.domain then .proxy.domain else (.proxy.parent_domain + (.proxy.base_path // "")) end),
     auth: (.proxy.auth // "none"),
     private_dns: ((.dns // .container_name) + (if .port then (":" + (.port|tostring)) else "" end)),
     port: (.port // null),
     category: ($s.value.category // null)
   }]
' "$CONSOLIDATED" > "$HERE/services_public.json"

# Private services = containers WITHOUT proxy.
jq '
[.services | to_entries[] as $s
 | ($s.value.containers // []) | .[]
 | select(type == "object")
 | select((.proxy.domain or .proxy.parent_domain) | not)
 | select(.container_name)
 | {
     name: .container_name,
     service: $s.key,
     vm: ($s.value.vm // "—"),
     private_dns: ((.dns // .container_name) + (if .port then (":" + (.port|tostring)) else "" end)),
     port: (.port // null),
     protocol: (.protocol // "tcp"),
     category: ($s.value.category // null),
     db_engine: (.db_engine // null)
   }]
' "$CONSOLIDATED" > "$HERE/services_private.json"

echo "mesh nodes:       $(jq '.nodes | length' "$HERE/mesh.json")"
echo "mesh peers:       $(jq '.peers | length' "$HERE/mesh.json")"
echo "public services:  $(jq length    "$HERE/services_public.json")"
echo "private services: $(jq length    "$HERE/services_private.json")"
echo "linktree slides:  $(jq '.slides | length' "$HERE/linktree.json")"
