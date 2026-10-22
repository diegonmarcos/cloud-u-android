import { execSync } from 'node:child_process';

import { ProjectRoot } from '@cloud-notes-tools/utils/path';
import { Package } from '@cloud-notes-tools/utils/workspace';

const iosPackage = new Package('@cloud-notes/ios');

const PackageRoot = iosPackage.path;

console.log('[*] PackageRoot', PackageRoot);

const version = process.argv[2] || '1.25.7';

console.log('[*] graphql...');
execSync(`${PackageRoot}/apollo-codegen-chore.sh "${version}"`, {
  stdio: 'inherit',
});

console.log('[*] rust...');
execSync(
  'cargo build -p cloud_notes_mobile_native --features use-as-lib --lib --release --target aarch64-apple-ios',
  {
    stdio: 'inherit',
    env: { ...process.env, IPHONEOS_DEPLOYMENT_TARGET: '16.5' },
  }
);

execSync(
  `cargo run -p cloud_notes_mobile_native --features use-as-lib --bin uniffi-bindgen generate \
  --library ${ProjectRoot}/target/aarch64-apple-ios/release/libcloud_notes_mobile_native.a \
  --language swift --out-dir ${PackageRoot}/App/App/uniffi`,
  { stdio: 'inherit' }
);

console.log('[+] codegen complete');
