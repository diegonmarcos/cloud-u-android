import {
  Button,
  Divider,
  Menu,
  PropertyCollapsibleContent,
  PropertyCollapsibleSection,
} from '@cloud-notes/component';
import { BacklinkGroups } from '@cloud-notes/core/blocksuite/block-suite-editor/bi-directional-link-panel';
import { CreatePropertyMenuItems } from '@cloud-notes/core/components/properties/menu/create-doc-property';
import { WorkspacePropertyRow } from '@cloud-notes/core/components/properties/table';
import type { DocCustomPropertyInfo } from '@cloud-notes/core/modules/db';
import { DocDatabaseBacklinkInfo } from '@cloud-notes/core/modules/doc-info';
import type {
  DatabaseRow,
  DatabaseValueCell,
} from '@cloud-notes/core/modules/doc-info/types';
import { DocLinksService } from '@cloud-notes/core/modules/doc-link';
import { GuardService } from '@cloud-notes/core/modules/permissions';
import { WorkspacePropertyService } from '@cloud-notes/core/modules/workspace-property';
import { useI18n } from '@cloud-notes/i18n';
import track from '@cloud-notes/track';
import { PlusIcon } from '@blocksuite/icons/rc';
import { useLiveData, useServices } from '@toeverything/infra';
import { useCallback, useEffect, useState } from 'react';

import * as styles from './info-modal.css';
import { LinksRow } from './links-row';

export const InfoTable = ({
  onClose,
  docId,
}: {
  docId: string;
  onClose: () => void;
}) => {
  const t = useI18n();
  const { workspacePropertyService, guardService, docLinksService } =
    useServices({
      WorkspacePropertyService,
      GuardService,
      DocLinksService,
    });
  const canEditPropertyInfo = useLiveData(
    guardService.can$('Workspace_Properties_Update')
  );
  const canEditProperty = useLiveData(guardService.can$('Doc_Update', docId));
  const [newPropertyId, setNewPropertyId] = useState<string | null>(null);
  const properties = useLiveData(workspacePropertyService.sortedProperties$);
  const links = useLiveData(docLinksService.links.links$);

  const backlinks = useLiveData(docLinksService.backlinks.backlinks$);

  const onBacklinkPropertyChange = useCallback(
    (_row: DatabaseRow, cell: DatabaseValueCell, _value: unknown) => {
      track.$.docInfoPanel.databaseProperty.editProperty({
        type: cell.property.type$.value,
      });
    },
    []
  );

  const onPropertyAdded = useCallback((property: DocCustomPropertyInfo) => {
    setNewPropertyId(property.id);
    track.$.docInfoPanel.property.addProperty({
      type: property.type,
      control: 'at menu',
    });
  }, []);

  const onPropertyChange = useCallback(
    (property: DocCustomPropertyInfo, _value: unknown) => {
      track.$.docInfoPanel.property.editProperty({
        type: property.type,
      });
    },
    []
  );

  const onPropertyInfoChange = useCallback(
    (
      property: DocCustomPropertyInfo,
      field: keyof DocCustomPropertyInfo,
      _value: string
    ) => {
      track.$.docInfoPanel.property.editPropertyMeta({
        type: property.type,
        field,
      });
    },
    []
  );

  useEffect(() => {
    docLinksService.backlinks.revalidateFromCloud();
  }, [docLinksService.backlinks]);

  return (
    <>
      <PropertyCollapsibleSection
        title={t.t('com.cloud-notes.workspace.properties')}
      >
        <PropertyCollapsibleContent
          className={styles.tableBodyRoot}
          collapseButtonText={({ hide, isCollapsed }) =>
            isCollapsed
              ? hide === 1
                ? t['com.cloud-notes.page-properties.more-property.one']({
                    count: hide.toString(),
                  })
                : t['com.cloud-notes.page-properties.more-property.more']({
                    count: hide.toString(),
                  })
              : hide === 1
                ? t['com.cloud-notes.page-properties.hide-property.one']({
                    count: hide.toString(),
                  })
                : t['com.cloud-notes.page-properties.hide-property.more']({
                    count: hide.toString(),
                  })
          }
        >
          {properties.map(property => (
            <WorkspacePropertyRow
              key={property.id}
              propertyInfo={property}
              readonly={!canEditProperty}
              propertyInfoReadonly={!canEditPropertyInfo}
              defaultOpenEditMenu={newPropertyId === property.id}
              onChange={value => onPropertyChange(property, value)}
              onPropertyInfoChange={(...args) =>
                onPropertyInfoChange(property, ...args)
              }
            />
          ))}
          {!canEditPropertyInfo ? (
            <Button
              disabled
              variant="plain"
              prefix={<PlusIcon />}
              className={styles.addPropertyButton}
            >
              {t['com.cloud-notes.page-properties.add-property']()}
            </Button>
          ) : (
            <Menu
              items={<CreatePropertyMenuItems onCreated={onPropertyAdded} />}
              contentOptions={{
                onClick(e) {
                  e.stopPropagation();
                },
              }}
            >
              <Button
                variant="plain"
                prefix={<PlusIcon />}
                className={styles.addPropertyButton}
              >
                {t['com.cloud-notes.page-properties.add-property']()}
              </Button>
            </Menu>
          )}
        </PropertyCollapsibleContent>
      </PropertyCollapsibleSection>
      <Divider size="thinner" />
      <DocDatabaseBacklinkInfo onChange={onBacklinkPropertyChange} />
      {backlinks && backlinks.length > 0 ? (
        <>
          <LinksRow
            count={backlinks.length}
            references={<BacklinkGroups />}
            onClick={onClose}
            label={t['com.cloud-notes.page-properties.backlinks']()}
          />
          <Divider size="thinner" />
        </>
      ) : null}
      {links && links.length > 0 ? (
        <>
          <LinksRow
            count={links.length}
            references={links}
            onClick={onClose}
            label={t['com.cloud-notes.page-properties.outgoing-links']()}
          />
          <Divider size="thinner" />
        </>
      ) : null}
    </>
  );
};
