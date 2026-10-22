import { IconButton } from '@cloud-notes/component';
import { useI18n } from '@cloud-notes/i18n';
import { ToggleRightIcon } from '@blocksuite/icons/rc';
import { Trigger as CollapsibleTrigger } from '@radix-ui/react-collapsible';

import * as styles from './section.css';

export const WorkspacePropertyListSidebarSection = () => {
  const t = useI18n();
  return (
    <div className={styles.headerRoot}>
      <span className={styles.headerTitle}>
        {t['com.cloud-notes.propertySidebar.property-list.section']()}
      </span>
      <CollapsibleTrigger asChild>
        <IconButton>
          <ToggleRightIcon className={styles.collapseIcon} />
        </IconButton>
      </CollapsibleTrigger>
    </div>
  );
};

export const AddWorkspacePropertySidebarSection = () => {
  const t = useI18n();
  return (
    <div className={styles.headerRoot}>
      <span className={styles.headerTitle}>
        {t['com.cloud-notes.propertySidebar.add-more.section']()}
      </span>
      <CollapsibleTrigger asChild>
        <IconButton>
          <ToggleRightIcon className={styles.collapseIcon} />
        </IconButton>
      </CollapsibleTrigger>
    </div>
  );
};
