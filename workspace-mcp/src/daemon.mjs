import { createServer } from 'node:http';
import { randomBytes, timingSafeEqual } from 'node:crypto';
import { appendFile, stat } from 'node:fs/promises';
import { configuration, file, readJson, writeJson } from './files.mjs';
import { remoteRpc } from './remote.mjs';
import { RuntimeManager } from './runtime.mjs';
import { SubscriptionSupervisor } from './subscriptions.mjs';

function authorized(request, secret) {
  const candidate = request.headers.authorization?.replace(/^Bearer /, '') || '';
  const left = Buffer.from(candidate);
  const right = Buffer.from(secret);
  return left.length === right.length && timingSafeEqual(left, right);
}

async function body(request) {
  let data = '';
  for await (const chunk of request) {
    data += chunk;
    if (data.length > 1024 * 1024) throw new Error('Local request too large');
  }
  return JSON.parse(data);
}

function send(response, status, value) {
  response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  response.end(JSON.stringify(value));
}

export async function startDaemon(options = {}) {
  const config = options.config || await configuration();
  const secret = randomBytes(32).toString('hex');
  const log = options.log || (message => void appendFile(file('daemon.log'), `${new Date().toISOString()} ${message}\n`));
  const runtime = options.runtime || new RuntimeManager(undefined, log);
  const supervisor = options.supervisor || new SubscriptionSupervisor(config, runtime, log);
  const bindings = await readJson('bindings.json', {});
  await supervisor.start(bindings);

  const server = createServer(async (request, response) => {
    if (!authorized(request, secret)) { send(response, 401, { error: 'Unauthorized' }); return; }
    if (request.url === '/health' && request.method === 'GET') {
      send(response, 200, { running: true, agents: Object.keys(bindings).length });
      return;
    }
    if (request.method !== 'POST') { send(response, 404, { error: 'Not found' }); return; }
    try {
      const input = await body(request);
      if (request.url === '/rpc') {
        const output = await remoteRpc(config, input.request);
        if (input.request?.method === 'tools/call' && input.request.params?.name === 'connect_agent' &&
            !output?.result?.isError) {
          try {
            const result = JSON.parse(output.result.content[0].text);
            await registerBinding(result.agentId, result.workspaceId, input.cwd);
          } catch (error) { log(`Agent connected remotely but local binding failed: ${error.message}`); }
        }
        send(response, 200, { output });
      } else if (request.url === '/bind') {
        await registerBinding(input.agentId, input.workspaceId, input.cwd);
        send(response, 200, { bound: true });
      } else if (request.url === '/stop') {
        send(response, 200, { stopped: true });
        setImmediate(() => void close());
      } else send(response, 404, { error: 'Not found' });
    } catch (error) { send(response, 502, { error: error.message }); }
  });

  async function registerBinding(agentId, workspaceId, cwd) {
    if (![agentId, workspaceId].every(value => /^[0-9a-f-]{36}$/i.test(value)) ||
        !(await stat(cwd).catch(() => null))?.isDirectory())
      throw new Error('Invalid agent binding');
    const binding = { agentId, workspaceId, cwd };
    if (bindings[agentId]?.cwd !== cwd || bindings[agentId]?.workspaceId !== workspaceId)
      supervisor.unbind(agentId);
    bindings[agentId] = binding;
    await writeJson('bindings.json', bindings);
    supervisor.bind(binding);
  }

  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const state = { pid: process.pid, port: server.address().port, secret };
  await writeJson('daemon.json', state);
  let closing = false;
  const close = async () => {
    if (closing) return;
    closing = true;
    supervisor.stop();
    server.close();
    const saved = await readJson('daemon.json', {});
    if (saved.secret === secret) await writeJson('daemon.json', {});
  };
  if (!options.noSignalHandlers) {
    process.once('SIGTERM', () => void close());
    process.once('SIGINT', () => void close());
  }
  return { server, state, close, supervisor };
}
