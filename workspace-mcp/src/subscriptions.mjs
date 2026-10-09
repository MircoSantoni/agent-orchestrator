import { readJson, writeJson } from './files.mjs';
import { modernRequest, readInbox, remoteRpc } from './remote.mjs';
import { setTimeout as pause } from 'node:timers/promises';

export async function* sseMessages(body) {
  const decoder = new TextDecoder();
  let buffer = '';
  for await (const chunk of body) {
    buffer += decoder.decode(chunk, { stream: true });
    buffer = buffer.replace(/\r\n/g, '\n');
    let boundary;
    while ((boundary = buffer.indexOf('\n\n')) >= 0) {
      const frame = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary + 2);
      const data = frame.split('\n').filter(line => line.startsWith('data:'))
        .map(line => line.slice(5).trimStart()).join('\n');
      if (data) yield JSON.parse(data);
    }
  }
}

export class SubscriptionSupervisor {
  constructor(config, runtime, log = console.error) {
    this.config = config;
    this.runtime = runtime;
    this.log = log;
    this.controllers = new Map();
    this.seen = null;
    this.syncing = new Map();
  }

  async start(bindings) {
    this.seen ??= await readJson('seen.json', {});
    for (const binding of Object.values(bindings)) this.bind(binding);
  }

  bind(binding) {
    if (this.controllers.has(binding.agentId)) return;
    const controller = new AbortController();
    this.controllers.set(binding.agentId, controller);
    void this.listen(binding, controller.signal);
  }

  unbind(agentId) {
    this.controllers.get(agentId)?.abort();
    this.controllers.delete(agentId);
  }

  stop() {
    for (const controller of this.controllers.values()) controller.abort();
    this.controllers.clear();
  }

  async sync(binding) {
    const prior = this.syncing.get(binding.agentId);
    if (prior) return prior;
    const work = this.syncNow(binding).finally(() => this.syncing.delete(binding.agentId));
    this.syncing.set(binding.agentId, work);
    return work;
  }

  async syncNow(binding) {
    const messages = await readInbox(this.config, `agent://${binding.agentId}/inbox`);
    this.seen ??= await readJson('seen.json', {});
    const previous = this.seen[binding.agentId];
    const known = new Set(previous || []);
    const fresh = previous ? messages.filter(message => !known.has(message.id)) : [];
    this.seen[binding.agentId] = [...new Set([...messages.map(message => message.id), ...known])].slice(0, 5000);
    await writeJson('seen.json', this.seen);
    await this.runtime.wake(binding, fresh.reverse());
  }

  async listen(binding, signal) {
    const uri = `agent://${binding.agentId}/inbox`;
    const heartbeat = setInterval(() => {
      void remoteRpc(this.config, modernRequest('tools/call', {
        name: 'heartbeat_agent', arguments: { agentId: binding.agentId }
      })).catch(error => this.log(`Heartbeat ${binding.agentId}: ${error.message}`));
    }, 15_000);
    let delay = 1000;
    try { while (!signal.aborted) {
      try {
        await this.sync(binding);
        const request = modernRequest('subscriptions/listen', {
          notifications: { resourceSubscriptions: [uri] }
        });
        const response = await remoteRpc(this.config, request, { stream: true, signal });
        delay = 1000;
        await this.sync(binding); // Covers messages arriving between the first read and stream acknowledgment.
        for await (const event of sseMessages(response.body)) {
          if (event.method === 'notifications/resources/updated' && event.params?.uri === uri)
            await this.sync(binding);
        }
      } catch (error) {
        if (!signal.aborted) this.log(`Subscription ${binding.agentId}: ${error.message}`);
      }
      if (!signal.aborted) {
        await pause(delay, undefined, { signal }).catch(() => {});
        delay = Math.min(delay * 2, 30_000);
      }
    } } finally { clearInterval(heartbeat); }
  }
}
