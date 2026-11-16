package dev.pixelhome

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import org.json.JSONObject
import java.util.Base64

/** Sensitive process-only value: never use a data class or print its contents. */
class HaQrToken private constructor(private val value: String) {
    fun forConnection() = value
    override fun toString() = "HaQrToken(redacted)"
    companion object {
        const val INVALID = "Use the QR code generated for a Home Assistant long-lived access token."
        fun parse(raw: String): HaQrToken {
            try {
                require(raw.toByteArray(Charsets.UTF_8).size <= 8192)
                val token = raw.trim()
                require(token.matches(Regex("[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{16,}")))
                val parts = token.split('.')
                fun boundedObject(part: String): JSONObject {
                    val json = String(Base64.getUrlDecoder().decode(part), Charsets.UTF_8)
                    require(json.count { it == '{' || it == '[' } <= 16)
                    return JSONObject(json)
                }
                val header = boundedObject(parts[0])
                boundedObject(parts[1])
                val algorithm = header.opt("alg")
                require(algorithm is String && algorithm.isNotBlank() && !algorithm.equals("none", true))
                // Shape validation only. The configured HA server verifies signature, permissions and expiry.
                return HaQrToken(token)
            } catch (_: Exception) { throw IllegalArgumentException(INVALID) }
        }
    }
}

/** One foreground/unlocked import per explicitly initiated scan, expiring after two minutes. */
class QrImportSession(private val startedAt: Long) {
    private var consumed = false
    fun accept(raw: String, now: Long, unlocked: Boolean, foreground: Boolean): HaQrToken {
        require(!consumed && unlocked && foreground && now >= startedAt && now - startedAt <= 120000) {
            "Scan cancelled or expired. Try again while Hearthline is open and unlocked."
        }
        consumed = true
        return HaQrToken.parse(raw)
    }
    fun cancel() { consumed = true }
}

object TokenQrDecoder {
    fun decode(pixels: IntArray, width: Int, height: Int): String {
        require(width in 1..2048 && height in 1..2048 && pixels.size.toLong() == width.toLong() * height) { "QR image is too large." }
        val reader = MultiFormatReader()
        return try {
            val source = RGBLuminanceSource(width, height, pixels)
            val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
            try { reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text }
            catch (_: ReaderException) { reader.reset(); reader.decode(BinaryBitmap(HybridBinarizer(source.invert())), hints).text }
        } catch (_: Exception) { throw IllegalArgumentException("No readable QR code found in this image.") }
        finally { reader.reset() }
    }
}
