import { Button } from '@cloud-notes/component';
import { useNavigateHelper } from '@cloud-notes/core/components/hooks/use-navigate-helper';
import { useI18n } from '@cloud-notes/i18n';

export const ImportTemplateButton = ({
  name,
  snapshotUrl,
}: {
  name: string;
  snapshotUrl: string;
}) => {
  const t = useI18n();
  const { jumpToImportTemplate } = useNavigateHelper();
  return (
    <Button
      variant="primary"
      onClick={() => jumpToImportTemplate(name, snapshotUrl)}
    >
      {t['com.cloud-notes.share-page.header.import-template']()}
    </Button>
  );
};
