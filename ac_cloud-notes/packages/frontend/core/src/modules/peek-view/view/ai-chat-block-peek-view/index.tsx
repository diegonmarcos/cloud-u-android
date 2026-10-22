import { toReactNode } from '@cloud-notes/component';
import { AIChatBlockPeekViewTemplate } from '@cloud-notes/core/blocksuite/ai';
import type { AIChatBlockModel } from '@cloud-notes/core/blocksuite/ai/blocks/ai-chat-block/model/ai-chat-model';
import { registerAIAppEffects } from '@cloud-notes/core/blocksuite/ai/effects/app';
import { useAIChatConfig } from '@cloud-notes/core/components/hooks/cloud-notes/use-ai-chat-config';
import { useAISubscribe } from '@cloud-notes/core/components/hooks/cloud-notes/use-ai-subscribe';
import {
  AIDraftService,
  AIModelService,
  AIToolsConfigService,
} from '@cloud-notes/core/modules/ai-button';
import { ServerService, SubscriptionService } from '@cloud-notes/core/modules/cloud';
import { WorkspaceDialogService } from '@cloud-notes/core/modules/dialogs';
import { FeatureFlagService } from '@cloud-notes/core/modules/feature-flag';
import type { EditorHost } from '@blocksuite/cloud-notes/std';
import { useFramework } from '@toeverything/infra';
import { useMemo } from 'react';

registerAIAppEffects();

export type AIChatBlockPeekViewProps = {
  model: AIChatBlockModel;
  host: EditorHost;
};

export const AIChatBlockPeekView = ({
  model,
  host,
}: AIChatBlockPeekViewProps) => {
  const { docDisplayConfig, searchMenuConfig, reasoningConfig } =
    useAIChatConfig();

  const framework = useFramework();
  const serverService = framework.get(ServerService);
  const cloudNotesFeatureFlagService = framework.get(FeatureFlagService);
  const cloudNotesWorkspaceDialogService = framework.get(WorkspaceDialogService);
  const aiDraftService = framework.get(AIDraftService);
  const aiToolsConfigService = framework.get(AIToolsConfigService);
  const aiModelService = framework.get(AIModelService);
  const subscriptionService = framework.get(SubscriptionService);
  const handleAISubscribe = useAISubscribe();

  return useMemo(() => {
    const template = AIChatBlockPeekViewTemplate(
      model,
      host,
      docDisplayConfig,
      searchMenuConfig,
      reasoningConfig,
      serverService,
      cloudNotesFeatureFlagService,
      cloudNotesWorkspaceDialogService,
      aiDraftService,
      aiToolsConfigService,
      aiModelService,
      subscriptionService,
      handleAISubscribe
    );
    return toReactNode(template);
  }, [
    model,
    host,
    docDisplayConfig,
    searchMenuConfig,
    reasoningConfig,
    serverService,
    cloudNotesFeatureFlagService,
    cloudNotesWorkspaceDialogService,
    aiDraftService,
    aiToolsConfigService,
    aiModelService,
    subscriptionService,
    handleAISubscribe,
  ]);
};
