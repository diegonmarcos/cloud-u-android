import { Button } from '@cloud-notes/component/ui/button';
import { notify } from '@cloud-notes/component/ui/notification';
import { useAsyncCallback } from '@cloud-notes/core/components/hooks/cloud-notes-async-hooks';
import { AuthService, ServerService } from '@cloud-notes/core/modules/cloud';
import { UrlService } from '@cloud-notes/core/modules/url';
import { UserFriendlyError } from '@cloud-notes/error';
import { OAuthProviderType } from '@cloud-notes/graphql';
import track from '@cloud-notes/track';
import {
  AppleIcon,
  GithubIcon,
  GoogleIcon,
  LockIcon,
} from '@blocksuite/icons/rc';
import { useLiveData, useService } from '@toeverything/infra';
import { type ReactElement, type SVGAttributes, useCallback } from 'react';

const OAuthProviderMap: Record<
  OAuthProviderType,
  {
    icon: ReactElement<SVGAttributes<SVGElement>>;
  }
> = {
  [OAuthProviderType.Google]: {
    icon: <GoogleIcon />,
  },

  [OAuthProviderType.GitHub]: {
    icon: <GithubIcon />,
  },

  [OAuthProviderType.OIDC]: {
    icon: <LockIcon />,
  },

  [OAuthProviderType.Apple]: {
    icon: <AppleIcon />,
  },
};

export function OAuth({ redirectUrl }: { redirectUrl?: string }) {
  const serverService = useService(ServerService);
  const urlService = useService(UrlService);
  const oauth = useLiveData(serverService.server.features$.map(r => r?.oauth));
  const oauthProviders = useLiveData(
    serverService.server.config$.map(r => r?.oauthProviders)
  );
  const auth = useService(AuthService);

  const onContinue = useAsyncCallback(
    async (provider: OAuthProviderType) => {
      track.$.$.auth.signIn({ method: 'oauth', provider });

      const open: () => Promise<void> | void = BUILD_CONFIG.isNative
        ? async () => {
            try {
              const scheme = urlService.getClientScheme();
              const options = await auth.oauthPreflight(
                provider,
                scheme ?? 'web'
              );
              urlService.openPopupWindow(options.url);
            } catch (e) {
              notify.error(UserFriendlyError.fromAny(e));
            }
          }
        : () => {
            const params = new URLSearchParams();

            params.set('provider', provider);

            if (redirectUrl) {
              params.set('redirect_uri', redirectUrl);
            }

            params.set('flow', 'redirect');

            const oauthUrl =
              serverService.server.baseUrl +
              `/oauth/login?${params.toString()}`;

            urlService.openExternal(oauthUrl);
          };

      const ret = open();

      if (ret instanceof Promise) {
        await ret;
      }
    },
    [urlService, redirectUrl, serverService, auth]
  );

  if (!oauth) {
    return null;
  }

  return oauthProviders?.map(provider => {
    return (
      <OAuthProvider
        key={provider}
        provider={provider}
        onContinue={onContinue}
      />
    );
  });
}

interface OauthProviderProps {
  provider: OAuthProviderType;
  onContinue: (provider: OAuthProviderType) => void;
}

function OAuthProvider({ onContinue, provider }: OauthProviderProps) {
  const { icon } =
    provider in OAuthProviderMap
      ? OAuthProviderMap[provider]
      : { icon: undefined };

  const onClick = useCallback(() => {
    onContinue(provider);
  }, [onContinue, provider]);

  return (
    <Button
      variant={provider === OAuthProviderType.Apple ? 'custom' : 'primary'}
      block
      size="extraLarge"
      style={{ width: '100%' }}
      prefix={icon}
      onClick={onClick}
    >
      Continue with {provider}
    </Button>
  );
}
