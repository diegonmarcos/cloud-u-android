import { useI18n } from '@cloud-notes/i18n';
import { TagIcon } from '@blocksuite/icons/rc';

import { NavigationPanelEmptySection } from '../../layouts/empty-section';

export const RootEmpty = () => {
  const t = useI18n();

  return (
    <NavigationPanelEmptySection
      icon={TagIcon}
      message={t['com.cloud-notes.rootAppSidebar.tags.empty']()}
      messageTestId="slider-bar-tags-empty-message"
    />
  );
};
