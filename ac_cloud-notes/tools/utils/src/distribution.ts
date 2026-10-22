import { PackageList, type PackageName } from './yarn';

export const PackageToDistribution = new Map<
  PackageName,
  BUILD_CONFIG_TYPE['distribution']
>([
  ['@cloud-notes/admin', 'admin'],
  ['@cloud-notes/web', 'web'],
  ['@cloud-notes/electron-renderer', 'desktop'],
  ['@cloud-notes/electron', 'desktop'],
  ['@cloud-notes/mobile', 'mobile'],
  ['@cloud-notes/ios', 'ios'],
  ['@cloud-notes/android', 'android'],
]);

export const AliasToPackage = new Map<string, PackageName>([
  ['admin', '@cloud-notes/admin'],
  ['web', '@cloud-notes/web'],
  ['electron', '@cloud-notes/electron'],
  ['desktop', '@cloud-notes/electron-renderer'],
  ['renderer', '@cloud-notes/electron-renderer'],
  ['mobile', '@cloud-notes/mobile'],
  ['ios', '@cloud-notes/ios'],
  ['android', '@cloud-notes/android'],
  ['server', '@cloud-notes/server'],
  ['gql', '@cloud-notes/graphql'],
  ...PackageList.map(
    pkg => [pkg.name.split('/').pop()!, pkg.name] as [string, PackageName]
  ),
]);
