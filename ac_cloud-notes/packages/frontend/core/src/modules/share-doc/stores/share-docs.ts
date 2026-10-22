import type { WorkspaceServerService } from '@cloud-notes/core/modules/cloud';
import { getWorkspacePublicPagesQuery } from '@cloud-notes/graphql';
import { Store } from '@toeverything/infra';

export class ShareDocsStore extends Store {
  constructor(private readonly workspaceServerService: WorkspaceServerService) {
    super();
  }

  async getWorkspacesShareDocs(workspaceId: string, signal?: AbortSignal) {
    if (!this.workspaceServerService.server) {
      throw new Error('No Server');
    }
    const data = await this.workspaceServerService.server.gql({
      query: getWorkspacePublicPagesQuery,
      variables: {
        workspaceId: workspaceId,
      },
      context: {
        signal,
      },
    });
    return data.workspace.publicDocs;
  }
}
