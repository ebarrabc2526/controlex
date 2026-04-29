'use strict';

const express = require('express');
const path = require('path');

const app = express();
const PORT = process.env.PORT || 3000;
const API_KEY = process.env.CONTROLEX_API_KEY || 'ControlEx-IES-ClaraDelRey-2026';

app.use(express.json({ limit: '25mb' }));
app.use(express.static(path.join(__dirname, 'public')));

// In-memory state
const clients = new Map();          // clientId -> ClientRecord
const pendingCommands = new Map();  // clientId -> Command[]
let seqCounter = 0;

// ── Plugin download ───────────────────────────────────────────────────────────

const PLUGIN_ZIP = path.join(__dirname, 'public', 'controlex-1.2.0.zip');

app.get('/plugin', (req, res) => {
    res.download(PLUGIN_ZIP, 'controlex-1.2.0.zip', err => {
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

    res.json({ commands });
});

// ── Dashboard API ─────────────────────────────────────────────────────────────

// List all known clients (without screenshot data)
app.get('/api/dashboard/clients', (req, res) => {
    const now = Date.now();
    const list = Array.from(clients.values())
        .sort((a, b) => a.seqNum - b.seqNum)
        .map(c => {
            const ageMs = now - new Date(c.lastSeen).getTime();
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
        });
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

// ── Start ─────────────────────────────────────────────────────────────────────

app.listen(PORT, () => {
    console.log(`Controlex server escuchando en el puerto ${PORT}`);
    console.log(`Dashboard: http://localhost:${PORT}`);
    console.log(`API key activa: ${API_KEY.slice(0, 8)}...`);
    console.log('Para cambiar la clave: CONTROLEX_API_KEY=nueva_clave node server.js');
});
