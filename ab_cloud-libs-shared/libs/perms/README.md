# libs:perms: the permission contract (#1274)

Every Android permission an app or lib needs is asked, checked and opened in ONE place: the app's Permissions page.
This lib owns that place. All other libs only declare what they need.

## The contract for a lib
1. Declare the needs: add the lib's feature name to the `features` of each entry in
   `src/main/assets/perms-catalogue.json` (for example `"maps"` on `fine_location`, `background_location`, `notifications`).
2. Read `PermsNeeds.missing(ctx, feature)` (or the lib's own check) to decide whether to show the banner.
3. Show "Permissions needed: <labels>" with an **Open Permissions** button (`PermsBanner.title` / `PermsBanner.BUTTON`,
   or `PermsBanner.view` for View UIs). The button calls `PermsLink.open(ctx, feature, entryIds)`.
4. Never call `requestPermissions`, `RequestPermission(s)`, a permission settings action, `ADD_DEVICE_ADMIN` or write a
   `pm grant` line. Shell grants go through `PermsShell`, fleet grants through `PermsGrant`, App info through `PermsPages`.

`PermsOnePlaceTest` (aa_cloud-superapp) scans every Gradle lib's `src/main` under `ab_cloud-libs-shared/libs`; the only
allowlisted lib is `perms` (`contract_allowlist` in the catalogue). Vendored trees that no app compiles (libs/gitsync,
a Flutter app with its own Android host) are not Gradle libs and are not scanned.

## Where the page is: per app, never another app
Android grants permissions to one package. A deep link from Calc into the SuperApp would grant the SuperApp, not Calc, so
each app hosts its own page:

- **SuperApp** hosts the full page (Configs > Setup > Perms, `PermissionsFragment`): `ShellActivity` implements
  `PermsHost`, and `PermsLink.open` finds it by walking the context wrappers.
- **Every other app** that embeds a lib using this contract (Calc, Browser, Nav, Drive, Store, Account, Keyboard,
  Termux, ...) gets `PermsActivity`, merged in from this lib's manifest (`exported=false`). It lists the requesting
  feature's entries first and highlighted, then every other applicable entry the app declares. Runtime entries use the
  system dialog, special ones use the exact settings page (`PermsSettings`). It also works where there is no activity
  (an IME such as libs:voice, a KDE Connect plugin, a notification `PendingIntent`).
- An app that embeds such a lib must list `libs:perms` in its `build.json` modules (checked by PermsOnePlaceTest).
