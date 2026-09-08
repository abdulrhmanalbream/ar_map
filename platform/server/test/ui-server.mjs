// Explicit, local-only visual verification server. Never included in Docker.
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { randomBytes } from 'node:crypto';
import { createPlatform } from '../src/app.mjs';

if (process.env.SARAB_UI_TEST !== '1') throw new Error('Set SARAB_UI_TEST=1 for an ephemeral local UI test database');
const directory = await mkdtemp(join(tmpdir(), 'sarab-ui-test-'));
const credentials = { username: 'local-ui-guide', password: randomBytes(24).toString('base64url') };
const path = join(directory, 'credentials.json');
await writeFile(path, JSON.stringify(credentials), { mode: 0o600 });
const platform = await createPlatform({ dataDir: directory, adminUsername: credentials.username,
  adminPassword: credentials.password, webRoot: resolve('../web/dist') });
platform.server.listen(Number(process.env.PORT ?? 8080), '127.0.0.1', () => {
  console.log(`Ephemeral LOCAL UI server: http://127.0.0.1:${platform.server.address().port}`);
  console.log(`Credentials file (test-only): ${path}`);
});
for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, async () => {
  await platform.close();
  await rm(directory, { recursive: true, force: true });
});
