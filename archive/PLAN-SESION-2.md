# Plan Sesión 2 — Pair coding "estilo Google Docs" (un escritor + caret del alumno)

> Este documento es un brief para retomar el trabajo. Léelo entero antes de empezar.
> Estado del proyecto cuando se escribió: v1.8.0 desplegado. Todo lo previo funciona
> (SSE inverso, comandos Nivel 1 y 2, firma Ed25519, streaming WebSocket 10 fps,
> inyección Robot teclado/ratón, lock-session, send-file, open-url).

---

## 0. Contexto rápido (no re-derivar)

- Proyecto en `/home/ebarrab/controlex` (cliente IntelliJ + servidor Node.js).
- Producción en `/home/ebarrab/pro/controlex/` (Apache reverse-proxy a `127.0.0.1:4000`, gestionado por PM2 — `pm2 reload controlex --update-env`).
- Despliegue del plugin: `./publish.sh` (compila + copia ZIP a `/home/ebarrab/pro/controlex/public/` + actualiza ruta en `server.js` + recarga PM2).
- Repo GitHub: `ebarrabc2526/controlex`. Releases por tag (`v1.x.y`).
- Panel: `https://controlex.ebarrab.com/` (OAuth Google, solo `ebarrabc2526@gmail.com`).
- Descarga plugin: `https://ebarrab.com/controlex` (alias) o `https://controlex.ebarrab.com/plugin`.
- API key plugin↔servidor: `Bearer ControlEx-IES-ClaraDelRey-2026` (en `.env` del servidor + `controlex.properties` del plugin).
- Cliente ID en plugin: `~/.controlex/client-id.txt` (per-machine).
- Firma Ed25519: clave privada en `.env` (`COMMAND_PRIVATE_KEY`), pública en `ControlexConfig.COMMAND_PUBLIC_KEY_B64`. `signCmd()` y `CommandSignatureVerifier` ya existen.
- Patrón WebSocket existente: `ScreenStreamService` (plugin) ↔ `/api/client/video` + `/api/dashboard/video` con token corto (`videoTokens`).

---

## 1. Objetivo de la sesión

Habilitar **edición colaborativa "Google Docs" simplificada** entre profesor (panel web) y alumno (IntelliJ) en un fichero concreto del proyecto del alumno:

- **Un solo escritor a la vez (el profesor)**. El alumno sigue editando localmente con su teclado en su IntelliJ.
- El profesor ve, en un editor en el panel, el contenido del fichero del alumno y el **caret del alumno con su color**.
- Lo que el profesor escribe en el panel aparece en tiempo real en el IntelliJ del alumno (insert/delete a nivel de offset).
- Lo que el alumno escribe en su IntelliJ se replica en el editor del panel del profesor.
- Cuando los dos editan a la vez la misma zona, **gana el último** que envía la op (rebase trivial por offsets, sin CRDT). Es aceptable para uso pedagógico.

### Alcance estricto (NO hacer más)

- ✅ Nuevo canal WebSocket bidireccional plugin↔servidor para edición (separado del de vídeo).
- ✅ Nuevo canal WebSocket dashboard↔servidor para edición (token corto, mismo patrón que vídeo).
- ✅ Comando `pair-open` (servidor→plugin) que activa la sesión sobre un fichero del proyecto.
- ✅ Comando `pair-close` que desactiva.
- ✅ Render del caret remoto (profesor) en el editor IntelliJ del alumno con `RangeHighlighter` / `CustomHighlighterRenderer` (línea vertical fina + inlay con nombre/color).
- ✅ Editor CodeMirror 6 en el panel para el profesor, con caret del alumno pintado.
- ✅ Rebase mínimo por offsets en el servidor cuando llegan ops cruzadas.
- ❌ NO CRDT ni OT real (Yjs/Automerge se descartó: 80% del valor con 20% del esfuerzo).
- ❌ NO multi-peer (sólo profesor + 1 alumno por sesión). Si quieres clase entera, abres N sesiones independientes.
- ❌ NO sincronización persistente: si el plugin se reinicia se pierde la sesión (el profesor reabre).
- ❌ NO conflict markers, NO undo compartido, NO selección remota (sólo caret).
- ❌ NO firma Ed25519 sobre cada `pair-edit` (van por WS autenticado en servidor; ver §7).

---

## 2. Arquitectura

```
Profesor (panel)              Servidor                       Alumno (plugin)
     |                            |                                |
     | GET /api/dashboard/        |                                |
     |   pair-token?clientId&path |                                |
     |  <----- {token} -----------|                                |
     |                            |                                |
     | WS /api/dashboard/pair?    |                                |
     |   token=...                |                                |
     |  <----- 'doc-state' -------|  (espera estado inicial)       |
     |                            |                                |
     |                            |  WS /api/client/pair?clientId  |
     |                            |  <----------------------------|  abre al iniciar plugin
     |                            |  (Bearer)                      |
     |                            |                                |
     | (servidor envía 'pair-     |                                |
     |  open' como comando SSE    |  --SSE 'command' pair-open---->|  abre fichero,
     |  por el canal existente)   |                                |  envía 'doc-state'
     |                            |  <-- 'doc-state' (WS) ---------|  con contenido + version
     |                            |                                |
     | --- WS 'edit' op {ops,     |                                |
     |   baseVersion} ----------->|  rebase si version desfasa     |
     |                            |  --- WS 'edit' op ------------>|  aplica (flag remoto)
     |                            |                                |
     |                            |  <-- WS 'edit' op (alumno) ----|  DocumentListener
     | <--- WS 'edit' op ---------|  rebase si version desfasa     |
     |                            |                                |
     | --- WS 'cursor' offset --->|  --- WS 'cursor' (profe) ----->|  pinta caret remoto
     | <--- WS 'cursor' (alumno)--|  <-- WS 'cursor' (alumno) -----|  CaretListener
     |                            |                                |
     | (profe cierra modal)       |                                |
     | --- WS close ------------->|  --- WS 'pair-close' --------->|  limpia listeners,
     |                            |                                |  borra caret remoto
```

El canal SSE existente (`/api/client/stream`) sigue usándose sólo para enviar `pair-open` / `pair-close` (eventos puntuales, firmados Ed25519). Las **ops y cursores** van por el WS dedicado, sin firma.

---

## 3. Cambios fichero por fichero

### 3.1 Servidor — `/home/ebarrab/pro/controlex/server.js`

**A. Estado de sesiones pair**

Cerca del bloque `// In-memory state`:

```javascript
// Pair-coding sessions: one per (clientId, path)
const pairSessions = new Map();   // sessionKey -> { clientId, path, version, content, plugin: ws|null, dashboards: Set<ws> }
const pairTokens   = new Map();   // token -> { sessionKey, expires }

function pairKey(clientId, path) { return `${clientId}::${path}`; }
```

**B. Endpoint para que el profesor pida un token y abra sesión**

```javascript
app.post('/api/dashboard/pair-open', requireApiAuth, (req, res) => {
    const { clientId, path: filePath } = req.body || {};
    if (!clientId || !filePath) return res.status(400).json({ error: 'clientId y path obligatorios' });

    const key = pairKey(clientId, filePath);
    if (!pairSessions.has(key)) {
        pairSessions.set(key, {
            clientId, path: filePath,
            version: 0, content: '',
            plugin: null, dashboards: new Set()
        });
    }

    // Token corto para que el navegador abra el WS
    const token = crypto.randomBytes(20).toString('hex');
    pairTokens.set(token, { sessionKey: key, expires: Date.now() + 60_000 });
    setTimeout(() => pairTokens.delete(token), 60_000);

    // Pedir al plugin que abra el fichero y empuje su estado
    pushCommand(clientId, { type: 'pair-open', path: filePath });

    res.json({ token });
});

app.post('/api/dashboard/pair-close', requireApiAuth, (req, res) => {
    const { clientId, path: filePath } = req.body || {};
    if (!clientId || !filePath) return res.status(400).json({ error: 'clientId y path obligatorios' });
    const key = pairKey(clientId, filePath);
    pairSessions.delete(key);
    pushCommand(clientId, { type: 'pair-close', path: filePath });
    res.json({ ok: true });
});
```

**C. Upgrade WebSocket (extender el bloque `httpServer.on('upgrade', ...)`)**

Añadir dos pathnames más:

```javascript
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
    const entry = pairTokens.get(token);
    if (!entry || Date.now() > entry.expires) { socket.destroy(); return; }
    pairTokens.delete(token);
    wss.handleUpgrade(req, socket, head, ws => {
        handleDashboardPairWs(ws, entry.sessionKey);
    });
}
```

**D. Lógica de los dos handlers + rebase**

```javascript
// Map<clientId, ws> — único canal de pair por cliente (multiplexa todas las sesiones de ese alumno)
const pluginPairs = new Map();

function handlePluginPairWs(ws, clientId) {
    pluginPairs.set(clientId, ws);
    ws.on('message', raw => {
        let msg; try { msg = JSON.parse(raw.toString()); } catch { return; }
        const session = pairSessions.get(pairKey(clientId, msg.path));
        if (!session) return;

        if (msg.type === 'doc-state') {
            session.version = Number(msg.version) || 0;
            session.content = String(msg.content || '');
            broadcastToDashboards(session, { type: 'doc-state', version: session.version, content: session.content });
        } else if (msg.type === 'edit') {
            applyAndFanout(session, msg, /*from*/ 'plugin');
        } else if (msg.type === 'cursor') {
            broadcastToDashboards(session, { type: 'cursor', who: 'student', offset: Number(msg.offset) || 0 });
        }
    });
    const cleanup = () => { if (pluginPairs.get(clientId) === ws) pluginPairs.delete(clientId); };
    ws.on('close', cleanup);
    ws.on('error', cleanup);
}

function handleDashboardPairWs(ws, sessionKey) {
    const session = pairSessions.get(sessionKey);
    if (!session) { ws.close(1008, 'session not found'); return; }
    session.dashboards.add(ws);

    // Snapshot inicial
    ws.send(JSON.stringify({ type: 'doc-state', version: session.version, content: session.content }));

    ws.on('message', raw => {
        let msg; try { msg = JSON.parse(raw.toString()); } catch { return; }
        if (msg.type === 'edit') {
            applyAndFanout(session, msg, /*from*/ 'dashboard');
        } else if (msg.type === 'cursor') {
            const pluginWs = pluginPairs.get(session.clientId);
            if (pluginWs && pluginWs.readyState === WebSocket.OPEN) {
                pluginWs.send(JSON.stringify({
                    type: 'cursor', path: session.path, who: 'teacher', offset: Number(msg.offset) || 0
                }));
            }
        }
    });
    ws.on('close', () => session.dashboards.delete(ws));
    ws.on('error', () => session.dashboards.delete(ws));
}

// Rebase trivial: una op = {opType:'insert'|'delete', offset, text? , len?}
function rebaseOp(op, againstOps) {
    let { offset } = op;
    for (const a of againstOps) {
        if (a.opType === 'insert') {
            if (a.offset <= offset) offset += a.text.length;
        } else if (a.opType === 'delete') {
            if (a.offset + a.len <= offset) offset -= a.len;
            else if (a.offset < offset) offset = a.offset;  // colapso parcial
        }
    }
    return { ...op, offset };
}

function applyOp(content, op) {
    if (op.opType === 'insert') return content.slice(0, op.offset) + op.text + content.slice(op.offset);
    if (op.opType === 'delete') return content.slice(0, op.offset) + content.slice(op.offset + op.len);
    return content;
}

function applyAndFanout(session, msg, from) {
    const baseVersion = Number(msg.baseVersion) || 0;
    let ops = Array.isArray(msg.ops) ? msg.ops : [];

    // Rebase si el cliente venía atrasado: aplicar contra todas las ops vistas desde baseVersion.
    // Para simplificar: guardamos un buffer recortable de las últimas N ops.
    const since = (session.history || []).slice(baseVersion);
    if (since.length) ops = ops.map(o => rebaseOp(o, since));

    if (!session.history) session.history = [];
    for (const op of ops) {
        session.content = applyOp(session.content, op);
        session.history.push(op);
        session.version++;
    }
    if (session.history.length > 1000) {
        // mantener acotado (pierde rebase muy antiguo, pero los clientes están sincronizados)
        const drop = session.history.length - 1000;
        session.history.splice(0, drop);
    }

    const out = JSON.stringify({ type: 'edit', version: session.version, ops });
    if (from !== 'dashboard') {
        for (const d of session.dashboards) {
            if (d.readyState === WebSocket.OPEN) { try { d.send(out); } catch {} }
        }
    }
    if (from !== 'plugin') {
        const pluginWs = pluginPairs.get(session.clientId);
        if (pluginWs && pluginWs.readyState === WebSocket.OPEN) {
            try { pluginWs.send(JSON.stringify({ type: 'edit', path: session.path, version: session.version, ops })); } catch {}
        }
    }
}

function broadcastToDashboards(session, msg) {
    const out = JSON.stringify(msg);
    for (const d of session.dashboards) {
        if (d.readyState === WebSocket.OPEN) { try { d.send(out); } catch {} }
    }
}
```

**Nota sobre `baseVersion`**: el cliente envía la versión que tenía cuando generó la op. El servidor rebasea contra todas las ops aplicadas desde entonces. Como sólo hay 2 peers, el `history.slice(baseVersion)` corresponde 1:1 con las ops del otro peer no vistas todavía.

### 3.2 Plugin — nuevo `PairSessionManager.kt`

Path: `/home/ebarrab/controlex/src/main/kotlin/es/iesclaradelrey/controlex/PairSessionManager.kt`

```kotlin
package es.iesclaradelrey.controlex

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.awt.Color
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class PairSessionManager(private val project: Project) : Disposable {

    private val log = Logger.getInstance(PairSessionManager::class.java)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "Controlex-Pair").apply { isDaemon = true }
    }

    @Volatile private var ws: WebSocket? = null
    @Volatile private var stopped = false

    private val sessions = mutableMapOf<String, PairSession>()  // path -> session

    private data class PairSession(
        val path: String,
        val editor: Editor,
        var version: Int,
        var docListener: DocumentListener?,
        var caretListener: CaretListener?,
        var teacherCaret: RangeHighlighter?,
        val applyingRemote: AtomicBoolean
    )

    fun start() { connect() }

    private fun connect() {
        if (stopped) return
        val clientId = project.service<ServerTransmitter>().clientId
        val uri = URI.create(
            ControlexConfig.SERVER_WS_URL + "/api/client/pair?clientId=" +
            URLEncoder.encode(clientId, StandardCharsets.UTF_8)
        )
        http.newWebSocketBuilder()
            .header("Authorization", "Bearer ${ControlexConfig.SERVER_API_KEY}")
            .buildAsync(uri, WsListener())
            .exceptionally { e ->
                log.warn("Controlex: pair WS error: ${e.message}")
                if (!stopped) scheduler.schedule({ connect() }, 5L, TimeUnit.SECONDS)
                null
            }
    }

    fun openSession(relativePath: String) {
        ApplicationManager.getApplication().invokeLater {
            val base = project.basePath ?: return@invokeLater
            val vf = LocalFileSystem.getInstance().findFileByIoFile(File(base, relativePath)) ?: return@invokeLater
            val editor = FileEditorManager.getInstance(project)
                .openTextEditor(OpenFileDescriptor(project, vf), true) ?: return@invokeLater

            val applying = AtomicBoolean(false)
            val session = PairSession(relativePath, editor, 0, null, null, null, applying)
            sessions[relativePath] = session

            // Envío estado inicial al servidor
            val content = editor.document.text
            sendJson("""{"type":"doc-state","path":${q(relativePath)},"version":0,"content":${q(content)}}""")

            // Listener de cambios locales (alumno)
            val docListener = object : DocumentListener {
                override fun documentChanged(e: DocumentEvent) {
                    if (applying.get()) return
                    val ops = mutableListOf<String>()
                    if (e.oldLength > 0) ops += """{"opType":"delete","offset":${e.offset},"len":${e.oldLength}}"""
                    if (e.newLength > 0) ops += """{"opType":"insert","offset":${e.offset},"text":${q(e.newFragment.toString())}}"""
                    sendJson("""{"type":"edit","path":${q(relativePath)},"baseVersion":${session.version},"ops":[${ops.joinToString(",")}]}""")
                }
            }
            editor.document.addDocumentListener(docListener)
            session.docListener = docListener

            // Listener de caret local
            val caretListener = object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) {
                    val offset = editor.caretModel.offset
                    sendJson("""{"type":"cursor","path":${q(relativePath)},"offset":$offset}""")
                }
            }
            editor.caretModel.addCaretListener(caretListener)
            session.caretListener = caretListener
        }
    }

    fun closeSession(relativePath: String) {
        ApplicationManager.getApplication().invokeLater {
            val s = sessions.remove(relativePath) ?: return@invokeLater
            s.docListener?.let { s.editor.document.removeDocumentListener(it) }
            s.caretListener?.let { s.editor.caretModel.removeCaretListener(it) }
            s.teacherCaret?.let { s.editor.markupModel.removeHighlighter(it) }
        }
    }

    private fun handleIncoming(json: String) {
        val type = field("type", json) ?: return
        val path = field("path", json) ?: return
        val s = sessions[path] ?: return

        when (type) {
            "edit" -> ApplicationManager.getApplication().invokeLater {
                val opsBlock = Regex(""""ops"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
                    .find(json)?.groupValues?.get(1) ?: return@invokeLater
                val opRegex = Regex("""\{[^}]*\}""")
                s.applyingRemote.set(true)
                try {
                    WriteCommandAction.runWriteCommandAction(project) {
                        for (m in opRegex.findAll(opsBlock)) {
                            val op = m.value
                            val opType = field("opType", op) ?: continue
                            val offset = numField("offset", op)?.toInt() ?: continue
                            when (opType) {
                                "insert" -> {
                                    val text = field("text", op) ?: continue
                                    s.editor.document.insertString(offset, text)
                                }
                                "delete" -> {
                                    val len = numField("len", op)?.toInt() ?: continue
                                    s.editor.document.deleteString(offset, offset + len)
                                }
                            }
                        }
                    }
                    s.version = numField("version", json)?.toInt() ?: s.version
                } finally {
                    s.applyingRemote.set(false)
                }
            }
            "cursor" -> ApplicationManager.getApplication().invokeLater {
                val offset = numField("offset", json)?.toInt() ?: return@invokeLater
                drawTeacherCaret(s, offset)
            }
        }
    }

    private fun drawTeacherCaret(s: PairSession, offset: Int) {
        s.teacherCaret?.let { s.editor.markupModel.removeHighlighter(it) }
        val safe = offset.coerceIn(0, s.editor.document.textLength)
        val attrs = TextAttributes()
        attrs.effectColor = Color(0xFF, 0x57, 0x22)  // naranja
        val rh = s.editor.markupModel.addRangeHighlighter(
            safe, (safe + 1).coerceAtMost(s.editor.document.textLength),
            HighlighterLayer.LAST + 1, attrs, HighlighterTargetArea.EXACT_RANGE
        )
        s.teacherCaret = rh
    }

    private fun sendJson(s: String) {
        val w = ws ?: return
        if (w.isOutputClosed) return
        try { w.sendText(s, true) } catch (_: Throwable) {}
    }

    private fun field(name: String, json: String): String? =
        Regex(""""$name"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json)?.groupValues?.get(1)
            ?.replace("\\n", "\n")?.replace("\\\"", "\"")?.replace("\\\\", "\\")
    private fun numField(name: String, json: String): Long? =
        Regex(""""$name"\s*:\s*(-?\d+)""").find(json)?.groupValues?.get(1)?.toLongOrNull()
    private fun q(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private inner class WsListener : WebSocket.Listener {
        private val buf = StringBuilder()
        override fun onOpen(webSocket: WebSocket) {
            ws = webSocket
            webSocket.request(Long.MAX_VALUE)
        }
        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletableFuture<*>? {
            buf.append(data)
            if (last) {
                val msg = buf.toString(); buf.setLength(0)
                handleIncoming(msg)
            }
            webSocket.request(1)
            return null
        }
        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletableFuture<*>? {
            ws = null
            if (!stopped) scheduler.schedule({ connect() }, 2L, TimeUnit.SECONDS)
            return null
        }
        override fun onError(webSocket: WebSocket, error: Throwable) {
            log.warn("Controlex: pair WS error: ${error.message}")
            ws = null
            if (!stopped) scheduler.schedule({ connect() }, 2L, TimeUnit.SECONDS)
        }
    }

    override fun dispose() {
        stopped = true
        for ((p, _) in sessions.toMap()) closeSession(p)
        try { ws?.sendClose(WebSocket.NORMAL_CLOSURE, "") } catch (_: Throwable) {}
        scheduler.shutdownNow()
    }
}
```

### 3.3 Plugin — `RemoteCommandHandlers.kt` (añadir 2 casos)

Dentro del `when (type)` añadir:

```kotlin
"pair-open" -> {
    val rel = strField("path", verified) ?: return
    project.service<PairSessionManager>().openSession(rel)
}
"pair-close" -> {
    val rel = strField("path", verified) ?: return
    project.service<PairSessionManager>().closeSession(rel)
}
```

### 3.4 Plugin — registrar el servicio y arrancarlo

**`plugin.xml`** — dentro de `<extensions>`:
```xml
<projectService serviceImplementation="es.iesclaradelrey.controlex.PairSessionManager"/>
```

**`ControlexStartupActivity.kt`** — añadir tras `ScreenStreamService`:
```kotlin
project.service<PairSessionManager>().start()
```

### 3.5 Panel — `/home/ebarrab/pro/controlex/public/index.html`

1. **Botón nuevo en la tarjeta de cliente**: "👥 Pair coding" → modal.
2. **Modal**:
   - Input "Ruta relativa" (p. ej. `src/Main.java`).
   - Botón "Abrir sesión" → `POST /api/dashboard/pair-open` → recibe token → abre `wss://.../api/dashboard/pair?token=...`.
   - Editor CodeMirror 6 (CDN https://esm.sh/codemirror@6 + extensiones; o el bundle compacto de `@codemirror/basic-setup`). Sustituye contenido al recibir `doc-state`. Al recibir `edit`, aplica las ops como `dispatch({changes})`. Pinta el caret del alumno con un `Decoration.widget` o un `Decoration.mark` de un único carácter con borde-izquierdo de color.
   - Al teclear el profesor, captura `transactionExtender` o `EditorView.updateListener` para extraer las `ChangeSet` y enviarlas como `{type:"edit", baseVersion, ops:[...]}`.
   - Al mover el caret, envía `{type:"cursor", offset}` (debounced 30 ms).
   - Cierre del modal → `POST /api/dashboard/pair-close` y cerrar WS.

**Mapping CodeMirror ChangeSet → ops**:
```js
tr.changes.iterChanges((fromA, toA, fromB, toB, inserted) => {
    if (toA > fromA) ops.push({opType:'delete', offset: fromA, len: toA - fromA});
    if (inserted.length > 0) ops.push({opType:'insert', offset: fromB, text: inserted.toString()});
});
```

**Aplicar `edit` remoto** sin disparar listener:
```js
view.dispatch({
    changes: opsToCM(ops),
    annotations: [Transaction.remote.of(true)]
});
// y en el updateListener: if (tr.annotation(Transaction.remote)) return;
```

### 3.6 `controlex.properties` — sin cambios

Ya está todo (URL servidor, API key).

---

## 4. Versionado y release

1. `build.gradle.kts`: `version = "1.9.0"`.
2. `src/main/resources/META-INF/plugin.xml`: `<version>1.9.0</version>`.
3. `./publish.sh`.
4. `git commit -am "v1.9.0: pair coding (un escritor + caret remoto) sobre WebSocket"`.
5. `git push && git tag v1.9.0 && git push origin v1.9.0`.
6. `gh release create v1.9.0 build/distributions/controlex-1.9.0.zip --title "v1.9.0" --notes "..."`.

---

## 5. Verificación end-to-end

1. **Compila**: `./gradlew compileKotlin` sin errores.
2. **WS plugin↔servidor conecta**: con un cliente real, `pm2 logs controlex` muestra upgrade a `/api/client/pair`.
3. **Abrir sesión desde panel**:
   - Click en "👥 Pair coding" → introduces `src/Main.java`.
   - El IntelliJ del alumno abre `Main.java`.
   - El editor del panel se rellena con el contenido del fichero.
4. **Profesor escribe en el panel** → aparece carácter a carácter en el IntelliJ del alumno (latencia <200 ms).
5. **Alumno escribe en IntelliJ** → aparece en el editor del panel.
6. **Edición simultánea** en zonas distintas → ambas se conservan.
7. **Edición simultánea** en el mismo offset → gana el último; el contenido final es coherente en ambos lados (no debe quedar desfasado).
8. **Caret del alumno** se ve en el panel; **caret del profesor** se ve en IntelliJ (línea fina de color).
9. **Cerrar sesión** desde el panel → en IntelliJ desaparece el caret remoto y los listeners se quitan (verifica que un `documentChanged` posterior NO vuelve a enviar al WS).
10. **Reconexión**: matar el plugin (cerrar IntelliJ) → en el panel se ve "desconectado". Reabrir IntelliJ → la sesión hay que reabrirla manualmente desde el panel (es esperado).
11. **Sesión abandonada**: si el WS del profesor se cierra sin llamar a `pair-close`, el alumno sigue editando localmente sin problemas; al volver a abrirla se hace `doc-state` nuevo.

---

## 6. Lo que NO hay que romper

- Todos los comandos previos (`show-dialog`, `open-file`, `goto-line`, `run-action`, `insert-text`, `highlight-line`, `show-inlay`, `clear-highlights`, `inject-text`, `inject-key`, `inject-click`, `lock-session`, `unlock-session`, `send-file`, `open-url`).
- SSE inverso `/api/client/stream` y firma Ed25519.
- Streaming WebSocket de vídeo `/api/client/video` ↔ `/api/dashboard/video`.
- OAuth Google del panel.
- Dedup deshabilitado.
- Polling `/api/screenshot` como canal principal de capturas.

El nuevo WS de pair es **independiente**: si falla, el resto del plugin sigue funcionando.

---

## 7. Riesgos a vigilar durante la implementación

- **Bucle de eco**: si el plugin aplica una op remota y el `DocumentListener` la re-emite, se entra en bucle. La barrera es `applyingRemote: AtomicBoolean` ya prevista. **Comprobar a mano**: log de `documentChanged` debe disparar 0 veces durante una aplicación remota.
- **EDT**: todo lo que toca `Document`, `Editor`, `MarkupModel` debe ir dentro de `invokeLater` + `WriteCommandAction.runWriteCommandAction` para escrituras. El esqueleto ya lo respeta — no romperlo.
- **Offsets desfasados** cuando llegan ops cruzadas: el rebase del servidor cubre el caso 2-peer. Ojo si `history` se trunca a 1000: clientes muy desfasados podrían recibir ops mal rebasaadas. En la práctica, las ops llegan en milisegundos.
- **CodeMirror updateListener loop**: si re-emite la op que acabas de aplicar como remota, doble-aplicación. Usa `Transaction.remote` (annotation) o un flag externo.
- **Saltos de línea CRLF/LF**: IntelliJ normaliza. Si el servidor manda `\r\n` y el documento es `\n`, los offsets se desfasan. Forzar `\n` en `q()` (ya lo hace el ejemplo).
- **Ficheros grandes**: el `doc-state` inicial va completo en un mensaje WS. Para ficheros >1 MB conviene comprimir o paginar; en esta sesión NO lo hagas, sólo aceptar ficheros < 200 KB y rechazar el resto en el plugin con un log.
- **Sin firma Ed25519 en `pair-edit`**: aceptable porque el WS está autenticado por Bearer (plugin) y por token corto (dashboard). El riesgo residual es que un atacante con la API key del plugin haga ops arbitrarias — pero ese atacante ya podría hacer cualquier comando vía REST. El comando `pair-open`/`pair-close` SÍ va por SSE firmado.
- **Múltiples sesiones simultáneas en el mismo cliente**: el `PairSessionManager` lleva un map `path -> session`, así que sí se puede. El servidor también: `pairSessions` está keyado por `(clientId, path)`.

---

## 8. Si algo va mal — rollback rápido

```bash
cd /home/ebarrab/pro/controlex
pm2 reload controlex --update-env
# Volver a v1.8.0:
gh release download v1.8.0
# o git revert + ./publish.sh en /home/ebarrab/controlex
```

---

## 9. Lo que viene después (NO HACER en esta sesión)

- **Sesión 3**: multi-alumno simultáneo (varias `dashboards` por sesión + colores distintos por peer; awareness compartida).
- **Sesión 4**: persistir el estado de la sesión entre reinicios del plugin (snapshot a disco + replay).
- **Sesión 5**: selección remota (no sólo caret) y "follow mode" (el alumno sigue al profesor automáticamente).
- **Migración a CRDT (Yjs)** si el rebase trivial empieza a generar inconsistencias en la práctica.

Buena suerte.
