import type {
  GroupByParams,
  OrderByParams,
} from '@cloud-notes/core/modules/collection-rules/types';
import type { DocCustomPropertyInfo } from '@cloud-notes/core/modules/db';
import type { DocRecord } from '@cloud-notes/core/modules/doc';

import type { DocListItemView } from './docs-view/doc-list-item';

export interface ExplorerDisplayPreference {
  view?: DocListItemView;
  groupBy?: GroupByParams;
  orderBy?: OrderByParams;
  displayProperties?: string[];
  showDocIcon?: boolean;
  showDocPreview?: boolean;
  showMoreOperation?: boolean;
  showDragHandle?: boolean;
  quickFavorite?: boolean;
  quickTrash?: boolean;
  quickSplit?: boolean;
  quickTab?: boolean;
  quickSelect?: boolean;
  quickDeletePermanently?: boolean;
  quickRestore?: boolean;
}

export interface DocListPropertyProps {
  value: any;
  doc: DocRecord;
  propertyInfo: DocCustomPropertyInfo;
}

export interface GroupHeaderProps {
  groupId: string;
  docCount: number;
  collapsed: boolean;
}
