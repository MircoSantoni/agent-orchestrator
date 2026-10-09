import { spawn } from 'node:child_process';
import { open, stat, unlink } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { mkdir } from 'node:fs/promises';
import { dataDir, file, readJson } from './files.mjs';

export async function localRequest(state, path, payload) {
  const response = await fetch(`http://127.0.0.1:${state.port}${path}`, {
    method: payload === undefined ? 'GET' : 'POST',
    headers: { Authorization: `Bearer ${state.secret}`, 'Content-Type': 'application/json' },
    body: payload === undefined ? undefined : JSON.stringify(payload),
    signal: AbortSignal.timeout(30_000)
  });
  const result = await response.json();
  if (!response.ok) throw new Error(result.error || `Local daemon HTTP ${response.status}`);
  return result;
}

export async function runningDaemon() {
  const state = await readJson('daemon.json', {});
  if (!state.port || !state.secret) return null;
  try { await localRequest(state, '/health'); return state; }
  catch { return null; }
}

export async function ensureDaemon() {
  const running = await runningDaemon();
  if (running) return running;
  await mkdir(dataDir(), { recursive: true, mode: 0o700 });
  let lock;
  try { lock = await open(file('startup.lock'), 'wx', 0o600); }
  catch (error) {
    if (error.code !== 'EEXIST') throw error;
    const age = Date.now() - (await stat(file('startup.lock')).catch(() => ({ mtimeMs: Date.now() }))).mtimeMs;
    if (age > 15_000) { await unlink(file('startup.lock')).catch(() => {}); return ensureDaemon(); }
  }
  let launchError;
  if (lock) {
    try {
      const child = spawn(process.execPath, [fileURLToPath(new URL('./cli.mjs', import.meta.url)), 'daemon'], {
        detached: true, stdio: 'ignore', windowsHide: true, env: process.env
      });
      child.on('error', error => { launchError = error; });
      child.unref();
    } catch (error) { launchError = error; }
  }
  try {
    for (let attempt = 0; attempt < 50; attempt++) {
      if (launchError) throw launchError;
      await new Promise(resolve => setTimeout(resolve, 100));
      const started = await runningDaemon();
      if (started) return started;
    }
    throw new Error('Local workspace daemon did not start');
  } finally {
    if (lock) { await lock.close(); await unlink(file('startup.lock')).catch(() => {}); }
  }
}
