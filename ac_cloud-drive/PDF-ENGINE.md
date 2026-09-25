# cloud-drive's PDF engine (#577)

cloud-drive is the fleet's PDF reader (#458) and its reader is the **one** in-process PDF renderer
in the fleet (#463). Every other app reaches it through the Android manifest — an
`ACTION_VIEW application/pdf` chooser (cloud-mail's attachments) — and never carries a renderer.
The pin lives in data: `build.json::pdf.engine`, read by `app/build.gradle`.

## What it replaced, and what was wrong with it

Audited on origin/main `c0c6e9de5` before touching it.

| | pdf.js 2.16.105 in the WebView (before) | pdfium via `io.legere:pdfiumandroid` (now) |
|---|---|---|
| Engine age | released 2022-08, last of the 2.x line | pdfium built with the binding, maintained (release 2.0.3 on 2026-07-26) |
| Scrolling | **one page on one canvas**; page change = a button press. No continuous scroll at all | continuous column **and** paged mode, momentum fling, fast-scroll thumb |
| Zoom | a CSS transform on the canvas: blurry until a re-render | pinch / double-tap to 6x; the visible window of each page is re-rendered at the real zoom |
| Open a large file | the whole file as one base64 string over the JS bridge, then parsed on the UI thread (worker ran on the main thread); **24 MB ceiling** | opened from a file descriptor, first page immediately, no size ceiling; sizes of the other pages stream in behind |
| Search | serial `getTextContent` over every page on the UI thread, highlights in a DOM text layer | pdfium's text search per page, streamed in batches, highlights drawn on the page, Next/Previous |
| Night mode | CSS `invert` + `hue-rotate` on the canvas | a colour matrix on the page paint (same maths), no re-render, highlights unaffected |
| Share / print | none | system share sheet, system print service |
| Weight in the APK | 1.35 MB of JS + 24 MB ceiling in memory | ~5 MB of native code for arm64-v8a |

## Why pdfium (the choice, with the alternatives)

* **androidx.pdf (Jetpack) — rejected.** `1.0.0-beta01`: minSdk 28 while this app is 26; it renders
  through the platform's `PdfRenderer` (`PdfRendererCompatAdapter`, needs a platform SDK extension),
  so behaviour depends on the phone's system components; still beta; no text-extraction API, which
  the PDF→txt/md/html/csv converters need. Read from its AAR and POMs, not from memory.
* **barteksc AndroidPdfViewer (and its forks) — rejected without a deep audit.** A ready-made viewer
  widget rather than an engine API: the converters and the search highlights need text and geometry,
  which is what the binding above exposes directly. (The fleet guard already names it as a second engine.)
* **MuPDF — rejected.** AGPL-3.0 / commercial: cannot be published in this fleet's APKs.
* **pdfium through `io.legere:pdfiumandroid` — chosen.** pdfium is the engine inside Chrome.
  The binding is Kotlin, Apache-2.0, on Maven Central, minSdk 24, ships arm64-v8a / armeabi-v7a /
  x86 / x86_64 native libs, and exposes what the reader needs: file-descriptor open, window
  rendering into a bitmap, text extraction, text search with rectangles, outline, links, hit tests,
  passwords. Its API was read from the 2.0.0 sources jar, not from memory.

### The version is pinned BELOW the latest on purpose

`2.0.0`. Releases 2.0.1 and later publish against `kotlin-stdlib` 2.4; this repository compiles with
Kotlin 2.3.20 (root `build.gradle`), and a Kotlin compiler reads class metadata at most one minor
version ahead. Raise the pin **together with** the root Kotlin plugin version, never alone.

### Both artifacts are on the compile classpath

`pdfiumandroid` and `pdfiumandroid-core` (`build.json::pdf.engine.artifacts`). The binding's Gradle
metadata publishes the core (where the `.so` files live) as runtime-only, but the public constructor
`PdfiumCore(context, config, coreInternal: PdfiumCoreU = …)` names a core class, so a bare
`PdfiumCore(context)` does not compile without it.

## Licence

The binding is Apache-2.0 (its `LICENSE`; the POM declares the same). The native engine inside it,
PDFium, is BSD-3-Clause. Both permissive. Nothing is vendored into this repository — it is a Maven
Central dependency — so `1_cicd/src/data/licence-boundaries.json` (which guards mixed-licence
upstreams whose restricted directories must not be vendored: AFFiNE, Acode) has nothing to hold it
to; `cloud-android-licence-boundary-guard.py --repo .` still passes on the tree.

## Shape

| File | Role |
|---|---|
| `PdfEngine.kt` | **the only file that names the library.** open (fd, password, spool fallback), render a window, text, search, rectangles, links, outline |
| `PdfReaderView.kt` | the surface: layout, gestures, bitmap cache, sharp tiles, highlights, selection, links |
| `PdfReaderActivity.kt` | toolbar, search bar, outline, go-to-page, share, print, convert; the manifest's PDF handler |
| `PdfLayout.kt` | page geometry for both modes (pure — JVM-tested) |
| `PdfConvert.kt` | txt/md/html/csv builders, name picking, word selection (pure — JVM-tested) |
| `DriveActions.openPdf (#579) / convertPdf` | the Files tab's two calls; the bridge carries a path and a result, never the document |

## Tests

* `app/src/test` — `PdfConvertTest`, `PdfLayoutTest`: 19 JVM tests that execute the converter and the
  layout on every ship (`build.json::tests.unit`).
* `test/test-drive-pdf-engine.sh` — one engine, wired features, manifest routing, no pdf.js left.
* `1_cicd/src/scripts/test/one-pdf-engine-guard.test.sh` — the fleet guard: linking the binding, or
  its Gradle coordinate, from any other app tree goes red.

Not covered by anything that runs in CI: drawing, gestures and the native calls. That a page renders
sharp at 6x, a fling feels smooth, or pdfium opens a given hostile or multi-gigabyte file needs a phone.

## Deliberate limits

* docx / xlsx / odt are **not** produced: that is layout reconstruction and needs the fleet converter
  service. The Files tab lists them disabled with that reason (as before #577).
* Paged mode pages vertically (snap per screen); it does not swipe horizontally.
* Selection is a long-press on a word (copied to the clipboard), not a draggable range.
