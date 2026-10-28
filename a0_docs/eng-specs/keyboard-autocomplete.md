# Cloud Keyboard: Autocomplete (#1103)

Autocomplete is its own engine and its own database. It shares only the look of the clipboard panel.

## Engine

- Pages (declared once, `AutocompletePage`): IDs, Addresses, URLs, Secrets. The Secrets page is masked and is revealed only behind the phone's screen lock (60 s, forgotten when the panel closes).
- Store: `latin/autocomplete/AutocompleteStore`, rows kept on the phone, sealed with AES-GCM under a non-exportable Android Keystore key, in the prefs file `autocomplete_store` (declared in `fleet-config.json`, class `device`: a cache that Account re-fills, never migrated).
- No vault call, no vault type, no network class in the engine, and no link from the clipboard to it (the old Clipboard tab, `AutocompletePanel` and the vault field tables are gone). `ac_cloud-keyboard/test/test-keyboard-autocomplete-separation.sh` and `AutocompleteSeparationTest` hold this.
- View: `keyboard/autocomplete/AutocompleteView`, its own `PanelKind.AUTOCOMPLETE` in `KeyboardViewState`, its own sub-page tabs. Geometry and the row background come from the neutral `keyboard/panel` package.

## Where the rows come from

Cloud Account holds the owner's private data (the cloud-me-* private repos, served by cloud-me-api) and gives it to the keyboard:

1. `MeAutocompleteSync` (libs:account) GETs `/me/autocomplete` on the declared `cloud-me-api` service (mesh first) at Account start and every 6 hours (`MeAutocompleteWorker`), and keeps the document in Account's encrypted configs prefs (`autocomplete_json`).
2. Account's `accountdata` provider (CONSTELLATION_DATA) answers the method `autocomplete` only to a package granted `autocomplete.read` (Secrets section; the keyboard package is seeded it, seed version 5).
3. The keyboard (`AutocompleteSync`) calls that provider when the panel opens and replaces its store. The keyboard never fetches these rows from the network.

Wire (`AutocompleteContract` in libs:autofill, mirrored in `AutocompletePublisher` in libs:account, held equal by the separation tester and `AutocompleteSyncTest`): `{"v":1,"ids":[{"label","value"}],"addresses":[...],"urls":[...],"secrets":[...]}`, at most 500 rows a page.

Server (cloud-u-containers `infra-api_cloud-me-api`, route `GET /me/autocomplete`): reads the declared private file `autocomplete.json` of `cloud-me-pvt-configs` (`runtime.autocomplete`), normalises it to the wire above and serves it behind the Authelia bearer or session check, `no-store`; no file yet answers the empty document. It is the one personal file served past the secret filter, and it is refused by the generic file and dataset routes. Reached at `http://cloud-me-api.mesh.diegonmarcos.com:8124/me/autocomplete` (mesh) and, as the fallback, `https://c3-pvt.diegonmarcos.com/cloud-me-api/me/autocomplete` (#1104). The service answers the same routes with or without the `/me` prefix. Account sends the Authelia token it holds (else the fleet bearer) on both paths.

## Toolbar and emoji search

- First row: Grammar mode, Translate, Enhance, Autocomplete, Clipboard, Numpad, Emoji, Voice, Switch language, Android keyboard settings, Vault, then the editing keys. Toolbar layout revision 4, adopted once by `DefaultsMigration` (revision 2).
- Grammar mode icon: each tap cycles Auto Correct, Highlight, Off, names the mode in a toast, and draws one of three icons.
- Emoji search: a tap on the field returns to the letter pad and opens the results box (95 dp, half of the old 190 dp) with the matching emoji in one row.

## CI evidence

`ship-cloud-keyboard.yml` job `keyboard_screenshots` boots an emulator, drives the debug build through `KeyboardDebugReceiver` (debuggable builds, `android.permission.DUMP` only), reads each state back and uploads the screenshots as the artifact `keyboard-screenshots`.
