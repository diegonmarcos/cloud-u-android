#!/usr/bin/env node
/**
 * test-pdf-reader-smoke.js — #458 the PDF engine really extracts text.
 *
 * WHY THIS FILE IS NODE, NOT GREP. The reader's two load-bearing claims can be
 * grepped (files exist, functions are named, the manifest declares the filter)
 * but one of them cannot: that pdf.js, loaded the way drive.html loads it,
 * actually pulls text out of a PDF and finds NOTHING in a scan. That is the
 * claim the scan refusal stands on, and the only honest way to prove it is to
 * run the vendored engine against a real PDF. Node is on the CI runner and is
 * declared in build.json::tests.shell.requires so its absence fails the suite
 * instead of silently skipping it (a tester that cannot reach its subject must
 * fail, never go quiet).
 *
 * The worker is attached the way drive.html attaches it: the vendored
 * pdf.worker.min.js is a classic UMD script whose browser branch sets
 * globalThis.pdfjsWorker, and pdf.min.js consumes that as its main-thread
 * message handler. In node the UMD takes the CommonJS branch, so attaching
 * require(...).WorkerMessageHandler under the same global name simulates the
 * browser branch with the very same bytes.
 *
 * Usage: node test-pdf-reader-smoke.js <ac_cloud-drive dir>
 * Exit: 0 all assertions hold, 1 any fails.
 */

"use strict";

const fs = require("fs");
const path = require("path");
const os = require("os");

const appDir = process.argv[2];
if (!appDir) {
  console.error("  FAIL  usage: node test-pdf-reader-smoke.js <ac_cloud-drive dir>");
  process.exit(1);
}

let failures = 0;
const pass = (message) => console.log("  PASS  " + message);
const fail = (message) => { console.log("  FAIL  " + message); failures += 1; };

const vendorDir = path.join(appDir, "app", "src", "main", "assets", "vendor");
const pdfMin = path.join(vendorDir, "pdf.min.js");
const pdfWorker = path.join(vendorDir, "pdf.worker.min.js");

for (const file of [pdfMin, pdfWorker]) {
  if (!fs.existsSync(file)) {
    console.error("  FAIL  missing vendored engine file: " + file);
    process.exit(1);
  }
}

// A one-page PDF whose page actually contains the text object below.
const TEXT_PDF_BASE64 =
  "JVBERi0xLjQKMSAwIG9iaiA8PCAvVHlwZSAvQ2F0YWxvZyAvUGFnZXMgMiAwIFIgPj4gZW5kb2JqCjIgMCBvYmogPDwgL1R5cGUgL1BhZ2VzIC9LaWRzIFszIDAgUl0gL0NvdW50IDEgPj4gZW5kb2JqCjMgMCBvYmogPDwgL1R5cGUgL1BhZ2UgL1BhcmVudCAyIDAgUiAvTWVkaWFCb3ggWzAgMCAzMDAgMTQ0XSAvQ29udGVudHMgNCAwIFIgL1Jlc291cmNlcyA8PCAvRm9udCA8PCAvRjEgNSAwIFIgPj4gPj4gPj4gZW5kb2JqCjQgMCBvYmogPDwgL0xlbmd0aCA0NCA+PiBzdHJlYW0KQlQgL0YxIDEyIFRmIDIwIDEyMCBUZCAoSGVsbG8gY2xvdWQgZHJpdmUgUERGKSBUaiBFVAplbmRzdHJlYW0gZW5kb2JqCjUgMCBvYmogPDwgL1R5cGUgL0ZvbnQgL1N1YnR5cGUgL1R5cGUxIC9CYXNlRm9udCAvSGVsdmV0aWNhID4+IGVuZG9iagp4cmVmCjAgNgowMDAwMDAwMDAwIDY1NTM1IGYgCjAwMDAwMDAwMDkgMDAwMDAgbiAKMDAwMDAwMDA1OCAwMDAwMCBuIAowMDAwMDAwMTE1IDAwMDAwIG4gCjAwMDAwMDAyMjIgMDAwMDAgbiAKMDAwMDAwMDMyOCAwMDAwMCBuIAp0cmFpbGVyIDw8IC9TaXplIDYgL1Jvb3QgMSAwIFIgPj4Kc3RhcnR4cmVmCjQyNAolJUVPRgo=";

// A one-page PDF whose page draws nothing — the honest stand-in for a scan:
// an image-only page has exactly this much text-layer content.
const SCAN_PDF_BASE64 =
  "JVBERi0xLjQKMSAwIG9iaiA8PCAvVHlwZSAvQ2F0YWxvZyAvUGFnZXMgMiAwIFIgPj4gZW5kb2JqCjIgMCBvYmogPDwgL1R5cGUgL1BhZ2VzIC9LaWRzIFszIDAgUl0gL0NvdW50IDEgPj4gZW5kb2JqCjMgMCBvYmogPDwgL1R5cGUgL1BhZ2UgL1BhcmVudCAyIDAgUiAvTWVkaWFCb3ggWzAgMCAzMDAgMTQ0XSAvQ29udGVudHMgNCAwIFIgPj4gZW5kb2JqCjQgMCBvYmogPDwgL0xlbmd0aCA0ID4+IHN0cmVhbQpxIFEKZW5kc3RyZWFtIGVuZG9iagp4cmVmCjAgNQowMDAwMDAwMDAwIDY1NTM1IGYgCjAwMDAwMDAwMDkgMDAwMDAgbiAKMDAwMDAwMDA1OCAwMDAwMCBuIAowMDAwMDAwMTE1IDAwMDAwIG4gCjAwMDAwMDAyMjEgMDAwMDAgbiAKdHJhaWxlciA8PCAvU2l6ZSA1IC9Sb290IDEgMCBSID4+CnN0YXJ0eHJlZgozMjMKJSVFT0YK";

const toBytes = (b64) => {
  // Buffer.from can hand back a slice of a shared pool; pdf.js may read the
  // underlying ArrayBuffer directly, so make the copy a fresh, unpooled one.
  return Uint8Array.from(Buffer.from(b64, "base64"));
};

// Attach the worker the way the page does: as a script-loaded main-thread
// handler, before the library is asked for anything.
globalThis.pdfjsWorker = require(pdfWorker);
const pdfjsLib = require(pdfMin);
pdfjsLib.GlobalWorkerOptions.workerSrc = "pdf.worker.min.js";

const extractPlainText = async (pdfBase64) => {
  const documentProxy = await pdfjsLib.getDocument({ data: toBytes(pdfBase64) }).promise;
  try {
    const fragments = [];
    for (let number = 1; number <= documentProxy.numPages; number++) {
      const page = await documentProxy.getPage(number);
      const textContent = await page.getTextContent();
      // The same item walk drive.html's pagePlainText performs: only items that
      // carry a string contribute, and a scan contributes none of them.
      for (const item of textContent.items) {
        if (typeof item.str === "string") fragments.push(item.str);
      }
    }
    return fragments.join("");
  } finally {
    documentProxy.destroy();
  }
};

Promise.resolve()
  .then(async () => {
    // The engine opens a real PDF and its text layer is real text.
    const text = await extractPlainText(TEXT_PDF_BASE64);
    if (text.includes("Hello cloud drive PDF")) {
      pass("the vendored engine extracts text-layer text from a real PDF");
    } else {
      fail("expected the text-layer text to come out of the text PDF, got: " + JSON.stringify(text));
    }

    // The engine finds nothing in a page that draws nothing — the pre-image of
    // the app's scan refusal.
    const scanText = await extractPlainText(SCAN_PDF_BASE64);
    if (scanText.length === 0) {
      pass("the same extraction over a scan (no text layer) yields zero text");
    } else {
      fail("a scan must yield zero text, got: " + JSON.stringify(scanText));
    }
  })
  .catch((error) => {
    fail("pdf.js smoke run crashed: " + (error && error.message));
  })
  .then(() => {
    if (failures === 0) {
      console.log("test-pdf-reader-smoke: OK");
      process.exit(0);
    }
    console.log("test-pdf-reader-smoke: " + failures + " assertion(s) FAILED");
    process.exit(1);
  });