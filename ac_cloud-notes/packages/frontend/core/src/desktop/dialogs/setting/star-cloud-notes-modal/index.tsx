import { OverlayModal } from '@cloud-notes/component';
import { useI18n } from '@cloud-notes/i18n';

export const StarCloudNotesModal = ({
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
          src={'/static/githubStar.mp4'}
          autoPlay
          loop
        />
      }
      title={t['com.cloud-notes.star-cloud-notes.title']()}
      onOpenChange={setOpen}
      description={t['com.cloud-notes.star-cloud-notes.description']()}
      cancelText={t['com.cloud-notes.star-cloud-notes.cancel']()}
      to={BUILD_CONFIG.githubUrl}
      confirmButtonOptions={{
        variant: 'primary',
      }}
      confirmText={t['com.cloud-notes.star-cloud-notes.confirm']()}
      external
    />
  );
};
