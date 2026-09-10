# cloud-writer — the fleet's home for the text tools

Status: design accepted, first slice landed. Spelling normalised from the owner's
"cloud-writter" to `cloud-writer`, because the fleet writes full correct words.

The owner asked for one application that owns Text Enhance, Translate, Grammar Check
and Summary ("Resume"); that owns the model choice for each of them separately; whose
user interface comes across from `cloud-mail` and `cloud-keyboard`; that every other
application references rather than copies; and beside which each consuming application
handles nothing but its own API token.

---

## 0. What is already true, before any of this is built

This is written down first because the request reads as "build a thing that does not
exist", and most of it does exist. Anyone designing on top of the request without
reading the tree would build a second one.

There is already a cross-process contract for exactly these tools:

| Piece | Where it lives today |
|---|---|
| The binder contract | `ab_cloud-libs-shared/libs/text-tools/src/main/aidl/.../ITextTools.aidl` |
| The client that binds it | `.../libs/text-tools/.../TextToolsClient.kt` |
| Addresses and reply shape | `.../libs/text-tools/.../TextTools.kt` |
| The service that answers it | `.../libs/keyboard/.../com/diegonmarcos/superapp/texttools/TextToolsService.kt` |
| The large-language-model engine | `.../libs/keyboard/.../helium314/keyboard/latin/AiRouter.kt` |
| The rewriter | `.../libs/keyboard/.../helium314/keyboard/latin/TextEnhancer.kt` |
| The grammar engine | `.../libs/keyboard/.../helium314/keyboard/latin/GrammarChecker.kt` |
| The translator | `.../libs/translate/.../Translator.kt`, `TranslateEngines.kt` |
| The fleet control page of task 223 | `aa_cloud-superapp/.../ai/AiFleetRoster.kt`, `AiTokensFleetFragment.kt` |
| Who takes part, declared | `aa_cloud-superapp/build.json::ui.ai_routing_peers` |

`ITextTools` already carries `enhance`, `translate`, `summarise`, `enhanceWith`,
`summariseWith`, `settingsSnapshot`, `aiRoutingSnapshot`, `setAiRouting` and
`revealAiKey`. The engines are already in one copy. The provider key already never
crosses the binder.

So the request is not "build the mechanism". The mechanism is built. The request is
**move the house the mechanism lives in**: today the serving application is
`com.diegonmarcos.cloudkeyboard`, an input method, and the owner wants it to be a
dedicated application he can open.

Three things in the request are genuinely new and are called out in section 6.

---

## 1. The mechanism: a library carrying the contract, plus an application carrying the
engine, the settings and the screens

**Chosen: both.** `libs:text-tools` stays the compile-time library every participant
links — it is the AIDL, the client and the addresses, and nothing else. `cloud-writer`
becomes the application that hosts `TextToolsService`, holds the engines, and shows the
settings screens. Consumers link the library and talk to the application at run time.

### Why not a shared Gradle library alone

Because of the credential. A library consumed at build time runs **inside the consuming
application's process**, under that application's user id, reading that application's
storage. Putting `AiRouter` in every consumer that way means every consumer needs the
provider key in its own sandbox — five copies of one credential, five places to forget
it, five applications whose logs and diagnostics bundles can leak it. The current design
exists specifically to have one holder, and a library-only design gives that up.

It also cannot satisfy "this new app". There is no screen to open and no icon on the
launcher, and the owner asked for an application.

And a fix to a prompt would then require every consumer to rebuild and reship — which,
in a fleet that just spent a day and a half without an APK, is the slowest possible
repair path.

### Why not a standalone application alone

Impossible. A binder contract needs a shared `.aidl` on both sides and a client on the
calling side. That shared thing is a library by definition. `libs:text-tools` is that
library and it must exist. "Application only" is not an option that exists.

### Why both is the right shape

The split is already load-bearing and it is the right one:

- `libs:text-tools` is **deliberately dependency-free beyond `core-ktx`**. Every
  constellation application links it, so anything added to it is added to all of them.
  It carries no engine, no model registry, no preference and no `R` — it is a boundary.
- The engine, the registry, the preferences and the credential sit on the far side of
  that boundary, in one application, in one process.

This also satisfies the fleet's module-boundary rule as written: `libs:text-tools` is an
engine module with no `R` and no import from any application; `cloud-writer/app` is
chrome.

---

## 2. How cloud-writer becomes the home without a flag day

This is the first slice, and it is the change that has to land **before** cloud-writer
exists rather than with it.

`TextTools.SERVICE_PKG` was a single hardcoded package name — `com.diegonmarcos.cloudkeyboard`.
One constant, in a library linked by everything, naming the keyboard as the home. While it
says that, cloud-writer can be written, shipped and installed and **nothing will ever talk
to it**.

It is replaced by `TextTools.SERVICE_PACKAGES`: an ordered preference list,
`cloud-writer` first, `cloud-keyboard` second. `TextToolsClient` asks the package manager
which packages actually publish the `ITextTools` action, and binds the first one on the
list that does.

The consequences are the whole point:

- **Today**, with no cloud-writer installed, every consumer resolves the keyboard and
  behaves exactly as it does now. Nothing changes for the owner. No regression is
  possible, because no behaviour moved.
- **On the day cloud-writer's APK is installed**, every consumer picks it up at its next
  rebind — no consumer rebuild, no consumer reship, no consumer commit.
- **If cloud-writer is later uninstalled or crashes**, `onBindingDied` fires, the client
  re-resolves, finds the keyboard, and carries on. The fallback is not a special case; it
  is the same code path as the first bind.
- **If neither is installed**, `TextTools.NOT_INSTALLED` is returned as a readable reason
  in reply slot 1, which is what every caller already renders. No crash, no silence.

Resolution is by intent, not by `getPackageInfo`. That is what the module's existing
`<queries><intent>` grant covers on Android 11 and later — a bare package lookup can come
back empty for a package that is installed, and would read as an uninstall.

### How this honours "nothing outside an application's own source may fail its release"

By construction, at both build time and run time:

- **Build time**: a consumer's dependency is `libs:text-tools` — an AIDL file, a client
  and a handful of constants, with no dependency of its own beyond `core-ktx`. It does
  not depend on the cloud-writer project, is not in cloud-writer's Gradle graph, and does
  not appear in any consumer's `settings.gradle`. cloud-writer failing to compile cannot
  fail `ship-cloud-mail`, `ship-cloud-keyboard` or `ship-cloud-superapp`, because none of
  those builds ever reads cloud-writer's source.
- **Run time**: an absent serving application is an ordinary, named, handled state that
  predates this design (`isServingAppInstalled`, `NOT_INSTALLED`, `PeerState.NOT_INSTALLED`).
  A broken cloud-writer degrades to the keyboard; a broken keyboard as well degrades to a
  sentence on screen.

The one thing this design must never grow is a consumer-side hard dependency on
cloud-writer — no `<uses-library>`, no required permission cloud-writer defines, no
`queries` entry naming cloud-writer's package specifically. The action-scoped `<queries>`
already in `libs:text-tools/AndroidManifest.xml` is correct and must stay action-scoped.

---

## 3. Global versus per-application — the question task 209 actually asked

Task 209 was not "sharing is wrong". It was **"WHY I CANT EDIT NOTHING"**. The previous
arrangement made the keyboard's preference store the only store, so cloud-mail's Text
pages were a view of settings cloud-mail could not change. The complaint was about
*editing*, not about *sharing*.

That constraint survives the reversal, and the fleet has already built the answer: the
`enhanceWith` / `summariseWith` pair. A consumer hands over its **own fully composed
prompt** and its **own chosen provider and model**; the serving application contributes
the engine and the credential and adds nothing of its own to the prompt. One engine,
several sets of settings.

So the line is drawn here, and it is drawn at **what a thing is**, not at what is
convenient:

| Setting | Owner | Why |
|---|---|---|
| The HTTP call, auth header, timeout, retry | **cloud-writer, global** | Plumbing. Two copies means fixing a bug twice. |
| Input chunking on paragraph/line/sentence and the rejoin | **cloud-writer, global** | Hard-won (`TextEnhancer.rewrite`). Never duplicated. |
| The summary input budget and the truncation note | **cloud-writer, global** | A summary of half a message handed over as a summary of the message is a correctness bug, not a preference. |
| Bullet enforcement and the canonical marker | **cloud-writer, global** | Tasks 190/191: the owner explicitly asked keyboard and mail to produce the *same* bullet shape. |
| Error wording | **cloud-writer, global** | The same failure must read the same way in every application. |
| **The provider API key** | **per application** — see section 4 | An account, not a choice. |
| Which provider | **per application, overridable** | `enhanceWith(providerId=…)` |
| Which model | **per application, overridable** | `enhanceWith(modelId=…)` |
| Enhance style / tone / length / output language | **per application, overridable** | The composed prompt is the caller's. |
| Summary shape, and whether it asks for bullets | **per application, overridable** | `summariseWith(systemPrompt, bullets, …)` |
| Translate target language | **per application, overridable** | `translate(targetTag)` |
| Enhance scope (auto / selection / field) | **keyboard only** | Task 84. It is about an `InputConnection`; nothing else has one. |
| Read-versus-compose tool set | **mail only** | Task 197. See section 5. |
| Grammar mode, remote URL, Portuguese variant, local fixes | **keyboard only today** | Section 6.3. |

**When the owner is in cloud-mail and wants that application's Enhance prompt to differ
from the keyboard's, what happens?** He opens cloud-mail's Text Enhancement page, changes
the style, tone, length or language, and the next Enhance in cloud-mail sends cloud-mail's
prompt and leaves the keyboard's alone. That works today — `MailTextToolsPrefs` writes to
cloud-mail's own `SharedPreferences`, in cloud-mail's data directory, under cloud-mail's
user id. There is no `sharedUserId`, no settings content provider, no shared backup agent;
the platform is what keeps the two stores apart, not a naming convention.

Centralising the *implementation* in cloud-writer does not touch any of that, because what
moves to cloud-writer is the engine, and the engine already takes the caller's decision as
an argument. **This is why the reversal does not repeat task 209: the thing being
centralised is the thing the owner never wanted to edit per-application, and the thing he
demanded to edit stays where he can edit it.**

cloud-writer's own screens are the *defaults and the fallback*, and its own tools' settings
— not a master switch over anyone else's. A consumer that has never been configured seeds
once from `settingsSnapshot()` and then owns its copy for good.

---

## 4. Where a token lives, and how it stays there

**This section describes a change of posture, and it is stated plainly because it is the
most dangerous part of the request.**

### Today

One holder. The keyboard's `SharedPreferences`, key `ai_token_<provider>`, in the
keyboard's data directory under the keyboard's user id. cloud-mail holds **no key at all**
— it names a provider and a model and the keyboard spends the credential. That is why
`ITextTools` has an `enhanceWith` and not a `completeWith`.

### What the owner asked for

> "each app would only now handle the addition of the token for each and the app would
> give it all to all them"

Read straight, that is **per-application tokens; everything else central**. cloud-writer
supplies engine, prompts, models and screens; each consuming application supplies its own
key.

### Which one this design adopts, and why

**The token stays with the process that spends it, and that process is cloud-writer.**
Concretely: cloud-writer holds the keys, in cloud-writer's own preferences, and the
request is made in cloud-writer's process. Each consuming application "handles the addition
of the token" in the sense the owner cares about — there is a per-application key slot, it
is set from that application's own page or from the task 223 console, and it is billed to
that application's account — but the plaintext is **written into cloud-writer over the
binder** (`setAiRouting`) rather than kept in the consumer's sandbox.

This is deliberately *not* "one shared key for the fleet", and it is deliberately *not*
"every application keeps its own copy of a credential". Stated as the trade it is:

- **What it buys**: one credential store to audit, one process that ever holds a key in
  memory, one code path that ever writes an `Authorization` header. A consumer that is
  compromised or that ships a bad diagnostics bundle has no key to leak, because it never
  had one.
- **What it costs**: cloud-writer's sandbox holds every per-application key, so cloud-writer
  is now the single most valuable target on the device. If the signing story ever changes,
  this must be revisited — `revealAiKey` already carries that warning and it now applies
  to more keys.
- **What it is not**: it is not a change to who *pays*. Per-application keys stay
  per-application; they are simply stored where they are spent.

If the owner wants the stronger reading — each application holding its own credential in
its own sandbox — that is a different design and a worse one, and it should be a decision
he makes explicitly rather than something that arrives by accident. It is not what this
design does.

### How a token never reaches a log or another application's storage

Every one of these is an existing, checkable property, and each is now covered by a test
(section 8):

1. **`aiRoutingSnapshot()` carries no key.** It carries `key_present` (a boolean) and
   `key_hint` (at most the last four characters, and nothing at all for a key shorter than
   twelve). Four characters distinguish two accounts and cannot spend either.
2. **`settingsSnapshot()` carries no key.** It is for a consumer copying the owner's
   *choices* when it first seeds. A snapshot carrying the token would put the credential
   into a second application's storage — the exact thing the binder design exists to
   prevent.
3. **`revealAiKey()` is the only method that emits a credential, and it is alone on
   purpose** — so the one dangerous call is greppable, assertable and auditable, and is a
   deliberate reveal gesture rather than a page load.
4. **The key travels only in a binder transaction.** Never an `Intent` extra, never a
   broadcast, never a content provider. A binder call is point-to-point between two
   processes, and the platform has already refused any caller not holding
   `com.diegonmarcos.cloud.permission.CONSTELLATION_DATA`, which is `signature`-level —
   so the reachable set is packages signed with the Cloud key, and a hostile application
   can neither hold a signature permission nor be granted one by any user action.
5. **Nothing logs it.** `setAiRouting`'s failure path deliberately logs
   `e.javaClass.simpleName` and not `e.toString()`, because the value being written is a
   secret. This matters more than it looks: this fleet ships a diagnostics path that reads
   `logcat` and uploads it, so a key that reaches the log is a key that leaves the phone.

---

## 5. The two implementations, and every divergence that had to be decided

Both trees were diffed. The result is not what the request assumed, and the difference is
worth stating: **the prompt registries have not diverged at all.**

### The registries are byte-identical

`ab_cloud-libs-shared/build.json::keyboard_ai` versus `ac_cloud-mail/build.json::mail_ai`,
compared field by field:

- `styles`: `clarity`, `grammar`, `polish` — same ids, same labels, same prompts.
- `tones`: `formal`, `fun`, `fun_emoji`, `informal`, `keep` — identical.
- `lengths`: `keep`, `sentence`, `paragraph_1`…`paragraph_4` — identical.
- `languages`: all 40 ids — identical.
- `summaries`: `actions`, `brief`, `bullets` — identical, including every `bullets` flag.
- `rewrite_preamble`, `summary_preamble`, `summary_truncated_note`,
  `summary_not_bullets_note`, `summary_bullet_marker`, `summary_bullet_aliases` — identical.
- `default_provider` `openrouter`, `default_style` `clarity`, `default_tone` `keep`,
  `default_length` `keep`, `default_language` `keep`, `default_summary` `bullets`,
  `timeout_ms` 30000, `max_chars` 4096, `max_tokens` 2048, `catalog_ttl_ms` 86400000 —
  identical.
- Providers `cloud` and `openrouter`: same fields, same default models
  (`claude-sonnet-4-6`, `google/gemini-2.5-flash`), same 1 and 15 model rows, no
  differing field on any shared model.
- The only key present in one and not the other is `mail_ai._doc_engine`, a documentation
  string.

**Nothing in the prompt data has to be reconciled.** The copy made under task 209 has not
drifted. That removes the largest risk the request anticipated.

### The divergences are behavioural, and there are seven

| # | Divergence | Decision |
|---|---|---|
| 1 | **Grammar Check exists only in the keyboard.** `GrammarChecker.kt` has four modes (`off`/`local`/`remote`/`ai`), a LanguageTool endpoint, a Portuguese `pt-PT`/`pt-BR` variant for a bare `pt` subtype, and local fixes (capitalise "I", sentence caps, repeated words). cloud-mail's `TextTool` enum is `ENHANCE`/`TRANSLATE`/`RESUME` — there is no grammar path in mail at all. | cloud-writer takes the keyboard's engine whole. Grammar becomes the fourth tool on the binder (`ITextTools` gains an appended `grammar` method — appended, never inserted, because transaction codes follow declaration order). Mail gains the tool only if the owner asks; it is a new feature there, not a migration. |
| 2 | **Enhance scope** (`auto`/`selection`/`field`, task 84) is keyboard-only and is about an `InputConnection`. | **Stays keyboard-only.** It is not a text-tool setting; it is a question about how to read an editor the keyboard is attached to. Nothing else has one. |
| 3 | **Read-versus-compose tool sets** (task 197): mail shows Enhance only when composing and Resume only when reading, enforced in `TextToolSurface` and re-checked inside `TextToolRunner.run` so a menu entry and its handler cannot drift. The keyboard has no equivalent. | **Stays mail-only, and stays exactly where it is.** It is a statement about mail's screens, not about the tools. Moving it to cloud-writer would make cloud-writer decide what mail's reader shows, which is the coupling that caused 209. Explicitly not flattened. |
| 4 | **Task 233** — `TextEnhancer` was silent when the buffer read blank in auto scope; the fix made it name the reason (an empty field, or an editor that exposes no text to an input method: a canvas editor, a web view, a custom drawing surface). | **Moves with the file, unmodified.** `TextEnhancer.kt` is moved, not rewritten. This is on the checklist for the migration commit. |
| 5 | **Prices.** The keyboard's registry carries `prompt`/`completion` in dollars per million tokens, a `catalog_url`, a `pricing_as_of`, and a live-catalogue refresh with a cache and a staleness check (tasks 214/217/219 — the format, the hundredfold cents-as-euros error, the stale baked price). `MailAiRegistry.Model` carries only `id`, `name`, `note` — **no price field at all**, so mail's routing page cannot show prices. | **cloud-writer takes the keyboard's priced registry.** Mail keeps naming a model and gets prices, when it wants them, from `aiRoutingSnapshot()` — which ships the registry's own rows so a second registry cannot go stale. Unit names stay `prompt_usd_per_million` / `completion_usd_per_million`, unconverted. |
| 6 | **Who resolves the route.** The keyboard's own surfaces call `enhance`/`summarise`, which resolve prompt and model from the *serving* application's preferences. cloud-mail calls `enhanceWith`/`summariseWith` and sends its own. | **`…With` is the shape everything migrates to**, including the keyboard's own surfaces once the engine is out of the keyboard. The no-argument forms stay for compatibility with an older installed peer. |
| 7 | **Seeding.** Mail seeds once from `settingsSnapshot()` and marks itself seeded only after a call that actually got an answer — a seed attempted while nothing is bound must retry, or a migration silently resets the owner's settings to defaults. The keyboard has no seeding path because it was the source. | **Preserved as the pattern for every consumer**, including the keyboard after the move. Named here because it is the one piece of migration logic that is easy to get subtly wrong. |

### What must not be lost, restated as a checklist for the migration commit

- Translate input re-synchronisation (tasks 204/238: emoji desynchronisation, the
  spacebar cursor-slide). Lives in `libs:translate`; **move, do not rewrite**.
- Multi-line parse and the word/token count (task 158).
- Output box editable, keys stay in the application's own field (task 87).
- Selection-versus-whole-field scope (task 84).
- The blank-buffer reason (task 233).
- Price format, unit and freshness (tasks 214/217/219).
- One shared bullet format between keyboard and mail (tasks 190/191) — this one is
  *global* and stays global.

---

## 6. The three things that are genuinely new

### 6.1 A dedicated application

`cloud-writer` (`ac_cloud-writer/`, package `com.diegonmarcos.cloudwriter`) hosts
`TextToolsService`, holds the engines and the registry, and carries the settings screens
lifted from the keyboard's `TextEnhanceScreen` and mail's `TextToolsScreens`. Registered
in `aa_cloud-superapp/data/constellation-fleet.json` — which is **baked into the SuperApp
APK**, so registration is not complete until a SuperApp APK carries it — and in
`aa_cloud-superapp/build.json::ui.ai_routing_peers` with role `serves`.

The keyboard's roster entry moves from `serves` to `routes` **in the same commit that
ships cloud-writer's APK, and not before**. `AiFleetRoster` documents that exactly one
application serves; two `serves` entries with only one real server would make the console
report a peer that cannot answer.

### 6.2 A model per tool

> "here we will define the AI model to do Summary, Grammar Only, Text Enhance"

**This does not exist today.** `AiRouter.model(context, provider)` returns one model per
provider, and Enhance, Summary and the `ai` grammar mode all use it. There is no way to
send Summary to a cheap model and Enhance to a strong one.

It belongs in cloud-writer, because the owner said "here we will define" it, and because
the storage is useless without the screen that sets it. Shape:

- A `Tool` enum: `ENHANCE`, `SUMMARY`, `GRAMMAR`.
- A preference `ai_tool_model_<tool>_<provider>`.
- `AiRouter.model(context, provider, tool)` resolving the per-tool override, falling back
  to the provider's single model when unset — so an existing device behaves identically
  until the owner sets something.
- `ITextTools` gains a tool argument on the appended methods; the three routes must be
  genuinely independent, and the test for that is in section 8.

Deliberately **not landed ahead of the screens**: a preference nothing can set is dead
code, and dead code in a credential path is worse than absent code.

### 6.3 Grammar on the binder

Grammar Check is one of the owner's four tools and is the only one with no binder method.
`ITextTools.grammar(text, …)` is appended when the engine moves.

---

## 7. The keyboard is an input method — how it binds safely

This is checked specifically, because a design that works everywhere except the keyboard
fails the most important consumer.

**It is already proven in this tree.** `ac_cloud-keyboard/.../AidlTranslateEngineClient.kt`
binds an AIDL service in another application (`com.diegonmarcos.cloudkeyboardlibs`) from
inside the input method, and has done through several rounds of hardening. Its comments
enumerate every way it went "not connected" for good, and `TextToolsClient` was written
from it deliberately. The properties that make it safe from an input method context:

1. **The bind is on the application context**, not on the `InputMethodService`. An input
   method's own context is torn down and rebuilt as the window comes and goes; a
   `ServiceConnection` held against it would leak and would be reported as such.
2. **`BIND_AUTO_CREATE`, and nothing stronger.** No `BIND_IMPORTANT`, no foreground
   promotion — an input method must not drag another process up its own priority band.
3. **`bindService` returns `false` rather than throwing** when the target cannot be
   resolved, and that return value is checked and logged. An unchecked `false` is
   indistinguishable from a pending bind, which is how a client stays dead until its
   process does.
4. **`onBindingDied` is handled separately from `onServiceDisconnected`.** An application
   update is reported as the former, and a died binding never reconnects by itself.
   Without this branch, updating cloud-writer would silently kill the keyboard's text
   tools until the keyboard process restarted.
5. **Rebinding is rate-limited** to one attempt per five seconds. An input method that
   retried a bind on every keystroke would be a battery bug.
6. **Nothing blocks the input method's main thread.** Every `ITextTools` method does
   network work and blocks the *calling* thread; the contract says so in capitals and the
   client does no threading of its own, on purpose, so the caller cannot forget that it
   must show progress. The keyboard's callers are already on `TextEnhancer`'s executor.
7. **Package resolution adds no new hazard.** `queryIntentServices` against an
   action-scoped `<queries>` grant is a package-manager call, not a bind, and the ordered
   list is walked in memory.

One thing that is **not** proven and needs the owner's eye: whether cloud-writer's process
being cold-started by a bind from the keyboard adds perceptible latency to the first
Enhance of a session. It is a bind to a not-running process, same as the translate
companion today, and the translate bar's progress line already covers that case — but
"same as today" is an argument, not a measurement, and there is no device here.

---

## 8. Relationship to the fleet control page (task 223)

**Task 223's page is not a parallel mechanism to be replaced. It is the console half of
this same mechanism, and cloud-writer must not become a second answer beside it.**

`AiTokensFleetFragment` reads `aiRoutingSnapshot()` and writes `setAiRouting()` over the
same `ITextTools` binder this design is about. `AiFleetRoster` splits the two kinds of fact
carefully, and the split is the reason the page is correct:

- The **roster** — who takes part and what part each plays — is *declared*, in
  `build.json::ui.ai_routing_peers`, because it cannot be discovered: every constellation
  application ships the fleet provider, so that marker answers "who is in the fleet" and
  not "who does AI routing".
- The **snapshot** — which models exist, which is chosen, whether a key is held — is
  *read from the serving application every time and never cached*, because a console
  holding its own model list would be a second registry, and a second registry is what put
  a stale price and then a wrong unit on this fleet's screens inside one week.

What this design changes about task 223 is exactly one thing: **which package answers**.
The console keeps working with no change, because `aiRoutingSnapshot()` already returns
`"app": packageName` — the console learns which application answered from the answer
itself, rather than from a constant it holds.

What it must **not** do, and this is the serious error the request warns about: build a
second control surface in cloud-writer that reads and writes keys for other applications.
cloud-writer's screens set **cloud-writer's own** provider, models, prompts and defaults.
The fleet-wide read-and-set-everyone's-key page is task 223's, it exists, and there must be
one of it.

`AiFleetRoster.PeerState` already names every failure separately — `NOT_INSTALLED`,
`UNREACHABLE`, `TOO_OLD`, `NO_KEY`, `READY`, `TIMED_OUT`, `NOT_APPLICABLE` — because they
are different repairs. Adding cloud-writer to the roster adds a row; it does not add a
state.

---

## 9. Sequencing — and a correction to the order this was requested in

The requested order was: design; scaffold the application; move the engine in; migrate one
consumer.

**Steps two and three cannot be done in that order, and the reason is not a preference.**
cloud-writer's whole purpose is to host `TextToolsService`. That service imports
`AiRouter`, `TextEnhancer` and `GrammarChecker`, which live in `libs:keyboard` — a module
that is an input method with an `ndk-build` native decoder, Compose, view binding, Glide
and a colour picker. An application that wants to rewrite a paragraph cannot take that as a
dependency. So cloud-writer cannot be scaffolded into a *building, serving* state until the
engine is linkable from outside `libs:keyboard`. **The engine extraction is a prerequisite
for the scaffold, not a follow-up to it.**

The order that works:

1. **Design.** (This document.)
2. **Serving-application resolution by ordered preference.** Must be first: it is the only
   change that has to exist *before* cloud-writer does, and it is a no-op until then. If it
   lands after cloud-writer, there is a window in which cloud-writer is installed and
   nothing talks to it.
3. **Extract `libs:text-engine`** — `AiRouter`, `TextEnhancer`'s rewriter, `GrammarChecker`
   and the AI preference keys — with `libs:keyboard` depending on it rather than owning it.
   **This must land alone and green.** It touches the keyboard's hot path and the
   preference constants that `Settings.java` publishes to roughly two hundred files, and
   there is no Java toolchain in the agent container, so continuous integration is the only
   compiler. Alone means: if it goes red, exactly one change is under suspicion.
4. **Scaffold `ac_cloud-writer`**, linking `libs:text-engine`, `libs:translate` and
   `libs:text-tools`; host the service; lift the screens; register in
   `constellation-fleet.json`, `ui.ai_routing_peers` (role `serves`, keyboard demoted to
   `routes` in this same commit) and a generated ship workflow. Both applications may serve
   the action during the overlap; the ordered list makes that unambiguous rather than a
   race, because clients resolve by preference and bind with an explicit package.
5. **Consumers need no migration commit.** Step 2 already did it. That is the point of
   doing step 2 first, and it is why "migrate one consumer as proof" collapses into
   "install the APK and watch".

### Which consumer proves it first, and why

**cloud-mail.** Three reasons: it already routes through the binder with its own settings,
so it exercises `enhanceWith`, `summariseWith` and `translate` — three of the four tools —
without any code change; it is the consumer the task 209 fight was about, so it is the one
whose editability has to be re-proven; and it is *not* an input method, so a failure there
is a failure the owner can see and recover from without losing the ability to type. The
keyboard migrates second precisely because it is the riskiest host: an input method that
cannot bind is an input method the owner is still typing on.

---

## 10. What is landed, and what is not

**Landed now**: this document, and step 2 — ordered serving-application resolution, with a
test.

**Not landed**: steps 3, 4 and 5. Deliberately, and the reason is the sequencing above plus
the state of the fleet. The engine extraction is a multi-cycle refactor of the keyboard's
hot path, validated only by continuous integration because there is no Java toolchain in
the agent container, into a fleet that is recovering from a publish outage. Landing it
half-done, or landing it in the same push as an application scaffold, is how a working
feature the owner uses daily regresses. A migration that regresses a working feature is
worse than no migration.

Step 2 is safe to land alone because it changes no behaviour on any device today: with no
cloud-writer installed, the ordered list resolves the keyboard, which is what the constant
it replaced said.

### Named follow-ups that this slice deliberately did not touch

- **`TextTools.NOT_INSTALLED` is a Kotlin constant, not a string resource, and stays one.**
  `libs:text-tools` carries no `R` by design — that is the module-boundary rule, and giving
  it resources would put a resource table into every application in the fleet. The string is
  therefore English-only, as it already was. If the owner wants it translated, the right fix
  is for each consumer to map the failure to its own resource, not for the boundary module
  to grow one.
- **The user-visible strings that name Cloud Keyboard as the key-holder are still correct
  and were left alone.** `ac_cloud-mail/.../values/strings.xml` `settings_text_key_note`
  ("Cloud Keyboard holds it and makes the request on this app's behalf"), and
  `aa_cloud-superapp`'s `ai_peer_routes`, both in `values/` and `values-es/`. Today they are
  true. They become wrong on the day cloud-writer serves, so they belong in that commit —
  changing them now would put a false sentence on the owner's screen in exchange for nothing.
- **The task 223 roster still lists cloud-keyboard as `serves`,** and must, until
  cloud-writer's APK exists. `AiFleetRoster` documents that exactly one application serves.
