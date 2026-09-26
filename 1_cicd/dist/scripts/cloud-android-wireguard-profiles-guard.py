# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-wireguard-profiles-guard.py ───
#!/usr/bin/env python3
"""WireGuard profiles guard — the APK's export block cannot drift from the vault.

WHAT IS DECLARED WHERE, AND WHY THIS GUARD EXISTS.

The four phone tunnel profiles (wg-v4-{split,full}, wg-v6-{split,full}) are
declared twice, and for a long time nothing derived one from the other:

  1. cloud-vault/A_A0-Providers/C_TOOLS-INFRA/c0-wireguard/termux-public/config-v{4,6}-*
     is the _source. cloud-infra regenerates
     1_cloud-configs/src/inputs/superapp-wireguard-profiles.json and then
     1_cloud-configs/dist/build-cloud-superapp-diego.json from it. That chain
     is derived and works — the dist is the vault's word, published to a
     PUBLIC repo.
  2. aa_cloud-superapp/build.json::ui.wireguard_profiles in THIS repo is the
     block WireGuardProfiles.kt renders and the export writes. Its own _doc
     claims it is "mirrored from the vault" and "must not disagree". Nothing
     enforced that — it had drifted, and on 2026-09-19 #523 (wg-v6-full never
     handshakes: a v6 default route covers the oci-analytics peer's own v6
     endpoint, so the handshake is routed into the tunnel it is trying to
     establish) was fixed in the vault AND cloud-infra while the APK's copy
     still carried the broken `::/0`. That is the gap this file closes.

This script is the enforcement. It has two subcommands that share one fetch
and one normaliser, so the comparison and the derivation cannot disagree
about what a peer is:

  check          (default)  Fail when ui.wireguard_profiles disagrees with
                            the published dist. The comparison is the
                            NORMALISED peer set per profile: public_key,
                            endpoint, allowed_ips — order-insensitive within
                            allowed_ips, whitespace-insensitive. Prose
                            (comment/_doc, label, peer name/mesh) is allowed
                            to differ and is never compared. This is the
                            ticket's guard, verbatim.
  derive --check Fail when the working tree is not exactly what the deriver
                            would produce — a strictly stronger statement
                            than `check`: it also pins per-profile address
                            and dns, the shared interface_mtu, and
                            persistent_keepalive. This is how CI enforces
                            the derivation without writing.
  derive --write Rewrite aa_cloud-superapp/build.json's ui.wireguard_profiles
                            subtree from the published dist, preserving every
                            other byte of the file and every local annotation
                            (profile label, profile/peer comments, peer name,
                            mesh). Run this instead of hand-editing the peer
                            data; it cannot drift because it has nothing of
                            its own to remember. Read-only by default: the
                            write must be requested explicitly.

THE FETCH FAILS CLOSED. The dist is this guard's reference; a guard that
passed because it could not read its reference would be worse than no guard.
A missing network route, an empty body, unparseable JSON, a dist without a
`wireguard` object, and a dist that declares no profiles are ALL failures,
each with its own message.

REDACTED KEYS ARE STRUCTURALLY EXCLUDED. The comparison and the derivation
only ever read public_key / endpoint / allowed_ips per peer and the
interface address/mtu/dns. Private key material — the dist's `config_text`
PrivateKey line and its parsed.interface.private_key — is never read, and
build.json carries no private key by design. A <PROVIDED_BY_DEVICE>
placeholder therefore cannot be treated as a mismatch, because the guard
never looks where a redacted key would be. (The placeholder lives in
config_text; check does not read config_text, and derive only reads the
peer triple + interface fields, never config_text.)

WHY NOT A LOCAL CLOUD-INFRA CHECKOUT. cloud-infra is PUBLIC: every CI run
fetches the published dist over HTTPS, so the guard measures the same bytes
a phone would be measured against, and it cannot silently go quiet because a
sibling checkout went stale or missing. CLOUD_WG_DIST_FILE exists only so
the mutation test can point the script at a deterministic local fixture.

WHY THE DIST'S OWN PARSED SHAPE. The dist carries each profile as
{name, config_text, parsed:{interface:{...}, peers:[{public_key, endpoint,
allowed_ips, persistent_keepalive}]}}. That parsed object is the vault's
wire, re-expressed as JSON by cloud-infra's own generator. Deriving from the
parsed object (rather than re-parsing config_text here) means this script
relies on exactly one parser — cloud-infra's — instead of a second one that
could disagree with it.

WHY THE DERIVER REFUSES TO INVENT PEERS. Peer identity here is the public
key; the dist does not carry peer names, mesh membership or prose (those are
this file's local annotations). Adding or removing a hub therefore needs a
deliberate annotation edit, and the deriver fails loudly until that edit is
made rather than guessing a name or silently dropping a peer.
"""

import difflib
import json
import os
import sys
import urllib.error
import urllib.request

DIST_URL = (
    "https://raw.githubusercontent.com/diegonmarcos/cloud-infra/main/"
    "1_cloud-configs/dist/build-cloud-superapp-diego.json"
)
BUILD_JSON = os.path.join("aa_cloud-superapp", "build.json")
UI_KEY = "wireguard_profiles"


class DistError(Exception):
    """The published reference could not be read or is not usable."""


class DeriveError(Exception):
    """The dist cannot be expressed through this file's annotations."""


def repo_root():
    return os.environ.get("CLOUD_ANDROID_ROOT") or os.getcwd()


def fetch_dist():
    """Read the published dist: a local fixture file when CLOUD_WG_DIST_FILE is
    set (mutation tests only), otherwise the PUBLIC cloud-infra URL. Every
    failure mode raises DistError, which the caller turns into exit 1 — a
    guard that could not read its reference must never pass."""
    fixture = os.environ.get("CLOUD_WG_DIST_FILE")
    if fixture:
        if not os.path.isfile(fixture):
            raise DistError("could not read the published dist: %s is not a file" % fixture)
        with open(fixture, "r", encoding="utf-8") as handle:
            body = handle.read()
    else:
        try:
            with urllib.request.urlopen(DIST_URL, timeout=60) as response:
                status = getattr(response, "status", 200)
                if status != 200:
                    raise DistError("fetching %s answered HTTP %s" % (DIST_URL, status))
                body = response.read().decode("utf-8")
        except urllib.error.HTTPError as error:
            raise DistError("fetching %s answered HTTP %s" % (DIST_URL, error.code))
        except urllib.error.URLError as error:
            raise DistError("could not fetch the published dist from %s: %s" % (DIST_URL, error.reason))
        except OSError as error:
            raise DistError("could not fetch the published dist from %s: %s" % (DIST_URL, error))
    if not body.strip():
        raise DistError("published dist is EMPTY (0 bytes of content) — a guard that passed "
                        "without its reference would be worthless")
    try:
        payload = json.loads(body)
    except ValueError as error:
        raise DistError("published dist is not valid JSON: %s" % error)
    wireguard = payload.get("wireguard") if isinstance(payload, dict) else None
    if not isinstance(wireguard, dict):
        raise DistError("published dist has no 'wireguard' object — nothing to compare against")
    if not wireguard:
        raise DistError("published dist declares NO wireguard profiles")
    return wireguard


def normalised_allowed_ips(value):
    """Order-insensitive, whitespace-insensitive view of an AllowedIPs value,
    which arrives as a comma-separated string (build.json) or a list (dist)."""
    tokens = value if isinstance(value, list) else str(value).split(",")
    return frozenset(str(token).strip() for token in tokens if str(token).strip())


def build_profile_ids(build):
    """build.json -> {profile id: profile}. An absent block yields {} so the
    guard fails on "profile exists in the dist but not here" rather than on a
    TypeError."""
    ui = build.get("ui", {}) or {}
    profiles = (ui.get(UI_KEY, {}) or {}).get("profiles", []) or []
    return {profile.get("id"): profile for profile in profiles}


def build_peer_view(profile):
    """build.json profile -> {public_key: (endpoint, allowed_ips-set)}."""
    view = {}
    for peer in profile.get("peers", []) or []:
        view[peer.get("public_key", "").strip()] = (
            peer.get("endpoint", "").strip(),
            normalised_allowed_ips(peer.get("allowed_ips", "")),
        )
    return view


def dist_peer_view(dist_profile):
    """Published dist profile -> {public_key: (endpoint, allowed_ips-set)}."""
    view = {}
    parsed = dist_profile.get("parsed") if isinstance(dist_profile, dict) else None
    for peer in (parsed or {}).get("peers", []) or []:
        view[peer.get("public_key", "").strip()] = (
            peer.get("endpoint", "").strip(),
            normalised_allowed_ips(peer.get("allowed_ips", [])),
        )
    return view


def peer_name(build_profile, public_key):
    for peer in build_profile.get("peers", []) or []:
        if peer.get("public_key", "").strip() == public_key:
            return peer.get("name") or public_key
    return public_key


def check_agreement(build, wireguard):
    """Per profile, compare the normalised peer set. Returns (exit_code,
    problems) where problems is a list of human-readable FAIL lines."""
    problems = []
    build_profiles = build_profile_ids(build)
    dist_ids = set(wireguard.keys())
    for profile_id in sorted(set(build_profiles) | dist_ids):
        if profile_id not in dist_ids:
            problems.append(
                "profile %s exists in build.json but NOT in the published dist — "
                "the phone would export a tunnel the vault does not declare" % profile_id)
            continue
        if profile_id not in build_profiles:
            problems.append(
                "profile %s exists in the published dist but NOT in build.json — "
                "the phone has stopped exporting a tunnel the vault publishes" % profile_id)
            continue
        build_view = build_peer_view(build_profiles[profile_id])
        dist_view = dist_peer_view(wireguard[profile_id])
        for public_key in sorted(set(build_view) | set(dist_view)):
            name = peer_name(build_profiles[profile_id], public_key)
            if public_key not in dist_view:
                problems.append(
                    "profile %s peer %s exists in build.json but NOT in the published dist — "
                    "the phone would export a peer the vault does not declare"
                    % (profile_id, name))
                continue
            if public_key not in build_view:
                problems.append(
                    "profile %s peer %s exists in the published dist but NOT in build.json — "
                    "the phone has stopped exporting a peer the vault publishes"
                    % (profile_id, name))
                continue
            build_endpoint, build_allowed = build_view[public_key]
            dist_endpoint, dist_allowed = dist_view[public_key]
            if build_endpoint != dist_endpoint:
                problems.append(
                    "profile %s peer %s ENDPOINT differs from the published dist:\n"
                    "    build.json: %s\n    dist:       %s"
                    % (profile_id, name, build_endpoint, dist_endpoint))
            if build_allowed != dist_allowed:
                problems.append(
                    "profile %s peer %s ALLOWED_IPS differs from the published dist "
                    "(list order and whitespace are ignored):\n"
                    "    build.json: %s\n    dist:       %s"
                    % (profile_id, name, ", ".join(sorted(build_allowed)),
                       ", ".join(sorted(dist_allowed))))
    return (1 if problems else 0), problems


def derive_ui_value(ui, wireguard):
    """The ui.wireguard_profiles VALUE the dist implies. Local annotations —
    profile label/comment, peer name/mesh/comment — are preserved verbatim;
    every operator-controlled DATA field comes from the dist: per-peer
    endpoint + allowed_ips, per-profile address and dns, and the shared
    interface mtu. A profile or peer without a dist counterpart is an error
    (see the module docstring on why).

    Address is PER PROFILE (#522). It used to be one shared interface_address
    taken from whichever profile came first, and that silently discarded the
    v6 profiles' own order. The order is load-bearing: Android sources every
    IPv4 packet from the FIRST IPv4 Address, and a hub drops a source it does
    not allow. The APK exported v4-split's order into the v6 profiles, so they
    sourced from the wg0 identity and the wg-public hub dropped all their
    IPv4."""
    out = dict(ui)
    out.pop("interface_address", None)
    shared_interface = None
    for profile in out.get("profiles", []) or []:
        profile_id = profile.get("id")
        dist_profile = (wireguard or {}).get(profile_id)
        if dist_profile is None:
            raise DeriveError(
                "profile %s in build.json has NO counterpart in the published dist — "
                "remove or rename it deliberately, then re-run" % profile_id)
        parsed = dist_profile.get("parsed") if isinstance(dist_profile, dict) else None
        interface = (parsed or {}).get("interface") or {}
        if shared_interface is None:
            shared_interface = interface
        dist_peers = parsed.get("peers") if isinstance(parsed, dict) else None
        dist_by_key = {peer.get("public_key", "").strip(): peer for peer in dist_peers or []}
        local_keys = set()
        for peer in profile.get("peers", []) or []:
            public_key = peer.get("public_key", "").strip()
            local_keys.add(public_key)
            dist_peer = dist_by_key.get(public_key)
            if dist_peer is None:
                raise DeriveError(
                    "profile %s peer %s in build.json has NO counterpart in the published "
                    "dist — peers are identified by public key and added or removed "
                    "deliberately, then re-run" % (profile_id, peer.get("name") or public_key))
            peer["endpoint"] = str(dist_peer.get("endpoint", "")).strip()
            peer["allowed_ips"] = ", ".join(
                str(token).strip() for token in dist_peer.get("allowed_ips", [])
                if str(token).strip())
        for extra_key in dist_by_key:
            if extra_key not in local_keys:
                raise DeriveError(
                    "profile %s peer %s exists in the published dist but NOT in build.json — "
                    "add the peer by hand once (name/mesh/comment are local annotations), "
                    "then re-run" % (profile_id, extra_key))
        if "address" not in interface:
            raise DeriveError(
                "profile %s: the published dist has no interface address — refusing "
                "to export a profile without its own Address line" % profile_id)
        profile["address"] = ", ".join(
            str(token).strip() for token in interface["address"] if str(token).strip())
        if "dns" in profile and "dns" in interface:
            profile["dns"] = ", ".join(
                str(token).strip() for token in interface.get("dns", []) if str(token).strip())
    if shared_interface is not None:
        if "interface_mtu" in out and "mtu" in shared_interface:
            mtu = shared_interface["mtu"]
            out["interface_mtu"] = mtu.strip() if isinstance(mtu, str) else str(mtu)
    return out


def subtree_span(text, key):
    """(start, end) of `key`: { ... } in `text` — start at the key's opening
    quote, end just past the value's closing brace. ValueError when absent."""
    start = text.index('"%s"' % key)
    brace = text.index("{", start)
    depth = 0
    for index in range(brace, len(text)):
        char = text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return start, index + 1
    raise ValueError("unterminated %s block" % UI_KEY)


def rendered_subtree(first_line, value):
    """Canonical text of the `key: <value>` line plus its value, every value
    line re-indented by the key's base column. json.dumps(indent=2,
    ensure_ascii=False) matches this file's canonical subtree formatting, so
    the splice is byte-stable against the current file."""
    base = first_line[: len(first_line) - len(first_line.lstrip())]
    lines = [first_line + ": {"]
    for line in json.dumps(value, indent=2, ensure_ascii=False).split("\n")[1:]:
        lines.append(base + line)
    return "\n".join(lines)


def cmd_check(build, wireguard):
    exit_code, problems = check_agreement(build, wireguard)
    if problems:
        for problem in problems:
            print("FAIL: %s" % problem)
        print("FAIL: %d difference(s) against the published dist — run "
              "cloud-android-wireguard-profiles-guard.py derive --write" % len(problems))
        return 1
    print("OK: all %d wireguard profiles match the published dist (normalised peer set)."
          % len(build_profile_ids(build)))
    return 0


def cmd_derive(build, wireguard, path, write):
    with open(path, "r", encoding="utf-8") as handle:
        text = handle.read()
    try:
        start, end = subtree_span(text, UI_KEY)
    except ValueError:
        print("FAIL: cannot find ui.%s in build.json — nothing to derive" % UI_KEY)
        return 1
    line_start = text.rfind("\n", 0, start) + 1
    first_line = text[line_start:start + len(UI_KEY) + 2].rstrip("\r\n")
    current = text[line_start:end]
    try:
        expected = rendered_subtree(first_line, derive_ui_value(build["ui"][UI_KEY], wireguard))
    except DeriveError as error:
        print("FAIL: %s" % error)
        return 1
    if current == expected:
        print("OK: build.json's ui.%s is exactly what the deriver would produce." % UI_KEY)
        return 0
    if not write:
        print("FAIL: build.json's ui.%s is NOT what the deriver would produce from the "
              "published dist — run cloud-android-wireguard-profiles-guard.py derive --write "
              "and commit the result." % UI_KEY)
        for line in list(difflib.unified_diff(
                current.split("\n"), expected.split("\n"), n=1,
                fromfile="build.json (current)", tofile="build.json (derived)"))[2:]:
            print(line)
        return 1
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text[:line_start] + expected + text[end:])
    print("Derived: ui.%s rewritten from the published dist. Review the diff, then commit." % UI_KEY)
    return 0


def main(argv):
    subcommand, write, positional = "check", False, []
    for argument in argv:
        if argument == "--check":
            write = False
        elif argument == "--write":
            write = True
        elif argument in ("check", "derive"):
            subcommand = argument
        else:
            positional.append(argument)
    if positional:
        print("usage: cloud-android-wireguard-profiles-guard.py [check|derive] [--check|--write]")
        return 2
    path = os.path.join(repo_root(), BUILD_JSON)
    if not os.path.isfile(path):
        print("FAIL: %s is not a file — the guard has no build.json to check" % path)
        return 1
    try:
        with open(path, "r", encoding="utf-8") as handle:
            build = json.load(handle)
        wireguard = fetch_dist()
    except DistError as error:
        print("FAIL: %s" % error)
        return 1
    ui = (build.get("ui", {}) or {}).get(UI_KEY)
    if not isinstance(ui, dict):
        print("FAIL: build.json has no ui.%s object — the APK has no profiles to check" % UI_KEY)
        return 1
    if subcommand == "check":
        return cmd_check(build, wireguard)
    return cmd_derive(build, wireguard, path, write)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))