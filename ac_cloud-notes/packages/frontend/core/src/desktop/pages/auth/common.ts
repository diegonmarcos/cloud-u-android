import { z } from 'zod';

export const supportedClient = z.enum([
  'web',
  'cloud-notes',
  'cloud-notes-canary',
  'cloud-notes-beta',
  ...(BUILD_CONFIG.debug ? ['cloud-notes-dev'] : []),
]);
