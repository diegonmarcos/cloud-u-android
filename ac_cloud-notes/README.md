# Cloud Notes

A plain-Markdown notes app for the Cloud constellation: **files on disk, not a
database**. It opens the owner's existing Obsidian vault directly — the same
`.md` files, in the same folders — so Obsidian, Syncthing and every other tool
that speaks "a directory of markdown" keep working unchanged.

Built from a vendored, renamed [Markor](https://github.com/gsantner/markor)
tree (Apache-2.0). See `NOTICE.md` for provenance and the statement of changes,
and `UPSTREAM-README.md` for upstream's own documentation of the editor itself.

## Why this and not AFFiNE

Task #232 asked for an AFFiNE-based notes app. AFFiNE stores documents as
opaque CRDT blobs inside its own SQLite database and has **no folder-vault
mode**, so it would have opened on an empty workspace standing next to 179
existing markdown files it cannot read. Markor edits those files in place.
The full argument is in `a0_docs/eng-specs/cloud-notes-affine-feasibility.md`
§7.2 and in `build.json::_doc_product_substitution`.

## Identity

| | |
|---|---|
| applicationId | `com.diegonmarcos.cloudnotes` |
| FileProvider authority | `com.diegonmarcos.cloudnotes.provider` (derived from `${applicationId}`) |
| Release asset | `Cloud-Notes.apk` (one universal APK — pure Java, no ABI split) |
| Ship workflow | `1_cicd/src/cicd/ship-cloud-notes.yml` |

## The one permission to grant

Cloud Notes needs **All files access** (`MANAGE_EXTERNAL_STORAGE`) to open a
pre-existing vault directory by path and keep writing to it. Grant it once:

> Settings ▸ Apps ▸ Cloud Notes ▸ Permissions ▸ Files and media ▸
> **Allow management of all files**

The app routes to that screen itself on first run
(`StoragePermissionActivity`). It cannot be granted by an install flag, and the
ordinary runtime permission dialog does not cover it.

## Build

Everything is data-driven from `build.json`; `build.sh` is the dispatcher.

```sh
./build.sh build-fork "$(jq -r '.forks | keys[0]' build.json)"
```
