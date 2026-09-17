# Vendored: pdf.js (PDF reader engine)

## What and why

`pdf.min.js` + `pdf.worker.min.js` are the PDF.js general-purpose PDF engine
from the Mozilla project (Apache-2.0), pinned to **version 2.16.105**
(released 2022-08-17, the final release of the 2.x line).

cloud-drive renders PDFs inside its own WebView, so the engine must travel
with the APK, exactly like `tailwind.js` in this same directory: no CDN, no
network, ever. It is the single mechanism for every PDF feature — page
rendering, the text layer that find and conversion read, and the document
outline. Android's own `PdfRenderer` is deliberately NOT used anywhere (it
returns bitmaps with no text, so search and outline would need a second
engine plus bitmap plumbing across the FilesBridge boundary).

## Pin

- Upstream: https://github.com/mozilla/pdf.js (Apache-2.0)
- Tag: `v2.16.105`
- Files fetched from the cdnjs mirror of that tag:
  - https://cdnjs.cloudflare.com/ajax/libs/pdf.js/2.16.105/pdf.min.js
  - https://cdnjs.cloudflare.com/ajax/libs/pdf.js/2.16.105/pdf.worker.min.js
- `APACHE-LICENSE.txt` is the upstream LICENSE at that tag.

| File | sha256 |
| ---- | ------ |
| `pdf.min.js` | `1fc294eefda602e591a06c1d5af361cae23756b5a334fc2421d5f9accce038e5` |
| `pdf.worker.min.js` | `99732130603cd4980f009a29a38a86373b43ffb9ae22c35ec85440f311ceddfc` |

## Why the 2.x classic build, not a newer module build

- 2.16.105 is the last release whose builds are **classic scripts** that set a
  global (`window.pdfjsLib` / `globalThis.pdfjsWorker`), loadable with a plain
  `<script src="vendor/...">` tag — the identical mechanism `tailwind.js` has
  proven works from `file:///android_asset/...` in this app. Newer pdf.js
  ships ES modules only, and Chromium blocks module/fetch traffic on `file://`
  origins by CORS policy (documented in `ac_cloud-nav`'s SpaceViewActivity,
  which had to move to WebViewAssetLoader for the same reason).
- The worker file is loaded as a SECOND classic script. pdf.js reads
  `globalThis.pdfjsWorker` and runs the worker's message handler on the main
  thread directly (`PDFWorker._mainThreadWorkerMessageHandler`); it never
  needs to `fetch()` the worker, which would be blocked on `file://`. Its own
  fallback path (`loadScript`) also inserts a plain script tag, never a fetch.
- The document bytes are handed to `getDocument({ data })` as a
  `Uint8Array` decoded from base64 that `FilesBridge.readPdf()` returns over
  the JavascriptInterface. pdf.js is never given a URL, so its own network
  layer (fetch / XHR) is never exercised.

## Size budget

The two files are ~1.35 MB raw, ~385 KB gzipped into the APK. The worker is
loaded on the main thread, so the page pays for it once at load, not per PDF
and not per render.

## License

Apache-2.0, © Mozilla Foundation and contributors. See `APACHE-LICENSE.txt`.