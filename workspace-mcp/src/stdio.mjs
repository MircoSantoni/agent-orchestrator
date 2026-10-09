import { createInterface } from 'node:readline';
import { ensureDaemon, localRequest } from './local.mjs';

export async function serveStdio() {
  let daemon = await ensureDaemon();
  const lines = createInterface({ input: process.stdin, crlfDelay: Infinity });
  for await (const line of lines) {
    if (!line.trim()) continue;
    void (async () => {
      let request;
      try {
        request = JSON.parse(line);
        const { output } = await localRequest(daemon, '/rpc', { request, cwd: process.cwd() });
        if (output !== null && output !== undefined) process.stdout.write(JSON.stringify(output) + '\n');
      } catch (error) {
        // Do not replay a failed tool call: the cloud mutation may already have committed.
        // Recreate the local supervisor for the next request instead.
        void ensureDaemon().then(state => { daemon = state; }).catch(next =>
          process.stderr.write(`workspace-mcp: daemon restart failed: ${next.message}\n`));
        if (request?.id !== undefined) process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: request.id,
          error: { code: -32603, message: error.message } }) + '\n');
        else process.stderr.write(`workspace-mcp: ${error.message}\n`);
      }
    })();
  }
}
