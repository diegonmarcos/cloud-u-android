import { WorkspaceScope } from '@cloud-notes/core/modules/workspace';
import { type Framework } from '@toeverything/infra';

import { MobileSearchService } from './service/search';

export { MobileSearchService };

export function configureMobileSearchModule(framework: Framework) {
  framework.scope(WorkspaceScope).service(MobileSearchService);
}
