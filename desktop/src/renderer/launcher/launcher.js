'use strict';

/* Launcher renderer. No framework on purpose: this is three screens of chrome
   around an IPC surface, and a build step here would be a second toolchain to
   keep alive for no gain. The DeepSQL app itself is React — it is loaded from
   the server, not reimplemented. */

const api = window.deepsql;

const el = (id) => document.getElementById(id);

const dom = {
  list: el('profile-list'),
  meta: el('app-meta'),
  empty: el('empty-state'),
  editor: el('editor'),
  subtitle: el('editor-subtitle'),
  statusPill: el('status-pill'),
  feedback: el('feedback'),
  hostKeyRow: el('host-key-row'),
  hostKeyValue: el('host-key-value'),
  secretHint: el('secret-hint'),
  fields: {
    name: el('f-name'),
    url: el('f-url'),
    tlsMode: el('f-tls-mode'),
    tlsFingerprint: el('f-tls-fingerprint'),
    tlsCa: el('f-tls-ca'),
    sshHost: el('f-ssh-host'),
    sshPort: el('f-ssh-port'),
    sshUser: el('f-ssh-user'),
    sshAuth: el('f-ssh-auth'),
    sshKey: el('f-ssh-key'),
    sshPassphrase: el('f-ssh-passphrase'),
    sshPassword: el('f-ssh-password'),
    remoteHost: el('f-remote-host'),
    remotePort: el('f-remote-port'),
    localPort: el('f-local-port'),
  },
};

const state = {
  profiles: [],
  selectedId: null,
  /** Unsaved new profile, held here until the first save. */
  draft: null,
  transport: 'direct',
  active: new Set(),
  busy: false,
  info: null,
};

// ── Rendering ────────────────────────────────────────────────────────────

function selected() {
  if (state.draft) return state.draft;
  return state.profiles.find((p) => p.id === state.selectedId) || null;
}

function renderList() {
  dom.list.replaceChildren();

  for (const profile of state.profiles) {
    const item = document.createElement('button');
    item.type = 'button';
    item.className = 'profile-item';
    if (!state.draft && profile.id === state.selectedId) item.classList.add('is-active');

    const dot = document.createElement('span');
    dot.className = `profile-dot${state.active.has(profile.id) ? ' is-live' : ''}`;

    const copy = document.createElement('span');
    copy.className = 'profile-copy';
    const name = document.createElement('span');
    name.className = 'profile-name';
    name.textContent = profile.name;
    const sub = document.createElement('span');
    sub.className = 'profile-sub';
    sub.textContent = describe(profile);
    copy.append(name, sub);

    item.append(dot, copy);
    item.addEventListener('click', () => select(profile.id));
    dom.list.append(item);
  }

  if (state.draft) {
    const item = document.createElement('button');
    item.type = 'button';
    item.className = 'profile-item is-active';
    const dot = document.createElement('span');
    dot.className = 'profile-dot';
    const copy = document.createElement('span');
    copy.className = 'profile-copy';
    const name = document.createElement('span');
    name.className = 'profile-name';
    name.textContent = state.draft.name || 'New connection';
    const sub = document.createElement('span');
    sub.className = 'profile-sub';
    sub.textContent = 'Unsaved';
    copy.append(name, sub);
    item.append(dot, copy);
    dom.list.append(item);
  }
}

function describe(profile) {
  if (profile.transport === 'tunnel') {
    const { username, host, remotePort } = profile.ssh;
    return host ? `ssh ${username}@${host} → :${remotePort}` : 'SSH tunnel';
  }
  try {
    return new URL(profile.url).host;
  } catch {
    return 'No URL set';
  }
}

function renderEditor() {
  const profile = selected();
  const hasProfile = Boolean(profile);
  dom.empty.hidden = hasProfile;
  dom.editor.hidden = !hasProfile;
  if (!profile) return;

  state.transport = profile.transport;

  dom.fields.name.value = profile.name || '';
  dom.fields.url.value = profile.url || '';
  dom.fields.tlsMode.value = profile.tls?.mode || 'system';
  dom.fields.tlsFingerprint.value = profile.tls?.fingerprint || '';
  dom.fields.tlsCa.value = profile.tls?.caPath || '';

  const ssh = profile.ssh || {};
  dom.fields.sshHost.value = ssh.host || '';
  dom.fields.sshPort.value = ssh.port ?? 22;
  dom.fields.sshUser.value = ssh.username || '';
  dom.fields.sshAuth.value = ssh.authMethod || 'key';
  dom.fields.sshKey.value = ssh.privateKeyPath || '';
  dom.fields.sshPassphrase.value = '';
  dom.fields.sshPassword.value = '';
  dom.fields.remoteHost.value = ssh.remoteHost || '127.0.0.1';
  dom.fields.remotePort.value = ssh.remotePort ?? 3000;
  dom.fields.localPort.value = ssh.localPort ?? 0;

  dom.hostKeyRow.hidden = !ssh.hostKeyFingerprint;
  dom.hostKeyValue.textContent = ssh.hostKeyFingerprint || '';

  dom.secretHint.textContent = ssh.hasStoredPassphrase
    ? state.info?.keychainAvailable
      ? 'A passphrase is saved in your OS keychain. Type here only to replace it.'
      : 'A passphrase is held for this session only — the OS keychain is unavailable.'
    : '';

  dom.subtitle.textContent = profile.lastConnectedAt
    ? `Last connected ${formatWhen(profile.lastConnectedAt)}`
    : state.draft
      ? 'Not saved yet'
      : 'Never connected';

  el('delete-profile').hidden = Boolean(state.draft);

  syncVisibility();
  setStatus(
    state.active.has(profile.id) ? 'live' : 'idle',
    state.active.has(profile.id) ? 'Connected' : 'Not connected',
  );
}

/** Show only the fields the current transport / TLS mode / auth method needs. */
function syncVisibility() {
  const transport = state.transport;
  for (const seg of document.querySelectorAll('.seg')) {
    seg.classList.toggle('is-selected', seg.dataset.transport === transport);
  }
  for (const panel of document.querySelectorAll('[data-panel]')) {
    panel.hidden = panel.dataset.panel !== transport;
  }
  const tlsMode = dom.fields.tlsMode.value;
  for (const node of document.querySelectorAll('[data-tls]')) {
    node.hidden = node.dataset.tls !== tlsMode;
  }
  const auth = dom.fields.sshAuth.value;
  for (const node of document.querySelectorAll('[data-auth]')) {
    node.hidden = node.dataset.auth !== auth;
  }
}

function setStatus(kind, text) {
  dom.statusPill.className = `pill pill-${kind}`;
  dom.statusPill.textContent = text;
}

function say(message, kind = '') {
  dom.feedback.className = `feedback${kind ? ` is-${kind}` : ''}`;
  dom.feedback.replaceChildren();
  if (kind === 'busy') {
    const spinner = document.createElement('span');
    spinner.className = 'spinner';
    dom.feedback.append(spinner);
  }
  dom.feedback.append(document.createTextNode(message));
}

function formatWhen(iso) {
  const then = new Date(iso);
  const minutes = Math.round((Date.now() - then.getTime()) / 60000);
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes}m ago`;
  if (minutes < 60 * 24) return `${Math.round(minutes / 60)}h ago`;
  return then.toLocaleDateString();
}

function setBusy(busy) {
  state.busy = busy;
  el('connect').disabled = busy;
  el('test-connection').disabled = busy;
}

// ── Form → payload ───────────────────────────────────────────────────────

function readForm() {
  const profile = selected();
  const payload = {
    id: state.draft ? undefined : profile?.id,
    name: dom.fields.name.value,
    transport: state.transport,
    url: dom.fields.url.value,
    tls: {
      mode: dom.fields.tlsMode.value,
      fingerprint: dom.fields.tlsFingerprint.value,
      caPath: dom.fields.tlsCa.value,
    },
    ssh: {
      host: dom.fields.sshHost.value,
      port: dom.fields.sshPort.value,
      username: dom.fields.sshUser.value,
      authMethod: dom.fields.sshAuth.value,
      privateKeyPath: dom.fields.sshKey.value,
      remoteHost: dom.fields.remoteHost.value,
      remotePort: dom.fields.remotePort.value,
      localPort: dom.fields.localPort.value,
    },
  };

  // An empty secret box means "leave what is stored alone", not "clear it" —
  // otherwise every save would wipe the keychain entry the user just made.
  if (dom.fields.sshPassphrase.value) payload.ssh.passphrase = dom.fields.sshPassphrase.value;
  if (dom.fields.sshPassword.value) payload.ssh.password = dom.fields.sshPassword.value;

  return payload;
}

/** Persist the form and return the saved profile. */
async function save() {
  const saved = await api.profiles.save(readForm());
  state.draft = null;
  state.selectedId = saved.id;
  state.profiles = await api.profiles.list();
  renderList();
  return saved;
}

function validate() {
  if (state.transport === 'direct') {
    if (!dom.fields.url.value.trim()) return 'Enter the DeepSQL server URL.';
    if (dom.fields.tlsMode.value === 'custom-ca' && !dom.fields.tlsCa.value.trim()) {
      return 'Select the CA bundle to verify against.';
    }
    return null;
  }
  if (!dom.fields.sshHost.value.trim()) return 'Enter the SSH host.';
  if (!dom.fields.sshUser.value.trim()) return 'Enter the SSH username.';
  if (dom.fields.sshAuth.value === 'key' && !dom.fields.sshKey.value.trim()) {
    return 'Select the private key file to authenticate with.';
  }
  return null;
}

// ── Actions ──────────────────────────────────────────────────────────────

function select(id) {
  state.draft = null;
  state.selectedId = id;
  say('');
  renderList();
  renderEditor();
}

function newProfile(prefill = {}) {
  state.draft = {
    id: null,
    name: prefill.name || 'DeepSQL VM',
    transport: prefill.transport || 'direct',
    url: prefill.url || '',
    tls: { mode: 'system', fingerprint: '', caPath: '' },
    ssh: {
      host: prefill.sshHost || '',
      port: 22,
      username: prefill.sshUser || 'ubuntu',
      authMethod: 'key',
      privateKeyPath: '',
      remoteHost: '127.0.0.1',
      remotePort: 3000,
      localPort: 0,
      hostKeyFingerprint: '',
      hasStoredPassphrase: false,
      hasStoredPassword: false,
    },
    lastConnectedAt: null,
  };
  state.selectedId = null;
  say('');
  renderList();
  renderEditor();
  dom.fields.name.focus();
  dom.fields.name.select();
}

async function testConnection() {
  const problem = validate();
  if (problem) {
    say(problem, 'error');
    return;
  }
  setBusy(true);
  setStatus('busy', 'Testing');
  say('Testing connection…', 'busy');
  try {
    const profile = await save();
    const result = await api.profiles.test(profile.id, transientSecrets());
    if (result.ok) {
      setStatus('idle', 'Not connected');
      say(
        `DeepSQL responded in ${result.latencyMs} ms${
          result.hostKeyFingerprint ? ` · host key ${result.hostKeyFingerprint}` : ''
        }.`,
        'ok',
      );
      state.profiles = await api.profiles.list();
      renderEditor();
    } else {
      setStatus('error', 'Failed');
      say(result.detail, 'error');
    }
  } catch (err) {
    setStatus('error', 'Failed');
    say(err.message, 'error');
  } finally {
    setBusy(false);
  }
}

async function connect() {
  const problem = validate();
  if (problem) {
    say(problem, 'error');
    return;
  }
  setBusy(true);
  setStatus('busy', 'Connecting');
  say('Connecting…', 'busy');
  try {
    const profile = await save();
    const result = await api.connection.open(profile.id, transientSecrets());
    if (result.ok) {
      state.active.add(profile.id);
      setStatus('live', 'Connected');
      say('Connected. Opening DeepSQL…', 'ok');
      renderList();
    } else {
      setStatus('error', 'Failed');
      say(result.detail, 'error');
    }
  } catch (err) {
    setStatus('error', 'Failed');
    say(err.message, 'error');
  } finally {
    setBusy(false);
  }
}

/**
 * Secrets typed right now are also passed straight to the transport, so a
 * passphrase works on this attempt even when the OS keychain refused to store
 * it (headless Linux) or the user is deliberately not saving it.
 */
function transientSecrets() {
  return {
    passphrase: dom.fields.sshPassphrase.value || undefined,
    password: dom.fields.sshPassword.value || undefined,
  };
}

async function deleteProfile() {
  const profile = selected();
  if (!profile?.id) return;
  await api.profiles.remove(profile.id);
  state.active.delete(profile.id);
  state.profiles = await api.profiles.list();
  state.selectedId = state.profiles[0]?.id || null;
  state.draft = null;
  renderList();
  renderEditor();
  say('Connection deleted.');
}

// ── Wiring ───────────────────────────────────────────────────────────────

function wire() {
  el('new-connection').addEventListener('click', () => newProfile());
  el('empty-new').addEventListener('click', () => newProfile());
  el('delete-profile').addEventListener('click', deleteProfile);
  el('test-connection').addEventListener('click', testConnection);

  dom.editor.addEventListener('submit', (event) => {
    event.preventDefault();
    connect();
  });

  for (const seg of document.querySelectorAll('.seg')) {
    seg.addEventListener('click', () => {
      state.transport = seg.dataset.transport;
      syncVisibility();
      say('');
    });
  }

  dom.fields.tlsMode.addEventListener('change', syncVisibility);
  dom.fields.sshAuth.addEventListener('change', syncVisibility);

  dom.fields.name.addEventListener('input', () => {
    if (state.draft) state.draft.name = dom.fields.name.value;
    renderList();
  });

  el('browse-key').addEventListener('click', async () => {
    const picked = await api.dialog.pickPrivateKey();
    if (picked) dom.fields.sshKey.value = picked;
  });

  el('browse-ca').addEventListener('click', async () => {
    const picked = await api.dialog.pickCaBundle();
    if (picked) dom.fields.tlsCa.value = picked;
  });

  el('clear-host-key').addEventListener('click', async () => {
    const profile = selected();
    if (!profile?.id) return;
    await api.profiles.clearHostKey(profile.id);
    state.profiles = await api.profiles.list();
    renderEditor();
    say('Pinned host key cleared. The next connection will pin whatever the VM presents.');
  });

  api.onStatus((payload) => {
    if (state.busy && payload.detail) say(payload.detail, 'busy');
    if (payload.status === 'ready') state.active.add(payload.profileId);
    if (payload.status === 'closed') state.active.delete(payload.profileId);
    renderList();
  });

  api.onWorkspaceClosed(({ profileId }) => {
    state.active.delete(profileId);
    renderList();
    if (selected()?.id === profileId) setStatus('idle', 'Not connected');
  });

  api.onDeepLink((payload) => {
    newProfile(payload);
    say('Pre-filled from a deepsql:// link. Review it, then connect.');
  });
}

async function renderMeta() {
  const info = state.info;
  dom.meta.replaceChildren();

  const version = document.createElement('div');
  version.textContent = `DeepSQL Desktop ${info.version} · Electron ${info.electron}`;

  const keychain = document.createElement('div');
  keychain.textContent = info.keychainAvailable
    ? 'Secrets stored in the OS keychain'
    : 'No OS keychain — secrets kept for this session only';

  const logs = document.createElement('button');
  logs.type = 'button';
  logs.textContent = 'Open log file';
  logs.addEventListener('click', () => api.app.openLog());

  dom.meta.append(version, keychain, logs);
}

async function boot() {
  state.info = await api.app.info();
  state.profiles = await api.profiles.list();
  state.active = new Set(await api.connection.activeIds());
  state.selectedId = state.profiles[0]?.id || null;

  wire();
  await renderMeta();
  renderList();
  renderEditor();

  if (state.profiles.length === 0) newProfile();
}

boot();
