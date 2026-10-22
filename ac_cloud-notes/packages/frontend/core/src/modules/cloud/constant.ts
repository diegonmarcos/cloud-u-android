import {
  OAuthProviderType,
  ServerDeploymentType,
  ServerFeature,
} from '@cloud-notes/graphql';

import { DEFAULT_SELF_HOSTED_SERVER_NAME } from './server-name';
import type { ServerConfig, ServerMetadata } from './types';

export const BUILD_IN_SERVERS: (ServerMetadata & { config: ServerConfig })[] =
  environment.isSelfHosted
    ? [
        {
          id: 'cloud-notes-cloud',
          baseUrl: location.origin,
          // selfhosted baseUrl is `location.origin`
          // this is ok for web app, but not for desktop app
          // since we never build desktop app in selfhosted mode, so it's fine
          config: {
            serverName: DEFAULT_SELF_HOSTED_SERVER_NAME,
            features: [],
            oauthProviders: [],
            type: ServerDeploymentType.Selfhosted,
            credentialsRequirement: {
              password: {
                minLength: 8,
                maxLength: 32,
              },
            },
          },
        },
      ]
    : BUILD_CONFIG.debug
      ? [
          {
            id: 'cloud-notes-cloud',
            baseUrl: BUILD_CONFIG.isElectron
              ? 'http://localhost:8080'
              : location.origin,
            config: {
              serverName: 'Cloud Notes',
              features: [
                ServerFeature.Indexer,
                ServerFeature.Copilot,
                ServerFeature.CopilotEmbedding,
                ServerFeature.OAuth,
                ServerFeature.Payment,
                ServerFeature.LocalWorkspace,
              ],
              oauthProviders: [
                OAuthProviderType.Google,
                OAuthProviderType.Apple,
              ],
              type: ServerDeploymentType.CloudNotes,
              credentialsRequirement: {
                password: {
                  minLength: 8,
                  maxLength: 32,
                },
              },
            },
          },
        ]
      : BUILD_CONFIG.appBuildType === 'stable'
        ? [
            {
              id: 'cloud-notes-cloud',
              baseUrl: BUILD_CONFIG.isNative
                ? BUILD_CONFIG.isIOS
                  ? 'https://apple.getcloudnotesapp.com'
                  : 'https://notes.diegonmarcos.com'
                : location.origin,
              config: {
                serverName: 'Cloud Notes',
                features: [
                  ServerFeature.Indexer,
                  ServerFeature.Copilot,
                  ServerFeature.CopilotEmbedding,
                  ServerFeature.OAuth,
                  ServerFeature.Payment,
                  ServerFeature.LocalWorkspace,
                ],
                oauthProviders: [
                  OAuthProviderType.Google,
                  OAuthProviderType.Apple,
                ],
                type: ServerDeploymentType.CloudNotes,
                credentialsRequirement: {
                  password: {
                    minLength: 8,
                    maxLength: 32,
                  },
                },
              },
            },
          ]
        : BUILD_CONFIG.appBuildType === 'beta'
          ? [
              {
                id: 'cloud-notes-cloud',
                baseUrl: BUILD_CONFIG.isNative
                  ? BUILD_CONFIG.isIOS
                    ? 'https://apple.getcloudnotesapp.com'
                    : 'https://notes.diegonmarcos.com'
                  : location.origin,
                config: {
                  serverName: 'Cloud Notes',
                  features: [
                    ServerFeature.Indexer,
                    ServerFeature.Copilot,
                    ServerFeature.CopilotEmbedding,
                    ServerFeature.OAuth,
                    ServerFeature.Payment,
                    ServerFeature.LocalWorkspace,
                  ],
                  oauthProviders: [
                    OAuthProviderType.Google,
                    OAuthProviderType.Apple,
                  ],
                  type: ServerDeploymentType.CloudNotes,
                  credentialsRequirement: {
                    password: {
                      minLength: 8,
                      maxLength: 32,
                    },
                  },
                },
              },
            ]
          : BUILD_CONFIG.appBuildType === 'internal'
            ? [
                {
                  id: 'cloud-notes-cloud',
                  baseUrl: 'https://notes.diegonmarcos.com',
                  config: {
                    serverName: 'Cloud Notes',
                    features: [
                      ServerFeature.Indexer,
                      ServerFeature.Copilot,
                      ServerFeature.CopilotEmbedding,
                      ServerFeature.OAuth,
                      ServerFeature.Payment,
                      ServerFeature.LocalWorkspace,
                    ],
                    oauthProviders: [
                      OAuthProviderType.Google,
                      OAuthProviderType.Apple,
                    ],
                    type: ServerDeploymentType.CloudNotes,
                    credentialsRequirement: {
                      password: {
                        minLength: 8,
                        maxLength: 32,
                      },
                    },
                  },
                },
              ]
            : BUILD_CONFIG.appBuildType === 'canary'
              ? [
                  {
                    id: 'cloud-notes-cloud',
                    baseUrl: BUILD_CONFIG.isNative
                      ? 'https://notes.diegonmarcos.com'
                      : location.origin,
                    config: {
                      serverName: 'Cloud Notes',
                      features: [
                        ServerFeature.Indexer,
                        ServerFeature.Copilot,
                        ServerFeature.CopilotEmbedding,
                        ServerFeature.OAuth,
                        ServerFeature.Payment,
                        ServerFeature.LocalWorkspace,
                      ],
                      oauthProviders: [
                        OAuthProviderType.Google,
                        OAuthProviderType.Apple,
                      ],
                      type: ServerDeploymentType.CloudNotes,
                      credentialsRequirement: {
                        password: {
                          minLength: 8,
                          maxLength: 32,
                        },
                      },
                    },
                  },
                ]
              : [];

export type TelemetryChannel =
  | 'stable'
  | 'beta'
  | 'internal'
  | 'canary'
  | 'local';

const OFFICIAL_TELEMETRY_ENDPOINTS: Record<TelemetryChannel, string> = {
  stable: 'https://notes.diegonmarcos.com',
  beta: 'https://notes.diegonmarcos.com',
  internal: 'https://notes.diegonmarcos.com',
  canary: 'https://notes.diegonmarcos.com',
  local: 'http://localhost:8080',
};

export function getOfficialTelemetryEndpoint(
  channel = BUILD_CONFIG.appBuildType
): string {
  if (BUILD_CONFIG.debug) {
    return BUILD_CONFIG.isNative
      ? OFFICIAL_TELEMETRY_ENDPOINTS.local
      : location.origin;
  } else if (['beta', 'internal', 'canary', 'stable'].includes(channel)) {
    return OFFICIAL_TELEMETRY_ENDPOINTS[channel];
  }

  return OFFICIAL_TELEMETRY_ENDPOINTS.stable;
}
