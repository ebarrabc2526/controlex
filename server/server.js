'use strict';

require('dotenv').config();

const express      = require('express');
const session      = require('express-session');
const passport     = require('passport');
const GoogleStrategy = require('passport-google-oauth20').Strategy;
const path         = require('path');

const app = express();
const PORT          = process.env.PORT || 3000;
const API_KEY       = process.env.CONTROLEX_API_KEY  || 'ControlEx-IES-ClaraDelRey-2026';
const ALLOWED_EMAIL = process.env.ALLOWED_EMAIL      || 'ebarrabc2526@gmail.com';
const CLIENT_ID     = process.env.GOOGLE_CLIENT_ID;
const CLIENT_SECRET = process.env.GOOGLE_CLIENT_SECRET;
const SESSION_SECRET= process.env.SESSION_SECRET     || 'change-me';

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
    saveUninitialized: false,
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
    passport.authenticate('google', { scope: ['profile', 'email'] })
);

app.get('/auth/google/callback',
    passport.authenticate('google', { failureRedirect: '/forbidden' }),
    (req, res) => {
        if (req.user && req.user.email === ALLOWED_EMAIL) return res.redirect('/');
        res.redirect('/forbidden');
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

// In-memory state
const clients = new Map();
const pendingCommands = new Map();
const sseClients = new Set();
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
        online:              ageMs < thresholdMs
    };
}

function broadcast(event, data) {
    const payload = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
    for (const res of sseClients) {
        try { res.write(payload); } catch (_) {}
    }
}

// ── Plugin download (public) ──────────────────────────────────────────────────

const PLUGIN_ZIP = path.join(__dirname, 'public', 'controlex-1.3.0.zip');

app.get('/plugin', (req, res) => {
    res.download(PLUGIN_ZIP, 'controlex-1.3.0.zip', err => {
        if (err) res.status(404).send('Plugin no disponible');
    });
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

app.post('/api/screenshot', requireClientAuth, (req, res) => {
    const {
        clientId, ip, hostname, projectName, intellijUser, osUser,
        captureFreqMin, captureFreqMax, transmitFreqSeconds, screenshot
    } = req.body;

    if (!clientId || !screenshot) {
        return res.status(400).json({ error: 'Faltan campos requeridos: clientId, screenshot' });
    }

    const existing = clients.get(clientId);
    const seqNum = existing ? existing.seqNum : ++seqCounter;

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
        screenshotData:     Buffer.from(screenshot, 'base64')
    });

    if (!pendingCommands.has(clientId)) pendingCommands.set(clientId, []);
    const commands = pendingCommands.get(clientId).splice(0);

    broadcast(existing ? 'update' : 'add', clientView(clients.get(clientId)));

    res.json({ commands });
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
    const hb = setInterval(() => { try { res.write(': heartbeat\n\n'); } catch (_) {} }, 25_000);
    req.on('close', () => { clearInterval(hb); sseClients.delete(res); });
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

// ── Offline detector ──────────────────────────────────────────────────────────

const onlineState = new Map();

setInterval(() => {
    for (const c of clients.values()) {
        const ageMs = Date.now() - new Date(c.lastSeen).getTime();
        const thresholdMs = c.transmitFreqSeconds * 1000 + 10_000;
        const isOnline = ageMs < thresholdMs;
        const prev = onlineState.get(c.clientId);
        if (prev !== isOnline) {
            onlineState.set(c.clientId, isOnline);
            if (prev !== undefined) broadcast(isOnline ? 'online' : 'offline', clientView(c));
        }
    }
}, 1000);

// ── Start ─────────────────────────────────────────────────────────────────────

app.listen(PORT, () => {
    console.log(`Controlex server escuchando en el puerto ${PORT}`);
    console.log(`Dashboard: https://controlex.ebarrab.com (OAuth Google)`);
    console.log(`Email autorizado: ${ALLOWED_EMAIL}`);
});
