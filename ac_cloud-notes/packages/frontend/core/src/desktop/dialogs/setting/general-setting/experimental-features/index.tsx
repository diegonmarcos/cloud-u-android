import { Button, Checkbox, Loading, Switch, Tooltip } from '@cloud-notes/component';
import { SettingHeader } from '@cloud-notes/component/setting-components';
import { useAsyncCallback } from '@cloud-notes/core/components/hooks/cloud-notes-async-hooks';
import {
  CLOUD_NOTES_FLAGS,
  FeatureFlagService,
  type Flag,
} from '@cloud-notes/core/modules/feature-flag';
import { useI18n } from '@cloud-notes/i18n';
import {
  ArrowRightSmallIcon,
  DiscordIcon,
  EmailIcon,
  GithubIcon,
} from '@blocksuite/icons/rc';
import { useLiveData, useServices } from '@toeverything/infra';
import { useAtom } from 'jotai';
import { atomWithStorage } from 'jotai/utils';
import { Suspense, useCallback, useState } from 'react';

import { ExperimentalFeatureArts } from './arts';
import * as styles from './index.css';

const ExperimentalFeaturesPrompt = ({
  onConfirm,
}: {
  onConfirm: () => void;
}) => {
  const t = useI18n();
  const [checked, setChecked] = useState(false);

  const onChange: (
    event: React.ChangeEvent<HTMLInputElement>,
    checked: boolean
  ) => void = useCallback((_, checked) => {
    setChecked(checked);
  }, []);

  return (
    <div className={styles.promptRoot} data-testid="experimental-prompt">
      <div className={styles.promptTitle}>
        {t[
          'com.cloud-notes.settings.workspace.experimental-features.prompt-header'
        ]()}
      </div>
      <div className={styles.promptArt}>
        <ExperimentalFeatureArts />
      </div>
      <div className={styles.promptWarning}>
        <div className={styles.promptWarningTitle}>
          {t[
            'com.cloud-notes.settings.workspace.experimental-features.prompt-warning-title'
          ]()}
        </div>
        {t[
          'com.cloud-notes.settings.workspace.experimental-features.prompt-warning'
        ]()}
      </div>

      <div className={styles.spacer} />

      <label className={styles.promptDisclaimer}>
        <Checkbox
          checked={checked}
          onChange={onChange}
          data-testid="experimental-prompt-disclaimer"
        />
        {t[
          'com.cloud-notes.settings.workspace.experimental-features.prompt-disclaimer'
        ]()}
      </label>

      <div className={styles.promptDisclaimerConfirm}>
        <Button
          disabled={!checked}
          onClick={onConfirm}
          variant="primary"
          data-testid="experimental-confirm-button"
        >
          {t[
            'com.cloud-notes.settings.workspace.experimental-features.get-started'
          ]()}
        </Button>
      </div>
    </div>
  );
};

const FeedbackIcon = ({ type }: { type: Flag['feedbackType'] }) => {
  switch (type) {
    case 'discord':
      return <DiscordIcon fontSize={16} />;
    case 'email':
      return <EmailIcon fontSize={16} />;
    case 'github':
      return <GithubIcon fontSize={16} />;
    default:
      return null;
  }
};

const feedbackLink: Record<NonNullable<Flag['feedbackType']>, string> = {
  discord: BUILD_CONFIG.discordUrl,
  email: 'mailto:support@toeverything.info',
  github: 'https://github.com/diegonmarcos/cloudy-u-android/issues',
};

const ExperimentalFeaturesItem = ({
  flag,
  flagKey,
}: {
  flag: Flag;
  flagKey: string;
}) => {
  const value = useLiveData(flag.$);
  const t = useI18n();
  const onChange = useCallback(
    (checked: boolean) => {
      flag.set(checked);
    },
    [flag]
  );
  const link = flag.feedbackType
    ? flag.feedbackLink
      ? flag.feedbackLink
      : feedbackLink[flag.feedbackType]
    : undefined;

  if (flag.configurable === false || flag.hide) {
    return null;
  }

  return (
    <div className={styles.rowContainer}>
      <div className={styles.switchRow}>
        {t[flag.displayName]()}
        <Switch data-testid={flagKey} checked={value} onChange={onChange} />
      </div>
      {!!flag.description && (
        <Tooltip content={t[flag.description]()}>
          <div className={styles.description}>{t[flag.description]()}</div>
        </Tooltip>
      )}
      {!!flag.feedbackType && (
        <a
          className={styles.feedback}
          href={link}
          target="_blank"
          rel="noreferrer"
        >
          <FeedbackIcon type={flag.feedbackType} />
          <span>Discussion about this feature</span>
          <ArrowRightSmallIcon
            fontSize={20}
            className={styles.arrowRightIcon}
          />
        </a>
      )}
    </div>
  );
};

const ExperimentalFeaturesMain = () => {
  const t = useI18n();
  const { featureFlagService } = useServices({ FeatureFlagService });

  return (
    <>
      <SettingHeader
        title={t[
          'com.cloud-notes.settings.workspace.experimental-features.header.plugins'
        ]()}
        subtitle={t[
          'com.cloud-notes.settings.workspace.experimental-features.header.subtitle'
        ]()}
      />
      <div
        className={styles.settingsContainer}
        data-testid="experimental-settings"
      >
        {Object.keys(CLOUD_NOTES_FLAGS).map(key => (
          <ExperimentalFeaturesItem
            key={key}
            flagKey={key}
            flag={featureFlagService.flags[key as keyof CLOUD_NOTES_FLAGS]}
          />
        ))}
      </div>
    </>
  );
};

// TODO(@Peng): save to workspace meta instead?
const experimentalFeaturesDisclaimerAtom = atomWithStorage(
  'cloud-notes:experimental-features-disclaimer',
  false
);

export const ExperimentalFeatures = () => {
  const [enabled, setEnabled] = useAtom(experimentalFeaturesDisclaimerAtom);
  const handleConfirm = useAsyncCallback(async () => {
    setEnabled(true);
  }, [setEnabled]);
  if (!enabled) {
    return <ExperimentalFeaturesPrompt onConfirm={handleConfirm} />;
  } else {
    return (
      <Suspense fallback={<Loading />}>
        <ExperimentalFeaturesMain />
      </Suspense>
    );
  }
};
