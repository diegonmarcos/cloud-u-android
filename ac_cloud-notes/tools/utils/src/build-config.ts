import type { Package } from '@cloud-notes-tools/utils/workspace';

import { PackageToDistribution } from './distribution';

export interface BuildFlags {
  channel: 'stable' | 'beta' | 'internal' | 'canary';
  mode: 'development' | 'production';
}

export function getBuildConfig(
  pkg: Package,
  buildFlags: BuildFlags
): BUILD_CONFIG_TYPE {
  const distribution = PackageToDistribution.get(pkg.name);

  if (!distribution) {
    throw new Error(`Distribution for ${pkg.name} is not found`);
  }

  const buildPreset: Record<BuildFlags['channel'], BUILD_CONFIG_TYPE> = {
    get stable() {
      return {
        debug: buildFlags.mode === 'development',
        distribution,
        isDesktopEdition: (
          ['web', 'desktop', 'admin'] as BUILD_CONFIG_TYPE['distribution'][]
        ).includes(distribution),
        isMobileEdition: (
          ['mobile', 'ios', 'android'] as BUILD_CONFIG_TYPE['distribution'][]
        ).includes(distribution),
        isElectron: distribution === 'desktop',
        isWeb: distribution === 'web',
        isMobileWeb: distribution === 'mobile',
        isIOS: distribution === 'ios',
        isAndroid: distribution === 'android',
        isNative:
          distribution === 'desktop' ||
          distribution === 'ios' ||
          distribution === 'android',
        isAdmin: distribution === 'admin',

        appBuildType: 'stable' as const,
        appVersion: pkg.version,
        // editorVersion: pkg.dependencies['@blocksuite/cloud-notes'],
        editorVersion: pkg.version,
        githubUrl: 'https://github.com/diegonmarcos/cloud-u-android',
        changelogUrl: 'https://notes.diegonmarcos.com/what-is-new',
        downloadUrl: 'https://notes.diegonmarcos.com/download',
        pricingUrl: 'https://notes.diegonmarcos.com/pricing',
        discordUrl: 'https://notes.diegonmarcos.com/redirect/discord',
        requestLicenseUrl: 'https://notes.diegonmarcos.com/redirect/license',
        imageProxyUrl: '/api/worker/image-proxy',
        linkPreviewUrl: '/api/worker/link-preview',
        SENTRY_DSN: process.env.SENTRY_DSN ?? '',
      };
    },
    get beta() {
      return {
        ...this.stable,
        appBuildType: 'beta' as const,
        changelogUrl: 'https://github.com/diegonmarcos/cloud-u-android/releases',
      };
    },
    get internal() {
      return {
        ...this.stable,
        appBuildType: 'internal' as const,
        changelogUrl: 'https://github.com/diegonmarcos/cloud-u-android/releases',
      };
    },
    // canary will be aggressive and enable all features
    get canary() {
      return {
        ...this.stable,
        appBuildType: 'canary' as const,
        changelogUrl: 'https://github.com/diegonmarcos/cloud-u-android/releases',
      };
    },
  };

  const currentBuild = buildFlags.channel;

  if (!(currentBuild in buildPreset)) {
    throw new Error(`BUILD_TYPE ${currentBuild} is not supported`);
  }

  const currentBuildPreset = buildPreset[currentBuild];

  const environmentPreset = {
    changelogUrl: process.env.CHANGELOG_URL ?? currentBuildPreset.changelogUrl,
  };

  return {
    ...currentBuildPreset,
    // environment preset will overwrite current build preset
    // this environment variable is for debug proposes only
    // do not put them into CI
    ...(process.env.CI ? {} : environmentPreset),
  };
}
