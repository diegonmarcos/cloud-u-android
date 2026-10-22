import { z } from 'zod';

export const appSchemes = z.enum([
  'cloud-notes',
  'cloud-notes-canary',
  'cloud-notes-beta',
  'cloud-notes-internal',
  'cloud-notes-dev',
]);

export type Scheme = z.infer<typeof appSchemes>;
export type Channel = 'stable' | 'canary' | 'beta' | 'internal';

export const schemeToChannel = {
  cloudNotes: 'stable',
  'cloud-notes-canary': 'canary',
  'cloud-notes-beta': 'beta',
  'cloud-notes-internal': 'internal',
  'cloud-notes-dev': 'canary', // dev does not have a dedicated app. use canary as the placeholder.
} as Record<Scheme, Channel>;

export const channelToScheme = {
  stable: 'cloud-notes',
  canary: BUILD_CONFIG.debug ? 'cloud-notes-dev' : 'cloud-notes-canary',
  beta: 'cloud-notes-beta',
  internal: 'cloud-notes-internal',
} as Record<Channel, Scheme>;

export const appIconMap = {
  stable: '/imgs/app-icon-stable.ico',
  canary: '/imgs/app-icon-canary.ico',
  beta: '/imgs/app-icon-beta.ico',
  internal: '/imgs/app-icon-internal.ico',
} satisfies Record<Channel, string>;

export const appNames = {
  stable: 'Cloud Notes',
  canary: 'Cloud Notes Canary',
  beta: 'Cloud Notes Beta',
  internal: 'Cloud Notes Internal',
} satisfies Record<Channel, string>;

export const appSchemaUrl = z.custom<string>(
  (url: string) => {
    try {
      return appSchemes.safeParse(new URL(url).protocol.replace(':', ''))
        .success;
    } catch {
      return false;
    }
  },
  { message: 'Invalid URL or protocol' }
);
