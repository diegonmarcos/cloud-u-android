# Cloud Office

Collabora Office, built from source, rebranded, and with Text Enhance wired to the
fleet enhancer.

This directory used to be a mirror: `ship-cloud-sheets.yml` downloaded Collabora's
official APK, verified it against a pinned `sha256` and republished those exact
bytes. That is no longer what this is. The owner asked for a full clone, a full
rebuild, and Text Enhance that actually works — the first two because the third is
impossible without them.

## Where things are

| | |
|---|---|
| `build.json` | every pin, flag and decision, as data. Read it first. |
| `patches/` | the edits we own, applied to the pinned upstream at build time |
| `patches/README.md` | why this app patches-on-fetch when the rest of the fleet vendors |
| `tests/patches-apply.sh` | the assertion the whole structure rests on |

There is **no source in this directory and there must not be.** See
`build.json::_doc_no_source_in_git`.

## Text Enhance — the path, in three legs

Enhance was a menu item that silently did nothing. Collabora's Android text input
is a hidden `contenteditable` that never holds the document text; the real
selection lives in the canvas and in core behind it. Anything reading the input
element reads a blank buffer and enhances nothing — visibly present, actually
absent, for weeks.

Copy is the one operation on this platform that demonstrably gets the real
selection, so all three legs follow something that already worked:

1. **Get the real selection.** `Control.Menubar` sets a one-shot flag on
   `Clipboard` and sends core `gettextselection`, exactly as the `Action_Copy`
   PostMessage API does. Core answers `textselectioncontent:`; the handler that
   already routes `Action_Copy` routes ours the same way and returns *before* the
   real clipboard is touched, so an enhance cannot destroy what the owner copied.
2. **Reach the enhancer.** Over `COOLMessageHandler`, the bridge already carrying
   `SAVE`, `PRINT` and `.uno:Paste`. The enhancer is **not** reimplemented:
   routing, the model registry, chunking of oversized input, the timeout and the
   provider **API key** stay in the Cloud Keyboard behind `ITextTools`. Cloud
   Office calls `enhanceWith()` — the method that exists so a caller can hold its
   own settings and send its whole decision, the split `cloud-mail` already made.
3. **Put it back as a real edit.** `LOActivity.paste()`, the same native call the
   ordinary paste path uses. Core replaces the selection through its own paste,
   which is **one undo step**. Delete-then-insert would have been two.

### Behaviour, stated rather than discovered

- **No selection** → refused, with "Select the text you want to enhance first" on
  screen. Enhancing the paragraph at the cursor was considered and rejected: it
  needs a second round trip and silently edits text the owner never pointed at.
- **Large selections** → chunked by the existing enhancer, behind the binder,
  where the provider's token budget is actually known. No second implementation.
- **Failure** → always visible. Missing keyboard, missing API key, network error,
  provider error, empty reply and a failed paste each produce a message. The
  silence *was* the defect.
- **Formatting** → plain text in, plain text out. Bold, italics, links and list
  markers *inside* the enhanced range do **not** survive; the replacement takes the
  formatting at the insertion point. This is a decision, not an accident: asking
  core for `text/html` would preserve them but would also let a model emit
  arbitrary markup into a document, and a rewrite that invents a table is worse
  than one that flattens a bold word.
- **Undo** → one step, by construction, because it is core's own paste. Designed,
  not observed: nobody here has a phone.
- **Writer only.** Calc's Edit menu is untouched on purpose — a spreadsheet
  selection can span cells and one plain-text rewrite pasted back would scatter
  across them.
- **The selection never reaches logcat.** This fleet uploads logcat from its
  diagnostics screens, so a logged selection is a selection that leaves the phone.

## Settings

Moved into the document's own **Edit** menu. It was previously reachable only from
the document-browser screen — that is, only by closing the document you were
editing, which is why nobody found it.

`android/lib` cannot name `SettingsActivity` (different package, different module,
and the applicationId is a third string again), so patch 0001 gives it an
`intent-filter` and the shell launches it by action. `exported` stays `false`.

## What the application-id change means for the owner

`com.collabora.libreoffice` → `com.diegonmarcos.cloudoffice`.

**The new app does not replace the installed Collabora Office.** It installs
alongside it as a second launcher icon; to Android they are unrelated apps.
Both will offer to open documents until the old one is removed. Nothing migrates —
the old app keeps its own settings and recent-files list. Uninstalling the old one
is manual and safe: documents live in normal storage, not in its private data
directory.

A rebuilt app **cannot** claim upstream's id. Those bytes are signed with
Collabora's key; ours are signed with the fleet key, and Android refuses an update
across different signing keys.

## What we gave up, plainly

The `sha256` pin over Collabora's release APK was the entire supply-chain
guarantee, and building ourselves gives it up. It is replaced by two pinned commit
shas, verified at fetch time, plus a patch series that must apply exactly.

That is **genuinely weaker in three ways**, spelled out in
`build.json::supply_chain`: we no longer verify a Collabora-signed artefact; the
build now trusts an NDK toolchain and LibreOffice's whole `external/` tree; and a
compromise of the fleet signing key now reaches this app, where before it could
not. The trade was made knowingly, for ownership.

## Running the test

```sh
./tests/patches-apply.sh
```

It talks to the network on purpose — the pin is a claim about a remote object, and
a test that mocked the remote would assert nothing that can actually break. A fetch
failure is a failure, not a skip.
