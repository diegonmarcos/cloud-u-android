import type { FilterParams } from '@cloud-notes/core/modules/collection-rules';
import type { DocRecord } from '@cloud-notes/core/modules/doc';
import type { I18nString } from '@cloud-notes/i18n';
import {
  CloudWorkspaceIcon,
  DateTimeIcon,
  EdgelessIcon,
  FavoriteIcon,
  HistoryIcon,
  IntegrationsIcon,
  LongerIcon,
  MemberIcon,
  TagIcon,
  TemplateIcon,
  TitleIcon,
} from '@blocksuite/icons/rc';

import type { GroupHeaderProps } from '../explorer/types';
import { DateFilterMethod } from '../workspace-property-types';
import {
  CreateAtDocListProperty,
  CreatedAtFilterValue,
  CreatedAtGroupHeader,
  UpdatedAtDocListProperty,
  UpdatedAtFilterValue,
  UpdatedAtGroupHeader,
} from './created-updated-at';
import {
  CreatedByDocListInlineProperty,
  CreatedByUpdatedByFilterValue,
  ModifiedByGroupHeader,
  UpdatedByDocListInlineProperty,
} from './created-updated-by';
import {
  EdgelessThemeDocListProperty,
  EdgelessThemeFilterValue,
  EdgelessThemeGroupHeader,
} from './edgeless-theme';
import { FavoriteFilterValue } from './favorite';
import {
  IntegrationTypeDocListProperty,
  IntegrationTypeFilterValue,
  IntegrationTypeGroupHeader,
} from './integration-type';
import {
  PageWidthDocListProperty,
  PageWidthFilterValue,
  PageWidthGroupHeader,
} from './page-width';
import { SharedFilterValue } from './shared';
import { TagsDocListProperty, TagsFilterValue, TagsGroupHeader } from './tags';
import {
  TemplateDocListProperty,
  TemplateFilterValue,
  TemplateGroupHeader,
} from './template';

export const SystemPropertyTypes = {
  title: {
    icon: TitleIcon,
    name: 'Title',
    allowInOrderBy: true,
  },
  tags: {
    icon: TagIcon,
    name: 'Tags',
    filterMethod: {
      'include-all': 'com.cloud-notes.filter.contains all',
      'include-any-of': 'com.cloud-notes.filter.contains one of',
      'not-include-all': 'com.cloud-notes.filter.does not contains all',
      'not-include-any-of': 'com.cloud-notes.filter.does not contains one of',
      'is-not-empty': 'com.cloud-notes.filter.is not empty',
      'is-empty': 'com.cloud-notes.filter.is empty',
    },
    filterValue: TagsFilterValue,
    allowInGroupBy: true,
    allowInOrderBy: true,
    defaultFilter: { method: 'is-not-empty' },
    showInDocList: 'stack',
    docListProperty: TagsDocListProperty,
    groupHeader: TagsGroupHeader,
  },
  createdBy: {
    icon: MemberIcon,
    name: 'com.cloud-notes.page-properties.property.createdBy',
    allowInGroupBy: true,
    allowInOrderBy: true,
    filterMethod: {
      include: 'com.cloud-notes.filter.contains all',
    },
    filterValue: CreatedByUpdatedByFilterValue,
    defaultFilter: { method: 'include', value: '' },
    showInDocList: 'inline',
    docListProperty: CreatedByDocListInlineProperty,
    groupHeader: ModifiedByGroupHeader,
  },
  updatedBy: {
    icon: MemberIcon,
    name: 'com.cloud-notes.page-properties.property.updatedBy',
    allowInGroupBy: true,
    allowInOrderBy: true,
    filterMethod: {
      include: 'com.cloud-notes.filter.contains all',
    },
    filterValue: CreatedByUpdatedByFilterValue,
    defaultFilter: { method: 'include', value: '' },
    showInDocList: 'inline',
    docListProperty: UpdatedByDocListInlineProperty,
    groupHeader: ModifiedByGroupHeader,
  },
  updatedAt: {
    icon: DateTimeIcon,
    name: 'com.cloud-notes.page-properties.property.updatedAt',
    allowInGroupBy: true,
    allowInOrderBy: true,
    filterMethod: {
      ...DateFilterMethod,
    },
    filterValue: UpdatedAtFilterValue,
    defaultFilter: { method: 'this-week' },
    showInDocList: 'inline',
    docListProperty: UpdatedAtDocListProperty,
    groupHeader: UpdatedAtGroupHeader,
  },
  createdAt: {
    icon: HistoryIcon,
    name: 'com.cloud-notes.page-properties.property.createdAt',
    allowInGroupBy: true,
    allowInOrderBy: true,
    filterMethod: {
      ...DateFilterMethod,
    },
    filterValue: CreatedAtFilterValue,
    defaultFilter: { method: 'this-week' },
    showInDocList: 'inline',
    docListProperty: CreateAtDocListProperty,
    groupHeader: CreatedAtGroupHeader,
  },
  favorite: {
    icon: FavoriteIcon,
    name: 'Favorited',
    filterMethod: {
      is: 'com.cloud-notes.filter.is',
    },
    filterValue: FavoriteFilterValue,
  },
  shared: {
    icon: CloudWorkspaceIcon,
    name: 'Shared',
    filterMethod: {
      is: 'com.cloud-notes.filter.is',
    },
    filterValue: SharedFilterValue,
  },
  edgelessTheme: {
    icon: EdgelessIcon,
    name: 'com.cloud-notes.page-properties.property.edgelessTheme',
    showInDocList: 'stack',
    allowInGroupBy: true,
    allowInOrderBy: true,
    docListProperty: EdgelessThemeDocListProperty,
    groupHeader: EdgelessThemeGroupHeader,
    filterMethod: {
      is: 'com.cloud-notes.editCollection.rules.include.is',
      'is-not': 'com.cloud-notes.editCollection.rules.include.is-not',
    },
    filterValue: EdgelessThemeFilterValue,
    defaultFilter: { method: 'is', value: 'system' },
  },
  pageWidth: {
    icon: LongerIcon,
    name: 'com.cloud-notes.page-properties.property.pageWidth',
    showInDocList: 'stack',
    allowInGroupBy: true,
    allowInOrderBy: true,
    docListProperty: PageWidthDocListProperty,
    groupHeader: PageWidthGroupHeader,
    filterMethod: {
      is: 'com.cloud-notes.editCollection.rules.include.is',
      'is-not': 'com.cloud-notes.editCollection.rules.include.is-not',
    },
    filterValue: PageWidthFilterValue,
    defaultFilter: { method: 'is', value: 'fullWidth' },
  },
  template: {
    icon: TemplateIcon,
    name: 'com.cloud-notes.page-properties.property.template',
    showInDocList: 'stack',
    allowInGroupBy: true,
    allowInOrderBy: true,
    docListProperty: TemplateDocListProperty,
    groupHeader: TemplateGroupHeader,
    filterMethod: {
      is: 'com.cloud-notes.editCollection.rules.include.is',
      'is-not': 'com.cloud-notes.editCollection.rules.include.is-not',
    },
    filterValue: TemplateFilterValue,
    defaultFilter: { method: 'is', value: 'true' },
  },
  integrationType: {
    icon: IntegrationsIcon,
    name: 'com.cloud-notes.integration.integrations',
    showInDocList: 'stack',
    allowInGroupBy: true,
    allowInOrderBy: true,
    docListProperty: IntegrationTypeDocListProperty,
    groupHeader: IntegrationTypeGroupHeader,
    filterMethod: {
      is: 'com.cloud-notes.editCollection.rules.include.is',
      'is-not': 'com.cloud-notes.editCollection.rules.include.is-not',
    },
    filterValue: IntegrationTypeFilterValue,
    defaultFilter: { method: 'is', value: 'readwise' },
  },
} as {
  [type: string]: {
    icon: React.FC<React.SVGProps<SVGSVGElement>>;
    name: I18nString;

    allowInOrderBy?: boolean;
    allowInGroupBy?: boolean;
    filterMethod?: { [key: string]: I18nString };
    filterValue?: React.FC<{
      filter: FilterParams;
      isDraft?: boolean;
      onDraftCompleted?: () => void;
      onChange?: (filter: FilterParams) => void;
    }>;
    defaultFilter?: Omit<FilterParams, 'type' | 'key'>;
    /**
     * Whether to show the property in the doc list,
     * - `inline`: show the property in the doc list inline
     * - `stack`: show as tags
     */
    showInDocList?: 'inline' | 'stack';
    docListProperty?: React.FC<{ doc: DocRecord }>;
    groupHeader?: React.FC<GroupHeaderProps>;
  };
};

export type SystemPropertyType = keyof typeof SystemPropertyTypes;

export const isSupportedSystemPropertyType = (type?: string) => {
  return type ? type in SystemPropertyTypes : false;
};
