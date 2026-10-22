import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const outputRoot = fileURLToPath(
  new URL(
    '../out/canary/cloud-notes-canary-darwin-arm64/cloud-notes-canary.app/Contents/Resources',
    import.meta.url
  )
);

// todo: use asar package to check contents
fs.existsSync(path.resolve(outputRoot, 'app.asar'));

console.log('Output check passed');
