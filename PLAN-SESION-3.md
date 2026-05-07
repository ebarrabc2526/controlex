# Plan Sesión 3 — Live bajo demanda + nombre/categorías + ocultar clientes + clientId determinista

> Este documento es un brief para retomar el trabajo. Léelo entero antes de empezar.
> Estado del proyecto cuando se escribió: v1.9.0 desplegado. Todo lo previo funciona
> (SSE inverso, comandos firmados, streaming WebSocket, lock-session, send-file, open-url, pair coding).

---

## 0. Contexto rápido (no re-derivar)

- Proyecto en `/home/ebarrab/controlex` (cliente IntelliJ + servidor Node.js).
- Producción en `/home/ebarrab/pro/controlex/` (Apache reverse-proxy a `127.0.0.1:4000`, gestionado por PM2 — `pm2 reload controlex --update-env`).
- Despliegue del plugin: `./publish.sh` (compila + copia ZIP a `/home/ebarrab/pro/controlex/public/` + actualiza ruta en `server.js` + recarga PM2).
- Repo GitHub: `ebarrabc2526/controlex`. Releases por tag (`v1.x.y`).
- Panel: `https://controlex.ebarrab.com/` (OAuth Google, solo `ebarrabc2526@gmail.com`).
- API key plugin↔servidor: `Bearer ControlEx-IES-ClaraDelRey-2026`.
- Cliente ID actual: UUID per-machine en `~/.controlex/client-id.txt`.
- Streaming actual: el plugin (`ScreenStreamService`) emite frames JPEG continuamente al WS `/api/client/video` desde el arranque; el panel **no tiene UI** de visualización live.

---

## 1. Objetivo de la sesión

Cuatro bloques **independientes** entre sí (se pueden implementar y probar por separado, en este orden):

1. **Streaming live bajo demanda** desde el panel: vistas fullscreen / mosaico / cuadrícula con WebSocket de vídeo, controlado por el panel (start/stop al plugin).
2. **Ocultar/mostrar clientes** desde el panel (incluido "ocultar todos" / "mostrar todos"). Persistencia en `localStorage` del navegador.
3. **Nombre + categorías** del cliente. El alumno asigna un nombre `CATEGORIA#APODO` desde el plugin; el servidor extrae la categoría principal automáticamente; desde el panel se pueden añadir categorías extra; persistencia en JSON de servidor.
4. **clientId determinista** = `sha256(name + intellijUser + hostname)` — para evitar duplicados al reinstalar el plugin o cambiar de proyecto.

### Alcance estricto (NO hacer más)

- ✅ Toggle "🔴 Live" global en la barra; cuando ON, las imágenes del panel se sustituyen por frames del WS.
- ✅ Comandos nuevos `stream-start` / `stream-stop` (servidor → plugin, vía SSE inverso firmado).
- ✅ El plugin **no emite vídeo** hasta que llega `stream-start` (cambio de comportamiento).
- ✅ Botón ✕ "Ocultar" en cada tarjeta y acciones globales "Ocultar todos / Mostrar todos / Lista de ocultos".
- ✅ Diálogo "Configurar nombre" en el menú **Tools** del IDE; nombre persistido en `~/.controlex/client-name.txt`.
- ✅ El servidor parsea `name` y deduce `categoryMain` y `nickname`. El panel agrupa por categoría (cabecera por grupo).
- ✅ Endpoint `POST /api/dashboard/categories` para añadir/quitar categorías extra de un cliente; persistido en `~/.controlex-server/categories.json`.
- ✅ `clientId = sha256(name + intellijUser + hostname)[:32]`. Si `name` está vacío, se mantiene el UUID legacy (transición suave).
- ❌ NO multi-categorías en el nombre del cliente (`CATEGORIA#APODO` es un solo `#`).
- ❌ NO persistencia de "ocultos" en el servidor — sólo `localStorage` del navegador.
- ❌ NO throttling adaptativo del live (el plugin emite a `STREAM_FPS = 10` cuando está activo, igual que ahora; el panel pinta lo que recibe).
- ❌ NO multi-viewer concurrente para el mismo cliente con distintos panels (el modelo es single-user).
- ❌ NO detección de duplicados pre-existentes con UUID legacy: el cambio entra en vigor cuando el alumno asigna nombre.

---

## 2. Arquitectura

### 2.1 Live bajo demanda

```
Panel                              Servidor                          Plugin
  |  click "🔴 Live ON"               |                                 |
  |  abre WS /api/dashboard/video     |                                 |
  |  ?token=...                       |                                 |
  |                                   | si no hay videoSenders[clientId]|
  |                                   | manda 'stream-start' por SSE    |
  |                                   |  ─────────────────────────────→ |
  |                                   |                                 | abre WS /api/client/video
  |                                   |  ←──── frames JPEG (binary) ─── |
  |  ←──── frames JPEG (binary) ──────|                                 |
  |  panel pinta en <img> o <canvas>  |                                 |
  |                                   |                                 |
  |  click "Live OFF" o cierre WS     |                                 |
  |  panel cierra WS                  |                                 |
  |                                   | si ya no hay viewers,           |
  |                                   | manda 'stream-stop' por SSE     |
  |                                   |  ─────────────────────────────→ |
  |                                   |                                 | cierra WS de video,
  |                                   |                                 | para frame loop
```

Decisión: el plugin **abre y cierra** el WS bajo demanda. El servidor lleva la cuenta de viewers por clientId; cuando pasa de 0 a 1 manda `stream-start`, cuando pasa de 1 a 0 manda `stream-stop`.

### 2.2 Nombre + categorías

```
Plugin: Tools → "Controlex: configurar nombre"
   diálogo: input "DM1D1A#01-BLAMA"
   guarda ~/.controlex/client-name.txt
   recalcula clientId determinista al siguiente envío

Servidor:
   recibe name en /api/screenshot → parse split('#', 2)
   - categoryMain  = parte antes de '#' (o null)
   - nickname      = parte después de '#'  (o el name completo si no hay '#')
   categorías extra = leídas de ~/.controlex-server/categories.json[clientId]

Panel:
   agrupa clientes por categoryMain (cabecera por grupo)
   en la tarjeta: badge con categorías extra (clic → editar)
   editor de categorías extra: input "tag1, tag2" → POST /api/dashboard/categories
```

### 2.3 Ocultar clientes (panel-only)

```
localStorage['controlex.hidden'] = JSON [clientId, ...]
   - botón ✕ en tarjeta → añade a la lista
   - "Ocultar todos" → todos los actuales a la lista
   - "Mostrar todos" → vacía la lista
   - "Lista de ocultos" abre un panel lateral con sus nombres y un botón para reactivarlos
   - render() filtra out las tarjetas en la lista
```

---

## 3. Cambios fichero por fichero

### 3.1 Servidor — `/home/ebarrab/pro/controlex/server.js`

**A. Imports / paths**

Cerca del inicio:
```javascript
const fs = require('fs');
const os = require('os');
const CATEGORIES_PATH = path.join(os.homedir(), '.controlex-server', 'categories.json');
```

**B. Cargar categorías persistidas**

```javascript
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
```

**C. Aceptar `name` en `/api/screenshot`**

Dentro del handler, añadir `name` al destructuring y al objeto `clients.set(...)`. Calcular `categoryMain` y `nickname`:

```javascript
const rawName = String(req.body.name || '').trim();
let categoryMain = null, nickname = rawName || null;
if (rawName.includes('#')) {
    const idx = rawName.indexOf('#');
    categoryMain = rawName.slice(0, idx) || null;
    nickname     = rawName.slice(idx + 1) || null;
}
// ... añadir a clients.set:
//    name: rawName,
//    categoryMain,
//    nickname,
```

**D. Extender `clientView`**

```javascript
function clientView(c) {
    // ... lo que ya hay
    return {
        // ... campos previos
        name:           c.name || '',
        categoryMain:   c.categoryMain || null,
        nickname:       c.nickname || null,
        extraCategories: extraCategories.get(c.clientId) || []
    };
}
```

**E. Endpoint para gestionar categorías extra**

```javascript
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
```

**F. Comandos `stream-start` / `stream-stop`**

Añadir al `switch(type)` de `/api/dashboard/command`:

```javascript
case 'stream-start':
case 'stream-stop':
    // payload vacío, sólo el type
    break;
```

Añadirlos también al allowlist del plugin (§3.4).

**G. Auto-start/stop del stream según viewers**

Modificar `handleDashboardVideoWs` para llamar a `pushCommand` cuando los viewers pasen de 0→1:

```javascript
function handleDashboardVideoWs(ws, clientId) {
    if (!videoViewers.has(clientId)) videoViewers.set(clientId, new Set());
    const viewers = videoViewers.get(clientId);
    const wasEmpty = viewers.size === 0;
    viewers.add(ws);
    if (wasEmpty) {
        pushCommand(clientId, { type: 'stream-start' });
    }
    ws.on('close', () => {
        viewers.delete(ws);
        if (videoViewers.get(clientId)?.size === 0) {
            videoViewers.delete(clientId);
            pushCommand(clientId, { type: 'stream-stop' });
        }
    });
    ws.on('error', () => {
        viewers.delete(ws);
        if (videoViewers.get(clientId)?.size === 0) {
            videoViewers.delete(clientId);
            pushCommand(clientId, { type: 'stream-stop' });
        }
    });
}
```

### 3.2 Plugin — `ServerTransmitter.kt`

**A. clientId determinista**

Sustituir `loadOrCreateClientId()` por algo así:

```kotlin
val clientId: String get() = computeClientId()

private fun computeClientId(): String {
    val name = readClientName()
    val user = gitUserName()
    val host = try { InetAddress.getLocalHost().hostName } catch (_: Exception) { "unknown" }
    if (name.isBlank()) {
        // Fallback: UUID legacy en disco
        return loadLegacyClientId()
    }
    val md = MessageDigest.getInstance("SHA-256")
    val bytes = md.digest("$name|$user|$host".toByteArray(StandardCharsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }.substring(0, 32)
}

private fun readClientName(): String {
    val home = System.getProperty("user.home") ?: return ""
    val f = File(home, ".${ControlexConfig.DIR_NAME}/client-name.txt")
    return if (f.exists()) f.readText().trim() else ""
}

private fun loadLegacyClientId(): String {
    // El método anterior tal cual, sin cambios.
}
```

> **Importante**: cambiar `clientId` de `by lazy` a `get()` para que se recalcule cuando el alumno asigne/cambie el nombre. Asumir coste mínimo (lectura de un fichero pequeño + un SHA-256).

**B. Enviar `name` en cada captura**

Añadir en `buildRequestJson`:
```kotlin
append(""","name":"""); append(esc(readClientName()))
```

### 3.3 Plugin — nuevo `ConfigureNameAction.kt`

`/home/ebarrab/controlex/src/main/kotlin/es/iesclaradelrey/controlex/ConfigureNameAction.kt`

```kotlin
package es.iesclaradelrey.controlex

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import java.io.File

class ConfigureNameAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val home = System.getProperty("user.home") ?: return
        val f = File(home, ".${ControlexConfig.DIR_NAME}/client-name.txt").also { it.parentFile.mkdirs() }
        val current = if (f.exists()) f.readText().trim() else ""
        val input = Messages.showInputDialog(
            e.project,
            "Formato sugerido: CATEGORIA#APODO  (ej. DM1D1A#01-BLAMA)",
            "Controlex — Configurar nombre",
            null,
            current,
            null
        ) ?: return
        f.writeText(input.trim())
        Messages.showInfoMessage(
            e.project,
            "Nombre guardado: \"${input.trim()}\".\nTu identidad en el panel se actualizará en el próximo envío.",
            "Controlex"
        )
    }
}
```

Registrar en `plugin.xml` dentro de `<actions>`:
```xml
<action id="Controlex.ConfigureName"
        class="es.iesclaradelrey.controlex.ConfigureNameAction"
        text="Controlex: configurar nombre"
        icon="AllIcons.General.User">
    <add-to-group group-id="ToolsMenu" anchor="last"/>
</action>
```

### 3.4 Plugin — `RemoteCommandHandlers.kt`

Añadir dos casos al `when (type)`:

```kotlin
"stream-start" -> project.service<ScreenStreamService>().startStream()
"stream-stop"  -> project.service<ScreenStreamService>().stopStream()
```

### 3.5 Plugin — `ScreenStreamService.kt` (refactor a on-demand)

Añadir/exponer métodos públicos `startStream()` y `stopStream()`. Cambiar `start()` actual para que **no abra** el WS automáticamente (sólo prepare estructuras).

```kotlin
fun start() { /* no-op: esperar stream-start */ }

fun startStream() {
    if (stopped) return
    if (ws != null) return  // ya activo
    connect()
}

fun stopStream() {
    frameTask?.cancel(false)
    frameTask = null
    try { ws?.sendClose(WebSocket.NORMAL_CLOSURE, "stop") } catch (_: Throwable) {}
    ws = null
}
```

Y en `WsListener.onClose` / `onError`, **NO** reconectar automáticamente — el servidor dirá `stream-start` cuando vuelva a haber viewers.

```kotlin
override fun onClose(...): CompletableFuture<*>? {
    ws = null
    frameTask?.cancel(false)
    return null
}
```

### 3.6 Panel — `index.html`

**A. Toggle live + viewer**

En la barra superior:
```html
<button class="mode-btn" id="btn-live" onclick="toggleLive()">🔴 Live OFF</button>
```

JS:
```js
let liveOn = false;
const liveSockets = new Map();   // clientId -> WebSocket

async function toggleLive() {
    liveOn = !liveOn;
    document.getElementById('btn-live').textContent = liveOn ? '🔴 Live ON' : '🔴 Live OFF';
    document.getElementById('btn-live').classList.toggle('active', liveOn);
    if (liveOn) {
        for (const c of clients) await openLive(c.clientId);
    } else {
        for (const [_, ws] of liveSockets) { try { ws.close(); } catch {} }
        liveSockets.clear();
        render();
    }
}

async function openLive(clientId) {
    if (liveSockets.has(clientId)) return;
    const r = await fetch('/api/dashboard/video-token?clientId=' + encodeURIComponent(clientId));
    const { token } = await r.json();
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    const ws = new WebSocket(`${proto}://${location.host}/api/dashboard/video?token=${token}`);
    ws.binaryType = 'blob';
    ws.onmessage = (ev) => {
        const url = URL.createObjectURL(ev.data);
        for (const img of document.querySelectorAll(`[data-id="${CSS.escape(clientId)}"] img`)) {
            const old = img.src; img.src = url;
            if (old.startsWith('blob:')) URL.revokeObjectURL(old);
        }
        const fullImg = document.getElementById('full-img');
        if (fullImg && fullImg.dataset.id === clientId) {
            const old = fullImg.src; fullImg.src = url;
            if (old.startsWith('blob:')) URL.revokeObjectURL(old);
        }
    };
    ws.onclose = () => liveSockets.delete(clientId);
    liveSockets.set(clientId, ws);
}
```

**B. Ocultar / mostrar**

```js
let hidden = new Set(JSON.parse(localStorage.getItem('controlex.hidden') || '[]'));
function persistHidden() { localStorage.setItem('controlex.hidden', JSON.stringify([...hidden])); }
function hideClient(id)  { hidden.add(id);    persistHidden(); render(); }
function showClient(id)  { hidden.delete(id); persistHidden(); render(); }
function hideAll()       { for (const c of clients) hidden.add(c.clientId); persistHidden(); render(); }
function showAll()       { hidden.clear(); persistHidden(); render(); }

// En renderGrid / renderMosaic, filtrar:
const visible = clients.filter(c => !hidden.has(c.clientId));
```

Botones en la barra: "👁 Mostrar todos", "🙈 Ocultar todos", "📋 Ocultos (N)" (abre un modal lista).

En cada tarjeta, un botón ✕ pequeño que llama `hideClient(c.clientId)`.

**C. Agrupación por categoría**

En `renderGrid`, antes de renderizar, agrupar:
```js
const groups = new Map();
for (const c of visible) {
    const key = c.categoryMain || '(sin categoría)';
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(c);
}
// Render cabecera por grupo + tarjetas dentro.
```

En cada tarjeta, mostrar `c.nickname || c.clientId.slice(0,8)` como título, y badges con `c.extraCategories`.

**D. Editor de categorías extra**

Botón "🏷" en la tarjeta → `prompt("Categorías extra (separadas por coma):", c.extraCategories.join(', '))`. Llama `POST /api/dashboard/categories`.

### 3.7 Plugin — `RemoteCommandHandlers.kt` allowlist (servidor)

Añadir al `ALLOWED_ACTIONS` (servidor) — espera, el allowlist es de IntelliJ actions. Aquí necesitamos extender el switch de tipos válidos para `stream-start` y `stream-stop` en el endpoint `/api/dashboard/command`, lo cual ya está cubierto en §3.1 punto F.

---

## 4. Versionado y release

1. `build.gradle.kts`: `version = "1.10.0"`.
2. `src/main/resources/META-INF/plugin.xml`: `<version>1.10.0</version>`.
3. `./publish.sh`.
4. `git commit -am "v1.10.0: live on-demand + nombre/categorías + ocultar + clientId determinista"`.
5. `git push && git tag v1.10.0 && git push origin v1.10.0`.
6. `gh release create v1.10.0 build/distributions/controlex-1.10.0.zip --title "v1.10.0" --notes "..."`.

---

## 5. Verificación end-to-end

### 5.1 Live on-demand
1. Toggle "🔴 Live OFF" → click → "ON". El plugin recibe `stream-start` (visible en `pm2 logs` o en la consola del IDE).
2. Las tarjetas pasan de mostrar JPEG cada N segundos a actualizar a 10 fps.
3. Click otra vez → "OFF". El plugin recibe `stream-stop`. Los frames paran.
4. **Sin viewers**: arrancar IntelliJ del alumno con el panel cerrado. `videoSenders` permanece vacío hasta que el panel abra una conexión.
5. Multi-vista: abrir fullscreen mientras Live ON está activo en la cuadrícula → los frames llegan a las dos vistas.

### 5.2 Ocultar / mostrar
6. Click ✕ en una tarjeta → desaparece. Recargar página → sigue oculta (localStorage).
7. "🙈 Ocultar todos" → cuadrícula vacía.
8. "📋 Ocultos (N)" → modal con la lista. Click "Mostrar" en uno → reaparece.
9. "👁 Mostrar todos" → todos vuelven.

### 5.3 Nombre / categorías
10. Plugin: `Tools → Controlex: configurar nombre` → escribir `DM1D1A#01-BLAMA`. Diálogo confirma.
11. Próximo envío: el panel agrupa el cliente bajo la cabecera "DM1D1A" con nombre "01-BLAMA".
12. Click "🏷" en la tarjeta → `REFUERZO, EXAMEN`. Recargar página o cambiar de vista → los badges siguen.
13. Reiniciar PM2 → categorías extra siguen (persistencia JSON).
14. Cliente sin nombre asignado → cae en "(sin categoría)".

### 5.4 clientId determinista
15. Tras asignar nombre, el clientId mostrado en el panel es un hash de 32 chars hex.
16. Borrar `~/.controlex/client-id.txt` (o reinstalar plugin sin tocar `client-name.txt`) → al próximo envío, MISMO clientId que antes.
17. Cambiar el nombre a `DM1D1A#02-PEREZ` → aparece como cliente nuevo (esperado).
18. Si el alumno arranca el plugin SIN nombre asignado y luego lo asigna, el primer envío lleva UUID legacy y el segundo ya el hash → en el panel verás dos entradas. Al teacher le toca borrar la fantasma con "Wipe offline" o esperar al pruning automático.

---

## 6. Lo que NO hay que romper

- Comandos previos siguen igual: `show-dialog`, `open-file`, `goto-line`, `run-action`, `insert-text`, `highlight-line`, `show-inlay`, `clear-highlights`, `inject-text`, `inject-key`, `inject-click`, `lock-session`, `unlock-session`, `send-file`, `open-url`, `pair-open`, `pair-close`.
- SSE inverso, firma Ed25519, OAuth Google del panel: intactos.
- Pair coding (v1.9.0): independiente del WS de vídeo, no se toca.
- `/plugin` y `ebarrab.com/controlex` siguen sirviendo el ZIP.

---

## 7. Riesgos a vigilar durante la implementación

- **clientId con `name` vacío**: el alumno arranca por primera vez SIN nombre → fallback a UUID legacy. Cuando asigne nombre, el clientId cambia → aparece como cliente nuevo y queda el viejo como fantasma. Aceptable para uso pedagógico (al inicio del curso); el profesor limpia con "Wipe offline".
- **clientId con `get()` en vez de `by lazy`**: si el código lee `clientId` muchas veces por captura, ahora cada lectura recalcula. **Cachear** dentro de `buildRequestJson` (una sola lectura por envío) o memoizar con un volatile + invalidación al cambiar el fichero.
- **Bytes blob URL**: si no haces `URL.revokeObjectURL(old)` cuando swappeas la imagen, el navegador acumula memoria muy rápido (10 fps × N clientes × minutos). Ya está en el snippet, no lo quites.
- **Reconexión del stream**: el plugin **NO** debe auto-reconectar al WS de vídeo cuando se cierra (era el patrón antiguo). Si lo deja, el `stream-stop` es ignorado en la práctica. Confirmar con `pm2 logs`: tras `stream-stop`, no debe haber upgrades a `/api/client/video` en X segundos.
- **Hostname en Windows**: `InetAddress.getLocalHost().hostName` puede devolver el FQDN o el nombre corto según entorno. Para que el hash sea estable, lo cogemos tal cual (si es siempre el mismo en la máquina, no importa que sea largo). Ojo si el alumno cambia de red Wi-Fi: el hostname suele NO cambiar, pero verificar.
- **Categorías extra y wipe-all**: si el profesor hace "Wipe all", se borran los clientes en memoria pero `extraCategories.json` sigue. Cuando vuelvan, se les volverá a asignar las categorías. Es correcto.
- **Retrocompatibilidad servidor↔plugin**: si un alumno tiene v1.9.0 y el servidor v1.10.0, no envía `name` → el panel lo muestra en "(sin categoría)" con UUID. Funciona. Al revés (servidor 1.9, plugin 1.10) también: el `name` se ignora silenciosamente.
- **Multi-tab del panel**: si el profesor abre dos pestañas y ambas activan Live, hay 2 viewers → start/stop funciona bien (contador). Pero `localStorage` de "ocultos" se sincroniza sólo al recargar; cambios en una pestaña no se reflejan en la otra hasta refrescar. Aceptable.

---

## 8. Si algo va mal — rollback rápido

```bash
cd /home/ebarrab/pro/controlex
pm2 reload controlex --update-env
# Volver a v1.9.0:
gh release download v1.9.0
# o git revert + ./publish.sh en /home/ebarrab/controlex
```

---

## 9. Lo que viene después (NO HACER en esta sesión)

- **Sesión 4**: filtros del panel por categoría (selector múltiple → muestra sólo clientes que matchean), búsqueda por nickname.
- **Sesión 5**: vista "follow" en cuadrícula (un alumno destacado en grande mientras los demás siguen en thumbnails).
- **Sesión 6**: throttling adaptativo del live (bajar fps según número de viewers visibles).
- **Sesión 7**: persistir lista de "ocultos" en el servidor cuando haya múltiples profesores (no urgente).

Buena suerte.
