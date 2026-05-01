# Plan Sesión 1 — SSE inverso + Nivel 1 comandos remotos + botón "Pedir ayuda"

> Este documento es un brief para retomar el trabajo. Léelo entero antes de empezar.
> Estado del proyecto cuando se escribió: v1.4.3 desplegado. Todo lo previo funciona.

---

## 0. Contexto rápido (no re-derivar)

- Proyecto en `/home/ebarrab/controlex` (cliente IntelliJ + servidor Node.js).
- Producción en `/home/ebarrab/pro/controlex/` (Apache reverse-proxy a `127.0.0.1:4000`, gestionado por PM2 — `pm2 reload controlex --update-env`).
- Despliegue del plugin: `./publish.sh` (compila + copia ZIP a `/home/ebarrab/pro/controlex/public/` + actualiza ruta en `server.js` + recarga PM2).
- Repo GitHub: `ebarrabc2526/controlex`. Releases por tag (`v1.x.y`).
- Panel: `https://controlex.ebarrab.com/` (OAuth Google, solo `ebarrabc2526@gmail.com`).
- Descarga plugin: `https://ebarrab.com/controlex` (alias) o `https://controlex.ebarrab.com/plugin`.
- API key plugin↔servidor: `Bearer ControlEx-IES-ClaraDelRey-2026` (en `.env` del servidor + `controlex.properties` del plugin).
- Cliente ID en plugin: `~/.controlex/client-id.txt` (per-machine desde v1.4.2).

---

## 1. Objetivo de la sesión

Sustituir el polling de comandos (que va atado al ciclo de upload de capturas, hasta 30s de latencia) por un canal **SSE inverso servidor→plugin** con latencia <1s, e implementar el primer set de comandos remotos seguros. Añadir además un botón "Pedir ayuda" en el plugin que el alumno puede usar para llamar la atención del profesor.

### Alcance estricto (NO hacer más)

- ✅ SSE inverso para entrega push de comandos.
- ✅ Comandos Nivel 1: `show-dialog` (texto + imagen opcional), `open-file`, `goto-line`, `run-action` (con allowlist).
- ✅ Botón "Pedir ayuda" en plugin → notificación en panel.
- ✅ Log local de comandos en `controlex/comandos.log` del proyecto del alumno.
- ❌ NO inyección de texto, NO highlights persistentes, NO patches — eso es Sesión 2.
- ❌ NO firma de comandos — pendiente Sesión 2.
- ❌ NO streaming rápido de pantalla, NO Robot keyboard/mouse — eso es Sesión 3.

---

## 2. Arquitectura

```
Profesor (panel)        Servidor                      Alumno (plugin)
     |                      |                              |
     |                      |  GET /api/client/stream  <---|  abre al iniciar
     |                      |  (Bearer, ?clientId=...)     |  (long-lived)
     |                      |  --SSE keep-alive------>     |
     |                      |                              |
     |--POST /api/dashboard/|                              |
     |   command  --------> |                              |
     |   {clientId, type,   |  push event 'command'-->     | ejecutar
     |    payload}          |                              |
     |                      |                              |
     |                      |  POST /api/help-request <----| (alumno pulsa botón)
     |                      |                              |
     |  SSE 'help-request'  |  push help-request --->      |
     |  <-------- (ya existe stream del panel)             |
```

El stream existente del panel (`GET /api/dashboard/stream`) se reutiliza para enviarle al profesor los `help-request` que llegan de los alumnos.

---

## 3. Cambios fichero por fichero

### 3.1 Servidor — `/home/ebarrab/pro/controlex/server.js`

**A. Nuevo: endpoint SSE para el plugin**

Añadir cerca del bloque `// ── Client API ──`:

```javascript
// Per-client SSE streams: server pushes commands here in real time
const clientStreams = new Map();  // clientId -> Set<res>

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

    // Drain any pending commands queued via /api/screenshot (back-compat)
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
    const set = clientStreams.get(clientId);
    if (!set || set.size === 0) {
        // Fallback: queue for next /api/screenshot poll
        if (!pendingCommands.has(clientId)) pendingCommands.set(clientId, []);
        pendingCommands.get(clientId).push(cmd);
        return false;
    }
    const payload = `event: command\ndata: ${JSON.stringify(cmd)}\n\n`;
    let delivered = 0;
    for (const r of set) {
        try { r.write(payload); delivered++; }
        catch (_) { set.delete(r); }
    }
    return delivered > 0;
}
```

**B. Nuevo: endpoint para el profesor enviar comandos**

```javascript
// Allowlist of IntelliJ action IDs the dashboard is allowed to trigger
const ALLOWED_ACTIONS = new Set([
    'SaveAll', 'Synchronize', 'CompileDirty',
    'Run', 'Stop', 'Debug', 'Rerun',
    'EditorCopy', 'EditorPaste', 'EditorSelectAll',
    'ToggleLineBreakpoint',
    'Find', 'Replace',
    'ReformatCode',
    'Tree-selectFirst', 'Tree-selectLast'
]);

app.post('/api/dashboard/command', requireApiAuth, (req, res) => {
    const { clientId, type, payload } = req.body || {};
    if (!clientId || !type) return res.status(400).json({ error: 'clientId y type son obligatorios' });

    // Validate per type
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
```

**C. Nuevo: endpoint para que el alumno pida ayuda**

```javascript
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
```

(`broadcast()` ya existe y emite por el stream del panel `/api/dashboard/stream`.)

### 3.2 Plugin — nuevo `CommandStreamReceiver.kt`

Path: `/home/ebarrab/controlex/src/main/kotlin/es/iesclaradelrey/controlex/CommandStreamReceiver.kt`

Servicio de proyecto que abre la conexión SSE al servidor y dispatcha comandos.

Esqueleto:

```kotlin
package es.iesclaradelrey.controlex

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.Flow
import java.util.concurrent.Executors

@Service(Service.Level.PROJECT)
class CommandStreamReceiver(private val project: Project) : Disposable {

    private val log = Logger.getInstance(CommandStreamReceiver::class.java)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "Controlex-CmdStream").apply { isDaemon = true }
    }
    @Volatile private var stopped = false

    fun start() { connect() }

    private fun connect() {
        if (stopped) return
        val clientId = project.service<ServerTransmitter>().clientId  // expose `clientId` (currently private)
        val url = ControlexConfig.SERVER_URL + "/api/client/stream?clientId=" +
                  java.net.URLEncoder.encode(clientId, StandardCharsets.UTF_8)
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer ${ControlexConfig.SERVER_API_KEY}")
            .GET()
            .timeout(Duration.ofMinutes(60))
            .build()

        val sub = SseLineSubscriber()
        http.sendAsync(req, BodyHandlers.fromLineSubscriber(sub))
            .whenComplete { _, _ ->
                if (!stopped) scheduler.schedule({ connect() }, 2, java.util.concurrent.TimeUnit.SECONDS)
            }
    }

    private inner class SseLineSubscriber : Flow.Subscriber<String> {
        private var event: String? = null
        private val data = StringBuilder()
        override fun onSubscribe(s: Flow.Subscription) { s.request(Long.MAX_VALUE) }
        override fun onNext(line: String) {
            when {
                line.startsWith(":") -> {}  // comment/heartbeat
                line.startsWith("event:") -> event = line.substringAfter(":").trim()
                line.startsWith("data:")  -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substringAfter(":").trim())
                }
                line.isEmpty() -> {
                    val ev = event; val payload = data.toString()
                    event = null; data.setLength(0)
                    if (ev == "command" && payload.isNotEmpty()) {
                        ApplicationManager.getApplication().invokeLater {
                            RemoteCommandHandlers.execute(project, payload)
                        }
                    }
                }
            }
        }
        override fun onError(t: Throwable) {}
        override fun onComplete() {}
    }

    override fun dispose() {
        stopped = true
        scheduler.shutdownNow()
    }
}
```

**Importante**: hay que exponer el `clientId` de `ServerTransmitter` cambiando `private val clientId` a `val clientId` (sigue siendo solo accesible desde el mismo paquete porque es `internal` por defecto en Kotlin... realmente es `public` por defecto, pero solo lo lee otro service del plugin, no es problema).

### 3.3 Plugin — nuevo `RemoteCommandHandlers.kt`

Path: `/home/ebarrab/controlex/src/main/kotlin/es/iesclaradelrey/controlex/RemoteCommandHandlers.kt`

```kotlin
package es.iesclaradelrey.controlex

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.WindowManager
import java.io.File

object RemoteCommandHandlers {
    private val log = Logger.getInstance(RemoteCommandHandlers::class.java)

    private val ALLOWED_ACTIONS = setOf(
        "SaveAll", "Synchronize", "CompileDirty",
        "Run", "Stop", "Debug", "Rerun",
        "EditorCopy", "EditorPaste", "EditorSelectAll",
        "ToggleLineBreakpoint",
        "Find", "Replace",
        "ReformatCode"
    )

    fun execute(project: Project, jsonPayload: String) {
        try {
            val type = strField("type", jsonPayload) ?: return
            ControlexLog.appendCommand(project, type, jsonPayload)
            when (type) {
                "show-dialog" -> {
                    val text  = strField("text", jsonPayload) ?: return
                    val title = strField("title", jsonPayload) ?: "Mensaje del profesor"
                    Messages.showInfoMessage(project, text, title)
                }
                "open-file" -> {
                    val rel = strField("path", jsonPayload) ?: return
                    val line = numField("line", jsonPayload)?.toInt()
                    openFile(project, rel, line)
                }
                "goto-line" -> {
                    val line = numField("line", jsonPayload)?.toInt() ?: return
                    val rel = strField("path", jsonPayload)
                    if (rel != null) openFile(project, rel, line)
                    else gotoLineInActiveEditor(project, line)
                }
                "run-action" -> {
                    val id = strField("actionId", jsonPayload) ?: return
                    if (id !in ALLOWED_ACTIONS) { log.warn("Controlex: action no permitida: $id"); return }
                    runAction(project, id)
                }
                else -> log.warn("Controlex: tipo de comando desconocido: $type")
            }
        } catch (e: Exception) {
            log.warn("Controlex: error ejecutando comando", e)
        }
    }

    private fun openFile(project: Project, relativePath: String, line: Int?) {
        val base = project.basePath ?: return
        val abs = File(base, relativePath)
        val vf = LocalFileSystem.getInstance().findFileByIoFile(abs) ?: return
        val descriptor = if (line != null) OpenFileDescriptor(project, vf, line - 1, 0)
                         else OpenFileDescriptor(project, vf)
        FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
    }

    private fun gotoLineInActiveEditor(project: Project, line: Int) {
        val editor: Editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        editor.caretModel.moveToLogicalPosition(LogicalPosition(line - 1, 0))
        editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
    }

    private fun runAction(project: Project, actionId: String) {
        val action = ActionManager.getInstance().getAction(actionId) ?: return
        val component = WindowManager.getInstance().getFrame(project)?.rootPane
        val dataContext: DataContext = DataContext { dataId ->
            when (dataId) {
                "project" -> project
                else -> null
            }
        }
        val event = AnActionEvent.createFromDataContext("ControlexRemote", null, dataContext)
        action.actionPerformed(event)
    }

    // — Lightweight JSON field extractors (same approach used in ServerTransmitter) —
    private fun strField(name: String, json: String): String? =
        Regex("""\"$name\"\s*:\s*\"((?:[^\"\\]|\\.)*)\"""").find(json)?.groupValues?.get(1)
            ?.replace("\\n", "\n")?.replace("\\\"", "\"")?.replace("\\\\", "\\")
    private fun numField(name: String, json: String): Long? =
        Regex("""\"$name\"\s*:\s*(-?\d+)""").find(json)?.groupValues?.get(1)?.toLongOrNull()
}
```

### 3.4 Plugin — añadir log de comandos en `ControlexLog.kt`

Agregar una nueva función `appendCommand(project, type, raw)` que escribe en `controlex/comandos.log` con timestamp + tipo + payload (sanitizado: cortar a 500 chars). Sigue el patrón de las funciones existentes.

### 3.5 Plugin — botón "Pedir ayuda"

Crear `RequestHelpAction.kt` (un `AnAction`) que:
1. Muestre un input `Messages.showInputDialog("¿Qué necesitas?", "Pedir ayuda al profesor", null)`.
2. POST a `/api/help-request` con `{clientId, text}` y Bearer.
3. Notifique al alumno con `Messages.showInfoMessage("Tu profesor ha sido avisado.", "Controlex")`.

Registrar la acción en `plugin.xml` dentro de `<actions>`:
```xml
<action id="Controlex.RequestHelp"
        class="es.iesclaradelrey.controlex.RequestHelpAction"
        text="Pedir ayuda al profesor"
        icon="AllIcons.Actions.Help">
    <add-to-group group-id="ToolsMenu" anchor="last"/>
    <keyboard-shortcut keymap="$default" first-keystroke="ctrl alt H"/>
</action>
```

### 3.6 Plugin — registrar el nuevo servicio y arrancar

**`plugin.xml`** — dentro de `<extensions>`:
```xml
<projectService serviceImplementation="es.iesclaradelrey.controlex.CommandStreamReceiver"/>
```

**`ControlexStartupActivity.kt`** — añadir al final del bloque que arranca servicios:
```kotlin
project.service<CommandStreamReceiver>().start()
```

### 3.7 Servidor `/api/dashboard/stream` — manejar 'help-request'

Ya está. El `broadcast()` envía a todos los SSE de panel. Solo hay que añadir el listener en el panel.

### 3.8 Panel — `/home/ebarrab/pro/controlex/public/index.html`

Añadir:

1. Listener SSE `help-request` que muestre un toast con el alumno + texto, y un botón flotante "🆘 N solicitudes" en el navbar.
2. UI para enviar comandos desde una tarjeta de cliente:
   - Botón ✉️ "Diálogo" → modal con textarea + URL de imagen opcional.
   - Botón 📂 "Abrir archivo" → modal con input de ruta relativa y línea opcional.
   - Botón ⚡ "Acción" → select con la allowlist de acciones.

Endpoint a usar: `POST /api/dashboard/command` con `{clientId, type, payload}`.

---

## 4. Versionado y release

1. `build.gradle.kts`: `version = "1.5.0"`.
2. `src/main/resources/META-INF/plugin.xml`: `<version>1.5.0</version>`.
3. `./publish.sh` (compila + despliega + reload PM2).
4. `git commit -am "v1.5.0: SSE inverso + comandos remotos Nivel 1 + Pedir ayuda"`.
5. `git push && git tag v1.5.0 && git push origin v1.5.0`.
6. `gh release create v1.5.0 build/distributions/controlex-1.5.0.zip --title "v1.5.0" --notes "..."`.

---

## 5. Verificación end-to-end

1. **Plugin compila**: `./gradlew compileKotlin` sin errores.
2. **SSE conecta**: con un cliente real corriendo, `pm2 logs controlex` debe mostrar request a `/api/client/stream`. Latencia esperada de comando: <500ms.
3. **Comando `show-dialog`**: enviar desde panel → diálogo aparece en IntelliJ del alumno casi al instante.
4. **Comando `open-file`**: enviar `{path: "src/Main.java"}` → IntelliJ abre el archivo y salta a la línea si se especifica.
5. **Comando `run-action` con allowlist**: `SaveAll` funciona; un id arbitrario devuelve 400.
6. **Pedir ayuda**: Ctrl+Alt+H en IntelliJ → input → enviar → panel muestra toast y contador.
7. **Log local**: `~/proyecto/controlex/comandos.log` contiene cada comando ejecutado con timestamp.
8. **Resiliencia**: matar el servidor 30s y volver a arrancar — el plugin reconecta automáticamente (logs muestran reintento).
9. **Sin OAuth**: `/api/client/stream` y `/api/help-request` no piden cookie de Google; usan Bearer.
10. **Con OAuth**: `/api/dashboard/command` rechaza sin sesión Google (probar con curl sin cookie → 401).

---

## 6. Lo que NO hay que romper

- Polling de `/api/screenshot` sigue siendo el canal principal de datos. No quitarlo.
- Sistema de comandos antiguo (que viaja en la respuesta del POST `/api/screenshot`) **debe seguir funcionando** como fallback (el endpoint nuevo `/api/client/stream` es additivo). El servidor ya cae a `pendingCommands.get(clientId).push(cmd)` si no hay SSE conectado — no quitar esa rama.
- OAuth del panel intacto.
- `/plugin` y `ebarrab.com/controlex` siguen sirviendo el ZIP.
- Dedup deshabilitado (estado actual). NO reactivar dedup.

---

## 7. Riesgos a vigilar durante la implementación

- **EDT vs background thread**: todo lo que toca el editor de IntelliJ debe ir por `ApplicationManager.getApplication().invokeLater { ... }`. El `RemoteCommandHandlers.execute` ya se llama desde invokeLater en el subscriber, pero ojo si refactorizas.
- **`HttpClient.sendAsync` con `BodyHandlers.fromLineSubscriber`** tiene un timeout efectivo: si el servidor no escribe nada en X tiempo, la conexión muere. Por eso el heartbeat cada 10s (ya en el server). Confirmar que el plugin no lo cierra antes — `Duration.ofMinutes(60)` en el request da margen.
- **Reconexión**: si el plugin se queda sin red, la promesa `whenComplete` reagenda en 2s. No hacer backoff exponencial — para el caso de uso (aulas con red estable) está bien.
- **clientId leak**: el `clientId` viaja en query string y queda en logs de Apache (`access.log`). Aceptable para esta versión; en una iteración futura mover a header.
- **HTTP/2 buffering en Apache**: el header `X-Accel-Buffering: no` (que ya tenemos) es para nginx; en Apache es `mod_proxy_http` que respeta `flushHeaders()` por defecto. Si los eventos no llegan en tiempo real, revisar `ProxyPass /api/client/stream ... flushpackets=on` en el vhost. El stream del panel ya funciona — debería funcionar este igual.

---

## 8. Si algo va mal — rollback rápido

```bash
cd /home/ebarrab/pro/controlex
git stash               # solo si hubieras editado en producción
pm2 reload controlex --update-env
# Volver a v1.4.3:
gh release download v1.4.3 -A zip
# o simplemente revert en /home/ebarrab/controlex y publish.sh
```

---

## 9. Lo que viene después (NO HACER en esta sesión)

- **Sesión 2**: firma asimétrica de comandos (clave pública embebida en plugin) + Nivel 2 (insertar texto, comentario flotante, resaltar línea con `RangeHighlighter`).
- **Sesión 3**: streaming MJPEG sobre WebSocket (10-15 fps) + inyección teclado/ratón con `java.awt.Robot`.

Buena suerte. Si te atascas con la API de IntelliJ Platform, el sample oficial está en: https://github.com/JetBrains/intellij-sdk-code-samples
