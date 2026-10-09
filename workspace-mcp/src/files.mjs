import { homedir } from 'node:os';
import { join } from 'node:path';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';

export const dataDir = () => process.env.WORKSPACE_MCP_HOME || join(homedir(), '.agent-orchestrator');
export const file = name => join(dataDir(), name);

export async function readJson(name, fallback) {
  try { return JSON.parse(await readFile(file(name), 'utf8')); }
  catch (error) {
    if (error.code === 'ENOENT') return fallback;
    throw error;
  }
}

export async function writeJson(name, value) {
  await mkdir(dataDir(), { recursive: true, mode: 0o700 });
  const temporary = file(`${name}.${randomUUID()}.tmp`);
  await writeFile(temporary, JSON.stringify(value, null, 2) + '\n', { mode: 0o600 });
  await rename(temporary, file(name));
}

export async function configuration() {
  const saved = await readJson('config.json', {});
  const url = process.env.WORKSPACE_MCP_URL || saved.url;
  const token = process.env.WORKSPACE_MCP_TOKEN || saved.token;
  if (!url || !token) throw new Error('Configure WORKSPACE_MCP_URL and WORKSPACE_MCP_TOKEN, or run workspace-mcp configure');
  const parsed = new URL(url);
  if (!['https:', 'http:'].includes(parsed.protocol) || (parsed.protocol === 'http:' &&
      !['localhost', '127.0.0.1', '[::1]'].includes(parsed.hostname)))
    throw new Error('The remote MCP URL must use HTTPS');
  return { url: parsed.href, token };
}
