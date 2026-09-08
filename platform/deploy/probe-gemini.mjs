import { loadEnvFile } from 'node:process';
import { createAssistant } from '../server/src/assistant.mjs';
import { assistantRequest } from '../server/src/validation.mjs';

if (!process.argv[2]) throw new Error('Pass the private environment path');
loadEnvFile(process.argv[2]);
let providerStatus = null;
let providerErrorCode = null;
const assistant = createAssistant({
  geminiApiKey: process.env.GEMINI_API_KEY,
  geminiModel: process.env.GEMINI_MODEL || undefined,
  fetchImpl: async (...args) => {
    const response = await fetch(...args);
    providerStatus = response.status;
    if (!response.ok) providerErrorCode = (await response.clone().json().catch(() => ({}))).error?.status;
    return response;
  },
});
const request = assistantRequest({ message: 'ودني إلى نقطة اللقاء التجريبية', language: 'ar', context: {},
  destinations: [{ id: 'probe-meeting', name: 'نقطة اللقاء التجريبية', aliases: [] }], history: [] });
const result = await assistant(request);
console.log(JSON.stringify({ providerStatus, providerErrorCode, ...result }, null, 2));
if (result.provider !== 'gemini' || result.action.destinationId !== 'probe-meeting') process.exitCode = 1;
