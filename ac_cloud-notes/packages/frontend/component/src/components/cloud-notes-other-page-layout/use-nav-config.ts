import { useI18n } from '@cloud-notes/i18n';
import { useMemo } from 'react';

export const useNavConfig = () => {
  const t = useI18n();
  return useMemo(
    () => [
      {
        title: t['com.cloud-notes.other-page.nav.official-website'](),
        path: 'https://notes.diegonmarcos.com',
      },
      {
        title: t['com.cloud-notes.other-page.nav.blog'](),
        path: 'https://notes.diegonmarcos.com/blog',
      },
      {
        title: t['com.cloud-notes.other-page.nav.contact-us'](),
        path: 'https://notes.diegonmarcos.com/about-us',
      },
    ],
    [t]
  );
};
