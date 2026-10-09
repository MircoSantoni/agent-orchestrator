import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { createServer } from 'node:http';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { startDaemon } from '../src/daemon.mjs';
import { localRequest } from '../src/local.mjs';
import { RuntimeManager } from '../src/runtime.mjs';
import { sseMessages } from '../src/subscriptions.mjs';

const agentId = '550e8400-e29b-41d4-a716-446655440000';
const workspaceId = '550e8400-e29b-41d4-a716-446655440001';
let directory;
let remote;
let daemon;
let stream;
let inbox = [];
const wakes = [];

before(async () => {
  directory = await mkdtemp(join(tmpdir(), 'workspace-mcp-'));
  process.env.WORKSPACE_MCP_HOME = directory;
  remote = createServer(async (request, response) => {
    let data = '';
    for await (const chunk of request) data += chunk;
    const rpc = JSON.parse(data);
    if (rpc.method === 'subscriptions/listen') {
      stream = response;
      response.writeHead(200, { 'Content-Type': 'text/event-stream' });
      response.write('data: ' + JSON.stringify({ jsonrpc: '2.0', method: 'notifications/subscriptions/acknowledged',
        params: { notifications: { resourceSubscriptions: [rpc.params.notifications.resourceSubscriptions[0]] } } }) + '\n\n');
      return;
    }
    response.setHeader('Content-Type', 'application/json');
    const result = rpc.method === 'resources/read' ?
      { contents: [{ uri: rpc.params.uri, text: JSON.stringify({ messages: inbox }) }] } :
      rpc.method === 'tools/call' && rpc.params.name === 'connect_agent' ?
        { content: [{ type: 'text', text: JSON.stringify({ agentId, workspaceId }) }], isError: false } :
        rpc.method === 'initialize' ? { protocolVersion: '2025-11-25', capabilities: {} } :
          { content: [{ type: 'text', text: '{}'}], isError: false };
    response.end(JSON.stringify({ jsonrpc: '2.0', id: rpc.id, result }));
  });
  await new Promise(resolve => remote.listen(0, '127.0.0.1', resolve));
  const runtime = new RuntimeManager(async (binding, messages) => {
    wakes.push({ binding, messages });
    return 'session-1';
  });
  daemon = await startDaemon({
    config: { url: `http://127.0.0.1:${remote.address().port}/mcp`, token: 'test-token' },
    runtime, noSignalHandlers: true, log: () => {}
  });
});

after(async () => {
  await daemon?.close();
  stream?.end();
  await new Promise(resolve => remote?.close(resolve));
  await rm(directory, { recursive: true, force: true });
});

async function until(check) {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (check()) return;
    await new Promise(resolve => setTimeout(resolve, 20));
  }
  assert.fail('Timed out waiting for subscription');
}

test('stdio proxy preserves legacy MCP and binds connected agents without changing the result', async () => {
  const legacy = await localRequest(daemon.state, '/rpc', {
    request: { jsonrpc: '2.0', id: 1, method: 'initialize', params: {} }, cwd: directory
  });
  assert.equal(legacy.output.result.protocolVersion, '2025-11-25');
  const connected = await localRequest(daemon.state, '/rpc', {
    request: { jsonrpc: '2.0', id: 2, method: 'tools/call',
      params: { name: 'connect_agent', arguments: {} } }, cwd: directory
  });
  assert.equal(connected.output.result.isError, false);
  assert.equal((await localRequest(daemon.state, '/health')).agents, 1);
  await until(() => stream !== undefined);
});

test('MCP resource updates wake an agent once and ignore nonactionable messages', async () => {
  await until(() => stream !== undefined);
  inbox = [{ id: 'msg-1', type: 'DISCOVERY', status: 'PENDING' }];
  stream.write('data: ' + JSON.stringify({ method: 'notifications/resources/updated',
    params: { uri: `agent://${agentId}/inbox` } }) + '\n\n');
  await new Promise(resolve => setTimeout(resolve, 100));
  assert.equal(wakes.length, 0);
  inbox = [{ id: 'msg-2', type: 'REVIEW_REQUEST', status: 'PENDING' }, ...inbox];
  stream.write('data: ' + JSON.stringify({ method: 'notifications/resources/updated',
    params: { uri: `agent://${agentId}/inbox` } }) + '\n\n');
  await until(() => wakes.length === 1);
  assert.equal(wakes[0].messages[0].id, 'msg-2');
  stream.write('data: ' + JSON.stringify({ method: 'notifications/resources/updated',
    params: { uri: `agent://${agentId}/inbox` } }) + '\n\n');
  await new Promise(resolve => setTimeout(resolve, 100));
  assert.equal(wakes.length, 1);
});

test('after a broken stream, the supervisor refetches durable inbox messages', async () => {
  stream.end();
  stream = undefined;
  inbox = [{ id: 'msg-3', type: 'HELP_REQUEST', status: 'PENDING' }, ...inbox];
  await until(() => wakes.length === 2);
  assert.equal(wakes[1].messages[0].id, 'msg-3');
  await until(() => stream !== undefined);
});

test('local daemon requires its private secret', async () => {
  const response = await fetch(`http://127.0.0.1:${daemon.state.port}/health`);
  assert.equal(response.status, 401);
});

test('SSE parser handles frames split across chunks', async () => {
  const chunks = (async function* () {
    yield Buffer.from('data: {"method":"one"}\r');
    yield Buffer.from('\n\r\ndata: {"method":"two"}\n\n');
  })();
  const messages = [];
  for await (const value of sseMessages(chunks)) messages.push(value.method);
  assert.deepEqual(messages, ['one', 'two']);
});
