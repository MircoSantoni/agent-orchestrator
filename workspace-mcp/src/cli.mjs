#!/usr/bin/env node
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { startDaemon } from './daemon.mjs';
import { configuration, readJson, writeJson } from './files.mjs';
import { ensureDaemon, localRequest, runningDaemon } from './local.mjs';
import { serveStdio } from './stdio.mjs';

function hiddenToken() {
  if (!process.stdin.isTTY || !process.stdin.setRawMode)
    throw new Error('Set WORKSPACE_MCP_TOKEN when configuring without an interactive terminal');
  return new Promise((resolve, reject) => {
    let value = '';
    const done = (error) => {
      process.stdin.off('data', onData);
      process.stdin.setRawMode(false);
      process.stdin.pause();
      process.stderr.write('\n');
      error ? reject(error) : resolve(value);
    };
    const onData = chunk => {
      for (const byte of chunk) {
        if (byte === 13 || byte === 10) { done(); return; }
        if (byte === 3) { done(new Error('Cancelled')); return; }
        if (byte === 8 || byte === 127) value = value.slice(0, -1);
        else value += String.fromCharCode(byte);
      }
    };
    process.stderr.write('MCP credential (input hidden): ');
    process.stdin.setRawMode(true);
    process.stdin.resume();
    process.stdin.on('data', onData);
  });
}

async function addClaudeServer() {
  await configuration();
  const args = ['mcp', 'add', '--scope', 'user', '--transport', 'stdio', 'agent-orchestrator',
    '--', 'node', fileURLToPath(import.meta.url), 'stdio'];
  const code = await new Promise((resolve, reject) => {
    const child = spawn('claude', args, { stdio: 'inherit', shell: false });
    child.on('error', reject);
    child.on('close', resolve);
  });
  if (code !== 0) throw new Error(`Claude MCP registration exited ${code}`);
}

async function main() {
  const [command, ...args] = process.argv.slice(2);
  if (command === 'stdio') return serveStdio();
  if (command === 'daemon') {
    if (await runningDaemon()) return;
    await startDaemon(); return;
  }
  if (command === 'status') {
    const state = await runningDaemon();
    console.log(state ? JSON.stringify(await localRequest(state, '/health')) : 'stopped');
    return;
  }
  if (command === 'stop') {
    const state = await runningDaemon();
    if (state) await localRequest(state, '/stop', {});
    return;
  }
  if (command === 'bind') {
    const [agentId, workspaceId, cwd = process.cwd()] = args;
    if (!agentId || !workspaceId) throw new Error('Usage: workspace-mcp bind <agentId> <workspaceId> [directory]');
    await localRequest(await ensureDaemon(), '/bind', { agentId, workspaceId, cwd });
    console.log('Agent bound to local directory');
    return;
  }
  if (command === 'configure') {
    const url = args[0];
    if (!url) throw new Error('Usage: workspace-mcp configure <https://host/mcp>');
    const parsed = new URL(url);
    if (parsed.protocol !== 'https:' && !(parsed.protocol === 'http:' &&
        ['localhost', '127.0.0.1', '[::1]'].includes(parsed.hostname)))
      throw new Error('The remote MCP URL must use HTTPS');
    const token = process.env.WORKSPACE_MCP_TOKEN || await hiddenToken();
    if (!/^ao_[0-9a-f-]{36}_[A-Za-z0-9_-]{43}$/.test(token))
      throw new Error('Invalid MCP credential format');
    const saved = await readJson('config.json', {});
    await writeJson('config.json', { ...saved, url: parsed.href, token });
    console.log('Configuration saved for this operating-system user');
    return;
  }
  if (command === 'install') { await addClaudeServer(); return; }
  console.log('Usage: workspace-mcp <configure|install|stdio|daemon|status|stop|bind>');
}

main().catch(error => { console.error(`workspace-mcp: ${error.message}`); process.exitCode = 1; });
