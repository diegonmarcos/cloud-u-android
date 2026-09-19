# The notification centre exists TWICE — #497 clause 3

Diagnosis only. Nothing was changed as a result of it; the reason is in
**Why this was not fixed** at the bottom.

All line numbers are against `398978cf7` (the commit this was read at).


## The two surfaces

### A — the launcher's own drop-down shade

| | |
|---|---|
| Renderer | `aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/notificationcenter/NotificationCenterFragment.kt:35` |
| Added by | `ShellActivity.openNotificationCenter()` — `ShellActivity.kt:2080`, commits the fragment at `ShellActivity.kt:2087` into `R.id.overlay_container` |
| Reached from | `ShellActivity.kt:1161` (dynamic-island tap, when `ui.dynamic_island_action == "notifications"`) and `ShellActivity.kt:2009` (home action `open_notification_center`) |
| Data | `com.diegonmarcos.superapp.core.NotificationStore` — read at `NotificationCenterFragment.kt:84`. Producers: `App.detectVersionBump` (`App.kt:166`) and `CrashLogger` (`CrashLogger.kt:84`) |
| Side effect on open | `NotificationManager.cancelAll()` — `NotificationCenterFragment.kt:82` |
| Extra it owns alone | the live "Cloud SA - KDE" status badge, `NotificationCenterFragment.kt:204` (added deliberately in `d239ad2c2`) |

Its own class docstring still says the content is "a placeholder list of recent
app events … real notification feed wires here later".

### B — the Notify page

| | |
|---|---|
| Renderer | `aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/launcher/AggregatorStackFragment.kt:711` dispatches panel `kind: "notification_center"` → `renderNotificationCenter` at `:1269` → `renderCloudCenter` at `:1388` |
| Declared by | `build.json::ui.sections[communication].stack_my-rss` (and the five sibling `stack_rss-*`), three panels: *Cloud publishers* / *Phone apps* / *C3 Ntfy* |
| Reached from | bottom nav **Notify** (`sections[communication].bottom_nav: true`); the Home tile *Inboxes Apps RSS* → `page:communication/my-rss` (`build.json:469`); and Configs ▸ Panel ▸ **Notify**, which is a facet with `mirror_page: "communication/my-rss"` (`build.json:4572`) resolved at `LauncherNavController.kt:441` |
| Data | `PhoneNotificationStore` (`AggregatorStackFragment.kt:1330`) **and the same `core.NotificationStore` surface A reads** (`AggregatorStackFragment.kt:1402`, under the "IN-APP FEED" heading) |
| Side effect on open | `NotificationManager.cancelAll()` — `AggregatorStackFragment.kt:1398`, the identical call |

The mirror is not the duplicate. Configs ▸ Panel ▸ Notify asks
`pageFragment("communication", "my-rss")` for the very fragment the Notify
section opens, so it cannot drift — one screen, two entrances, by design.


## What makes them a duplicate rather than two features

1. **Same store, drawn twice.** `core.NotificationStore` is read at
   `NotificationCenterFragment.kt:84` and at `AggregatorStackFragment.kt:1402`.
   Every crash line and every version-bump line exists on both surfaces.
2. **Same destructive side effect.** Both call `NotificationManager.cancelAll()`
   on render. Whichever one is opened first silently destroys the system
   dismissal state the other would have shown.
3. **Same name.** `NotificationCenterFragment.BACK_STACK_TAG` is the string
   `"notification_center"` (`:319`) — the same token `build.json` uses as the
   panel `kind` for surface B.
4. **Two implementations of one shade.** Surface A hardcodes its colours
   (`0xFFE9D8FD`, `0xFFB794F4` at `:70`/`:86`); surface B themes through
   `LauncherPalette` and honours the page's Sort/Show/Source filters. Only B
   is declaration-driven.


## Why it happened

| date | commit | event |
|---|---|---|
| 2026-06-01 | `e5feaae1b` | "Search bar app-icon + AI sparkle + Notification Centre on island tap" — surface A is born, bound to the dynamic island |
| 2026-06-08 | `b4109f0f8` | "dynamic-island → Nix-on-Droid" — `ui.dynamic_island_action` is retargeted to `app:com.termux.nix` |
| 2026-09-05 | `ac2c2af07` | "Apps RSS: a notification centre divided per app" — surface B is born in the aggregator, the notification centre is rebuilt as declared data |

Surface B was written as the notification centre. Nobody deleted surface A.


## Reachability today — read this before removing anything

Surface A has exactly two call sites and **neither is live in this build**:

- `ShellActivity.kt:1161` fires only when `ui.dynamic_island_action == "notifications"`.
  It is `"app:com.termux.nix"` (`build.json:4882`) and has been since `b4109f0f8`.
- `ShellActivity.kt:2009` answers the home action `open_notification_center`.
  That string appears **nowhere** in `build.json` or any resource — grepped over
  the whole repo, one hit, the Kotlin branch itself.

So the duplicate is currently **latent in code**, not on screen. That is a
finding, not a dismissal: it means the second surface Diego saw was reachable
when he filed this (the island still opened it until `b4109f0f8`, and the value
is one build.json line away from coming back), and it means removing surface A
would not, by itself, change anything he can see today.

**What could not be established here:** which two surfaces Diego is looking at
*now*. If the duplicate is still visible on his device, A/B is not the pair, and
the one question that settles it is which two screens he is comparing — the
drop-down shade, or two entrances to the Notify page (bottom nav vs. Configs ▸
Panel ▸ Notify, which are the same screen and *should* look identical).


## Why this was not fixed

Retiring surface A means deleting `openNotificationCenter()` and its two call
sites in **`ShellActivity.kt`**, which #497 forbids touching (it is Diego's file,
#340) and which slot #435 is inside right now. `NotificationCenterFragment.kt`
cannot be deleted on its own — `ShellActivity.kt:50` imports it, so the build
breaks and the repair is in the forbidden file.

Two things would also be lost and are not mechanical:

- the **KDE Connect status badge** (`NotificationCenterFragment.kt:204`), added
  on purpose in `d239ad2c2`. Its live state is now also readable in Configs ▸
  Panel ▸ Push (`kde_status` badge box), but that is a settings page, not a shade.
- the **`notifications` value of `ui.dynamic_island_action`**, a documented,
  supported binding (`build.json::_doc_dynamic_island_action`). Removing the
  surface silently turns a declared option into a no-op.

### What would be needed

1. Diego's call on whether the drop-down shade should exist at all, now that
   the Notify page is the notification centre.
2. If it should not: a slot that is allowed to edit `ShellActivity.kt`, after
   #435 is out of the power-saving code, deleting `openNotificationCenter()`,
   both call sites, the import at `:50`, and `NotificationCenterFragment.kt`;
   plus dropping `notifications` from `_doc_dynamic_island_action`'s vocabulary
   so the declaration stops offering a target that no longer exists.
3. If it should: make it a *view* of surface B rather than a second
   implementation — one store, one `cancelAll()`, one theme — which is an edit
   confined to `NotificationCenterFragment.kt` and needs no forbidden file.
