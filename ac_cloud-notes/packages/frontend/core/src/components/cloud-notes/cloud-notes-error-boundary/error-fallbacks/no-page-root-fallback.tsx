import { NoPageRootError } from '@cloud-notes/core/blocksuite/block-suite-editor/no-page-error';
import { useI18n } from '@cloud-notes/i18n';

import { ContactUS, ErrorDetail } from '../error-basic/error-detail';
import { createErrorFallback } from '../error-basic/fallback-creator';

export const NoPageRootFallback = createErrorFallback(
  NoPageRootError,
  props => {
    const { resetError } = props;
    const t = useI18n();

    return (
      <ErrorDetail
        title={t['com.cloud-notes.error.no-page-root.title']()}
        description={<ContactUS />}
        resetError={resetError}
      />
    );
  }
);
