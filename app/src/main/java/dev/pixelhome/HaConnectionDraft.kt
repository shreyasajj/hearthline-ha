package dev.pixelhome

import androidx.compose.runtime.*

/** Connection form stays in process memory, including across permission/picker activities. */
class HaConnectionDraft {
    var legacy by mutableStateOf(false)
    var url by mutableStateOf("")
    var token by mutableStateOf(""); private set
    var sensor by mutableStateOf("sensor.pixel_home_dashboard")
    var scannerRequested by mutableStateOf(false)
    var note by mutableStateOf<String?>(null)
    var importingImage by mutableStateOf(false)
    private var cameraPendingAt: Long? = null
    private var imagePendingAt: Long? = null
    fun updateToken(value: String) {
        if(value.length > 8192) { note = "Token is too long."; return }
        token = value; note = null
    }
    fun importToken(value: HaQrToken) {
        token = value.forConnection(); scannerRequested = false
        note = "Token imported. Check your HTTPS server address, then press Connect to verify it."
    }
    fun requestCamera(now: Long) { cameraPendingAt = now }
    fun cameraResult(granted: Boolean, now: Long, unlocked: Boolean) {
        val start = cameraPendingAt; cameraPendingAt = null
        if(start == null || !unlocked || now - start !in 0..120000) return
        scannerRequested = granted
        note = if(granted) null else "Camera permission was not allowed. Import a QR image or paste the token instead."
    }
    fun requestImage(now: Long) { imagePendingAt = now }
    fun consumeImageRequest(now: Long): Boolean {
        val start = imagePendingAt; imagePendingAt = null
        return start != null && now - start in 0..120000
    }
    fun clearToken() { token = ""; scannerRequested = false }
    fun cancelRequests() { cameraPendingAt = null; imagePendingAt = null; scannerRequested = false }
}
