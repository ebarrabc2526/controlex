# Controlex

Plugin de IntelliJ + panel web para supervisar exámenes de programación Java en aula. Capturas periódicas, streaming en vivo bajo demanda, comandos remotos firmados (Ed25519), pair coding, mensajes, bloqueo de sesión y categorización por alumno.

- **Plugin (cliente)**: `src/main/kotlin/es/iesclaradelrey/controlex` — IntelliJ 2024.2+.
- **Servidor (panel)**: `server/server.js` — Node.js / Express / WebSockets.
- **Producción**: https://controlex.ebarrab.com (Apache reverse-proxy → PM2).

## Instalación del plugin

### Auto-actualización (recomendado)

Una vez por IDE:

1. **Settings → Plugins**
2. Engranaje **⚙** (arriba a la derecha) → **Manage Plugin Repositories…**
3. **+** y pegar:
   ```
   https://controlex.ebarrab.com/updatePlugins.xml
   ```
4. **OK** → **Apply**

Desde ese momento IntelliJ comprueba al arrancar (y al pulsar "Check for updates") y ofrece la nueva versión en la pestaña **Updates** de Plugins.

> Atajo desde dentro del plugin: `Tools → Controlex → Auto-actualización (ayuda)…` muestra estos pasos y permite copiar la URL al portapapeles.

### Instalación manual

1. Descargar el ZIP de la última release: https://controlex.ebarrab.com/plugin (o desde la página de [Releases](https://github.com/ebarrabc2526/controlex/releases)).
2. **Settings → Plugins → ⚙ → Install Plugin from Disk…**
3. Seleccionar el ZIP descargado.
4. Reiniciar el IDE.

## Configurar el alumno

Tras instalar, en `Tools → Controlex → Configurar nombre` introducir un identificador con formato:

```
CATEGORIA#APODO   (ej. DM1D1A#01-BLAMA)
```

El servidor extrae automáticamente la categoría principal (lo que va antes de `#`) y agrupa al alumno bajo ella en el panel. La parte después de `#` es el nick visible.

El estado del plugin (conexión + identidad + versión) aparece en la barra de estado del IDE.

## Acciones disponibles

`Tools → Controlex → …`:

- **Pedir ayuda al profesor** (`Ctrl+Alt+H`) — manda al panel un evento de ayuda.
- **Configurar nombre** — fija el identificador del alumno (`CATEGORIA#APODO`).
- **Auto-actualización (ayuda)…** — muestra cómo añadir el repositorio personalizado.

Click izquierdo en el widget de la barra de estado abre el mismo submenú como popup.

## Releases y deploy

- Tags y release notes en https://github.com/ebarrabc2526/controlex/releases.
- Deploy a producción: `./publish.sh` (lo ejecuta también `/agh` automáticamente).
