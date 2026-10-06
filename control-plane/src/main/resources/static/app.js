const ui = Object.fromEntries(['login', 'logout', 'load', 'projectId', 'devIdentity', 'devUser',
  'status', 'proposals', 'tasks', 'agents', 'intents', 'activity'].map(id => [id, document.getElementById(id)]));
let config;
let accessToken;
let refreshToken;
let expiresAt = 0;

function status(message, error = false) {
  ui.status.textContent = message;
  ui.status.className = error ? 'error' : '';
}
function randomBase64Url(length = 32) {
  const bytes = crypto.getRandomValues(new Uint8Array(length));
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
async function sha256Base64Url(value) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value));
  return btoa(String.fromCharCode(...new Uint8Array(digest))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
function redirectUri() { return `${location.origin}/`; }
async function login() {
  if (!config.cognitoDomain || !config.humanClientId || !config.baseUrl) {
    status('Cognito todavía no está configurado.', true); return;
  }
  const verifier = randomBase64Url(64);
  const state = randomBase64Url();
  sessionStorage.setItem('pkce-verifier', verifier);
  sessionStorage.setItem('oauth-state', state);
  const params = new URLSearchParams({ response_type: 'code', client_id: config.humanClientId,
    redirect_uri: redirectUri(), scope: `openid profile ${config.scope}`, state,
    code_challenge_method: 'S256', code_challenge: await sha256Base64Url(verifier),
    resource: config.baseUrl });
  location.assign(`${config.cognitoDomain}/oauth2/authorize?${params}`);
}
async function exchange(params) {
  const response = await fetch(`${config.cognitoDomain}/oauth2/token`, {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: params });
  const data = await response.json();
  if (!response.ok || !data.access_token) throw new Error(data.error_description || 'No se pudo obtener el token');
  accessToken = data.access_token;
  if (data.refresh_token) refreshToken = data.refresh_token;
  expiresAt = Date.now() + (data.expires_in || 300) * 1000;
  ui.login.hidden = true; ui.logout.hidden = false;
}
async function completeLogin() {
  const params = new URLSearchParams(location.search);
  if (params.has('error')) throw new Error(params.get('error_description') || params.get('error'));
  if (!params.has('code')) return;
  const expected = sessionStorage.getItem('oauth-state');
  const verifier = sessionStorage.getItem('pkce-verifier');
  if (!expected || params.get('state') !== expected || !verifier) throw new Error('Estado OAuth inválido');
  if (params.has('iss') && params.get('iss') !== config.issuer) throw new Error('Emisor OAuth inválido');
  await exchange(new URLSearchParams({ grant_type: 'authorization_code', client_id: config.humanClientId,
    code: params.get('code'), redirect_uri: redirectUri(), code_verifier: verifier }));
  sessionStorage.removeItem('oauth-state'); sessionStorage.removeItem('pkce-verifier');
  history.replaceState({}, '', '/');
  status('Sesión iniciada.');
}
async function validToken() {
  if (config.dev) return '';
  if (!accessToken) throw new Error('Ingresá antes de consultar el proyecto.');
  if (Date.now() < expiresAt - 60000) return accessToken;
  if (!refreshToken) throw new Error('La sesión venció. Ingresá nuevamente.');
  await exchange(new URLSearchParams({ grant_type: 'refresh_token', client_id: config.humanClientId,
    refresh_token: refreshToken }));
  return accessToken;
}
async function api(path, options = {}) {
  const token = await validToken();
  const headers = { ...(options.headers || {}) };
  if (config.dev) headers['X-Dev-User'] = ui.devUser.value.trim() || 'local-user';
  else headers.Authorization = `Bearer ${token}`;
  const response = await fetch(`/api/v1${path}`, { ...options, headers });
  const body = await response.json();
  if (!response.ok) throw new Error(body.error || `HTTP ${response.status}`);
  return body;
}
function text(parent, tag, value, className) {
  const node = document.createElement(tag);
  node.textContent = value ?? '';
  if (className) node.className = className;
  parent.append(node);
  return node;
}
function empty(container, message) { container.replaceChildren(); text(container, 'p', message); }
function renderRows(container, rows, render) {
  container.replaceChildren();
  if (!rows.length) { text(container, 'p', 'Sin registros.'); return; }
  rows.forEach(row => { const article = document.createElement('article'); render(article, row); container.append(article); });
}
async function decide(id, action) {
  if (!confirm(`${action === 'approve' ? 'Aprobar' : 'Rechazar'} esta propuesta?`)) return;
  await api(`/context/${encodeURIComponent(id)}/${action}`, { method: 'POST' });
  await load();
}
async function load() {
  const project = ui.projectId.value.trim();
  if (!/^[0-9a-fA-F-]{36}$/.test(project)) { status('Ingresá un Project ID válido.', true); return; }
  localStorage.setItem('agent-orchestrator-project', project);
  status('Cargando estado del proyecto…');
  try {
    const root = `/projects/${encodeURIComponent(project)}`;
    const [context, tasks, agents, intents, activity] = await Promise.all([
      api(`${root}/context`), api(`${root}/tasks`), api(`${root}/agents`),
      api(`${root}/resource-intents`), api(`${root}/activity?limit=100`)]);
    renderRows(ui.proposals, context.filter(x => x.status === 'PENDING_APPROVAL'), (article, row) => {
      text(article, 'strong', row.title); text(article, 'p', row.content);
      text(article, 'small', row.id, 'badge');
      const actions = document.createElement('div'); actions.className = 'actions';
      for (const [label, action, css] of [['Aprobar', 'approve', ''], ['Rechazar', 'reject', 'danger']]) {
        const button = text(actions, 'button', label, css);
        button.addEventListener('click', () => decide(row.id, action).catch(e => status(e.message, true)));
      }
      article.append(actions);
    });
    renderRows(ui.tasks, tasks, (article, row) => {
      text(article, 'strong', row.title); text(article, 'p', row.description);
      text(article, 'small', `${row.status} · ${row.id}`, 'badge');
    });
    renderRows(ui.agents, agents, (article, row) => {
      text(article, 'strong', row.name); text(article, 'p', `${row.role || 'Agente'} · ${row.status}`);
      text(article, 'small', row.current_task_id || 'Sin tarea', 'badge');
    });
    renderRows(ui.intents, intents.filter(x => x.status === 'ACTIVE'), (article, row) => {
      text(article, 'strong', row.resource_path); text(article, 'p', `${row.intent_type} · ${row.resource_type}`);
      text(article, 'small', `Vence ${row.lease_until}`, 'badge');
    });
    renderRows(ui.activity, activity.slice(-25).reverse(), (article, row) => {
      text(article, 'strong', row.type); text(article, 'p', row.summary);
      text(article, 'small', row.created_at, 'badge');
    });
    status('Estado actualizado.');
  } catch (error) { status(error.message, true); }
}
ui.login.addEventListener('click', () => login().catch(e => status(e.message, true)));
ui.logout.addEventListener('click', () => { accessToken = refreshToken = undefined; expiresAt = 0;
  ui.login.hidden = false; ui.logout.hidden = true; status('Sesión cerrada.'); });
ui.load.addEventListener('click', load);
(async () => {
  config = await (await fetch('/public/config')).json();
  ui.devIdentity.hidden = !config.dev;
  ui.login.hidden = config.dev;
  ui.projectId.value = localStorage.getItem('agent-orchestrator-project') || '';
  await completeLogin();
  if (ui.projectId.value) await load();
})().catch(e => status(e.message, true));
