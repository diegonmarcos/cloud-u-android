import { useI18n } from '@cloud-notes/i18n';

import tagsDark from './assets/tag-list.dark.png';
import tagsLight from './assets/tag-list.light.png';
import { EmptyLayout } from './layout';
import type { UniversalEmptyProps } from './types';

export const EmptyTags = (props: UniversalEmptyProps) => {
  const t = useI18n();

  return (
    <EmptyLayout
      illustrationLight={tagsLight}
      illustrationDark={tagsDark}
      title={t['com.cloud-notes.empty.tags.title']()}
      description={t['com.cloud-notes.empty.tags.description']()}
      {...props}
    />
  );
};
