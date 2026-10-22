import '@cloud-notes/env/constant';
import '@blocksuite/cloud-notes/global/types'

declare module '@blocksuite/cloud-notes/store' {
  interface DocMeta {
    /**
     * @deprecated
     */
    favorite?: boolean;
    trash?: boolean;
    trashDate?: number;
    updatedDate?: number;
    mode?: 'page' | 'edgeless';
    // todo: support `number` in the future
    isPublic?: boolean;
  }
}


declare global {

declare type Environment = {
  // Variant
  isSelfHosted: boolean;

  // Device
  isLinux: boolean;
  isMacOs: boolean;
  isIOS: boolean;
  isSafari: boolean;
  isWindows: boolean;
  isFireFox: boolean;
  isMobile: boolean;
  isChrome: boolean;
  isPwa: boolean;
  chromeVersion?: number;

  // runtime configs
  publicPath: string;
  subPath: string;
};

  var process: {
    env: Record<string, string>;
  };
  var environment: Environment;
  var $CLOUD_NOTES_SETUP: boolean | undefined;
  /**
   * Inject by https://www.npmjs.com/package/@sentry/webpack-plugin
   */
  var SENTRY_RELEASE: { id: string } | undefined;
}
