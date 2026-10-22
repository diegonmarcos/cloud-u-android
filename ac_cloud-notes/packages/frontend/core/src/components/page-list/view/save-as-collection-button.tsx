import { Button, usePromptModal } from '@cloud-notes/component';
import { useI18n } from '@cloud-notes/i18n';
import { SaveIcon } from '@blocksuite/icons/rc';
import { useCallback } from 'react';

import * as styles from './save-as-collection-button.css';

interface SaveAsCollectionButtonProps {
  onConfirm: (collectionName: string) => void;
}

export const SaveAsCollectionButton = ({
  onConfirm,
}: SaveAsCollectionButtonProps) => {
  const t = useI18n();
  const { openPromptModal } = usePromptModal();
  const handleClick = useCallback(() => {
    openPromptModal({
      title: t['com.cloud-notes.editCollection.saveCollection'](),
      label: t['com.cloud-notes.editCollectionName.name'](),
      inputOptions: {
        placeholder: t['com.cloud-notes.editCollectionName.name.placeholder'](),
      },
      children: (
        <div className={styles.createTips}>
          {t['com.cloud-notes.editCollectionName.createTips']()}
        </div>
      ),
      confirmText: t['com.cloud-notes.editCollection.save'](),
      cancelText: t['com.cloud-notes.editCollection.button.cancel'](),
      confirmButtonOptions: {
        variant: 'primary',
      },
      onConfirm(name) {
        onConfirm(name);
      },
    });
  }, [openPromptModal, t, onConfirm]);
  return (
    <Button
      onClick={handleClick}
      data-testid="save-as-collection"
      prefix={<SaveIcon />}
      className={styles.button}
    >
      {t['com.cloud-notes.editCollection.saveCollection']()}
    </Button>
  );
};
