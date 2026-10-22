import '@cloud-notes/core/bootstrap/electron';

import { apis } from '@cloud-notes/electron-api';
import { broadcastChannelStorages } from '@cloud-notes/nbstore/broadcast-channel';
import {
  cloudStorages,
  configureSocketAuthMethod,
} from '@cloud-notes/nbstore/cloud';
import { bindNativeDBApis, sqliteStorages } from '@cloud-notes/nbstore/sqlite';
import {
  bindNativeDBV1Apis,
  sqliteV1Storages,
} from '@cloud-notes/nbstore/sqlite/v1';
import {
  StoreManagerConsumer,
  type WorkerManagerOps,
} from '@cloud-notes/nbstore/worker/consumer';
import { OpConsumer } from '@toeverything/infra/op';

// oxlint-disable-next-line no-non-null-assertion
bindNativeDBApis(apis!.nbstore);
// oxlint-disable-next-line no-non-null-assertion
bindNativeDBV1Apis(apis!.db);
configureSocketAuthMethod((endpoint, cb) => {
  // oxlint-disable-next-line no-non-null-assertion
  apis!.auth
    .getValidAccessToken(endpoint)
    .then(({ token }: { token?: string | null }) => {
      cb(token ? { token, tokenType: 'jwt' } : {});
    })
    .catch(() => cb({ error: 'AUTH_SESSION_TEMPORARILY_UNAVAILABLE' }));
});

const storeManager = new StoreManagerConsumer([
  ...sqliteStorages,
  ...sqliteV1Storages,
  ...broadcastChannelStorages,
  ...cloudStorages,
]);

window.addEventListener('message', ev => {
  if (ev.data.type === 'electron:worker-connect') {
    const port = ev.ports[0];

    const consumer = new OpConsumer<WorkerManagerOps>(port);
    storeManager.bindConsumer(consumer);
  }
});
