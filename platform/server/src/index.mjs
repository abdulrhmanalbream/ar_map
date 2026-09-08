import { createPlatform } from './app.mjs';

const port = Number(process.env.PORT ?? 8080);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('PORT must be between 1 and 65535');

let platform;
try {
  platform = await createPlatform({
    dataDir: process.env.DATA_DIR ?? '/data',
    adminUsername: process.env.ADMIN_USERNAME,
    adminPassword: process.env.ADMIN_PASSWORD,
    apiKey: process.env.OPENAI_API_KEY?.trim() ?? '',
    model: process.env.OPENAI_MODEL?.trim() || 'gpt-4.1-mini',
    geminiApiKey: process.env.GEMINI_API_KEY?.trim() ?? '',
    geminiModel: process.env.GEMINI_MODEL?.trim() || 'gemini-3.8-flash',
    webRoot: process.env.WEB_ROOT,
    trustProxy: process.env.TRUST_PROXY === 'true',
  });
  platform.server.listen(port, '0.0.0.0', () => {
    console.log(`المطوف الذكي platform 3.0 listening on port ${port}; AI ${process.env.GEMINI_API_KEY?.trim() || process.env.OPENAI_API_KEY?.trim() ? 'configured' : 'not configured'}`);
  });
  platform.server.on('error', () => { console.error('Unable to bind the configured platform listener'); process.exitCode = 1; platform.close(); });
} catch (error) {
  const bootstrap = typeof error.message === 'string' && error.message.startsWith('First bootstrap requires');
  console.error(bootstrap ? error.message : 'المطوف الذكي startup failed. Check Node version, database permissions, and configuration.');
  process.exitCode = 1;
}

for (const signal of ['SIGTERM', 'SIGINT']) {
  process.once(signal, async () => {
    const deadline = setTimeout(() => process.exit(1), 15000);
    deadline.unref();
    await platform?.close();
    clearTimeout(deadline);
  });
}
