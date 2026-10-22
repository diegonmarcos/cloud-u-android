import { ConfirmModal, Input } from '@cloud-notes/component';
import type { Member } from '@cloud-notes/core/modules/permissions';
import { useI18n } from '@cloud-notes/i18n';
import { cssVar } from '@toeverything/theme';

import * as styles from './styles.css';

export const ConfirmAssignModal = ({
  open,
  setOpen,
  member,
  inputValue,
  placeholder,
  setInputValue,
  isEquals,
  onConfirm,
}: {
  open: boolean;
  setOpen: (value: boolean) => void;
  isEquals: boolean;
  member: Member;
  inputValue: string;
  placeholder?: string;
  onConfirm: () => void;
  setInputValue: (value: string) => void;
}) => {
  const t = useI18n();

  return (
    <ConfirmModal
      childrenContentClassName={styles.confirmAssignModalContent}
      open={open}
      onOpenChange={setOpen}
      title={t['com.cloud-notes.payment.member.team.assign.confirm.title']()}
      confirmText={t['com.cloud-notes.payment.member.team.assign.confirm.button']()}
      onConfirm={onConfirm}
      confirmButtonOptions={{ disabled: !isEquals, variant: 'error' }}
    >
      <div className={styles.confirmAssignModalContent}>
        <p>
          {t['com.cloud-notes.payment.member.team.assign.confirm.description']({
            name: member.name || member.email || member.id,
          })}
        </p>
        <div className={styles.descriptions}>
          <div className={styles.description}>
            <span className={styles.prefixDot} />
            <span>
              {t[
                'com.cloud-notes.payment.member.team.assign.confirm.description-1'
              ]()}
            </span>
          </div>
          <div className={styles.description}>
            <span className={styles.prefixDot} />
            <span>
              {t[
                'com.cloud-notes.payment.member.team.assign.confirm.description-2'
              ]()}
            </span>
          </div>
          <div className={styles.description}>
            <span className={styles.prefixDot} />
            <span>
              {t[
                'com.cloud-notes.payment.member.team.assign.confirm.description-3'
              ]()}
            </span>
          </div>
        </div>

        <div className={styles.confirmInputContainer}>
          {t['com.cloud-notes.payment.member.team.assign.confirm.description-4']()}
          <Input
            value={inputValue}
            inputStyle={{ fontSize: cssVar('fontSm') }}
            onChange={setInputValue}
            placeholder={placeholder}
          />
        </div>
      </div>
    </ConfirmModal>
  );
};
