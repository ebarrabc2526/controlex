'use strict';

require('dotenv').config();

const express      = require('express');
const session      = require('express-session');
const passport     = require('passport');
const GoogleStrategy = require('passport-google-oauth20').Strategy;
const path         = require('path');
const crypto       = require('crypto');
const fs           = require('fs');
const os           = require('os');
const http         = require('http');
const { WebSocketServer, WebSocket } = require('ws');

const CATEGORIES_PATH = path.join(os.homedir(), '.controlex-server', 'categories.json');
const QUALITY_PATH    = path.join(os.homedir(), '.controlex-server', 'quality.json');
const HELP_PATH       = path.join(os.homedir(), '.controlex-server', 'help-requests.json');
const MAX_HELP_REQUESTS = 500;
const CHAT_PATH       = path.join(os.homedir(), '.controlex-server', 'chat.json');
const MAX_CHAT_ENTRIES_PER_CLIENT = 500;

const app = express();
const PORT          = process.env.PORT || 3000;
const API_KEY       = process.env.CONTROLEX_API_KEY  || 'ControlEx-IES-ClaraDelRey-2026';
const ALLOWED_EMAIL = process.env.ALLOWED_EMAIL      || 'ebarrabc2526@gmail.com';
const CLIENT_ID     = process.env.GOOGLE_CLIENT_ID;
const CLIENT_SECRET = process.env.GOOGLE_CLIENT_SECRET;
const SESSION_SECRET= process.env.SESSION_SECRET     || 'change-me';

// Ed25519 signing key for command integrity
const CMD_PRIVATE_KEY = (() => {
    const b64 = process.env.COMMAND_PRIVATE_KEY;
    if (!b64) return null;
    try {
        return crypto.createPrivateKey({ key: Buffer.from(b64, 'base64'), format: 'der', type: 'pkcs8' });
    } catch (e) {
        console.error('[controlex] COMMAND_PRIVATE_KEY inválida:', e.message);
        return null;
    }
})();

function signCmd(cmd) {
    const payload = { ...cmd, ts: Date.now() };
    if (!CMD_PRIVATE_KEY) return payload;
    const json = JSON.stringify(payload);
    const sigBuf = crypto.sign(null, Buffer.from(json, 'utf8'), CMD_PRIVATE_KEY);
    return { ...payload, sig: sigBuf.toString('base64') };
}

// ── Auth setup ────────────────────────────────────────────────────────────────

passport.serializeUser((user, done) => done(null, user));
passport.deserializeUser((user, done) => done(null, user));

passport.use(new GoogleStrategy({
    clientID:     CLIENT_ID,
    clientSecret: CLIENT_SECRET,
    callbackURL:  'https://controlex.ebarrab.com/auth/google/callback'
}, (accessToken, refreshToken, profile, done) => {
    const email = (profile.emails && profile.emails[0] && profile.emails[0].value) || '';
    done(null, { email, name: profile.displayName });
}));

app.set('trust proxy', 1);
// 100 MB lets the plugin send PNG of multi-monitor 4K/5K setups without
// hitting payload limits. PNG of a single 4K screen ≈ 5-15 MB, multi-monitor
// can easily exceed 25 MB, which was the previous (too-strict) ceiling.
app.use(express.json({ limit: '100mb' }));
app.use(session({
    secret: SESSION_SECRET,
    resave: false,
    saveUninitialized: true,
    cookie: { secure: true, httpOnly: true, sameSite: 'lax', maxAge: 8 * 3600 * 1000 }
}));
app.use(passport.initialize());
app.use(passport.session());

// Disable cache for HTML/JS/CSS (dashboard) and root path
app.use((req, res, next) => {
    if (req.path === '/' || /\.(html|js|css)$/.test(req.path)) {
        res.set('Cache-Control', 'no-store, no-cache, must-revalidate');
        res.set('Pragma', 'no-cache');
        res.set('Expires', '0');
    }
    next();
});

function isAuthorized(req) {
    return req.isAuthenticated() && req.user && req.user.email === ALLOWED_EMAIL;
}

function requireDashboardAuth(req, res, next) {
    if (isAuthorized(req)) return next();
    if (req.isAuthenticated()) {
        return res.status(403).redirect('/forbidden');
    }
    res.redirect('/auth/google');
}

// ── Auth routes ───────────────────────────────────────────────────────────────

app.get('/auth/google',
    passport.authenticate('google', { scope: ['profile', 'email'], prompt: 'select_account' })
);

app.get('/auth/google/callback',
    (req, res, next) => {
        passport.authenticate('google', (err, user, info) => {
            if (err)  { console.error('OAuth error:', err); return res.redirect('/forbidden'); }
            if (!user){ console.warn('OAuth no user:', info); return res.redirect('/forbidden'); }
            req.logIn(user, e => {
                if (e) { console.error('OAuth login error:', e); return res.redirect('/forbidden'); }
                if (user.email === ALLOWED_EMAIL) return res.redirect('/');
                console.warn(`OAuth: email no autorizado: ${user.email}`);
                res.redirect('/forbidden');
            });
        })(req, res, next);
    }
);

app.get('/auth/logout', (req, res) => {
    req.logout(() => req.session.destroy(() => res.redirect('/auth/google')));
});

app.get('/forbidden', (req, res) => {
    const email = (req.user && req.user.email) ? req.user.email : 'desconocido';
    res.status(403).send(`<!DOCTYPE html>
<html lang="es"><head><meta charset="UTF-8"><title>Acceso denegado — Controlex</title>
<style>
  *,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
  body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
       background:#1a0000;color:#fff;min-height:100vh;display:flex;
       align-items:center;justify-content:center;padding:20px;text-align:center}
  .box{max-width:720px}
  .icon{font-size:7rem;margin-bottom:24px}
  h1{font-size:3.5rem;color:#ff3b3b;letter-spacing:3px;margin-bottom:18px;
     text-shadow:0 0 20px rgba(255,59,59,.6)}
  .email{font-family:monospace;background:rgba(255,255,255,.08);
         padding:8px 16px;border-radius:6px;display:inline-block;margin:14px 0;
         font-size:1.1rem;color:#ffb3b3}
  p{font-size:1.2rem;line-height:1.6;color:#ddd;margin-bottom:14px}
  a.btn{display:inline-block;margin-top:30px;padding:12px 28px;
        background:#ff3b3b;color:#fff;text-decoration:none;border-radius:8px;
        font-weight:600;letter-spacing:1px;transition:background .15s}
  a.btn:hover{background:#e02020}
</style></head>
<body>
  <div class="box">
    <div class="icon">⛔</div>
    <h1>ACCESO DENEGADO</h1>
    <p>Esta cuenta no está autorizada para acceder al panel de control de Controlex.</p>
    <p>Cuenta utilizada:</p>
    <div class="email">${email.replace(/[<>&"]/g, c => ({'<':'&lt;','>':'&gt;','&':'&amp;','"':'&quot;'}[c]))}</div>
    <p>Solo el administrador del centro tiene acceso a este panel.</p>
    <a class="btn" href="/auth/logout">Cerrar sesión y volver a intentar</a>
  </div>
</body></html>`);
});

// ── Static (only for authorized dashboard users) ──────────────────────────────
// Serve index.html and dashboard assets ONLY behind auth.
app.get('/', requireDashboardAuth, (req, res) => {
    // Inject the current plugin version into the toolbar so the user sees it
    // immediately (no flash from a fetch /api/version round-trip).
    let html = fs.readFileSync(path.join(__dirname, 'public', 'index.html'), 'utf8');
    const v = currentVersion();
    html = html.replace('id="tb-ver"></span>', `id="tb-ver">v${v}</span>`);
    res.set('Content-Type', 'text/html; charset=utf-8');
    res.set('Cache-Control', 'no-store, no-cache, must-revalidate');
    res.send(html);
});

// Self-hosted JS bundles (CodeMirror for Pair, etc.). Public on purpose:
// they are static JS, no secrets, and self-hosting avoids school proxies
// blocking esm.run / esm.sh / unpkg.
app.use('/vendor', express.static(path.join(__dirname, 'public/vendor'), {
    maxAge: '1d', immutable: false
}));

// ── Category persistence ──────────────────────────────────────────────────────

const extraCategories = (() => {
    try {
        fs.mkdirSync(path.dirname(CATEGORIES_PATH), { recursive: true });
        if (fs.existsSync(CATEGORIES_PATH)) {
            return new Map(Object.entries(JSON.parse(fs.readFileSync(CATEGORIES_PATH, 'utf8'))));
        }
    } catch (e) { console.warn('[controlex] no se pudo leer categories.json:', e.message); }
    return new Map();
})();

function persistCategories() {
    try {
        const obj = Object.fromEntries(extraCategories);
        fs.writeFileSync(CATEGORIES_PATH, JSON.stringify(obj, null, 2));
    } catch (e) { console.warn('[controlex] no se pudo escribir categories.json:', e.message); }
}

// ── Quality persistence ───────────────────────────────────────────────────────
// Three independent contexts:
//   - archive: PNG saved to the local zip on the student's machine. Only maxWidth.
//   - panel:   JPEG snapshots transmitted periodically to this server. jpegQuality + maxWidth.
//   - live:    JPEG WebSocket frames. jpegQuality + maxWidth + fps.
// Per-context settings are nested inside each scope:
//   { global: { archive:{...}, panel:{...}, live:{...} },
//     byCategory: { name: {archive,panel,live} }, byClient: {...} }
// Older flat-shape configs are migrated on load.

const QUALITY_CONTEXTS = ['archive', 'panel', 'live'];

function migrateLegacyScope(scope) {
    if (!scope || typeof scope !== 'object') return {};
    if (QUALITY_CONTEXTS.some(c => c in scope)) return scope; // already new
    const out = {};
    if (scope.maxWidth != null) {
        out.archive = { maxWidth: scope.maxWidth };
        out.panel   = { maxWidth: scope.maxWidth };
        out.live    = { maxWidth: scope.maxWidth };
    }
    if (scope.jpegQuality != null) {
        out.panel = { ...(out.panel || {}), jpegQuality: scope.jpegQuality };
        out.live  = { ...(out.live  || {}), jpegQuality: scope.jpegQuality };
    }
    if (scope.fps != null) {
        out.live = { ...(out.live || {}), fps: scope.fps };
    }
    return out;
}

const qualityState = (() => {
    const fallback = { global: {}, byCategory: {}, byClient: {} };
    try {
        fs.mkdirSync(path.dirname(QUALITY_PATH), { recursive: true });
        if (fs.existsSync(QUALITY_PATH)) {
            const raw = JSON.parse(fs.readFileSync(QUALITY_PATH, 'utf8'));
            const out = {
                global:     migrateLegacyScope(raw.global || {}),
                byCategory: {},
                byClient:   {}
            };
            for (const k of Object.keys(raw.byCategory || {})) out.byCategory[k] = migrateLegacyScope(raw.byCategory[k]);
            for (const k of Object.keys(raw.byClient   || {})) out.byClient[k]   = migrateLegacyScope(raw.byClient[k]);
            return out;
        }
    } catch (e) { console.warn('[controlex] no se pudo leer quality.json:', e.message); }
    return fallback;
})();

function persistQuality() {
    try { fs.writeFileSync(QUALITY_PATH, JSON.stringify(qualityState, null, 2)); }
    catch (e) { console.warn('[controlex] no se pudo escribir quality.json:', e.message); }
}

function sanitizeContext(ctx, input) {
    const out = {};
    if (!input || typeof input !== 'object') return out;
    const num = (v, lo, hi) => Number.isFinite(+v) ? Math.max(lo, Math.min(hi, Math.round(+v))) : null;
    const fmt = (v) => (v === 'png' || v === 'jpeg') ? v : null;
    if (ctx === 'archive') {
        const w = num(input.maxWidth, 0, 3840);    if (w != null) out.maxWidth = w;
    } else if (ctx === 'panel') {
        const j = num(input.jpegQuality, 1, 100);  if (j != null) out.jpegQuality = j;
        const w = num(input.maxWidth, 0, 3840);    if (w != null) out.maxWidth    = w;
        const f = fmt(input.format);               if (f != null) out.format      = f;
    } else if (ctx === 'live') {
        const j = num(input.jpegQuality, 1, 100);  if (j != null) out.jpegQuality = j;
        const w = num(input.maxWidth, 0, 3840);    if (w != null) out.maxWidth    = w;
        const fp = num(input.fps,        1, 15);   if (fp != null) out.fps        = fp;
        const f = fmt(input.format);               if (f != null) out.format      = f;
    }
    return out;
}

function sanitizeQuality(input) {
    const out = {};
    for (const ctx of QUALITY_CONTEXTS) {
        const c = sanitizeContext(ctx, input?.[ctx]);
        if (Object.keys(c).length) out[ctx] = c;
    }
    return out;
}

function effectiveQuality(clientId) {
    const c = clients.get(clientId);
    const cat = c?.categoryMain || null;
    const layers = [
        qualityState.global || {},
        cat ? (qualityState.byCategory[cat] || {}) : {},
        qualityState.byClient[clientId] || {}
    ];
    const eff = {};
    for (const ctx of QUALITY_CONTEXTS) {
        const merged = Object.assign({}, ...layers.map(l => l[ctx] || {}));
        if (Object.keys(merged).length) eff[ctx] = merged;
    }
    return eff;
}

function pushQuality(clientId) {
    const eff = effectiveQuality(clientId);
    if (Object.keys(eff).length === 0) return false;
    return pushCommand(clientId, { type: 'quality-set', ...eff });
}

// In-memory state
const clients = new Map();
const pendingCommands = new Map();
const sseClients = new Set();
const clientStreams = new Map();  // clientId -> Set<res> (SSE command streams)
const videoSenders  = new Map();  // clientId -> WebSocket (plugin video uploads)
const videoViewers  = new Map();  // clientId -> Set<WebSocket> (dashboard live views)
const videoTokens   = new Map();  // token -> { clientId, expires }
let seqCounter = 0;

// ── Helpers ───────────────────────────────────────────────────────────────────

function clientView(c) {
    const ageMs = Date.now() - new Date(c.lastSeen).getTime();
    const thresholdMs = c.transmitFreqSeconds * 1000 + 10_000;
    return {
        clientId:            c.clientId,
        seqNum:              c.seqNum,
        ip:                  c.ip,
        hostname:            c.hostname,
        projectName:         c.projectName,
        intellijUser:        c.intellijUser,
        osUser:              c.osUser,
        captureFreqMin:      c.captureFreqMin,
        captureFreqMax:      c.captureFreqMax,
        transmitFreqSeconds: c.transmitFreqSeconds,
        lastSeen:            c.lastSeen,
        online:              ageMs < thresholdMs,
        name:                c.name || '',
        categoryMain:        c.categoryMain || null,
        nickname:            c.nickname || null,
        extraCategories:     extraCategories.get(c.clientId) || []
    };
}

function broadcast(event, data) {
    const payload = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
    for (const res of sseClients) {
        try { res.write(payload); }
        catch (_) {
            // dead connection — drop it so it doesn't pile up
            sseClients.delete(res);
            try { res.end(); } catch (_) {}
        }
    }
}

// ── Plugin distribution (public) ──────────────────────────────────────────────

const PUBLIC_DIR = path.join(__dirname, 'public');
const ZIP_RE = /^controlex-([\d.]+)\.zip$/;

function semverCompare(a, b) {
    const pa = a.split('.').map(Number);
    const pb = b.split('.').map(Number);
    for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
        const da = pa[i] || 0, db = pb[i] || 0;
        if (da !== db) return da - db;
    }
    return 0;
}

function listVersions() {
    return fs.readdirSync(PUBLIC_DIR)
        .map(f => { const m = f.match(ZIP_RE); return m ? { file: f, version: m[1] } : null; })
        .filter(Boolean)
        .sort((a, b) => semverCompare(b.version, a.version));
}

function currentVersion() {
    const list = listVersions();
    return list[0] ? list[0].version : '0.0.0';
}

const GITHUB_REPO = 'ebarrabc2526/controlex';
const _releaseCache = new Map(); // tag → { data, expiresAt }
const RELEASE_TTL_MS = 10 * 60 * 1000;

async function fetchReleaseByTag(version) {
    const tag = `v${version}`;
    const cached = _releaseCache.get(tag);
    if (cached && Date.now() < cached.expiresAt) return cached.data;
    try {
        const r = await fetch(`https://api.github.com/repos/${GITHUB_REPO}/releases/tags/${tag}`, {
            headers: { 'User-Agent': 'controlex-server', 'Accept': 'application/vnd.github+json' },
            signal: AbortSignal.timeout(5000),
        });
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        const j = await r.json();
        const data = { tag, name: j.name || tag, body: j.body || '' };
        _releaseCache.set(tag, { data, expiresAt: Date.now() + RELEASE_TTL_MS });
        return data;
    } catch (e) {
        console.warn(`[controlex] release fetch ${tag} failed:`, e.message);
        return cached ? cached.data : null;
    }
}

function escapeHTML(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
                    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

// Minimal markdown subset: ## headings, - lists, `code`, **bold**, [text](url), paragraphs.
function renderMarkdown(md) {
    if (!md) return '';
    const lines = escapeHTML(md).replace(/\r\n/g, '\n').split('\n');
    const blocks = [];
    let buf = [];
    const flush = () => { if (buf.length) { blocks.push(buf.join('\n')); buf = []; } };
    for (const ln of lines) (ln.trim() === '' ? flush() : buf.push(ln));
    flush();
    const inline = s => s
        .replace(/\\([`*\[\]()_~])/g, '$1')
        .replace(/`([^`]+)`/g, '<code>$1</code>')
        .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
        .replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');
    return blocks.map(blk => {
        const ls = blk.split('\n');
        if (ls.every(l => /^\s*-\s+/.test(l))) {
            return '<ul>' + ls.map(l => `<li>${inline(l.replace(/^\s*-\s+/, ''))}</li>`).join('') + '</ul>';
        }
        if (ls[0].startsWith('### ')) return `<h4>${inline(ls[0].slice(4))}</h4>`;
        if (ls[0].startsWith('## '))  return `<h3>${inline(ls[0].slice(3))}</h3>`;
        if (ls[0].startsWith('# '))   return `<h2>${inline(ls[0].slice(2))}</h2>`;
        return `<p>${inline(ls.join('<br>'))}</p>`;
    }).join('\n');
}

// Legacy: /plugin (used by IntelliJ via updatePlugins.xml). Always serves the latest.
app.get('/plugin', (req, res) => {
    const v = currentVersion();
    res.download(path.join(PUBLIC_DIR, `controlex-${v}.zip`), `controlex-${v}.zip`, err => {
        if (err) res.status(404).send('Plugin no disponible');
    });
});

app.get('/api/version', (req, res) => res.json({ version: currentVersion() }));

// IntelliJ custom plugin repository: add this URL in
//   Settings → Plugins → ⚙ → Manage Plugin Repositories…
// IntelliJ will poll it at startup and when the user runs "Check for updates".
app.get('/updatePlugins.xml', (req, res) => {
    const v = currentVersion();
    const xml = `<?xml version="1.0" encoding="UTF-8"?>
<plugins>
    <plugin id="es.iesclaradelrey.controlex"
            url="https://controlex.ebarrab.com/plugin"
            version="${v}">
        <name>Controlex</name>
        <vendor email="ebarrabc2526@gmail.com" url="https://iesclaradelrey.es">IES Clara del Rey</vendor>
        <idea-version since-build="242"/>
        <description><![CDATA[Plugin de control para exámenes de programación Java en IntelliJ IDEA.]]></description>
        <change-notes><![CDATA[Historial completo en https://github.com/ebarrabc2526/controlex/releases]]></change-notes>
    </plugin>
</plugins>`;
    res.set('Content-Type', 'application/xml; charset=utf-8');
    res.set('Cache-Control', 'no-store');
    res.send(xml);
});

// Public landing page (proxied at https://ebarrab.com/controlex)
app.get('/controlex', async (req, res) => {
    const versions = listVersions();
    const release = versions[0] ? await fetchReleaseByTag(versions[0].version) : null;
    res.set('Cache-Control', 'no-store');
    res.set('Content-Type', 'text/html; charset=utf-8');
    res.send(renderLanding(versions, release));
});

app.get('/controlex/download/:version', (req, res) => {
    const v = req.params.version;
    if (!/^[\d.]+$/.test(v)) return res.status(400).send('Versión inválida');
    const fp = path.join(PUBLIC_DIR, `controlex-${v}.zip`);
    if (!fs.existsSync(fp)) return res.status(404).send('Versión no encontrada');
    res.download(fp, `controlex-${v}.zip`);
});

function renderLanding(versions, release) {
    const current = versions[0] || { version: '?' };
    const recent = versions.slice(1, 6);
    const older = versions.slice(6);
    const ghTag = v => `https://github.com/${GITHUB_REPO}/releases/tag/v${v}`;
    const item = v => `<li><span>v${v.version}</span><span class="row-actions"><a href="${ghTag(v.version)}" target="_blank" rel="noopener">Notas</a> · <a href="/controlex/download/${v.version}">Descargar</a></span></li>`;
    const REPO_URL = 'https://controlex.ebarrab.com/updatePlugins.xml';
    const releaseHTML = release && release.body
        ? `<div class="release-notes">${renderMarkdown(release.body)}<p class="meta"><a href="${ghTag(current.version)}" target="_blank" rel="noopener">Ver en GitHub →</a></p></div>`
        : `<p class="meta">Notas no disponibles. <a href="${ghTag(current.version)}" target="_blank" rel="noopener">Ver en GitHub →</a></p>`;
    return `<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Controlex — Plugin para IntelliJ IDEA</title>
<style>
  body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", system-ui, sans-serif; max-width: 760px; margin: 2rem auto; padding: 0 1rem; line-height: 1.55; color: #222; }
  h1 { font-size: 1.9rem; margin: 0 0 0.2rem; }
  h2 { margin-top: 2.2rem; border-bottom: 1px solid #e5e5e5; padding-bottom: 0.3rem; font-size: 1.2rem; }
  p.lead { color: #555; }
  code { background: #f3f3f3; padding: 0.1em 0.4em; border-radius: 3px; font-size: 0.92em; }
  ol { padding-left: 1.4rem; }
  ol li { margin-bottom: 0.5rem; }
  .download-btn { display: inline-block; padding: 0.85rem 1.5rem; background: #2563eb; color: white !important; text-decoration: none; border-radius: 6px; font-weight: 600; }
  .download-btn:hover { background: #1d4ed8; }
  .meta { font-size: 0.9rem; color: #777; margin-left: 0.7rem; }
  .repo-url { background: #f3f3f3; padding: 0.6rem 0.8rem; border-radius: 4px; font-family: ui-monospace, monospace; word-break: break-all; display: flex; align-items: center; justify-content: space-between; gap: 0.5rem; margin: 0.3rem 0; }
  .copy-btn { font-size: 0.8rem; padding: 0.25rem 0.7rem; background: white; border: 1px solid #ddd; border-radius: 3px; cursor: pointer; flex-shrink: 0; }
  .copy-btn:hover { background: #f7f7f7; }
  ul.versions { list-style: none; padding: 0; }
  ul.versions li { padding: 0.45rem 0.2rem; border-bottom: 1px solid #f0f0f0; display: flex; justify-content: space-between; }
  .row-actions { font-size: 0.92rem; }
  .release-notes { background: #fafafa; border: 1px solid #eee; border-radius: 6px; padding: 0.8rem 1.2rem; margin-top: 0.6rem; }
  .release-notes h2 { font-size: 1.05rem; margin: 1rem 0 0.4rem; border-bottom: none; padding-bottom: 0; }
  .release-notes h3 { font-size: 1rem; margin: 0.9rem 0 0.3rem; color: #333; }
  .release-notes h4 { font-size: 0.95rem; margin: 0.7rem 0 0.2rem; color: #555; }
  .release-notes ul { padding-left: 1.3rem; }
  .release-notes li { margin: 0.2rem 0; }
  .release-notes p { margin: 0.5rem 0; }
  ul.versions a { color: #2563eb; text-decoration: none; }
  ul.versions a:hover { text-decoration: underline; }
  details { margin-top: 0.8rem; }
  summary { cursor: pointer; color: #555; padding: 0.3rem 0; }
  .footer { margin-top: 3rem; color: #888; font-size: 0.85rem; text-align: center; }
  .footer a { color: #888; }
</style>
</head>
<body>
<h1>Controlex</h1>
<p class="lead">Plugin de control para exámenes de programación Java en IntelliJ IDEA.</p>

<h2>Descarga</h2>
<a class="download-btn" href="/controlex/download/${current.version}">⬇ Descargar v${current.version}</a>
<span class="meta">.zip · IntelliJ IDEA 2024.2+</span>

<h2>Novedades de v${current.version}</h2>
${releaseHTML}

<h2>Auto-actualización (recomendado)</h2>
<p>Configura el repositorio personalizado en IntelliJ y olvídate de descargar manualmente:</p>
<ol>
  <li>En IntelliJ: <code>Settings</code> → <code>Plugins</code> → ⚙ → <code>Manage Plugin Repositories…</code></li>
  <li>Pulsa <code>+</code> y añade esta URL:
    <div class="repo-url"><span id="repoUrl">${REPO_URL}</span><button class="copy-btn" onclick="copyRepoUrl(this)">Copiar</button></div>
  </li>
  <li><code>OK</code> → <code>Apply</code>. IntelliJ comprobará nuevas versiones al iniciar y al pulsar <code>Check for updates</code>.</li>
  <li>Si es la primera vez, instala el plugin desde el Marketplace (busca "Controlex") o usa la instalación manual de abajo.</li>
</ol>

<h2>Instalación manual desde ZIP</h2>
<ol>
  <li>Descarga el ZIP del botón de arriba.</li>
  <li>En IntelliJ: <code>Settings</code> → <code>Plugins</code> → ⚙ → <code>Install Plugin from Disk…</code></li>
  <li>Selecciona el ZIP descargado y reinicia IntelliJ.</li>
</ol>

<h2>Versiones anteriores</h2>
${recent.length ? `<ul class="versions">${recent.map(item).join('')}</ul>` : '<p class="meta">No hay versiones anteriores recientes.</p>'}
${older.length ? `<details><summary>Ver todas las versiones (${older.length} más)</summary><ul class="versions">${older.map(item).join('')}</ul></details>` : ''}

<div class="footer">
  Controlex · IES Clara del Rey · <a href="https://github.com/ebarrabc2526/controlex/releases">Notas de cada versión</a>
</div>

<script>
function copyRepoUrl(btn) {
  const url = document.getElementById('repoUrl').textContent;
  navigator.clipboard.writeText(url).then(() => {
    const old = btn.textContent;
    btn.textContent = '✓ Copiado';
    setTimeout(() => btn.textContent = old, 2000);
  });
}
</script>
</body>
</html>`;
}

// ── Auth middleware for client uploads ────────────────────────────────────────

function requireClientAuth(req, res, next) {
    const auth = req.headers['authorization'];
    if (!auth || auth !== `Bearer ${API_KEY}`) {
        return res.status(401).json({ error: 'No autorizado' });
    }
    next();
}

// ── Client API (public, Bearer auth) ──────────────────────────────────────────

// Client: graceful disconnect notification (called from plugin dispose)
app.post('/api/disconnect', requireClientAuth, (req, res) => {
    const { clientId } = req.body || {};
    const c = clients.get(String(clientId || ''));
    if (c) {
        // Force "long ago" lastSeen so it's instantly past the offline threshold
        c.lastSeen = new Date(Date.now() - (c.transmitFreqSeconds * 1000 + 10_000) - 1000);
        onlineState.set(c.clientId, false);
        broadcast('offline', clientView(c));
    }
    res.json({ ok: true });
});

app.post('/api/screenshot', requireClientAuth, (req, res) => {
    const {
        clientId, ip, hostname, projectName, intellijUser, osUser,
        captureFreqMin, captureFreqMax, transmitFreqSeconds, screenshot, name
    } = req.body;

    if (!clientId || !screenshot) {
        return res.status(400).json({ error: 'Faltan campos requeridos: clientId, screenshot' });
    }

    const rawName = String(name || '').trim();
    let categoryMain = null, nickname = rawName || null;
    if (rawName.includes('#')) {
        const idx = rawName.indexOf('#');
        categoryMain = rawName.slice(0, idx) || null;
        nickname     = rawName.slice(idx + 1) || null;
    }

    const existing = clients.get(clientId);
    const seqNum = existing ? existing.seqNum : ++seqCounter;

    // When a new clientId appears for an already-known machine (hostname +
    // intellijUser + osUser), evict the prior entries instantly. This kicks in
    // every time the plugin's clientId changes — e.g., when the alumno asigna
    // un nombre por primera vez (legacy UUID → deterministic sha256), o cada
    // vez que renombra (la categoría/apodo formaba parte del hash). Sin esto
    // la entrada anterior queda colgada como "(sin categoría)" hasta el prune.
    if (!existing) {
        const h = String(hostname || 'unknown');
        const iu = String(intellijUser || 'unknown');
        const ou = String(osUser || 'unknown');
        for (const [otherId, c] of clients) {
            if (otherId !== clientId && c.hostname === h && c.intellijUser === iu && c.osUser === ou) {
                clients.delete(otherId);
                onlineState.delete(otherId);
                broadcast('remove', { clientId: otherId });
            }
        }
    }

    clients.set(clientId, {
        clientId,
        seqNum,
        ip:                 String(ip || 'unknown'),
        hostname:           String(hostname || 'unknown'),
        projectName:        String(projectName || 'unknown'),
        intellijUser:       String(intellijUser || 'unknown'),
        osUser:             String(osUser || 'unknown'),
        captureFreqMin:     Number(captureFreqMin)     || 30,
        captureFreqMax:     Number(captureFreqMax)     || 120,
        transmitFreqSeconds:Number(transmitFreqSeconds)|| 30,
        lastSeen:           new Date(),
        screenshotData:     Buffer.from(screenshot, 'base64'),
        name:               rawName,
        categoryMain,
        nickname
    });

    if (!pendingCommands.has(clientId)) pendingCommands.set(clientId, []);
    const commands = pendingCommands.get(clientId).splice(0);

    broadcast(existing ? 'update' : 'add', clientView(clients.get(clientId)));

    // Resync quality config with the client whenever its category changes
    // (or on first registration). Cheap to repeat; the plugin is idempotent.
    if (!existing || existing.categoryMain !== categoryMain) pushQuality(clientId);

    res.json({ commands });
});

// Client: SSE stream — server pushes commands in real time
app.get('/api/client/stream', requireClientAuth, (req, res) => {
    const clientId = String(req.query.clientId || '');
    if (!clientId) return res.status(400).json({ error: 'clientId requerido' });

    res.set({
        'Content-Type':      'text/event-stream',
        'Cache-Control':     'no-cache, no-transform',
        'Connection':        'keep-alive',
        'X-Accel-Buffering': 'no'
    });
    res.flushHeaders();
    res.write(`event: hello\ndata: ${JSON.stringify({ clientId })}\n\n`);

    if (!clientStreams.has(clientId)) clientStreams.set(clientId, new Set());
    clientStreams.get(clientId).add(res);

    // Drain any commands already queued via /api/screenshot polling path
    const queued = (pendingCommands.get(clientId) || []).splice(0);
    for (const cmd of queued) {
        res.write(`event: command\ndata: ${JSON.stringify(cmd)}\n\n`);
    }

    const hb = setInterval(() => {
        try { res.write(': heartbeat\n\n'); }
        catch (_) {
            clearInterval(hb);
            clientStreams.get(clientId)?.delete(res);
        }
    }, 10_000);

    const cleanup = () => {
        clearInterval(hb);
        const set = clientStreams.get(clientId);
        if (set) { set.delete(res); if (set.size === 0) clientStreams.delete(clientId); }
    };
    req.on('close', cleanup);
    req.on('error', cleanup);
    res.on('error', cleanup);
});

function pushCommand(clientId, cmd) {
    const signed = signCmd(cmd);
    const set = clientStreams.get(clientId);
    if (!set || set.size === 0) {
        // Fallback: queue for next /api/screenshot poll
        if (!pendingCommands.has(clientId)) pendingCommands.set(clientId, []);
        pendingCommands.get(clientId).push(signed);
        return false;
    }
    const payload = `event: command\ndata: ${JSON.stringify(signed)}\n\n`;
    let delivered = 0;
    for (const r of set) {
        try { r.write(payload); delivered++; }
        catch (_) { set.delete(r); }
    }
    return delivered > 0;
}

// Multichat persistence (teacher ↔ student conversations).
// Key=clientId → array of {id, who:'teacher'|'student', text, at, read}
// Read flag is for incoming (student) messages so the panel can show an
// unread counter; teacher messages are always read=true.
let chatStore = (() => {
    try {
        fs.mkdirSync(path.dirname(CHAT_PATH), { recursive: true });
        if (fs.existsSync(CHAT_PATH)) {
            const raw = JSON.parse(fs.readFileSync(CHAT_PATH, 'utf8'));
            return (raw && typeof raw === 'object') ? raw : {};
        }
    } catch (e) { console.warn('[controlex] chat load:', e.message); }
    return {};
})();
function persistChat() {
    try { fs.writeFileSync(CHAT_PATH, JSON.stringify(chatStore)); }
    catch (e) { console.warn('[controlex] chat persist:', e.message); }
}
function addChatEntry(clientId, who, text) {
    if (!chatStore[clientId]) chatStore[clientId] = [];
    const entry = {
        id:   crypto.randomBytes(6).toString('hex'),
        who, text: String(text || '').slice(0, 4000),
        at:   new Date().toISOString(),
        read: who === 'teacher'   // teacher's own messages are auto-read
    };
    chatStore[clientId].push(entry);
    if (chatStore[clientId].length > MAX_CHAT_ENTRIES_PER_CLIENT) {
        chatStore[clientId].splice(0, chatStore[clientId].length - MAX_CHAT_ENTRIES_PER_CLIENT);
    }
    persistChat();
    // Send a snapshot of the relevant client view so the panel can render the
    // sidebar entry even before any /api/dashboard/clients call.
    const c = clients.get(String(clientId));
    const view = c ? clientView(c) : null;
    broadcast('chat-message', { clientId, entry, client: view });
    return entry;
}

// Help-request persistence (history that survives restarts).
let helpRequests = (() => {
    try {
        fs.mkdirSync(path.dirname(HELP_PATH), { recursive: true });
        if (fs.existsSync(HELP_PATH)) {
            const raw = JSON.parse(fs.readFileSync(HELP_PATH, 'utf8'));
            return Array.isArray(raw) ? raw : [];
        }
    } catch (e) { console.warn('[controlex] help-requests load:', e.message); }
    return [];
})();
function persistHelpRequests() {
    try { fs.writeFileSync(HELP_PATH, JSON.stringify(helpRequests, null, 2)); }
    catch (e) { console.warn('[controlex] help-requests persist:', e.message); }
}

// Client: student requests help
app.post('/api/help-request', requireClientAuth, (req, res) => {
    const { clientId, text } = req.body || {};
    if (!clientId) return res.status(400).json({ error: 'clientId requerido' });

    const c = clients.get(String(clientId));
    const view = c ? clientView(c) : { clientId };

    const entry = {
        id: crypto.randomBytes(8).toString('hex'),
        clientId: String(clientId),
        seqNum:       view.seqNum       ?? null,
        nickname:     view.nickname     ?? null,
        osUser:       view.osUser       ?? null,
        ip:           view.ip           ?? null,
        categoryMain: view.categoryMain ?? null,
        text: String(text || '').slice(0, 1000),
        at:   new Date().toISOString(),
        resolved:   false,
        resolvedAt: null
    };
    helpRequests.unshift(entry);
    if (helpRequests.length > MAX_HELP_REQUESTS) helpRequests.length = MAX_HELP_REQUESTS;
    persistHelpRequests();
    broadcast('help-request', entry);
    // También al chat para que aparezca como mensaje del alumno en el hilo.
    if (entry.text) addChatEntry(entry.clientId, 'student', entry.text);
    res.json({ ok: true });
});

// List historical help-requests (newest first)
app.get('/api/dashboard/help-requests', requireApiAuth, (req, res) => {
    res.json(helpRequests);
});

// Toggle resolved on a single entry
app.patch('/api/dashboard/help-requests/:id', requireApiAuth, (req, res) => {
    const entry = helpRequests.find(h => h.id === req.params.id);
    if (!entry) return res.status(404).json({ error: 'No existe' });
    if (typeof req.body?.resolved === 'boolean') {
        entry.resolved = req.body.resolved;
        entry.resolvedAt = req.body.resolved ? new Date().toISOString() : null;
    }
    persistHelpRequests();
    broadcast('help-request-update', { id: entry.id, resolved: entry.resolved, resolvedAt: entry.resolvedAt });
    res.json({ ok: true });
});

// Delete a single entry
app.delete('/api/dashboard/help-requests/:id', requireApiAuth, (req, res) => {
    const idx = helpRequests.findIndex(h => h.id === req.params.id);
    if (idx === -1) return res.status(404).json({ error: 'No existe' });
    helpRequests.splice(idx, 1);
    persistHelpRequests();
    broadcast('help-request-removed', { id: req.params.id });
    res.json({ ok: true });
});

// Bulk: { action: 'delete'|'resolve'|'unresolve', ids: [...] }
app.post('/api/dashboard/help-requests/bulk', requireApiAuth, (req, res) => {
    const { action, ids } = req.body || {};
    if (!Array.isArray(ids) || ids.length === 0) return res.status(400).json({ error: 'ids vacío' });
    const set = new Set(ids);
    if (action === 'delete') {
        helpRequests = helpRequests.filter(h => !set.has(h.id));
    } else if (action === 'resolve' || action === 'unresolve') {
        const r = action === 'resolve';
        for (const h of helpRequests) {
            if (set.has(h.id)) { h.resolved = r; h.resolvedAt = r ? new Date().toISOString() : null; }
        }
    } else {
        return res.status(400).json({ error: "action debe ser 'delete', 'resolve' o 'unresolve'" });
    }
    persistHelpRequests();
    broadcast('help-request-bulk', { action, ids });
    res.json({ ok: true });
});

// ── Dashboard API (require Google OAuth) ──────────────────────────────────────

function requireApiAuth(req, res, next) {
    if (isAuthorized(req)) return next();
    res.status(401).json({ error: 'No autorizado' });
}

app.get('/api/dashboard/stream', requireApiAuth, (req, res) => {
    res.set({
        'Content-Type':    'text/event-stream',
        'Cache-Control':   'no-cache, no-transform',
        'Connection':      'keep-alive',
        'X-Accel-Buffering': 'no'
    });
    res.flushHeaders();

    const snapshot = Array.from(clients.values())
        .sort((a, b) => a.seqNum - b.seqNum)
        .map(clientView);
    res.write(`event: snapshot\ndata: ${JSON.stringify(snapshot)}\n\n`);

    sseClients.add(res);

    // Heartbeat every 10s — also acts as dead-connection probe
    const hb = setInterval(() => {
        try { res.write(': heartbeat\n\n'); }
        catch (_) {
            clearInterval(hb);
            sseClients.delete(res);
            try { res.end(); } catch (_) {}
        }
    }, 10_000);

    const cleanup = () => { clearInterval(hb); sseClients.delete(res); };
    req.on('close', cleanup);
    req.on('error', cleanup);
    res.on('error', cleanup);
});

app.get('/api/dashboard/clients', requireApiAuth, (req, res) => {
    const list = Array.from(clients.values())
        .sort((a, b) => a.seqNum - b.seqNum)
        .map(clientView);
    res.json(list);
});

// Detect image MIME from magic bytes so the dashboard can send either JPEG or PNG
// transparently (the plugin chooses the format per-context in QualityConfig).
function detectImageMime(buf) {
    if (!buf || buf.length < 4) return 'image/jpeg';
    if (buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4E && buf[3] === 0x47) return 'image/png';
    if (buf[0] === 0xFF && buf[1] === 0xD8 && buf[2] === 0xFF) return 'image/jpeg';
    return 'image/jpeg';
}

app.get('/api/dashboard/screenshot/:clientId', requireApiAuth, (req, res) => {
    const client = clients.get(req.params.clientId);
    if (!client || !client.screenshotData) return res.status(404).send('Sin captura disponible');
    res.set('Content-Type', detectImageMime(client.screenshotData));
    res.set('Cache-Control', 'no-store');
    res.send(client.screenshotData);
});

app.post('/api/dashboard/message', requireApiAuth, (req, res) => {
    const { clientId, text } = req.body;
    if (!text || !String(text).trim()) return res.status(400).json({ error: 'El campo text es obligatorio' });
    const cleanText = String(text).trim().slice(0, 4000);
    const targets = clientId === '*' ? Array.from(clients.keys()) : [clientId].filter(id => clients.has(id));
    for (const id of targets) {
        // pushCommand firma + envía vía SSE (con fallback a queue). Antes esto
        // metía un {type:'message'} sin firmar que el plugin descartaba.
        pushCommand(id, { type: 'show-dialog', title: 'Mensaje del profesor', text: cleanText });
        addChatEntry(id, 'teacher', cleanText);
    }
    res.json({ ok: true, count: targets.length });
});

// Allowlist of IntelliJ action IDs the dashboard is allowed to trigger
const ALLOWED_ACTIONS = new Set([
    'SaveAll', 'Synchronize', 'CompileDirty',
    'Run', 'Stop', 'Debug', 'Rerun',
    'EditorCopy', 'EditorPaste', 'EditorSelectAll',
    'ToggleLineBreakpoint',
    'Find', 'Replace',
    'ReformatCode'
]);

app.post('/api/dashboard/command', requireApiAuth, (req, res) => {
    const { clientId, type, payload } = req.body || {};
    if (!clientId || !type) return res.status(400).json({ error: 'clientId y type son obligatorios' });

    const cmd = { type };
    switch (type) {
        case 'show-dialog':
            if (!payload?.text) return res.status(400).json({ error: 'payload.text obligatorio' });
            cmd.text     = String(payload.text).slice(0, 4000);
            cmd.imageUrl = payload.imageUrl ? String(payload.imageUrl).slice(0, 1000) : null;
            cmd.title    = String(payload.title || 'Mensaje del profesor').slice(0, 200);
            break;
        case 'open-file':
            if (!payload?.path) return res.status(400).json({ error: 'payload.path obligatorio' });
            cmd.path = String(payload.path).slice(0, 500);
            cmd.line = Number.isInteger(payload.line) ? payload.line : null;
            break;
        case 'goto-line':
            if (!Number.isInteger(payload?.line)) return res.status(400).json({ error: 'payload.line obligatorio' });
            cmd.line = payload.line;
            cmd.path = payload.path ? String(payload.path).slice(0, 500) : null;
            break;
        case 'run-action':
            if (!payload?.actionId || !ALLOWED_ACTIONS.has(payload.actionId)) {
                return res.status(400).json({ error: 'actionId no permitido' });
            }
            cmd.actionId = payload.actionId;
            break;
        case 'insert-text':
            if (!payload?.text) return res.status(400).json({ error: 'payload.text obligatorio' });
            cmd.text = String(payload.text).slice(0, 10000);
            cmd.path = payload.path ? String(payload.path).slice(0, 500) : null;
            cmd.line = Number.isInteger(payload.line) ? payload.line : null;
            break;
        case 'highlight-line':
            if (!Number.isInteger(payload?.line)) return res.status(400).json({ error: 'payload.line obligatorio' });
            cmd.line    = payload.line;
            cmd.path    = payload.path    ? String(payload.path).slice(0, 500)    : null;
            cmd.color   = ['yellow','red','green','blue'].includes(payload.color)  ? payload.color : 'yellow';
            cmd.tooltip = payload.tooltip ? String(payload.tooltip).slice(0, 500) : null;
            break;
        case 'clear-highlights':
            break;
        case 'show-inlay':
            if (!payload?.text || !Number.isInteger(payload?.line))
                return res.status(400).json({ error: 'payload.text y payload.line obligatorios' });
            cmd.text = String(payload.text).slice(0, 500);
            cmd.line = payload.line;
            cmd.path = payload.path ? String(payload.path).slice(0, 500) : null;
            break;
        case 'inject-text':
            if (!payload?.text) return res.status(400).json({ error: 'payload.text obligatorio' });
            cmd.text = String(payload.text).slice(0, 1000);
            break;
        case 'inject-key': {
            const ALLOWED_KEYS = new Set([
                'ENTER', 'ESCAPE', 'TAB', 'BACK_SPACE', 'DELETE', 'SPACE',
                'UP', 'DOWN', 'LEFT', 'RIGHT', 'HOME', 'END', 'PAGE_UP', 'PAGE_DOWN',
                'F1','F2','F3','F4','F5','F6','F7','F8','F9','F10','F11','F12'
            ]);
            if (!payload?.key || !ALLOWED_KEYS.has(payload.key))
                return res.status(400).json({ error: 'key no permitido' });
            cmd.key = payload.key;
            cmd.modifiers = Array.isArray(payload.modifiers)
                ? payload.modifiers.filter(m => ['ctrl','shift','alt'].includes(m))
                : [];
            break;
        }
        case 'inject-click':
            if (typeof payload?.normX !== 'number' || typeof payload?.normY !== 'number')
                return res.status(400).json({ error: 'normX y normY obligatorios' });
            cmd.normX  = Math.max(0, Math.min(1, payload.normX));
            cmd.normY  = Math.max(0, Math.min(1, payload.normY));
            cmd.button = [1, 2, 3].includes(payload.button) ? payload.button : 1;
            break;
        case 'lock-session':
            cmd.message = payload?.message
                ? String(payload.message).slice(0, 500)
                : 'Espera instrucciones del profesor.';
            break;
        case 'unlock-session':
            break;
        case 'send-file':
            if (!payload?.path || typeof payload.content === 'undefined')
                return res.status(400).json({ error: 'payload.path y payload.content obligatorios' });
            cmd.path    = String(payload.path).slice(0, 500);
            // Base64-encode content so the plugin avoids JSON-unescaping issues
            cmd.content = Buffer.from(String(payload.content).slice(0, 200_000), 'utf8').toString('base64');
            break;
        case 'open-url': {
            const rawUrl = String(payload?.url || '');
            if (!rawUrl.startsWith('https://') && !rawUrl.startsWith('http://'))
                return res.status(400).json({ error: 'URL debe comenzar con https:// o http://' });
            cmd.url = rawUrl.slice(0, 2000);
            break;
        }
        case 'stream-start':
        case 'stream-stop':
            // payload vacío, sólo el type
            break;
        default:
            return res.status(400).json({ error: `type no soportado: ${type}` });
    }

    const targets = clientId === '*' ? Array.from(clients.keys()) : [clientId];
    let delivered = 0, queued = 0;
    for (const id of targets) {
        if (pushCommand(id, cmd)) delivered++; else queued++;
    }
    res.json({ ok: true, delivered, queued });
});

// Wipe ALL clients (online and offline). Useful when the in-memory list
// has stale entries after restarts or testing.
// Issue a short-lived token so dashboard can authenticate over WebSocket
app.get('/api/dashboard/video-token', requireApiAuth, (req, res) => {
    const token = crypto.randomBytes(20).toString('hex');
    const clientId = String(req.query.clientId || '');
    videoTokens.set(token, { clientId, expires: Date.now() + 60_000 });
    setTimeout(() => videoTokens.delete(token), 60_000);
    res.json({ token });
});

// Expose streaming status per client
app.get('/api/dashboard/video-status', requireApiAuth, (req, res) => {
    const { clientId } = req.query;
    res.json({ streaming: videoSenders.has(String(clientId || '')) });
});

// ── Multichat endpoints ──────────────────────────────────────────────────────
// Sidebar summary: which clients have conversations and how many unread.
app.get('/api/dashboard/chats', requireApiAuth, (req, res) => {
    const out = [];
    for (const [clientId, msgs] of Object.entries(chatStore)) {
        if (!msgs.length) continue;
        const last = msgs[msgs.length - 1];
        const unread = msgs.filter(m => m.who === 'student' && !m.read).length;
        const c = clients.get(clientId);
        const view = c ? clientView(c) : null;
        out.push({
            clientId,
            client: view,
            lastAt: last.at, lastText: last.text, lastWho: last.who,
            unread, total: msgs.length
        });
    }
    out.sort((a, b) => (a.lastAt < b.lastAt ? 1 : -1));
    res.json(out);
});

// Full thread for a client.
app.get('/api/dashboard/chats/:clientId', requireApiAuth, (req, res) => {
    res.json(chatStore[req.params.clientId] || []);
});

// Mark all student-side entries of a client as read.
app.post('/api/dashboard/chats/:clientId/read', requireApiAuth, (req, res) => {
    const msgs = chatStore[req.params.clientId];
    if (msgs) {
        let changed = false;
        for (const m of msgs) if (m.who === 'student' && !m.read) { m.read = true; changed = true; }
        if (changed) { persistChat(); broadcast('chat-read', { clientId: req.params.clientId }); }
    }
    res.json({ ok: true });
});

// Delete the whole thread of a client.
app.delete('/api/dashboard/chats/:clientId', requireApiAuth, (req, res) => {
    delete chatStore[req.params.clientId];
    persistChat();
    broadcast('chat-thread-removed', { clientId: req.params.clientId });
    res.json({ ok: true });
});

// Pair: open a co-editing session against a client's file. Returns a token
// that the dashboard then uses to open the WS at /api/dashboard/pair?token=X.
app.post('/api/dashboard/pair-open', requireApiAuth, (req, res) => {
    const { clientId, path: relPath } = req.body || {};
    if (!clientId || !relPath) return res.status(400).json({ error: 'clientId y path obligatorios' });
    if (!clients.has(clientId)) return res.status(404).json({ error: 'Cliente no conectado' });
    const token = crypto.randomBytes(16).toString('hex');
    pairSessions.set(token, {
        clientId, path: String(relPath),
        teacherWs: null,
        expiresAt: Date.now() + 60_000   // 60s to redeem the token
    });
    pushCommand(clientId, { type: 'pair-open', path: String(relPath) });
    res.json({ token });
});

app.post('/api/dashboard/pair-close', requireApiAuth, (req, res) => {
    const { clientId, path: relPath } = req.body || {};
    if (!clientId || !relPath) return res.status(400).json({ error: 'clientId y path obligatorios' });
    pushCommand(clientId, { type: 'pair-close', path: String(relPath) });
    for (const [tok, s] of pairSessions) {
        if (s.clientId === clientId && s.path === relPath) {
            if (s.teacherWs) try { s.teacherWs.close(); } catch (_) {}
            pairSessions.delete(tok);
        }
    }
    res.json({ ok: true });
});

// Wipe ALL clients (online and offline). Useful when the in-memory list
// has stale entries after restarts or testing.
app.post('/api/dashboard/wipe-all', requireApiAuth, (req, res) => {
    const ids = Array.from(clients.keys());
    for (const id of ids) {
        clients.delete(id);
        onlineState.delete(id);
        broadcast('remove', { clientId: id });
    }
    res.json({ ok: true, removed: ids.length });
});

// Prune all currently-offline clients (manual cleanup button on dashboard)
app.post('/api/dashboard/prune-offline', requireApiAuth, (req, res) => {
    let removed = 0;
    for (const c of Array.from(clients.values())) {
        const ageMs = Date.now() - new Date(c.lastSeen).getTime();
        const thresholdMs = c.transmitFreqSeconds * 1000 + 10_000;
        if (ageMs >= thresholdMs) {
            clients.delete(c.clientId);
            onlineState.delete(c.clientId);
            broadcast('remove', { clientId: c.clientId });
            removed++;
        }
    }
    res.json({ ok: true, removed });
});

app.post('/api/dashboard/config', requireApiAuth, (req, res) => {
    const { clientId, captureFreqMin, captureFreqMax, transmitFreqSeconds } = req.body;
    const targets = clientId === '*' ? Array.from(clients.keys()) : [clientId].filter(id => clients.has(id));
    for (const id of targets) {
        if (!pendingCommands.has(id)) pendingCommands.set(id, []);
        pendingCommands.get(id).push({
            type: 'config',
            captureFreqMin:      Number(captureFreqMin),
            captureFreqMax:      Number(captureFreqMax),
            transmitFreqSeconds: Number(transmitFreqSeconds)
        });
    }
    res.json({ ok: true, count: targets.length });
});

app.get('/api/dashboard/quality', requireApiAuth, (req, res) => {
    res.json(qualityState);
});

app.post('/api/dashboard/quality', requireApiAuth, (req, res) => {
    const { scope, target } = req.body || {};
    const settings = sanitizeQuality(req.body);   // { archive?, panel?, live? }
    const key = String(target || '').trim();

    function mergeInto(holder) {
        for (const ctx of QUALITY_CONTEXTS) {
            if (!settings[ctx]) continue;
            holder[ctx] = { ...(holder[ctx] || {}), ...settings[ctx] };
        }
    }

    let affectedClientIds = [];

    if (scope === 'global') {
        qualityState.global = qualityState.global || {};
        mergeInto(qualityState.global);
        affectedClientIds = Array.from(clients.keys());
    } else if (scope === 'category') {
        if (!key) return res.status(400).json({ error: 'target (categoría) obligatorio' });
        qualityState.byCategory[key] = qualityState.byCategory[key] || {};
        mergeInto(qualityState.byCategory[key]);
        affectedClientIds = Array.from(clients.values()).filter(c => c.categoryMain === key).map(c => c.clientId);
    } else if (scope === 'client') {
        if (!key) return res.status(400).json({ error: 'target (clientId) obligatorio' });
        qualityState.byClient[key] = qualityState.byClient[key] || {};
        mergeInto(qualityState.byClient[key]);
        if (clients.has(key)) affectedClientIds = [key];
    } else if (scope === 'reset-global') {
        qualityState.global = {};
        affectedClientIds = Array.from(clients.keys());
    } else if (scope === 'reset-category') {
        if (!key) return res.status(400).json({ error: 'target (categoría) obligatorio' });
        delete qualityState.byCategory[key];
        affectedClientIds = Array.from(clients.values()).filter(c => c.categoryMain === key).map(c => c.clientId);
    } else if (scope === 'reset-client') {
        if (!key) return res.status(400).json({ error: 'target (clientId) obligatorio' });
        delete qualityState.byClient[key];
        if (clients.has(key)) affectedClientIds = [key];
    } else {
        return res.status(400).json({ error: "scope inválido" });
    }
    persistQuality();

    let pushed = 0;
    for (const id of affectedClientIds) { if (pushQuality(id)) pushed++; }
    res.json({ ok: true, pushed, state: qualityState });
});

app.post('/api/dashboard/categories', requireApiAuth, (req, res) => {
    const { clientId, categories } = req.body || {};
    if (!clientId || !Array.isArray(categories)) {
        return res.status(400).json({ error: 'clientId y categories obligatorios' });
    }
    const clean = categories
        .map(s => String(s).trim())
        .filter(s => s.length > 0 && s.length <= 64)
        .slice(0, 20);
    if (clean.length === 0) extraCategories.delete(clientId);
    else extraCategories.set(clientId, clean);
    persistCategories();
    const c = clients.get(clientId);
    if (c) broadcast('update', clientView(c));
    res.json({ ok: true, categories: clean });
});

// ── Offline detector + auto-cleanup ───────────────────────────────────────────

const onlineState = new Map();
const PRUNE_AFTER_OFFLINE_MS = 5 * 60_000;  // 5 minutes offline → remove from list

setInterval(() => {
    // Detección online/offline + prune de clientes muy antiguos
    for (const c of clients.values()) {
        const ageMs = Date.now() - new Date(c.lastSeen).getTime();
        const thresholdMs = c.transmitFreqSeconds * 1000 + 10_000;
        const isOnline = ageMs < thresholdMs;
        const prev = onlineState.get(c.clientId);
        if (prev !== isOnline) {
            onlineState.set(c.clientId, isOnline);
            if (prev !== undefined) broadcast(isOnline ? 'online' : 'offline', clientView(c));
        }
        if (!isOnline && ageMs > PRUNE_AFTER_OFFLINE_MS) {
            clients.delete(c.clientId);
            onlineState.delete(c.clientId);
            broadcast('remove', { clientId: c.clientId });
        }
    }
}, 1000);

// ── WebSocket server (video streaming) ────────────────────────────────────────

const httpServer = http.createServer(app);
// 100 MB per-frame ceiling for live PNG frames (default would let the
// underlying TCP buffers decide, which can drop big frames silently).
const wss = new WebSocketServer({ noServer: true, maxPayload: 100 * 1024 * 1024 });

httpServer.on('upgrade', (req, socket, head) => {
    const url = new URL(req.url, `http://localhost`);
    const pathname = url.pathname;

    if (pathname === '/api/client/video') {
        const auth = req.headers['authorization'];
        if (!auth || auth !== `Bearer ${API_KEY}`) { socket.destroy(); return; }
        wss.handleUpgrade(req, socket, head, ws => {
            const clientId = String(url.searchParams.get('clientId') || '');
            if (!clientId) { ws.close(1008, 'clientId required'); return; }
            handlePluginVideoWs(ws, clientId);
        });
    } else if (pathname === '/api/dashboard/video') {
        const token = String(url.searchParams.get('token') || '');
        const entry = videoTokens.get(token);
        if (!entry || Date.now() > entry.expires) { socket.destroy(); return; }
        videoTokens.delete(token);
        wss.handleUpgrade(req, socket, head, ws => {
            handleDashboardVideoWs(ws, entry.clientId);
        });
    } else if (pathname === '/api/client/pair') {
        const auth = req.headers['authorization'];
        if (!auth || auth !== `Bearer ${API_KEY}`) { socket.destroy(); return; }
        wss.handleUpgrade(req, socket, head, ws => {
            const clientId = String(url.searchParams.get('clientId') || '');
            if (!clientId) { ws.close(1008, 'clientId required'); return; }
            handlePluginPairWs(ws, clientId);
        });
    } else if (pathname === '/api/dashboard/pair') {
        const token = String(url.searchParams.get('token') || '');
        const session = pairSessions.get(token);
        if (!session) { socket.destroy(); return; }
        wss.handleUpgrade(req, socket, head, ws => {
            handleDashboardPairWs(ws, token);
        });
    } else {
        socket.destroy();
    }
});

function handlePluginVideoWs(ws, clientId) {
    videoSenders.set(clientId, ws);
    ws.on('message', (data, isBinary) => {
        if (!isBinary) return;
        const viewers = videoViewers.get(clientId);
        if (!viewers) return;
        for (const viewer of viewers) {
            if (viewer.readyState === WebSocket.OPEN) {
                try { viewer.send(data); } catch (_) {}
            }
        }
    });
    ws.on('close', () => videoSenders.delete(clientId));
    ws.on('error', () => videoSenders.delete(clientId));
}

function handleDashboardVideoWs(ws, clientId) {
    if (!videoViewers.has(clientId)) videoViewers.set(clientId, new Set());
    const viewers = videoViewers.get(clientId);
    const wasEmpty = viewers.size === 0;
    viewers.add(ws);
    if (wasEmpty) {
        pushCommand(clientId, { type: 'stream-start' });
    }
    const onViewerGone = () => {
        viewers.delete(ws);
        if (videoViewers.get(clientId)?.size === 0) {
            videoViewers.delete(clientId);
            pushCommand(clientId, { type: 'stream-stop' });
        }
    };
    ws.on('close', onViewerGone);
    ws.on('error', onViewerGone);
}

// ── Pair coding (text relay between teacher and plugin) ──────────────────────
// The plugin keeps a long-lived WS to /api/client/pair carrying doc-state /
// edit / cursor messages for whichever sessions it has open. The dashboard
// opens a session via POST /api/dashboard/pair-open (which generates a token
// and tells the plugin to open the file) and then connects via WS to
// /api/dashboard/pair?token=X. This server just relays JSON text between the
// two sockets — no OT/CRDT, the plugin/CodeMirror sides handle convergence.

const pluginPairSockets = new Map();   // clientId → WebSocket (plugin side)
const pairSessions      = new Map();   // token    → { clientId, path, teacherWs, expiresAt }

function handlePluginPairWs(ws, clientId) {
    const old = pluginPairSockets.get(clientId);
    if (old && old !== ws) { try { old.close(); } catch (_) {} }
    pluginPairSockets.set(clientId, ws);
    ws.on('message', (data, isBinary) => {
        if (isBinary) return;
        const text = data.toString();
        let msg = null;
        try { msg = JSON.parse(text); } catch (_) {}
        const msgPath = msg?.path;
        // Forward to matching teacher WS. If the teacher hasn't yet connected
        // its WS (typical race: panel POSTs pair-open, plugin sends doc-state
        // immediately, the teacher's WS upgrade is still on the wire), buffer
        // the message into the session — handleDashboardPairWs will drain the
        // buffer when the teacher attaches.
        for (const s of pairSessions.values()) {
            if (s.clientId !== clientId) continue;
            if (msgPath && s.path !== msgPath) continue;
            if (s.teacherWs && s.teacherWs.readyState === WebSocket.OPEN) {
                try { s.teacherWs.send(text); } catch (_) {}
            } else {
                if (!s.buffer) s.buffer = [];
                s.buffer.push(text);
                if (s.buffer.length > 200) s.buffer.shift();   // cap by drop-oldest
            }
        }
    });
    const onGone = () => {
        if (pluginPairSockets.get(clientId) === ws) pluginPairSockets.delete(clientId);
    };
    ws.on('close', onGone);
    ws.on('error', onGone);
}

function handleDashboardPairWs(ws, token) {
    const session = pairSessions.get(token);
    if (!session) { ws.close(1008, 'invalid token'); return; }
    if (session.teacherWs) { try { session.teacherWs.close(); } catch (_) {} }
    session.teacherWs = ws;
    session.expiresAt = Number.MAX_SAFE_INTEGER;  // valid until WS closes
    // Drain any buffered messages from the plugin that arrived before this
    // WS finished upgrading.
    if (session.buffer && session.buffer.length) {
        for (const text of session.buffer) {
            try { ws.send(text); } catch (_) {}
        }
        session.buffer = [];
    }
    ws.on('message', (data, isBinary) => {
        if (isBinary) return;
        const pluginWs = pluginPairSockets.get(session.clientId);
        if (pluginWs && pluginWs.readyState === WebSocket.OPEN) {
            try { pluginWs.send(data.toString()); } catch (_) {}
        }
    });
    const onGone = () => {
        if (session.teacherWs === ws) session.teacherWs = null;
    };
    ws.on('close', onGone);
    ws.on('error', onGone);
}

// Token cleanup: drop expired tokens that were never used. Sessions with an
// active teacherWs have expiresAt = MAX_SAFE_INTEGER (cleaned on disconnect).
setInterval(() => {
    const now = Date.now();
    for (const [tok, s] of pairSessions) {
        if (!s.teacherWs && now > s.expiresAt) pairSessions.delete(tok);
    }
}, 30_000).unref();

// ── Start ─────────────────────────────────────────────────────────────────────

httpServer.listen(PORT, () => {
    console.log(`Controlex server escuchando en el puerto ${PORT}`);
    console.log(`Dashboard: https://controlex.ebarrab.com (OAuth Google)`);
    console.log(`Email autorizado: ${ALLOWED_EMAIL}`);
});
