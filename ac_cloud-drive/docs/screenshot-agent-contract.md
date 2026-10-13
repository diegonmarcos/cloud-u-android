# Screenshot agent: output contract (v1)

Cloud Drive's **Agents** tab runs the Screenshot agent. It OCRs every screenshot on the phone and
writes on-device ML metadata. Other fleet apps read the result as plain files in the shared store.
The first reader is Cloud Calendar's event agent.

Machine-readable source of truth: `ac_cloud-drive/data/agent-screenshots-contract.json`.

## Where
`<shared storage>/<storage.shared_root>/.agents/screenshots/v1/`, which is `/storage/emulated/0/CloudDrive/.agents/screenshots/v1/` on a single-user phone.
Readers need all-files access (MANAGE_EXTERNAL_STORAGE), which every fleet app already has for the store.

| file | content |
|---|---|
| `records/<id>.json` | one record per screenshot (schema below) |
| `index.jsonl` | append-only, one line per write: `{id, source_path, taken_at_ms, processed_at_ms, has_text}`; `{id, deleted:true}` on removal |
| `state.json` | agent bookkeeping: `{version, last_scan_ms, processed, failed, cursor_mtime_ms}` |

`id` is the first 16 hex characters of `sha256(source_path + "\n" + size + "\n" + mtime_ms)`. An edited file gets a new id.
Every file is written as `.tmp` and then renamed, so a reader never sees a partial record.

## Record
```json
{
  "schema": "cloud-drive.agent.screenshots/1",
  "id": "9f2c1a0b7d3e4f51",
  "source_path": "/storage/emulated/0/Pictures/Screenshots/Screenshot_20261013-101500.png",
  "source_size": 482113, "source_mtime_ms": 1791886500000,
  "taken_at_ms": 1791886500000, "processed_at_ms": 1791886560000,
  "width": 1080, "height": 2400,
  "text": "Dentist\nThu 16 Oct 10:30",
  "lang": "und-Latn",
  "blocks": [{"text": "Dentist", "bbox": [40, 210, 380, 260], "lines": [{"text": "Dentist", "bbox": [40, 210, 380, 260]}]}],
  "labels": [{"label": "Text", "confidence": 0.93, "source": "image-labeling"}],
  "objects": [],
  "entities": [{"type": "date", "text": "Thu 16 Oct", "start": 8, "end": 18}, {"type": "time", "text": "10:30", "start": 19, "end": 24}],
  "engine": {"ocr": "mlkit-text-latin", "labeling": "mlkit-image-labeling", "objects": "mlkit-object-detection", "agent_version": 1},
  "error": null
}
```
`bbox` is `[left, top, right, bottom]` in source pixels. `taken_at_ms` comes from EXIF, then the file name's timestamp, then mtime.

## Reading incrementally
Keep your own `processed_at_ms` cursor and read `index.jsonl` lines past it. For each new id, load
`records/<id>.json`. If an id appears again later, the later line wins.

## Versioning
Added fields keep v1. A breaking change bumps both the `schema` string and the `v1` directory, and the
old directory keeps being written for one release.
