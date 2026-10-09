import { randomUUID } from 'node:crypto';

const VERSION = '2026-07-28';
const META = {
  'io.modelcontextprotocol/protocolVersion': VERSION,
  'io.modelcontextprotocol/clientInfo': { name: 'workspace-agentd', version: '0.1.0' },
  'io.modelcontextprotocol/clientCapabilities': {}
};

export function modernRequest(method, params = {}) {
  return { jsonrpc: '2.0', id: randomUUID(), method,
    params: { ...params, _meta: META } };
}

export function remoteHeaders(config, request, stream = false) {
  const headers = {
    Authorization: `Bearer ${config.token}`,
    'Content-Type': 'application/json',
    Accept: stream ? 'text/event-stream' : 'application/json'
  };
  if (request.params?._meta?.['io.modelcontextprotocol/protocolVersion'] === VERSION) {
    headers['MCP-Protocol-Version'] = VERSION;
    headers['Mcp-Method'] = request.method;
    if (request.method === 'resources/read') headers['Mcp-Name'] = encodeURIComponent(request.params.uri);
    if (request.method === 'tools/call') headers['Mcp-Name'] = request.params.name;
  }
  return headers;
}

export async function remoteRpc(config, request, { signal, stream = false } = {}) {
  const response = await fetch(config.url, {
    method: 'POST', headers: remoteHeaders(config, request, stream),
    body: JSON.stringify(request), signal
  });
  if (stream) {
    if (!response.ok || !response.body) throw new Error(`MCP subscription HTTP ${response.status}`);
    return response;
  }
  if (response.status === 202) return null;
  const body = await response.text();
  let parsed;
  try { parsed = JSON.parse(body); }
  catch { throw new Error(`MCP HTTP ${response.status}: invalid JSON response`); }
  if (!response.ok && !parsed.error) throw new Error(`MCP HTTP ${response.status}`);
  return parsed;
}

export async function readInbox(config, uri) {
  const response = await remoteRpc(config, modernRequest('resources/read', { uri }));
  if (response?.error) throw new Error(response.error.message || 'Resource read failed');
  return JSON.parse(response.result.contents[0].text).messages;
}
