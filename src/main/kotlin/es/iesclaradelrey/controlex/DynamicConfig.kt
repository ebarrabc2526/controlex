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
     *  - true  (examen): se capturan y transmiten miniaturas periódicas.
     *  - false (clase):  no se capturan miniaturas; el transmisor solo late
     *    para seguir online y recibir comandos/config. El streaming en vivo
     *    bajo demanda no se ve afectado.
     * Default true → comportamiento histórico mientras el panel no diga otra cosa.
     */
    @Volatile var captureEnabled: Boolean = true
}
