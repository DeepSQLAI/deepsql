'use strict';

/* Native top chrome for a workspace window. Everything it shows is state the
   embedded DeepSQL page cannot know: which VM this is, how we are reaching it,
   and whether that path is still healthy. */

const api = window.deepsqlChrome;
const el = (id) => document.getElementById(id);

const dom = {
  macInset: el('mac-inset'),
  back: el('back'),
  forward: el('forward'),
  reload: el('reload'),
  identityName: el('identity-name'),
  transportTag: el('transport-tag'),
  healthDot: el('health-dot'),
  healthText: el('health-text'),
  windowControls: el('window-controls'),
  banner: el('banner'),
  bannerText: el('banner-text'),
};

api.onState((state) => {
  dom.macInset.hidden = state.platform !== 'darwin';
  dom.windowControls.hidden = state.platform === 'darwin';

  dom.identityName.textContent = state.name;
  dom.back.disabled = !state.canGoBack;
  dom.forward.disabled = !state.canGoForward;

  if (state.transport === 'tunnel') {
    dom.transportTag.textContent = 'SSH';
    dom.transportTag.className = 'tag';
    dom.transportTag.title = `Tunnelled to ${state.origin}`;
  } else {
    const insecure = state.tlsMode === 'insecure';
    dom.transportTag.textContent = insecure ? 'UNVERIFIED' : 'TLS';
    dom.transportTag.className = insecure ? 'tag tag-warn' : 'tag';
    dom.transportTag.title = insecure
      ? 'The server certificate is not verified. Switch this connection to a pinned certificate.'
      : state.origin;
  }

  if (state.health) applyHealth(state.health);

  // A successful navigation means whatever the banner was warning about is over.
  if (!state.loading && state.url) hideBanner();
});

api.onHealth(applyHealth);

function applyHealth(health) {
  if (!health.ok) {
    setHealth('is-down', 'offline', health.detail);
    return;
  }
  const ms = health.latencyMs;
  setHealth(ms > 800 ? 'is-slow' : 'is-ok', `${ms} ms`, 'Round-trip to /api/actuator/health');
}

api.onLoadError(({ errorDescription }) => {
  showBanner(`DeepSQL did not load: ${errorDescription}`);
  setHealth('is-down', 'offline');
});

function setHealth(cls, text, title) {
  dom.healthDot.className = `health-dot ${cls}`;
  dom.healthText.textContent = text;
  if (title) el('health').title = title;
}

function showBanner(text) {
  dom.bannerText.textContent = text;
  dom.banner.hidden = false;
}

function hideBanner() {
  dom.banner.hidden = true;
}

const act = (id, action) => el(id).addEventListener('click', () => api.action(action));

act('back', 'back');
act('forward', 'forward');
act('reload', 'reload');
act('identity', 'switch');
act('open-external', 'open-external');
act('disconnect', 'disconnect');
act('banner-retry', 'reload');

el('win-min').addEventListener('click', () => api.window('minimize'));
el('win-max').addEventListener('click', () => api.window('maximize'));
el('win-close').addEventListener('click', () => api.window('close'));

api.ready();
