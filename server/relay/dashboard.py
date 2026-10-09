"""Self-contained admin pages: no external assets, strict CSP, all data rendered via textContent."""
from html import escape

from fastapi.responses import HTMLResponse

_STYLE = """
:root{color-scheme:light dark;--bg:#f4f5f8;--panel:#fff;--panel2:#f8f9fb;--line:#e3e6eb;--text:#151820;--muted:#636b78;
--accent:#2f6fed;--ok:#1f9d55;--warn:#c47f00;--bad:#d64545;--chip:#eef2ff;--sent:#2f6fed;--delivered:#1f9d55;--expired:#c47f00}
@media (prefers-color-scheme:dark){:root{--bg:#0e1014;--panel:#171a20;--panel2:#1c2027;--line:#2a2f38;--text:#e8ebf0;--muted:#98a1b0;
--accent:#6e9bff;--ok:#3ccf7a;--warn:#f0b03c;--bad:#ff6b6b;--chip:#232a3b;--sent:#6e9bff;--delivered:#3ccf7a;--expired:#f0b03c}}
*{box-sizing:border-box}body{margin:0;font:14px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:var(--bg);color:var(--text)}
header{display:flex;align-items:center;gap:16px;padding:14px 20px;background:var(--panel);border-bottom:1px solid var(--line);position:sticky;top:0;z-index:2;flex-wrap:wrap}
header h1{font-size:16px;margin:0;font-weight:650}header .spacer{flex:1}
nav{display:flex;gap:4px;flex-wrap:wrap}nav button{border:0;background:none;color:var(--muted);padding:7px 12px;border-radius:8px;font:inherit;cursor:pointer}
nav button[aria-selected=true]{background:var(--chip);color:var(--text);font-weight:600}
main{padding:20px;max-width:1280px;margin:0 auto}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px}
.card{background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:16px}
.stat .label{color:var(--muted);font-size:12px;text-transform:uppercase;letter-spacing:.04em}.stat .value{font-size:26px;font-weight:650;margin-top:4px}
.stat .sub{color:var(--muted);font-size:12px;margin-top:2px}
h2{font-size:15px;margin:22px 0 10px}table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:9px 10px;border-bottom:1px solid var(--line);vertical-align:top}
th{color:var(--muted);font-weight:600;font-size:12px}tr:last-child td{border-bottom:0}.table{overflow-x:auto;padding:4px}
.mono{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px}.muted{color:var(--muted)}
.dot{display:inline-block;width:8px;height:8px;border-radius:50%;margin-right:6px;background:var(--muted)}.dot.on{background:var(--ok)}.dot.blocked{background:var(--bad)}
.chip{display:inline-block;padding:1px 8px;border-radius:999px;background:var(--chip);font-size:12px;margin:1px 2px}
button.small{border:1px solid var(--line);background:var(--panel2);color:var(--text);border-radius:8px;padding:4px 9px;font:inherit;font-size:12px;cursor:pointer;margin:2px}
button.small.danger{color:var(--bad)}button.small:hover{border-color:var(--accent)}
.bar{height:8px;border-radius:4px;background:var(--panel2);overflow:hidden}.bar>i{display:block;height:100%;background:var(--accent)}
.sandbox{margin-bottom:12px}.sandbox .head{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.arrow{color:var(--muted)}
.lock{color:var(--ok)}.empty{color:var(--muted);padding:20px;text-align:center}
.legend span{margin-right:14px;font-size:12px;color:var(--muted)}.legend i{display:inline-block;width:10px;height:10px;border-radius:2px;margin-right:5px;vertical-align:-1px}
dialog{border:1px solid var(--line);border-radius:14px;background:var(--panel);color:var(--text);max-width:640px;width:calc(100% - 32px)}
dialog pre{white-space:pre-wrap;word-break:break-all;background:var(--panel2);padding:10px;border-radius:8px}
.qr svg{width:220px;height:220px;background:#fff;border-radius:10px;padding:6px}
.notice{background:var(--chip);border-radius:10px;padding:10px 12px;margin:10px 0}
.alert{border-left:4px solid var(--warn)}.alert.bad{border-left-color:var(--bad)}
a.small{display:inline-block;border:1px solid var(--line);background:var(--panel2);color:var(--text);border-radius:8px;padding:4px 9px;font-size:12px;margin:2px;text-decoration:none}
@media (max-width:640px){main{padding:12px}header{padding:12px}}
"""

_SCRIPT = r"""
const CSRF = document.body.dataset.csrf;
const $ = (s, r = document) => r.querySelector(s);
const el = (tag, props = {}, ...kids) => { const e = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) { if (k === 'class') e.className = v; else if (k.startsWith('on')) e.addEventListener(k.slice(2), v); else if (v !== undefined && v !== null) e.setAttribute(k, v); }
  for (const k of kids.flat()) e.append(k instanceof Node ? k : document.createTextNode(k ?? '')); return e; };
const bytes = n => n == null ? '—' : n < 1024 ? n + ' B' : n < 1048576 ? (n / 1024).toFixed(1) + ' KB' : (n / 1048576).toFixed(1) + ' MB';
const ago = t => { if (!t) return '—'; const s = Math.round((Date.now() - t) / 1000); if (s < 5) return 'just now'; if (s < 60) return s + 's ago';
  if (s < 3600) return Math.round(s / 60) + 'm ago'; if (s < 86400) return Math.round(s / 3600) + 'h ago'; return Math.round(s / 86400) + 'd ago'; };
const left = t => { const s = Math.max(0, Math.round((t - Date.now()) / 1000)); return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0'); };
const dur = s => s < 3600 ? Math.round(s / 60) + ' min' : s < 86400 ? (s / 3600).toFixed(1) + ' h' : (s / 86400).toFixed(1) + ' d';
let devices = {};
const PLATFORMS = { ios: 'iOS', android: 'Android', desktop: 'Desktop' };
const name = id => { const d = devices[id]; if (!d) return id ? id.slice(0, 12) : '—'; return d.label || ((PLATFORMS[d.platform] || d.platform || 'Device') + (d.model ? ' · ' + d.model : '')); };
const who = id => el('span', { title: id }, el('span', { class: 'dot' + (devices[id]?.online ? ' on' : '') }), name(id), ' ', el('span', { class: 'mono muted' }, id ? id.slice(0, 8) : ''));

async function api(path, options = {}) {
  const r = await fetch('/admin/api/' + path, { credentials: 'same-origin', ...options,
    headers: { 'X-CSRF-Token': CSRF, 'Content-Type': 'application/json', ...(options.headers || {}) } });
  if (r.status === 401) { location.reload(); throw new Error('login'); }
  if (!r.ok) throw new Error((await r.json().catch(() => ({}))).error || r.status);
  return r.json();
}
const post = (path, body) => api(path, { method: 'POST', body: JSON.stringify(body || {}) });
const del = path => api(path, { method: 'DELETE' });

function stat(label, value, sub) { return el('div', { class: 'card stat' }, el('div', { class: 'label' }, label), el('div', { class: 'value' }, String(value)), sub ? el('div', { class: 'sub' }, sub) : ''); }

function chart(hours, width) {
  const w = Math.max(320, width || 720), h = 140, pad = 20, max = Math.max(1, ...hours.map(x => x.sent + x.delivered + x.expired));
  const ns = 'http://www.w3.org/2000/svg', svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('viewBox', `0 0 ${w} ${h + pad}`); svg.setAttribute('width', '100%'); svg.setAttribute('role', 'img');
  svg.setAttribute('aria-label', 'Items per hour over the last 24 hours');
  const bw = w / hours.length;
  hours.forEach((x, i) => { let y = h; for (const k of ['sent', 'delivered', 'expired']) { const v = x[k]; if (!v) continue;
    const bh = v / max * (h - 10); y -= bh; const r = document.createElementNS(ns, 'rect');
    r.setAttribute('x', i * bw + 3); r.setAttribute('y', y); r.setAttribute('width', bw - 6); r.setAttribute('height', bh); r.setAttribute('rx', 2);
    r.setAttribute('fill', `var(--${k})`); const t = document.createElementNS(ns, 'title'); t.textContent = `${k}: ${v}`; r.append(t); svg.append(r); } });
  [0, 6, 12, 18, 23].forEach(i => { const t = document.createElementNS(ns, 'text'); t.setAttribute('x', i * bw + bw / 2); t.setAttribute('y', h + 15);
    t.setAttribute('text-anchor', 'middle'); t.setAttribute('font-size', '11'); t.setAttribute('fill', 'var(--muted)');
    t.textContent = new Date(hours[i].hour).getHours() + ':00'; svg.append(t); });
  return svg;
}

const views = {
  async overview(root) {
    const [o, d] = await Promise.all([api('overview'), api('devices')]);
    devices = Object.fromEntries(d.map(x => [x.id, x]));
    const used = o.sandbox.capacityBytes ? o.sandbox.bytes / o.sandbox.capacityBytes : 0;
    root.replaceChildren(
      ...(o.alerts || []).map(a => el('div', { class: 'notice alert' + (a.level === 'bad' ? ' bad' : ''), role: 'status' }, a.message)),
      el('div', { class: 'grid' },
        stat('Devices online', o.devices.online, `${o.devices.total} registered · ${o.devices.active24h} active 24h`),
        stat('Linked pairs', o.links.mutual, `${o.links.oneWay} one-way · ${o.links.openPairings} open QR codes`),
        stat('In sandboxes', o.sandbox.pending, `${bytes(o.sandbox.bytes)} · ${o.sandbox.nextExpiry ? 'next expiry ' + left(o.sandbox.nextExpiry) : 'empty'}`),
        stat('Delivered 24h', o.last24h.delivered, `${o.last24h.sent} sent · ${o.last24h.expired} expired unread`),
        stat('Median delivery', o.last24h.medianLatencyMs == null ? '—' : (o.last24h.medianLatencyMs / 1000).toFixed(1) + ' s',
             o.last24h.p95LatencyMs == null ? 'upload → acknowledged' : 'p95 ' + (o.last24h.p95LatencyMs / 1000).toFixed(1) + ' s'),
        stat('Uptime', dur(o.runtime.uptimeSeconds), 'relay v' + o.runtime.version)),
      el('h2', {}, 'Activity, last 24 hours'),
      el('div', { class: 'card' }, el('div', { class: 'legend' }, ...['sent', 'delivered', 'expired'].map(k => el('span', {}, el('i', { style: `background:var(--${k})` }), k))), chart(o.last24h.hourly, root.clientWidth - 40)),
      el('h2', {}, 'Health'),
      el('div', { class: 'grid' },
        el('div', { class: 'card' }, el('div', { class: 'muted' }, 'Sandbox storage'), el('div', { class: 'bar', style: 'margin:10px 0' }, el('i', { style: `width:${Math.min(100, used * 100).toFixed(1)}%` })),
          `${bytes(o.sandbox.bytes)} of ${bytes(o.sandbox.capacityBytes)} · items expire after ${dur(o.sandbox.maxLifetimeSeconds)}`),
        el('div', { class: 'card' }, el('div', { class: 'muted' }, 'Since start'),
          `${o.runtime.counters.requests} requests · ${o.runtime.counters.auth_failures} auth failures · ${o.runtime.counters.rate_limited} rate limited · push ${o.runtime.counters.push_sent} sent / ${o.runtime.counters.push_failed} failed`),
        el('div', { class: 'card' }, el('div', { class: 'muted' }, 'Policy'),
          `Enrollment: ${o.config.enrollment} · Push: ${o.config.push ? 'APNs on' : 'off'} · ${o.config.mailboxCount} items / ${bytes(o.config.mailboxBytes)} per mailbox`),
        el('div', { class: 'card' }, el('div', { class: 'muted' }, 'Export (metadata only)'),
          ...[['devices', 'csv'], ['devices', 'json'], ['events', 'csv'], ['events', 'json']].map(([k, f]) =>
            el('a', { class: 'small', href: `/admin/api/export/${k}?format=${f}`, download: '' }, `${k} .${f}`)),
          el('a', { class: 'small', href: '/admin/api/metrics', target: '_blank', rel: 'noopener' }, 'metrics'))));
  },

  async devices(root) {
    const d = await api('devices'); devices = Object.fromEntries(d.map(x => [x.id, x]));
    if (!d.length) { root.replaceChildren(el('div', { class: 'card empty' }, 'No devices registered yet. Use Setup to enroll the first one.')); return; }
    const rows = d.map(x => el('tr', {},
      el('td', {}, el('span', { class: 'dot' + (x.blocked ? ' blocked' : x.online ? ' on' : '') }), el('b', {}, name(x.id)), el('div', { class: 'mono muted' }, x.id.slice(0, 16) + '…')),
      el('td', {}, PLATFORMS[x.platform] || x.platform || '—', el('div', { class: 'muted' }, [x.model, x.appVersion && 'v' + x.appVersion].filter(Boolean).join(' · '))),
      el('td', {}, x.blocked ? 'blocked' : x.online ? 'online now' : ago(x.lastSeen), el('div', { class: 'muted' }, 'since ' + new Date(x.created || 0).toLocaleDateString())),
      el('td', {}, x.linked.length ? x.linked.map(p => el('span', { class: 'chip', title: p }, name(p))) : el('span', { class: 'muted' }, 'none')),
      el('td', {}, `${x.sent} / ${x.received}`, el('div', { class: 'muted' }, `${bytes(x.sentBytes)} / ${bytes(x.receivedBytes)}`)),
      el('td', {}, x.pending ? `${x.pending} (${bytes(x.pendingBytes)})` : '—'),
      el('td', {}, x.push ? 'APNs' : '—'),
      el('td', {},
        el('button', { class: 'small', onclick: async () => { const v = prompt('Label for this device', x.label); if (v !== null) { await post(`devices/${x.id}/label`, { label: v }); refresh(); } } }, 'Label'),
        el('button', { class: 'small', onclick: async () => { await post(`devices/${x.id}/block`, { blocked: !x.blocked }); refresh(); } }, x.blocked ? 'Unblock' : 'Block'),
        el('button', { class: 'small danger', onclick: async () => { if (confirm('Remove this device, its links and pending items?')) { await del(`devices/${x.id}`); refresh(); } } }, 'Remove'))));
    root.replaceChildren(el('div', { class: 'card table' }, el('table', {},
      el('thead', {}, el('tr', {}, ...['Device', 'Platform', 'Last seen', 'Linked with', 'Sent / received', 'Waiting', 'Push', ''].map(t => el('th', {}, t)))),
      el('tbody', {}, rows))));
  },

  async sandboxes(root) {
    const [s, d] = await Promise.all([api('sandboxes'), api('devices')]); devices = Object.fromEntries(d.map(x => [x.id, x]));
    const intro = el('div', { class: 'notice' }, el('span', { class: 'lock' }, '🔒 '),
      'Each item is sealed with the recipient device key (HPKE) and signed by the sender device. The relay — and this dashboard — can see sizes, timing and routing, never content.');
    if (!s.length) { root.replaceChildren(intro, el('div', { class: 'card empty' }, 'No linked devices yet.')); return; }
    root.replaceChildren(intro, ...s.map(x => el('div', { class: 'card sandbox' },
      el('div', { class: 'head' }, who(x.devices[0]), el('span', { class: 'arrow' }, x.mutual ? '⇄' : '→'), who(x.devices[1]),
        el('span', { class: 'spacer', style: 'flex:1' }),
        el('span', { class: 'muted' }, `linked ${ago(x.created)} · last activity ${ago(x.lastActivity)}`),
        el('button', { class: 'small danger', onclick: async () => { if (confirm('Remove this link and purge its pending items?')) { await del(`links/${x.devices[0]}/${x.devices[1]}`); refresh(); } } }, 'Unlink')),
      x.items.length ? el('div', { class: 'table' }, el('table', {},
        el('thead', {}, el('tr', {}, ...['Item', 'Direction', 'Size', 'Uploaded', 'Expires in', ''].map(t => el('th', {}, t)))),
        el('tbody', {}, x.items.map(i => el('tr', {},
          el('td', { class: 'mono' }, '🔒 ' + i.id.slice(0, 8)),
          el('td', {}, name(i.from), ' → ', name(i.to)),
          el('td', {}, bytes(i.size)), el('td', {}, ago(i.created)), el('td', { class: 'mono' }, left(i.expires)),
          el('td', {}, el('button', { class: 'small', onclick: () => inspect(i.id) }, 'Inspect'),
            el('button', { class: 'small danger', onclick: async () => { await del(`messages/${i.id}`); refresh(); } }, 'Purge')))))))
        : el('div', { class: 'muted', style: 'margin-top:8px' }, 'Sandbox empty — everything was delivered or expired.'))));
  },

  async activity(root) {
    const [e, d, p] = await Promise.all([api('events?limit=300'), api('devices'), api('pairings')]); devices = Object.fromEntries(d.map(x => [x.id, x]));
    const labels = { sent: 'uploaded item', delivered: 'delivered', expired: 'expired unread', registered: 'registered', linked: 'linked by QR',
      unlinked: 'unlinked', pairing_created: 'showed pairing QR', pairing_expired: 'pairing QR expired', device_blocked: 'blocked by admin',
      device_unblocked: 'unblocked by admin', device_removed: 'removed by admin', link_removed: 'link removed by admin', purged: 'item purged by admin', admin_login: 'admin signed in' };
    root.replaceChildren(
      el('h2', {}, 'Open pairing codes'),
      p.length ? el('div', { class: 'card table' }, el('table', {}, el('tbody', {}, p.map(x => el('tr', {}, el('td', {}, who(x.creator)), el('td', {}, x.state),
        el('td', {}, x.joiner ? who(x.joiner) : el('span', { class: 'muted' }, 'waiting for scan')), el('td', { class: 'mono' }, 'expires in ' + left(x.expires)))))))
        : el('div', { class: 'card empty' }, 'None.'),
      el('h2', {}, 'Recent activity'),
      e.length ? el('div', { class: 'card table' }, el('table', {}, el('tbody', {}, e.map(x => el('tr', {},
        el('td', { class: 'muted', title: new Date(x.at).toLocaleString() }, ago(x.at)),
        el('td', {}, x.device ? who(x.device) : '—'),
        el('td', {}, labels[x.kind] || x.kind),
        el('td', {}, x.peer ? who(x.peer) : ''),
        el('td', { class: 'muted' }, [x.size != null && bytes(x.size), x.kind === 'delivered' && x.value != null && (x.value / 1000).toFixed(1) + ' s'].filter(Boolean).join(' · ')))))))
        : el('div', { class: 'card empty' }, 'No activity yet.'));
  },

  async setup(root) {
    const s = await api('setup');
    const qr = el('div', { class: 'qr' }); if (s.qrSvg) qr.innerHTML = s.qrSvg; // server-generated SVG (segno), no user input
    root.replaceChildren(el('div', { class: 'grid' },
      el('div', { class: 'card' }, el('h2', { style: 'margin-top:0' }, 'Enroll the first device'),
        el('p', {}, 'Scan this code with DeviceLink on the device that will show pairing QR codes (usually the phone with the SDK app). Further devices only need to scan that device’s pairing code.'),
        qr, el('p', { class: 'mono', style: 'word-break:break-all' }, s.setupLink),
        s.enrollment === 'token' ? el('div', { class: 'notice' }, 'Contains the enrollment token. Treat it like a password.') : el('div', { class: 'notice' }, 'Enrollment is open: any device can register. Set RELAY_ENROLLMENT_TOKEN for private relays.')),
      el('div', { class: 'card' }, el('h2', { style: 'margin-top:0' }, 'Relay'),
        el('p', {}, 'Public URL: ', el('span', { class: 'mono' }, s.relayUrl)),
        el('p', {}, `Items stay at most ${dur(s.maxLifetimeSeconds)} and are deleted as soon as the receiver acknowledges them.`),
        el('p', {}, 'iPhone push wake-ups: ', s.pushEnabled ? 'enabled (APNs)' : 'off — iPhones receive while DeviceLink is open, or via the Shortcuts action.'))));
  },
};

async function inspect(id) {
  const m = await api('messages/' + id);
  const d = $('#inspect'); $('#inspect-body').textContent = JSON.stringify(m, null, 2); d.showModal();
}

let current = 'overview', timer;
async function refresh() {
  clearTimeout(timer);
  try { await views[current]($('#view')); $('#status').textContent = 'Updated ' + new Date().toLocaleTimeString(); }
  catch (e) { $('#status').textContent = 'Update failed: ' + e.message; }
  timer = setTimeout(refresh, current === 'setup' ? 60000 : 5000);
}
document.querySelectorAll('nav button').forEach(b => b.addEventListener('click', () => {
  current = b.dataset.view; document.querySelectorAll('nav button').forEach(x => x.setAttribute('aria-selected', x === b)); refresh(); }));
$('#logout').addEventListener('click', async () => { await post('../logout').catch(() => {}); location.reload(); });
$('#close').addEventListener('click', () => $('#inspect').close());
refresh();
"""


def _csp(nonce):
    return {"Content-Security-Policy": f"default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-{nonce}'; "
                                       "connect-src 'self'; img-src 'self' data:; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"}


def login_page(nonce, error=False):
    message = '<p class="notice" role="alert">Sign-in failed. Check the credentials in the relay configuration.</p>' if error else ""
    html = f"""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex"><title>DeviceLink Relay</title><style>{_STYLE}
form{{max-width:360px;margin:12vh auto;display:grid;gap:12px}} input{{padding:11px;border-radius:10px;border:1px solid var(--line);background:var(--panel2);color:var(--text);font:inherit}}
form button{{padding:11px;border:0;border-radius:10px;background:var(--accent);color:#fff;font:inherit;font-weight:600;cursor:pointer}}</style></head>
<body><form class="card" method="post" action="/admin/login"><h1 style="margin:0 0 4px;font-size:20px">DeviceLink Relay</h1>
<p class="muted" style="margin:0 0 8px">Administrator sign-in</p>{message}
<label>Username<br><input name="username" autocomplete="username" required style="width:100%"></label>
<label>Password<br><input name="password" type="password" autocomplete="current-password" required style="width:100%"></label>
<button type="submit">Sign in</button></form></body></html>"""
    return HTMLResponse(html, headers=_csp(nonce))


def dashboard_page(nonce, csrf, username):
    html = f"""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex"><title>DeviceLink Relay · Admin</title><style>{_STYLE}</style></head>
<body data-csrf="{escape(csrf)}"><header><h1>🔗 DeviceLink Relay</h1>
<nav role="tablist"><button data-view="overview" aria-selected="true">Overview</button><button data-view="devices" aria-selected="false">Devices</button>
<button data-view="sandboxes" aria-selected="false">Sandboxes</button><button data-view="activity" aria-selected="false">Activity</button>
<button data-view="setup" aria-selected="false">Setup</button></nav><span class="spacer"></span>
<span class="muted" id="status" aria-live="polite"></span><span class="muted">{escape(username)}</span><button class="small" id="logout">Sign out</button></header>
<main id="view"><div class="empty">Loading…</div></main>
<dialog id="inspect"><h3 style="margin-top:0">Encrypted item</h3><p class="muted">Public envelope metadata. The ciphertext can only be opened by the recipient device.</p>
<pre id="inspect-body"></pre><button class="small" id="close">Close</button></dialog>
<script nonce="{nonce}">{_SCRIPT}</script></body></html>"""
    return HTMLResponse(html, headers=_csp(nonce))
