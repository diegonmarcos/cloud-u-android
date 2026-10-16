// client.spec.mjs loads the app's nbstore source (../packages/common/nbstore), whose bare imports
// (yjs, eventemitter2) resolve upward from THAT directory. Without a monorepo install there is no
// ac_cloud-notes/node_modules, so point one at this package's: the same files, hence ONE yjs
// instance for the server, the tests and the client. Removed again afterwards.
import { existsSync, lstatSync, rmSync, symlinkSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const link = fileURLToPath(new URL('../../node_modules', import.meta.url));
const target = fileURLToPath(new URL('../node_modules', import.meta.url));

export default function setup() {
  let made = false;
  if (!existsSync(link)) {
    symlinkSync(target, link, 'dir');
    made = true;
  }
  return () => {
    if (made && lstatSync(link).isSymbolicLink()) rmSync(link);
  };
}
