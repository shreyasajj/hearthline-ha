package dev.pixelhome

import java.net.URI

/** Only explicit operator configuration enters the receiver; no HA/LLM-derived endpoints. */
data class SendspinConfig(val name: String = "Hearthline", val server: String = "", val port: Int = 8928) {
    fun validated(): SendspinConfig {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty() && cleanName.toByteArray().size <= 64 && cleanName.none { it.isISOControl() }) { "Use a player name of 1–64 UTF-8 bytes." }
        require(port in 1024..65535) { "Use a listening port from 1024 to 65535." }
        val cleanServer = server.trim()
        if(cleanServer.isNotEmpty()) {
            require(cleanServer.length <= 512) { "Server address is too long." }
            val uri = try { URI(cleanServer) } catch(_: Exception) { error("Enter a ws:// or wss:// Sendspin server URL.") }
            require(uri.scheme in listOf("ws", "wss") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.port in -1..65535 && uri.port != 0 && uri.rawPath == "/sendspin") { "Use ws://host:8927/sendspin or wss://host/sendspin without credentials or query parameters." }
        }
        return copy(name = cleanName, server = cleanServer)
    }
}
