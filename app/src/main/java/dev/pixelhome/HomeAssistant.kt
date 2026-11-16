package dev.pixelhome

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class Entity(val id: String, val state: String, val attrs: JSONObject) {
    val name get() = attrs.optString("friendly_name", id)
    val available get() = state !in listOf("unknown", "unavailable")
    fun supports(bit: Long) = attrs.optLong("supported_features", 0) and bit != 0L
}
data class MusicShortcut(val title: String, val uri: String, val type: String)
data class DashboardConfig(
    val title: String = "Good morning", val favorites: List<String> = emptyList(),
    val scenes: List<String> = emptyList(), val players: List<String> = emptyList(),
    val shortcuts: List<MusicShortcut> = emptyList(), val idleSeconds: Int = 45
)
private fun JSONObject.strings(key: String): List<String> = optJSONArray(key)?.let { a ->
    (0 until a.length()).map { a.getString(it) }
} ?: emptyList()
fun parseConfig(a: JSONObject): DashboardConfig {
    val m = a.optJSONArray("music") ?: JSONArray()
    return DashboardConfig(a.optString("title", "Welcome home"), a.strings("favorites").take(8),
        a.strings("scenes").filter { it.startsWith("scene.") }.take(6),
        a.strings("players").filter { it.startsWith("media_player.") },
        (0 until m.length()).map { m.getJSONObject(it) }.map {
            MusicShortcut(it.getString("title"), it.getString("uri"), it.optString("type", "music"))
        }, a.optInt("idle_seconds", 45).coerceIn(15, 600))
}
class HomeAssistant(base: String, private val token: String) {
    val origin = base.trim().trimEnd('/').toHttpUrl().also {
        require(it.isHttps) { "Use an HTTPS Home Assistant URL with a trusted certificate." }
        require(it.username.isEmpty() && it.password.isEmpty() && it.encodedPath == "/" && it.query == null && it.fragment == null) { "Enter the server origin only, without credentials or a path." }
    }
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private suspend fun request(path: String, body: JSONObject? = null, maxBytes: Int = 8 * 1024 * 1024): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(origin.resolve(path)!!).header("Authorization", "Bearer $token")
        if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        client.newCall(builder.build()).execute().use { response ->
            check(response.isSuccessful) { "Home Assistant returned HTTP ${response.code}. Check connection, permissions and entity IDs." }
            val source = response.body ?: error("Empty server response")
            source.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= maxBytes) { "Server response too large" }
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        }
    }
    suspend fun displayExchange(body: JSONObject): JSONObject =
        JSONObject(request("/api/services/pixel_home/exchange?return_response", body, 65536)).getJSONObject("service_response")
    suspend fun states(): Map<String, Entity> {
        val array = JSONArray(request("/api/states"))
        return (0 until array.length()).map { array.getJSONObject(it) }.associate {
            val id = it.getString("entity_id")
            id to Entity(id, it.getString("state"), it.getJSONObject("attributes"))
        }
    }
    suspend fun service(domain: String, action: String, entity: String, values: JSONObject = JSONObject()) {
        require(domain.matches(Regex("[a-z_]+")) && action.matches(Regex("[a-z_]+")))
        request("/api/services/$domain/$action", JSONObject(values.toString()).put("entity_id", entity))
    }
    suspend fun artwork(path: String): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        try {
            val request = artworkRequest(path) ?: return@withContext null
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                val bytes = body.byteStream().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= 4 * 1024 * 1024) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                if (bytes.size > 4 * 1024 * 1024) return@withContext null
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                var sample = 1
                while (options.outWidth / sample > 1024 || options.outHeight / sample > 1024) sample *= 2
                options.inJustDecodeBounds = false; options.inSampleSize = sample
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { null }
    }
    // Only forward authorization to the exact HA origin. Do not leak it to artwork CDNs.
    fun artworkRequest(path: String): Request? {
        val url = origin.resolve(path) ?: return null
        if (!url.isHttps || url.host != origin.host || url.port != origin.port) return null
        return Request.Builder().url(url).header("Authorization", "Bearer $token").build()
    }
}
