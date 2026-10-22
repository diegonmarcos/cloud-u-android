import { OverlayModal } from '@cloud-notes/component';
import { useI18n } from '@cloud-notes/i18n';

export const IssueFeedbackModal = ({
  open,
  setOpen,
}: {
  open: boolean;
  setOpen: (open: boolean) => void;
}) => {
  const t = useI18n();

  return (
    <OverlayModal
      open={open}
      topImage={
        <video
          width={400}
          height={300}
          style={{ objectFit: 'cover' }}
          src={'/static/newIssue.mp4'}
          autoPlay
          loop
        />
      }
      title={t['com.cloud-notes.issue-feedback.title']()}
      onOpenChange={setOpen}
      description={t['com.cloud-notes.issue-feedback.description']()}
      cancelText={t['com.cloud-notes.issue-feedback.cancel']()}
      to={`${BUILD_CONFIG.githubUrl}/issues/new/choose`}
      confirmText={t['com.cloud-notes.issue-feedback.confirm']()}
      confirmButtonOptions={{
        variant: 'primary',
      }}
      external
    />
  );
};
