import { CollectionService } from '@cloud-notes/core/modules/collection';
import { DocsService } from '@cloud-notes/core/modules/doc';
import { DocsSearchService } from '@cloud-notes/core/modules/docs-search';
import { FavoriteService } from '@cloud-notes/core/modules/favorite';
import { GlobalContextService } from '@cloud-notes/core/modules/global-context';
import { OrganizeService } from '@cloud-notes/core/modules/organize';
import { GuardService } from '@cloud-notes/core/modules/permissions';
import { TagService } from '@cloud-notes/core/modules/tag';
import {
  WorkspaceScope,
  WorkspaceService,
} from '@cloud-notes/core/modules/workspace';
import type { Framework } from '@toeverything/infra';

import { MobileShellDataProjection } from './shell-data-projection';

export * from './navigation-projection';
export * from './shell-data-projection';

export function configureMobileNavigationProjection(framework: Framework) {
  framework
    .scope(WorkspaceScope)
    .service(MobileShellDataProjection, [
      DocsService,
      DocsSearchService,
      FavoriteService,
      GlobalContextService,
      GuardService,
      WorkspaceService,
      CollectionService,
      OrganizeService,
      TagService,
    ]);
}
