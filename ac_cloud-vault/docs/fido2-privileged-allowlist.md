# Passkeys: which callers may report a web origin

When a browser asks Cloud Vault to create or use a passkey, Android's Credential Manager only
hands Vault a web origin if Vault vouches for the browser: `CallingAppInfo.getOrigin(allowlist)`
returns the origin only when the caller's package name **and** signing-certificate SHA-256 are in
the allowlist Vault passes. Otherwise the request fails (see
`OriginManagerImpl.validatePrivilegedAppOrigin`). Native apps are different: they have no web
origin, so Vault checks Digital Asset Links (`delegate_permission/common.handle_all_urls`) and the
SDK uses `android:apk-key-hash:<base64url sha256>` as their origin.

Lists consulted, in order (`app/src/main/assets/` plus the user's own):

| list | file | source | refresh |
|---|---|---|---|
| Google | `fido2_privileged_google.json` | https://www.gstatic.com/gpm-passkeys-privileged-apps/apps.json (Chrome, Brave, Firefox, Edge, Samsung Internet, DuckDuckGo, Vivaldi, ...) | `scripts/refresh-fido2-privileged-google.sh` (`--check` to detect drift) |
| Community | `fido2_privileged_community.json` | hand-curated browsers Google does not list, plus our own apps below | edit by hand; keep sorted by package |
| User | runtime "Trust" prompt | the user | n/a |

The Google file is Google's published data, used unmodified to identify browsers (public
identifiers: package names and certificate fingerprints, no code).

## Our own apps (community list)

`com.diegonmarcos.cloudbrowser` (Cloud Browser) and `com.diegonmarcos.cloudvault` (Cloud Vault)
are listed with the fleet's shared release certificate
`50:7E:56:A3:5B:0E:0D:7E:0A:CE:55:16:F4:94:96:E6:2F:ED:A7:21:ED:6C:17:6D:DF:B3:34:12:9C:EE:18:99`
(read from the published `Cloud-Browser.apk` and `Cloud-Vault.apk`). Package and certificate are
pinned together, so only an APK signed with the fleet key under that package name can claim a
web origin. Listing Cloud Browser is what lets its WebView hand a page's WebAuthn call to
Credential Manager with the page origin and reach Vault (backlog #923 does that bridging).

## Origin and relying-party rules Vault enforces

- Browser: `origin = getOrigin(allowlist)`; the request's `rpId` (or, when the site omits it,
  the origin host) must be that host or a registrable domain suffix of it, judged with the bundled
  Public Suffix List (`RpIdOriginMatcher`): `squarespace.com` is valid for
  `www.squarespace.com`; `com`, `co.uk` and `evilsquarespace.com` are not.
- Errors: browser not on any list -> "Passkey operation failed because browser (X) is not
  recognized" with a Trust prompt; package listed but certificate not -> "browser signature
  does not match"; rpId not allowed for the origin -> "relying party ID does not match the
  website the browser is on".
- Requests need only what WebAuthn requires (`challenge`, `user.id`); `rp.id`, `rp.name`,
  `user.name`/`displayName`, `authenticatorSelection` and `pubKeyCredParams` are optional and
  completed before the SDK sees them.
