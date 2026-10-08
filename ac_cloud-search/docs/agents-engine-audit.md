# Agents engine audit: does Cloud Browser and Cloud Mail expose what agentic work needs?

Scope: the Agents tab inside Cloud Search. Hard rule for every agent: draft-only. An agent reads, drafts and
shows; a person copies the draft and sends it. Nothing is posted, submitted or sent on the owner's behalf.
This audit looks only at what the two engines expose today (read from source at the commit this file was
written against) and what is missing. Nothing here proposes a write path to a third-party site.

## 1. Cloud Browser (libs:browser + ac_cloud-browser)

What exists:

| Need | Exists? | Where / how |
|---|---|---|
| Programmatic navigation | Partly | Loopback debug API `GET /api/browser/tabs/open?url=` (ac_cloud-browser BrowserDebugApi) and the in-browser AI agent tools `navigate` / `open_tab` (confirm-gated). The debug API is loopback HTTP with the fleet bearer token: reachable by a tool on the phone, not by another fleet app through a permission. |
| Page text / DOM read | Partly | `/api/browser/page/text?n=` and the `read_page` / `scrape` tools run `assets/browser/page_text.js` / `scrape.js` in the LIVE page. They need the browser on screen with a page loaded (`BrowserBus.call` answers "the browser is not on screen" otherwise). There is no way to read a page that is not the current tab. |
| Form fill | Yes, in-browser only | `fill_form` tool (`agent_fill`), confirm-gated, "never passwords or card data". Not exposed outside the browser's own agent panel. |
| Click | Yes, in-browser only | `click` tool (`agent_click`), confirm-gated. Same limit. |
| Load-wait | No | `tabs/open` returns at once; `page/text` reads whatever is there. `onPageFinished` is handled inside BrowserHostFragment but never surfaced as a signal or a "wait until loaded" call. |
| Cookie / session reuse | Process-internal only | The WebView `CookieManager` is shared by every tab, so a signed-in session is reused by the browser itself. `AuthMissionActivity` (#684) captures a cookie for ONE declared host for a fleet caller. There is no read of a site's cookies for an agent, and none is wanted: an agent must not carry the owner's login to a site on its own. |
| IPC surface | Only two, neither fit | (a) loopback debug API (token, needs a UI); (b) `AuthMissionActivity`, an exported activity behind the signature permission, the right pattern but cookie-capture only. |
| Groups | Yes, UI/prefs only | `BrowserTabPrefs.setGroup(url, group)`; no external entry. |

Verdict: the browser can do the work, but only for a human at the screen or a debug tool holding the fleet
token. A fleet app has no permission-guarded way to (1) open a URL in a named tab group and (2) read the text
of a URL it names. Click, fill and load-wait are intentionally not requested: draft-only agents never need them.

### Gaps and proposed engine contract

All three sit behind `com.diegonmarcos.cloud.permission.CONSTELLATION_DATA` (signature: only an app signed
with the constellation key can call them), exactly as `AuthMissionActivity` is guarded.

| Gap | Contract | Status in this change |
|---|---|---|
| G-B1 open URL, optionally in a group | Exported activity, action `com.diegonmarcos.cloudbrowser.action.AGENT_OPEN`, extras `url` (http/https), `group` (optional, trimmed, max 40 chars). Adds/raises the tab, applies the group, shows it. Navigates a visible tab for the user; submits nothing. | Added |
| G-B2 read-only page text for a URL | Provider `content://<applicationId>.agentapi`, `call("fetch_text")` with extras `url` (https only), `max_chars`. Answers `ok, url, final_url, http, title, text, truncated, error`. Plain GET, no cookies sent, no body, redirects re-checked, private/loopback hosts refused. | Added |
| G-B3 load-wait / click / form-fill over IPC | Deliberately NOT proposed. They are the write half of browsing. If ever wanted, each needs a per-call owner confirmation inside the browser (the existing `confirm: true` tool model), never an unattended IPC verb. | Not added |
| G-B4 session reuse for pages behind a login | Proposal only: `fetch_text` with `session=1` would send the browser's own cookies for that exact host. Needs a per-host allow-list the owner edits in Browser settings and an audit line. Not needed for wg-gesucht listing pages (public). | Not added |

## 2. Cloud Mail (ac_cloud-mail, vendored Sterna Mail)

What exists: a Room cache (`emails` rows with sender, subject, receivedAt, sortKey; `email_bodies` with the
JMAP body JSON), `MailRepository.cachedMessage` / `openMessage`, and an FTS index used by the in-app search.
There is NO IPC of any kind that returns messages: no exported provider, service or activity, no debug-API
route (the app registers no AppDebugServer routes), and the only fleet surface is `ITextTools` (Enhance /
Translate), which takes text in and returns text out. An agent in another app cannot list or read mail.

| Gap | Contract | Status in this change |
|---|---|---|
| G-M1 query messages by from / subject / since | Provider `content://<applicationId>.agentmail/messages?from=&subject=&since=&limit=`, behind CONSTELLATION_DATA. `from` and `subject` are case-insensitive substring matches (LIKE with escaping), `since` is epoch millis, `limit` 1..200 (default 50). Columns: `id, account_id, mailbox_id, subject, from_name, from_email, received_at, sort_key, seen`. Newest first. | Added |
| G-M2 message body | Same provider, `content://.../body?account=&id=`. One row: `text, html, truncated`. Served from the body cache, else fetched with `markRead=false`; the text is capped at 200k chars. Reading through this door never marks a message read. | Added |
| G-M3 write verbs (send, reply, move, mark, delete) | Not proposed and not added. The provider has `query` only; `insert/update/delete` answer 0/null. | Not added |
| G-M4 change notification | Proposal only: a `ContentObserver` URI notified on new mail, so an agent could run on arrival. Not needed for an on-demand run. | Not added |

Note on trust: the provider returns mail from every account on the phone to any constellation app. That is
the same trust the fleet already extends through CONSTELLATION_DATA (the Account's API keys cross it), but it
is wider than a text tool, so the Agents tab asks for the narrowest slice (from filter + since) and records in
its audit log which message ids it read.

## 3. What an agent needs that neither engine owes it

* An LLM: OpenRouter, key read per call from the fleet Account (`TextToolsClient.revealAiKey`), never stored.
* Untrusted content handling: mail and page text are attacker-controlled. They are only ever passed to the
  model as quoted data in a request that has no tools, and the model's output is only ever text inserted into a
  draft that a person reads before using it.
* A cost guard: per-run and per-day budgets enforced in the agent, before each model call.
* An audit trail: every read and every draft is logged (what, when, which ids/URLs), so a run can be reviewed.
