import assert from 'node:assert/strict';
import { test } from 'node:test';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

test('one stdio installation starts a daemon that survives its MCP host', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'workspace-stdio-'));
  const remote = createServer(async (request, response) => {
    let input = '';
    for await (const chunk of request) input += chunk;
    const rpc = JSON.parse(input);
    response.setHeader('Content-Type', 'application/json');
    response.end(JSON.stringify({ jsonrpc: '2.0', id: rpc.id,
      result: { protocolVersion: '2025-11-25', capabilities: {} } }));
  });
  await new Promise(resolve => remote.listen(0, '127.0.0.1', resolve));
  let daemon;
  try {
    const cli = fileURLToPath(new URL('../src/cli.mjs', import.meta.url));
    const child = spawn(process.execPath, [cli, 'stdio'], {
      cwd: directory, env: { ...process.env, WORKSPACE_MCP_HOME: directory,
        WORKSPACE_MCP_URL: `http://127.0.0.1:${remote.address().port}/mcp`,
        WORKSPACE_MCP_TOKEN: 'test-token' }, stdio: ['pipe', 'pipe', 'pipe']
    });
    const line = new Promise((resolve, reject) => {
      let output = '';
      child.stdout.on('data', chunk => {
        output += chunk;
        if (output.includes('\n')) resolve(output.split('\n')[0]);
      });
      child.on('error', reject);
      child.on('exit', code => { if (!output.includes('\n')) reject(new Error(`stdio exited ${code}`)); });
    });
    child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: 7, method: 'initialize', params: {} }) + '\n');
    let timer;
    const response = JSON.parse(await Promise.race([line,
      new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('stdio timeout')), 10_000); })
    ]).finally(() => clearTimeout(timer)));
    assert.equal(response.id, 7);
    assert.equal(response.result.protocolVersion, '2025-11-25');
    child.stdin.end();
    await new Promise(resolve => child.once('exit', resolve));
    daemon = JSON.parse(await readFile(join(directory, 'daemon.json'), 'utf8'));
    const health = await fetch(`http://127.0.0.1:${daemon.port}/health`, {
      headers: { Authorization: `Bearer ${daemon.secret}` }
    });
    assert.equal(health.status, 200);
    await fetch(`http://127.0.0.1:${daemon.port}/stop`, {
      method: 'POST', headers: { Authorization: `Bearer ${daemon.secret}`,
        'Content-Type': 'application/json' }, body: '{}'
    });
  } finally {
    await new Promise(resolve => remote.close(resolve));
    await rm(directory, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 });
  }
});
