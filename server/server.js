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
app.use(express.json({ limit: '25mb' }));
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
    res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

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

const qualityState = (() => {
    const fallback = { global: {}, byCategory: {}, byClient: {} };
    try {
        fs.mkdirSync(path.dirname(QUALITY_PATH), { recursive: true });
        if (fs.existsSync(QUALITY_PATH)) {
            const raw = JSON.parse(fs.readFileSync(QUALITY_PATH, 'utf8'));
            return {
                global:     raw.global     || {},
                byCategory: raw.byCategory || {},
                byClient:   raw.byClient   || {}
            };
        }
    } catch (e) { console.warn('[controlex] no se pudo leer quality.json:', e.message); }
    return fallback;
})();

function persistQuality() {
    try { fs.writeFileSync(QUALITY_PATH, JSON.stringify(qualityState, null, 2)); }
    catch (e) { console.warn('[controlex] no se pudo escribir quality.json:', e.message); }
}

function sanitizeQuality(input) {
    const out = {};
    if (input && Number.isFinite(+input.jpegQuality)) out.jpegQuality = Math.max(1,  Math.min(100,  Math.round(+input.jpegQuality)));
    if (input && Number.isFinite(+input.fps))         out.fps         = Math.max(1,  Math.min(15,   Math.round(+input.fps)));
    if (input && Number.isFinite(+input.maxWidth))    out.maxWidth    = Math.max(0,  Math.min(3840, Math.round(+input.maxWidth)));
    return out;
}

function effectiveQuality(clientId) {
    const c = clients.get(clientId);
    const cat = c?.categoryMain || null;
    return {
        ...(qualityState.global || {}),
        ...((cat && qualityState.byCategory[cat]) || {}),
        ...((qualityState.byClient[clientId]) || {})
    };
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

// ── Plugin download (public) ──────────────────────────────────────────────────

const PLUGIN_ZIP = path.join(__dirname, 'public', 'controlex-2.3.6.zip');
const VERSION = (path.basename(PLUGIN_ZIP).match(/controlex-([\d.]+)\.zip/) || [, '?'])[1];

app.get('/plugin', (req, res) => {
    res.download(PLUGIN_ZIP, 'controlex-2.3.6.zip', err => {
        if (err) res.status(404).send('Plugin no disponible');
    });
});

app.get('/api/version', (req, res) => res.json({ version: VERSION }));

// IntelliJ custom plugin repository: add this URL in
//   Settings → Plugins → ⚙ → Manage Plugin Repositories…
// IntelliJ will poll it at startup and when the user runs "Check for updates".
app.get('/updatePlugins.xml', (req, res) => {
    const xml = `<?xml version="1.0" encoding="UTF-8"?>
<plugins>
    <plugin id="es.iesclaradelrey.controlex"
            url="https://controlex.ebarrab.com/plugin"
            version="${VERSION}">
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

// Client: student requests help
app.post('/api/help-request', requireClientAuth, (req, res) => {
    const { clientId, text } = req.body || {};
    if (!clientId) return res.status(400).json({ error: 'clientId requerido' });

    const c = clients.get(String(clientId));
    const view = c ? clientView(c) : { clientId };

    broadcast('help-request', {
        ...view,
        text: String(text || '').slice(0, 1000),
        at:   new Date().toISOString()
    });
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

app.get('/api/dashboard/screenshot/:clientId', requireApiAuth, (req, res) => {
    const client = clients.get(req.params.clientId);
    if (!client || !client.screenshotData) return res.status(404).send('Sin captura disponible');
    res.set('Content-Type', 'image/jpeg');
    res.set('Cache-Control', 'no-store');
    res.send(client.screenshotData);
});

app.post('/api/dashboard/message', requireApiAuth, (req, res) => {
    const { clientId, text } = req.body;
    if (!text || !String(text).trim()) return res.status(400).json({ error: 'El campo text es obligatorio' });
    const targets = clientId === '*' ? Array.from(clients.keys()) : [clientId].filter(id => clients.has(id));
    for (const id of targets) {
        if (!pendingCommands.has(id)) pendingCommands.set(id, []);
        pendingCommands.get(id).push({ type: 'message', text: String(text).trim() });
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
    const settings = sanitizeQuality(req.body);
    const key = String(target || '').trim();

    let affectedClientIds = [];

    if (scope === 'global') {
        qualityState.global = { ...qualityState.global, ...settings };
        affectedClientIds = Array.from(clients.keys());
    } else if (scope === 'category') {
        if (!key) return res.status(400).json({ error: 'target (categoría) obligatorio' });
        qualityState.byCategory[key] = { ...(qualityState.byCategory[key] || {}), ...settings };
        affectedClientIds = Array.from(clients.values()).filter(c => c.categoryMain === key).map(c => c.clientId);
    } else if (scope === 'client') {
        if (!key) return res.status(400).json({ error: 'target (clientId) obligatorio' });
        qualityState.byClient[key] = { ...(qualityState.byClient[key] || {}), ...settings };
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
const wss = new WebSocketServer({ noServer: true });

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

// ── Start ─────────────────────────────────────────────────────────────────────

httpServer.listen(PORT, () => {
    console.log(`Controlex server escuchando en el puerto ${PORT}`);
    console.log(`Dashboard: https://controlex.ebarrab.com (OAuth Google)`);
    console.log(`Email autorizado: ${ALLOWED_EMAIL}`);
});
