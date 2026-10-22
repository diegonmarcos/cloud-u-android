import { SettingHeader } from '@cloud-notes/component/setting-components';
import { useWorkspaceInfo } from '@cloud-notes/core/components/hooks/use-workspace-info';
import { WorkspaceService } from '@cloud-notes/core/modules/workspace';
import { useI18n } from '@cloud-notes/i18n';
import { useService } from '@toeverything/infra';
import {
  type ReactNode,
  useCallback,
  useEffect,
  useMemo,
  useState,
} from 'react';

import { CALENDAR_INTEGRATION_SCROLL_ANCHOR } from '../../navigation-constants';
import { SubPageProvider, useSubPageIsland } from '../../sub-page';
import {
  IntegrationCard,
  IntegrationCardContent,
  IntegrationCardHeader,
} from './card';
import { getAllowedIntegrationList } from './constants';
import { type IntegrationItem } from './constants';
import { list } from './index.css';

export const IntegrationSetting = ({
  scrollAnchor,
}: {
  scrollAnchor?: string;
}) => {
  const t = useI18n();
  const [opened, setOpened] = useState<string | null>(null);
  const workspaceService = useService(WorkspaceService);
  const info = useWorkspaceInfo(workspaceService.workspace);
  const isCloudWorkspace = workspaceService.workspace.flavour !== 'local';
  const showByok = isCloudWorkspace && !!(info?.isOwner || info?.isAdmin);

  const integrationList = useMemo(
    () => getAllowedIntegrationList(isCloudWorkspace, showByok),
    [isCloudWorkspace, showByok]
  );

  useEffect(() => {
    if (scrollAnchor !== CALENDAR_INTEGRATION_SCROLL_ANCHOR) {
      return;
    }

    const hasCalendarSetting = integrationList.some(
      item => item.id === 'calendar' && 'setting' in item
    );
    if (hasCalendarSetting) {
      setOpened('calendar');
    }
  }, [integrationList, scrollAnchor]);

  const handleCardClick = useCallback((card: IntegrationItem) => {
    if ('setting' in card && card.setting) {
      setOpened(card.id);
    }
  }, []);

  return (
    <>
      <SettingHeader
        title={t['com.cloud-notes.integration.integrations']()}
        subtitle={
          <>
            {t['com.cloud-notes.integration.setting.description']()}
            {/* <br /> */}
            {/* <a>{t['Learn how to develop a integration for Cloud Notes']()}</a> */}
          </>
        }
      />
      <ul className={list}>
        {integrationList.map(item => {
          const title =
            typeof item.name === 'string'
              ? t[item.name]()
              : t[item.name.i18nKey]();
          const desc =
            typeof item.desc === 'string'
              ? t[item.desc]()
              : t[item.desc.i18nKey]();
          return (
            <li key={item.id}>
              <IntegrationCard
                onClick={() => handleCardClick(item)}
                link={'link' in item ? item.link : undefined}
              >
                <IntegrationCardHeader icon={item.icon} title={title} />
                <IntegrationCardContent desc={desc} />
              </IntegrationCard>

              {'setting' in item && item.setting ? (
                <IntegrationSettingPage
                  open={opened === item.id}
                  onClose={() => setOpened(null)}
                >
                  {item.setting}
                </IntegrationSettingPage>
              ) : null}
            </li>
          );
        })}
      </ul>
    </>
  );
};

const IntegrationSettingPage = ({
  children,
  open,
  onClose,
}: {
  children: ReactNode;
  open: boolean;
  onClose: () => void;
}) => {
  const t = useI18n();
  const island = useSubPageIsland();

  if (!island) {
    return null;
  }

  return (
    <SubPageProvider
      backText={t['com.cloud-notes.integration.integrations']()}
      island={island}
      open={open}
      onClose={onClose}
    >
      {children}
    </SubPageProvider>
  );
};
