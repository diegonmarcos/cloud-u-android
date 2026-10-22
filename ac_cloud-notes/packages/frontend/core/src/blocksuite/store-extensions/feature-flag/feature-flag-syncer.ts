import {
  CLOUD_NOTES_FLAGS,
  type FeatureFlagService,
} from '@cloud-notes/core/modules/feature-flag';
import { FeatureFlagService as BSFeatureFlagService } from '@blocksuite/cloud-notes/shared/services';
import { type ExtensionType, StoreExtension } from '@blocksuite/cloud-notes/store';

export function getFeatureFlagSyncer(
  featureFlagService: FeatureFlagService
): ExtensionType {
  class FeatureFlagSyncer extends StoreExtension {
    static override key = 'feature-flag-syncer';

    override loaded() {
      const bsFeatureFlagService = this.store.get(BSFeatureFlagService);
      Object.entries(CLOUD_NOTES_FLAGS).forEach(([key, flag]) => {
        if (flag.category === 'blocksuite') {
          const value =
            featureFlagService.flags[key as keyof CLOUD_NOTES_FLAGS].value;
          if (value !== undefined) {
            bsFeatureFlagService.setFlag(flag.bsFlag, value);
          }
        }
      });
    }
  }

  return FeatureFlagSyncer;
}
