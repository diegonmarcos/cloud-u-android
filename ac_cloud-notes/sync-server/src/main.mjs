import { verifierFromEnv } from './auth.mjs';
import { createSyncServer } from './server.mjs';

const verify = verifierFromEnv(); // throws (exit 1) when no verifier is configured
const server = createSyncServer({
  port: Number(process.env.PORT ?? 1234),
  address: process.env.HOST ?? '0.0.0.0',
  database: process.env.SYNC_DB ?? '/data/sync.sqlite',
  verify,
  quiet: false,
});

await server.listen();
for (const sig of ['SIGTERM', 'SIGINT']) {
  process.on(sig, () => server.destroy().finally(() => process.exit(0)));
}
