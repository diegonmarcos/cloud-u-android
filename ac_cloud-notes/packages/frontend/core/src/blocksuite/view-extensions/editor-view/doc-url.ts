import { generateUrl } from '@cloud-notes/core/components/hooks/cloud-notes/use-share-url';
import { WorkspaceServerService } from '@cloud-notes/core/modules/cloud';
import { resolveLinkToDoc } from '@cloud-notes/core/modules/navigation/utils';
import { WorkspaceService } from '@cloud-notes/core/modules/workspace';
import { type ReferenceParams } from '@blocksuite/cloud-notes/model';
import {
  GenerateDocUrlExtension,
  ParseDocUrlExtension,
} from '@blocksuite/cloud-notes/shared/services';
import type { FrameworkProvider } from '@toeverything/infra';

function patchParseDocUrlExtension(framework: FrameworkProvider) {
  const workspaceService = framework.get(WorkspaceService);
  const workspaceServerService = framework.get(WorkspaceServerService);
  const baseUrl = workspaceServerService.server?.baseUrl ?? location.origin;
  const ParseDocUrl = ParseDocUrlExtension({
    parseDocUrl(url) {
      const info = resolveLinkToDoc(url, baseUrl);
      if (!info || info.workspaceId !== workspaceService.workspace.id) return;

      delete info.refreshKey;

      return info;
    },
  });

  return ParseDocUrl;
}

function patchGenerateDocUrlExtension(framework: FrameworkProvider) {
  const workspaceService = framework.get(WorkspaceService);
  const workspaceServerService = framework.get(WorkspaceServerService);
  const baseUrl = workspaceServerService.server?.baseUrl ?? location.origin;
  const GenerateDocUrl = GenerateDocUrlExtension({
    generateDocUrl(pageId: string, params?: ReferenceParams) {
      return generateUrl({
        ...params,
        pageId,
        workspaceId: workspaceService.workspace.id,
        baseUrl,
      });
    },
  });

  return GenerateDocUrl;
}

export function patchDocUrlExtensions(framework: FrameworkProvider) {
  return [
    patchParseDocUrlExtension(framework),
    patchGenerateDocUrlExtension(framework),
  ];
}
