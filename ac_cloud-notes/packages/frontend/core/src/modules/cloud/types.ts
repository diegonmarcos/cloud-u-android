import type {
  CredentialsRequirementType,
  OAuthProviderType,
  ServerDeploymentType,
  ServerFeature,
} from '@cloud-notes/graphql';

export interface ServerMetadata {
  id: string;

  baseUrl: string;
}

export interface ServerConfig {
  serverName: string;
  features: ServerFeature[];
  oauthProviders: OAuthProviderType[];
  type: ServerDeploymentType;
  initialized?: boolean;
  version?: string;
  credentialsRequirement: CredentialsRequirementType;
}
