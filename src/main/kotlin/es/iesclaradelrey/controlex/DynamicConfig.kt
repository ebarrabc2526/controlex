package es.iesclaradelrey.controlex

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class DynamicConfig(@Suppress("UNUSED_PARAMETER") project: Project) {
    @Volatile var captureMinMs: Long = ControlexConfig.MIN_INTERVAL_MS
    @Volatile var captureMaxMs: Long = ControlexConfig.MAX_INTERVAL_MS
    @Volatile var transmitFreqMs: Long = ControlexConfig.TRANSMIT_FREQ_SECONDS * 1000L
}
