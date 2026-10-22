import { useI18n } from '@cloud-notes/i18n';
import { ViewLayersIcon } from '@blocksuite/icons/rc';

import { NavigationPanelEmptySection } from '../../layouts/empty-section';

export const RootEmpty = ({
  onClickCreate,
}: {
  onClickCreate?: () => void;
}) => {
  const t = useI18n();

  return (
    <NavigationPanelEmptySection
      icon={ViewLayersIcon}
      message={t['com.cloud-notes.collections.empty.message']()}
      messageTestId="slider-bar-collection-empty-message"
      actionText={t['com.cloud-notes.collections.empty.new-collection-button']()}
      onActionClick={onClickCreate}
    />
  );
};
