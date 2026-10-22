import type { apis, events } from '@cloud-notes/electron-api';

/**
 * Extends the global Window interface to include Cloud Notes's 
 * Electron bridge APIs and event emitters.
 */
declare global {
  interface Window {
    __apis?: {
      apis: typeof apis;
      events: typeof events;
    };
  }
}

export {};
