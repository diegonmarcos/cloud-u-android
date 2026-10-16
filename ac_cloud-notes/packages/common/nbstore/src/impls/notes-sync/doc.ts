// deep imports on purpose: the sync-server suite loads this file with only yjs and
// eventemitter2 installed, so it must not pull the storage index (lodash, dummies).
import { AutoReconnectConnection } from '../../connection/connection';
import {
  type DocClock,
  type DocClocks,
  type DocRecord,
  DocStorageBase,
  type DocStorageOptions,
  type DocUpdate,
} from '../../storage/doc';

/**
 * The remote peer of a Cloud Notes workspace: our own sync server
 * (ac_cloud-notes/sync-server) over its plain HTTP delta API, so every request
 * can carry `Authorization: Bearer` (a browser or WebView WebSocket cannot).
 *
 *   getDocTimestamps  GET  /v1/docs?after=ms          the clocks the pull compares
 *   getDocSnapshot    GET  /v1/docs/<room>/diff       the whole doc (no state vector)
 *   pushDocUpdate     POST /v1/docs/<room>/updates    resolves AFTER the server stored it
 *
 * Live updates are a poll of the clock list: when another client changed a doc,
 * an update event with an empty payload makes the sync peer pull the diff (the
 * same device the AFFiNE cloud storage uses to repair invalidated docs).
 * `<room>` is `<spaceId>/<docId>`.
 */
export interface NotesSyncDocStorageOptions extends DocStorageOptions {
  /** e.g. https://notes-sync.example.com (no trailing slash needed) */
  serverBaseUrl: string;
  /**
   * a Bearer token, or a function returning a fresh one for every request. Absent: the getter
   * the host installed with configureNotesSyncToken (the worker does, from the fleet token
   * provider), so the token never travels through the serialized storage options.
   */
  token?: string | (() => string | Promise<string>);
  /** how often other clients' changes are looked for; 0 turns the poll off */
  pollMs?: number;
  fetch?: typeof fetch;
}

type TokenGetter = (serverBaseUrl: string) => string | Promise<string>;
let hostTokenGetter: TokenGetter | undefined;

/** The worker installs how a token is obtained for a sync server (the fleet token provider). */
export function configureNotesSyncToken(getter: TokenGetter) {
  hostTokenGetter = getter;
}

export class NotesSyncHttpError extends Error {
  constructor(
    readonly status: number,
    message: string
  ) {
    super(message);
    this.name = 'NotesSyncHttpError';
  }
}

class NotesSyncConnection extends AutoReconnectConnection<{ baseUrl: string }> {
  constructor(private readonly storage: NotesSyncDocStorage) {
    super();
  }

  protected override async doConnect(signal?: AbortSignal) {
    // the clock list needs a valid Bearer: connecting proves the token as well as the route
    await this.storage.request(
      `/v1/docs?after=${Date.now()}`,
      { signal },
      [200]
    );
    this.storage.startPolling();
    return { baseUrl: this.storage.baseUrl };
  }

  protected override doDisconnect(_conn: { baseUrl: string }) {
    this.storage.stopPolling();
  }
}

const b64url = (bytes: Uint8Array) => {
  let s = '';
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
};

const fromB64url = (text: string | null) => {
  if (!text) return new Uint8Array();
  const std = text.replace(/-/g, '+').replace(/_/g, '/');
  const bin = atob(std + '='.repeat((4 - (std.length % 4)) % 4));
  return Uint8Array.from(bin, c => c.charCodeAt(0));
};

export class NotesSyncDocStorage extends DocStorageBase<NotesSyncDocStorageOptions> {
  static readonly identifier = 'NotesSyncDocStorage';

  override readonly connection = new NotesSyncConnection(this);

  get baseUrl() {
    return this.options.serverBaseUrl.replace(/\/+$/, '');
  }

  private readonly clocks = new Map<string, number>(); // docId -> last clock we know of
  private pollTimer?: ReturnType<typeof setInterval>;
  private polling = false;
  private lastPoll = 0;

  private room(docId: string) {
    return encodeURIComponent(`${this.spaceId}/${docId}`);
  }

  private async bearer() {
    const t = this.options.token;
    if (typeof t === 'function') return await t();
    if (typeof t === 'string') return t;
    if (!hostTokenGetter) {
      throw new NotesSyncHttpError(401, 'no sync token provider configured');
    }
    return await hostTokenGetter(this.baseUrl);
  }

  /** @internal one authenticated request; throws NotesSyncHttpError for anything not in `ok` */
  async request(
    path: string,
    init: RequestInit = {},
    ok: number[] = [200]
  ): Promise<Response> {
    const doFetch = this.options.fetch ?? fetch;
    const res = await doFetch(`${this.baseUrl}${path}`, {
      ...init,
      headers: {
        ...(init.headers as Record<string, string> | undefined),
        authorization: `Bearer ${await this.bearer()}`,
      },
    });
    if (!ok.includes(res.status)) {
      throw new NotesSyncHttpError(
        res.status,
        `sync server answered ${res.status} for ${path.split('?')[0]}`
      );
    }
    return res;
  }

  override async pushDocUpdate(
    update: DocUpdate,
    _origin?: string
  ): Promise<DocClock> {
    const res = await this.request(
      `/v1/docs/${this.room(update.docId)}/updates`,
      {
        method: 'POST',
        headers: { 'content-type': 'application/octet-stream' },
        body: update.bin as unknown as BodyInit,
      },
      [204]
    );
    const clock = Number(res.headers.get('x-doc-clock')) || Date.now();
    // our own write: the poll must not report it back as somebody else's change
    this.clocks.set(update.docId, clock);
    return { docId: update.docId, timestamp: new Date(clock) };
  }

  override async getDocTimestamps(after?: Date): Promise<DocClocks> {
    const res = await this.request(
      `/v1/docs?after=${after ? after.getTime() : 0}`
    );
    const list = (await res.json()) as { name: string; updatedAt: number }[];
    const prefix = `${this.spaceId}/`;
    const out: DocClocks = {};
    for (const d of list) {
      if (!d.name.startsWith(prefix)) continue;
      out[d.name.slice(prefix.length)] = new Date(d.updatedAt);
    }
    return out;
  }

  override async getDocTimestamp(docId: string): Promise<DocClock | null> {
    const all = await this.getDocTimestamps();
    const timestamp = all[docId];
    return timestamp ? { docId, timestamp } : null;
  }

  /** The server keeps documents; trashing is a lifecycle update inside the doc, not a delete. */
  override async deleteDoc(_docId: string): Promise<void> {}

  protected override async getDocSnapshot(
    docId: string
  ): Promise<DocRecord | null> {
    const res = await this.request(
      `/v1/docs/${this.room(docId)}/diff`,
      {},
      [200, 404]
    );
    if (res.status === 404) return null;
    const clock = Number(res.headers.get('x-doc-clock')) || Date.now();
    this.clocks.set(docId, Math.max(this.clocks.get(docId) ?? 0, clock));
    return {
      docId,
      bin: new Uint8Array(await res.arrayBuffer()),
      timestamp: new Date(clock),
    };
  }

  /** What a client with `state` is missing, straight from the server (no full download). */
  override async getDocDiff(docId: string, state?: Uint8Array) {
    if (!state || state.length === 0) {
      return super.getDocDiff(docId, state);
    }
    const res = await this.request(
      `/v1/docs/${this.room(docId)}/diff?sv=${b64url(state)}`,
      {},
      [200, 404]
    );
    if (res.status === 404) return null;
    const clock = Number(res.headers.get('x-doc-clock')) || Date.now();
    return {
      docId,
      missing: new Uint8Array(await res.arrayBuffer()),
      // the server's own state: the peer pushes back only what the server lacks
      state: fromB64url(res.headers.get('x-state-vector')),
      timestamp: new Date(clock),
    };
  }

  // The server holds the whole document, so there is no snapshot/update queue to manage here.
  protected override async setDocSnapshot(): Promise<boolean> {
    return true;
  }
  protected override async getDocUpdates(): Promise<DocRecord[]> {
    return [];
  }
  protected override async markUpdatesMerged(): Promise<number> {
    return 0;
  }

  /** @internal */
  startPolling() {
    const every = this.options.pollMs ?? 5000;
    if (every <= 0 || this.pollTimer) return;
    this.lastPoll = Date.now();
    this.pollTimer = setInterval(() => {
      this.pollOnce().catch(() => {
        /* the connection's own retry reports reachability; a missed poll is retried */
      });
    }, every);
  }

  /** @internal */
  stopPolling() {
    if (this.pollTimer) clearInterval(this.pollTimer);
    this.pollTimer = undefined;
  }

  /** One look at the clock list; exposed so a test (or a "sync now" button) need not wait for the timer. */
  async pollOnce() {
    if (this.polling) return;
    this.polling = true;
    try {
      const clocks = await this.getDocTimestamps(new Date(this.lastPoll));
      for (const [docId, timestamp] of Object.entries(clocks)) {
        const t = timestamp.getTime();
        this.lastPoll = Math.max(this.lastPoll, t);
        if ((this.clocks.get(docId) ?? 0) >= t) continue; // our own write, or already seen
        this.clocks.set(docId, t);
        this.emit('update', { docId, bin: new Uint8Array(), timestamp });
      }
    } finally {
      this.polling = false;
    }
  }
}
