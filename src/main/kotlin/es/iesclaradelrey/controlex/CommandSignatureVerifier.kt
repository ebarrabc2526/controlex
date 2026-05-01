package es.iesclaradelrey.controlex

import com.intellij.openapi.diagnostic.Logger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object CommandSignatureVerifier {

    private val log = Logger.getInstance(CommandSignatureVerifier::class.java)

    // Command signing is active as long as the public key is configured
    val enabled: Boolean get() = pubKey != null

    private val pubKey: PublicKey? = try {
        val bytes = Base64.getDecoder().decode(ControlexConfig.COMMAND_PUBLIC_KEY_B64)
        KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(bytes))
    } catch (e: Exception) {
        log.warn("Controlex: no se pudo cargar la clave pública de comandos", e)
        null
    }

    // Replay-protection window: reject commands with ts older than this
    private const val MAX_AGE_MS = 120_000L

    /**
     * Returns the JSON payload without the `sig` field if the signature is valid,
     * or null if the signature is missing or invalid.
     */
    fun verify(json: String): String? {
        val sigIdx = json.lastIndexOf(",\"sig\":")
        if (sigIdx < 0) {
            log.warn("Controlex: comando sin firma rechazado")
            return null
        }
        val sigValue = strField("sig", json.substring(sigIdx + 1)) ?: run {
            log.warn("Controlex: campo sig malformado")
            return null
        }
        // Reconstruct the original signed JSON (everything before the ,sig field)
        val originalJson = json.substring(0, sigIdx) + "}"

        val k = pubKey ?: run {
            log.warn("Controlex: clave pública no disponible, comando rechazado")
            return null
        }
        return try {
            val sigBytes = Base64.getDecoder().decode(sigValue)
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(k)
            verifier.update(originalJson.toByteArray(StandardCharsets.UTF_8))
            if (!verifier.verify(sigBytes)) {
                log.warn("Controlex: firma de comando inválida — posible manipulación")
                return null
            }
            // Replay protection: check ts field
            val ts = numField("ts", originalJson)
            if (ts == null || Math.abs(System.currentTimeMillis() - ts) > MAX_AGE_MS) {
                log.warn("Controlex: comando rechazado por timestamp (ts=$ts)")
                return null
            }
            originalJson
        } catch (e: Exception) {
            log.warn("Controlex: error verificando firma de comando", e)
            null
        }
    }

    private fun strField(name: String, json: String): String? =
        Regex(""""$name"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1)

    private fun numField(name: String, json: String): Long? =
        Regex(""""$name"\s*:\s*(-?\d+)""").find(json)?.groupValues?.get(1)?.toLongOrNull()
}
