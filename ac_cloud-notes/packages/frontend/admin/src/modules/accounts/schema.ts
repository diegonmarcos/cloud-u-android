import type { FeatureType, ListUsersQuery } from '@cloud-notes/graphql';

export type UserType = ListUsersQuery['users'][0];
export type UserInput = {
  name: string;
  email: string;
  password?: string;
  features: FeatureType[];
};
