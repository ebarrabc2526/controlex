'use strict';

const express = require('express');
const path = require('path');

const app = express();
const PORT = process.env.PORT || 3000;
const API_KEY = process.env.CONTROLEX_API_KEY || 'ControlEx-IES-ClaraDelRey-2026';

app.use(express.json({ limit: '25mb' }));

// Disable cache for HTML/JS/CSS (dashboard) and root path
app.use((req, res, next) => {
    if (req.path === '/' || /\.(html|js|css)$/.test(req.path)) {
        res.set('Cache-Control', 'no-store, no-cache, must-revalidate');
        res.set('Pragma', 'no-cache');
        res.set('Expires', '0');
    }
    next();
});

app.use(express.static(path.join(__dirname, 'public')));

// In-memory state
const clients = new Map();          // clientId -> ClientRecord
const pendingCommands = new Map();  // clientId -> Command[]
const sseClients = new Set();       // dashboard SSE connections
let seqCounter = 0;

// ── Plugin download ───────────────────────────────────────────────────────────

const PLUGIN_ZIP = path.join(__dirname, 'public', 'controlex-1.3.0.zip');

app.get('/plugin', (req, res) => {
    res.download(PLUGIN_ZIP, 'controlex-1.3.0.zip', err => {
        if (err) res.status(404).send('Plugin no disponible');
    });
});

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
        try { res.write(payload); } catch (_) { /* connection closed */ }
    }
}

// ── Auth middleware for client uploads ────────────────────────────────────────

function requireClientAuth(req, res, next) {
    const auth = req.headers['authorization'];
    if (!auth || auth !== `Bearer ${API_KEY}`) {
        return res.status(401).json({ error: 'No autorizado' });
    }
    next();
}

// ── Client API ────────────────────────────────────────────────────────────────

// Client: upload screenshot + retrieve pending commands
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
    const wasOnline = existing
        ? (Date.now() - new Date(existing.lastSeen).getTime()) < (existing.transmitFreqSeconds * 1000 + 10_000)
        : false;

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

    // Push update to all dashboards (new client OR existing client refresh)
    broadcast(existing ? 'update' : 'add', clientView(clients.get(clientId)));

    res.json({ commands });
});

// ── Dashboard API ─────────────────────────────────────────────────────────────

// SSE stream — pushes 'add'/'update'/'offline' events in real time
app.get('/api/dashboard/stream', (req, res) => {
    res.set({
        'Content-Type':    'text/event-stream',
        'Cache-Control':   'no-cache, no-transform',
        'Connection':      'keep-alive',
        'X-Accel-Buffering': 'no'
    });
    res.flushHeaders();

    // Send full snapshot first
    const snapshot = Array.from(clients.values())
        .sort((a, b) => a.seqNum - b.seqNum)
        .map(clientView);
    res.write(`event: snapshot\ndata: ${JSON.stringify(snapshot)}\n\n`);

    sseClients.add(res);

    // Heartbeat to keep proxies happy
    const hb = setInterval(() => {
        try { res.write(': heartbeat\n\n'); } catch (_) {}
    }, 25_000);

    req.on('close', () => {
        clearInterval(hb);
        sseClients.delete(res);
    });
});

// List all known clients (snapshot, kept for compat)
app.get('/api/dashboard/clients', (req, res) => {
    const list = Array.from(clients.values())
        .sort((a, b) => a.seqNum - b.seqNum)
        .map(clientView);
    res.json(list);
});

// Get latest screenshot for a client (JPEG binary)
app.get('/api/dashboard/screenshot/:clientId', (req, res) => {
    const client = clients.get(req.params.clientId);
    if (!client || !client.screenshotData) {
        return res.status(404).send('Sin captura disponible');
    }
    res.set('Content-Type', 'image/jpeg');
    res.set('Cache-Control', 'no-store');
    res.send(client.screenshotData);
});

// Send a message to one client or all ('*')
app.post('/api/dashboard/message', (req, res) => {
    const { clientId, text } = req.body;
    if (!text || !String(text).trim()) {
        return res.status(400).json({ error: 'El campo text es obligatorio' });
    }
    const targets = clientId === '*'
        ? Array.from(clients.keys())
        : [clientId].filter(id => clients.has(id));

    for (const id of targets) {
        if (!pendingCommands.has(id)) pendingCommands.set(id, []);
        pendingCommands.get(id).push({ type: 'message', text: String(text).trim() });
    }
    res.json({ ok: true, count: targets.length });
});

// Update frequency config for one client or all ('*')
app.post('/api/dashboard/config', (req, res) => {
    const { clientId, captureFreqMin, captureFreqMax, transmitFreqSeconds } = req.body;

    const targets = clientId === '*'
        ? Array.from(clients.keys())
        : [clientId].filter(id => clients.has(id));

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
// Cada segundo revisa qué clientes acaban de pasar a offline y emite evento.

const onlineState = new Map();  // clientId -> bool

setInterval(() => {
    for (const c of clients.values()) {
        const ageMs = Date.now() - new Date(c.lastSeen).getTime();
        const thresholdMs = c.transmitFreqSeconds * 1000 + 10_000;
        const isOnline = ageMs < thresholdMs;
        const prev = onlineState.get(c.clientId);
        if (prev !== isOnline) {
            onlineState.set(c.clientId, isOnline);
            if (prev !== undefined) {
                broadcast(isOnline ? 'online' : 'offline', clientView(c));
            }
        }
    }
}, 1000);

// ── Start ─────────────────────────────────────────────────────────────────────

app.listen(PORT, () => {
    console.log(`Controlex server escuchando en el puerto ${PORT}`);
    console.log(`Dashboard: http://localhost:${PORT}`);
    console.log(`API key activa: ${API_KEY.slice(0, 8)}...`);
    console.log('Para cambiar la clave: CONTROLEX_API_KEY=nueva_clave node server.js');
});
