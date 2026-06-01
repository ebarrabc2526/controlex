package es.iesclaradelrey.controlex

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class DynamicConfig(@Suppress("UNUSED_PARAMETER") project: Project) {
    @Volatile var captureMinMs: Long = ControlexConfig.MIN_INTERVAL_MS
    @Volatile var captureMaxMs: Long = ControlexConfig.MAX_INTERVAL_MS
    @Volatile var transmitFreqMs: Long = ControlexConfig.TRANSMIT_FREQ_SECONDS * 1000L

    /**
     * Modo de operación fijado por el panel (comando `mode-set`):
     *  - true  (examen): se capturan y transmiten miniaturas periódicas Y se
     *    mantiene el archivo forense local (carpeta `controlex/` del proyecto).
     *  - false (clase):  no se capturan miniaturas (el transmisor solo late) y
     *    NO existe la carpeta `controlex/`. El streaming en vivo bajo demanda
     *    no se ve afectado.
     * Default true (examen) → todo activo por defecto; las transmisiones van
     * en JPEG (con pérdida) por las defaults de QualityConfig — PNG sin pérdida
     * solo se aplica si el panel lo fuerza explícitamente.
     */
    @Volatile var captureEnabled: Boolean = true

    /**
     * Política de plugins de IA, fijada por el panel (comando `ai-policy`),
     * independiente del modo:
     *  - true  (allow): se permiten plugins de IA.
     *  - false (block): se fuerza su desinstalación (diálogo).
     * Default true → permitidos mientras el panel no diga lo contrario.
     */
    @Volatile var aiAllowed: Boolean = true
}
