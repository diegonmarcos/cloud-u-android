import {
  MenuItem,
  MenuSeparator,
  MenuTrigger,
  Switch,
} from '@cloud-notes/component';
import {
  SettingRow,
  SettingWrapper,
} from '@cloud-notes/component/setting-components';
import { DocsService } from '@cloud-notes/core/modules/doc';
import { DocDisplayMetaService } from '@cloud-notes/core/modules/doc-display-meta';
import { TemplateDocService } from '@cloud-notes/core/modules/template-doc';
import { TemplateListMenu } from '@cloud-notes/core/modules/template-doc/view/template-list-menu';
import { useI18n } from '@cloud-notes/i18n';
import { DeleteIcon } from '@blocksuite/icons/rc';
import { useLiveData, useService } from '@toeverything/infra';
import { useCallback } from 'react';

import * as styles from './template.css';

export const TemplateDocSetting = () => {
  const t = useI18n();
  const setting = useService(TemplateDocService).setting;

  const enablePageTemplate = useLiveData(setting.enablePageTemplate$);
  const pageTemplateDocId = useLiveData(setting.pageTemplateDocId$);
  const journalTemplateDocId = useLiveData(setting.journalTemplateDocId$);

  const togglePageTemplate = useCallback(
    (enable: boolean) => {
      setting.togglePageTemplate(enable);
    },
    [setting]
  );

  const updatePageTemplate = useCallback(
    (id?: string) => {
      setting.updatePageTemplateDocId(id);
    },
    [setting]
  );

  const updateJournalTemplate = useCallback(
    (id?: string) => {
      setting.updateJournalTemplateDocId(id);
    },
    [setting]
  );

  return (
    <SettingWrapper title={t['com.cloud-notes.settings.workspace.template.title']()}>
      <SettingRow
        name={t['com.cloud-notes.settings.workspace.template.journal']()}
        desc={t['com.cloud-notes.settings.workspace.template.journal-desc']()}
      >
        <TemplateSelector
          testId="journal-template-selector"
          current={journalTemplateDocId}
          onChange={updateJournalTemplate}
        />
      </SettingRow>
      <SettingRow
        name={t['com.cloud-notes.settings.workspace.template.page']()}
        desc={t['com.cloud-notes.settings.workspace.template.page-desc']()}
      >
        <Switch
          data-testid="page-template-switch"
          checked={enablePageTemplate}
          onChange={togglePageTemplate}
        />
      </SettingRow>
      {enablePageTemplate ? (
        <SettingRow
          name={t['com.cloud-notes.settings.workspace.template.page-select']()}
          desc={null}
        >
          <TemplateSelector
            testId="page-template-selector"
            current={pageTemplateDocId}
            onChange={updatePageTemplate}
          />
        </SettingRow>
      ) : null}
    </SettingWrapper>
  );
};

interface TemplateSelectorProps {
  current?: string;
  testId?: string;
  onChange?: (id?: string) => void;
}
const TemplateSelector = ({
  current,
  testId,
  onChange,
}: TemplateSelectorProps) => {
  const t = useI18n();
  const docsService = useService(DocsService);
  const docDisplayService = useService(DocDisplayMetaService);
  const doc = useLiveData(current ? docsService.list.doc$(current) : null);
  const title = useLiveData(doc ? docDisplayService.title$(doc.id) : null);
  // const isInTrash = useLiveData(doc?.trash$);

  return (
    <TemplateListMenu
      onSelect={onChange}
      contentOptions={{ align: 'end' }}
      suffixItems={
        <>
          <MenuSeparator />
          <MenuItem
            prefixIcon={<DeleteIcon className={styles.menuItemIcon} />}
            onClick={() => onChange?.()}
            type="danger"
            data-testid="template-doc-item-remove"
          >
            {t['com.cloud-notes.settings.workspace.template.remove']()}
          </MenuItem>
        </>
      }
    >
      <MenuTrigger className={styles.menuTrigger} data-testid={testId}>
        {/* TODO: in trash design */}
        {title ?? t['com.cloud-notes.settings.workspace.template.keep-empty']()}
      </MenuTrigger>
    </TemplateListMenu>
  );
};
