import '@cloud-notes/core/bootstrap/electron';
import '@cloud-notes/core/bootstrap/cleanup';
import '@cloud-notes/component/theme';
import './global.css';
import './native-import';

import { apis } from '@cloud-notes/electron-api';
import { bindNativeDBApis } from '@cloud-notes/nbstore/sqlite';
import { bindNativeDBV1Apis } from '@cloud-notes/nbstore/sqlite/v1';

// oxlint-disable-next-line no-non-null-assertion
bindNativeDBApis(apis!.nbstore);
// oxlint-disable-next-line no-non-null-assertion
bindNativeDBV1Apis(apis!.db);
