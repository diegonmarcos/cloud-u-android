# Profile sync — server contract

Client side is implemented and shipping. **The server side does not exist yet**;
this file is the contract the Android client already speaks. Implement it in
`cloud-u-containers/infra-api_c3-infra-api` — nothing here was edited in that
repo.

Until the route exists, every POST gets a 404, which the client classifies as
`Rejected` and reports on the profile screen. Nothing crashes and nothing
retries forever.

## Why c3-infra-api

The Android fleet already talks to `c3-infra-api` and nothing else: `Telemetry`
POSTs to `/public/events/{app}` and `LogUpload` to `/public/logs`. It is also
what the operator actually watches (ntfy `infra-*` topics). The profile exists
so that operator can reach users when the update chain breaks, so it belongs to
the same service.

`c3-public-api` serves unauthenticated public content, `c3-services-api` and
`claude-superset-api` are unrelated domains — none of them own fleet state.

## Why NOT the existing `/public/events/` ingest

The obvious move is to POST the profile as telemetry. It must not be done, for
three reasons that are all properties of that route today
(`src/code/api/routes/publicLogs.ts`):

| Property of `/public/events/:app`        | Consequence for a profile                     |
| ---------------------------------------- | --------------------------------------------- |
| Unauthenticated                          | anyone can overwrite anyone's contact details  |
| Appends to `<app>-events.jsonl`          | "delete my data" means rewriting a log file    |
| Fans out to ntfy `infra-<app>`           | names/emails/phones land in push notifications |

The profile therefore gets a record-oriented route: one install is one
document, and erasure is an unlink.

## Routes

Base: `https://api.diegonmarcos.com/c3-infra-api`

All three take identity from headers, never from the path or query string —
URLs land in access logs, and a per-person identifier in a proxy log is a
retention problem that stays invisible until someone requests a deletion.

```
X-Install-Id:   <uuid>            # opaque, client-generated, resettable
Authorization:  Bearer <secret>   # 32 hex chars, client-generated
Content-Type:   application/json; charset=utf-8
```

### Auth model — trust on first use

The client generates both values on first use and stores them locally. The
first `POST` for an unknown `X-Install-Id` **registers** it: store
`hash(secret)` (argon2/bcrypt/scrypt — it is a credential, not an id). Every
later request for that id must present the matching secret, else `403`.

No Authelia bearer: the app has no user credential by default (the ConfigSync
token is user-pasted and optional), and requiring one would mean only
already-configured users are reachable — the exact opposite of the recovery
goal.

Rate-limit per install id. Suggest 10/min, mirroring the existing per-app
limiter. `429` is treated as retryable by the client; other 4xx are not.

### `POST /fleet/profile` — create or replace

Full-state replace, not a patch: the client always sends the whole document, so
last-write-wins is correct and no merge logic is needed.

```jsonc
{
  "schema": 1,
  "install_id": "550e8400-e29b-41d4-a716-446655440000",
  "app_version_code": 1234,
  "app_version_name": "1.2.3",
  "updated_at": "2026-09-05T11:22:33Z",
  "profile": {
    "name": "Ada Lovelace",              // required, non-blank
    "email": "ada@example.com",          // required, must contain @ and a dot
    "phone": "+49 30 123456",            // optional
    "birth": "1815-12-10",               // optional, ISO YYYY-MM-DD
    "location": "Berlin/DE",             // optional, where they are now
    "company": "LEAFY",                  // optional
    "website": "example.com",            // optional
    "titles": "Engineer | Analyst"       // optional, ' | '-separated
                                         // (labelled "About" in the app)
  }
}
```

`profile` carries **exactly** these eight keys and no others. The client builds
the object by naming each one and then filters it against
`ProfileSync.ALLOWED_PROFILE_KEYS` before the POST, so a server that receives an
unlisted key is talking to something that is not this client.

### Retired fields

`city_from` and `social_media_links` were removed from the app (form, local
store and document) — the form no longer collects them, so continuing to upload
whatever an old install had on disk would have been personal data the user could
neither see nor edit. Servers should accept and ignore them on records written
by older clients; the current client ignores them on restore rather than writing
them back.

### What is never in this document

No credential of any kind. Specifically **not** the Authelia bearer token and
**not** the WireGuard interface private key, both of which the app now offers a
field for under Configs → Profile → Credentials. Those live only on the device
(`ConfigsPrefs`, EncryptedSharedPreferences; and `WireGuardPrefs`, the tunnel's
own settings) and no code path copies them into this payload. Records here are
stored as plain files on the host, so a mesh private key or a live session
bearer placed in one would be at rest on a server and on the wire to get there —
which is exactly what holding them on-device avoids. If a future version of this
contract appears to add such a field, it is wrong.

Responses: `200 {"ok":true}` · `400` malformed or missing name/email ·
`403` wrong secret · `413` body over 64 KB · `429` rate limited.

The client never sends an incomplete profile (name+email are gated client-side),
but the server must revalidate — a client-side gate is a UX affordance, not a
security control.

**Storage:** one document per install id, e.g.
`/app/data/fleet-profiles/<install_id>.json`, `chmod 600`. A file per record is
what makes `DELETE` an `unlink` rather than a log rewrite. Do **not** append to
a JSONL, do **not** forward to ntfy, and do **not** log field values — log the
install id, byte count and status only.

**Reconciling one person across devices:** group by `profile.email`
server-side. There is deliberately no device fingerprint to correlate on; that
is why email is the mandatory field.

### `GET /fleet/profile` — restore

Headers only, no body. Returns the same document last POSTed for that install
id, or `404` when there is none (the normal case on a fresh install — the
client treats it as "nothing to restore", not an error).

### `DELETE /fleet/profile` — erasure

Deletes the record for that install id. Returns `200 {"ok":true}`, and `200`
again if it was already gone (idempotent — a retried erasure must not look like
a failure).

The client sends this **before** wiping its local credential, because that
credential is the only proof it may erase the record. It then rotates its
install id regardless of the outcome, and reports a failed server delete to the
user verbatim rather than claiming success.

## Deletion caveats to handle server-side

These are the things that would make an erasure request impractical, and they
are all server-side:

- **Access logs.** Identity is in headers, not URLs, specifically to keep
  install ids out of the Caddy/Fastify access log. Do not add an
  `:installId` path parameter later — it would undo this.
- **Backups.** A per-file record is deletable; a snapshot of the volume is not.
  Whatever the retention on backups is, that is the real deletion latency.
- **A user who erases and reinstalls** gets a new random install id, so the old
  record cannot be reached by that id again. Erasure-by-email is therefore the
  only way to honour "delete everything about me" across a person's devices —
  worth an operator-side path, since the client cannot express it.
