package dev.pixelhome

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class HubViewModel : ViewModel() {
    private val navigation = PlaybackNavigation()
    private val access = ActionAccess()
    val connectionDraft = HaConnectionDraft()
    private var tokenQrJob: Job? = null
    fun attachAccessCheck(check: () -> Boolean) { access.install(check) }
    private fun unlocked() = access.allowed
    val assistant = AssistantDisplay(viewModelScope, { unlocked() }, { page.name.lowercase() })
    fun assistantNotice() { message = "No browser is available to open this source." }
    var page by mutableStateOf(PlaybackNavigation.Page.HOME); private set
    var demo by mutableStateOf(true); private set
    var config by mutableStateOf(DashboardConfig()); private set
    var entities by mutableStateOf<Map<String, Entity>>(emptyMap()); private set
    var selectedPlayer by mutableStateOf(""); private set
    var connected by mutableStateOf(false); private set
    var lastStateAt by mutableStateOf<Long?>(null); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var hubEnabled by mutableStateOf(true); private set
    var dark by mutableStateOf(false)
    var artwork by mutableStateOf<Bitmap?>(null); private set
    private var artworkKey = ""
    private var configEntity = "sensor.pixel_home_dashboard"
    private var api: HomeAssistant? = null
    private var poll: Job? = null
    private var foreground = false
    private val mutex = Mutex()
    val player get() = entities[selectedPlayer]
    val playing get() = player?.state == "playing"
    val canControl get() = unlocked() && !busy && (demo || connected)
    init { demoData() }
    private fun now() = SystemClock.elapsedRealtime()
    fun touch() { navigation.touch(now()) }
    fun open(value: PlaybackNavigation.Page) { if (!unlocked()) return; if(value != PlaybackNavigation.Page.SETTINGS) { connectionDraft.clearToken(); connectionDraft.cancelRequests(); tokenQrJob?.cancel() }; navigation.open(value, now()); page = navigation.page() }
    fun hub(value: Boolean) { hubEnabled = value; if (!value) navigation.leaveHub(now()); page = navigation.page() }
    fun tick() { if (!foreground) return; if (assistant.answer != null) touch(); navigation.tick(hubEnabled, playing, now(), config.idleSeconds * 1000L); page = navigation.page() }
    fun clearMessage() { message = null }
    fun select(id: String) {
        if (!unlocked()) return
        if (id !in config.players) return
        selectedPlayer = id; artwork = null; artworkKey = ""; touch()
    }
    fun foreground(value: Boolean) {
        foreground = value; poll?.cancel(); poll = null
        if(!value) {
            connectionDraft.scannerRequested = false
            if(connectionDraft.importingImage) connectionDraft.note = "QR image import cancelled. Try again."
            tokenQrJob?.cancel(); connectionDraft.importingImage = false
            if(!unlocked()) { connectionDraft.clearToken(); connectionDraft.cancelRequests() }
        }
        touch()
        assistant.lifecycle(value, api)
        if (value && api != null) poll = viewModelScope.launch {
            while (isActive) { refresh(); delay(2000) }
        }
    }
    fun acceptTokenQr(value: HaQrToken) {
        if(!foreground || !unlocked() || page != PlaybackNavigation.Page.SETTINGS) return
        connectionDraft.importToken(value)
    }
    fun importTokenQrImage(resolver: android.content.ContentResolver, uri: android.net.Uri) {
        if(!foreground || !unlocked() || page != PlaybackNavigation.Page.SETTINGS) return
        tokenQrJob?.cancel()
        val session = QrImportSession(now())
        connectionDraft.importingImage = true; connectionDraft.note = "Reading QR image on this tablet…"
        tokenQrJob = viewModelScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) { TokenQrImage.read(resolver, uri) }
                val token = session.accept(raw, now(), unlocked(), foreground && page == PlaybackNavigation.Page.SETTINGS)
                acceptTokenQr(token)
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { connectionDraft.note = "Could not import this QR image. Select Home Assistant's long-lived token QR, or scan/paste it instead." }
            finally { if(tokenQrJob === coroutineContext[Job]) connectionDraft.importingImage = false }
        }
    }
    fun connect(url: String, token: String, sensor: String) {
        if (!unlocked()) return
        if (token.isBlank() || !sensor.matches(Regex("sensor\\.[a-z0-9_]+"))) {
            message = "Enter a token and a valid sensor entity ID."; return
        }
        try {
            api = HomeAssistant(url, token.trim())
            demo = false; connected = false; message = null; entities = emptyMap(); config = DashboardConfig()
            selectedPlayer = ""; configEntity = sensor; artwork = null; artworkKey = ""
            navigation.reset(now()); page = navigation.page(); foreground(foreground)
        } catch (e: IllegalArgumentException) { message = e.message ?: "Invalid server URL" }
    }
    fun useDemo() {
        if (!unlocked()) return
        api = null; poll?.cancel(); connected = false; artwork = null; artworkKey = ""
        assistant.lifecycle(foreground, null)
        demo = true; message = null; navigation.reset(now()); page = navigation.page(); demoData()
    }
    private suspend fun refresh() = mutex.withLock {
        val server = api ?: return@withLock
        try {
            val states = server.states()
            val definition = states[configEntity]?.takeIf { it.available }
                ?: error("Dashboard config missing")
            val next = parseConfig(definition.attrs)
            if (api !== server) return@withLock
            config = next; entities = states; lastStateAt = now()
            if (!connected) message = null
            connected = true
            if (selectedPlayer !in next.players) selectedPlayer = next.players.firstOrNull().orEmpty()
            navigation.playback(playing, hubEnabled, now()); page = navigation.page()
            val path = player?.attrs?.optString("entity_picture").orEmpty()
            val key = selectedPlayer + path
            if (key != artworkKey) {
                artworkKey = key; artwork = null
                if (path.isNotBlank()) {
                    val bitmap = server.artwork(path)
                    if (api === server && artworkKey == key) artwork = bitmap
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            if (api === server) {
                connected = false
                message = "Connection unavailable. Check HTTPS, token and dashboard sensor. Displayed values may be stale."
            }
        }
    }
    fun command(id: String, action: String, values: JSONObject = JSONObject()) {
        if (!canControl || entities[id] == null || (entities[id]?.available != true && !(id.startsWith("scene.") && entities[id]?.state != "unavailable"))) return
        touch()
        if (demo) { demoCommand(id, action, values); return }
        val server = api ?: return
        busy = true
        viewModelScope.launch {
            try {
                val sent = mutex.withLock {
                    if (api === server) access.dispatch { server.service(id.substringBefore('.'), action, id, values) } else false
                }
                if (!sent) return@launch
                if (api === server) { message = "Command sent; waiting for device state."; refresh() }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (api === server) message = "Command failed. Device state has not been changed locally." }
            finally { busy = false }
        }
    }
    fun playShortcut(shortcut: MusicShortcut) {
        command(selectedPlayer, "play_media", JSONObject().put("media_content_id", shortcut.uri).put("media_content_type", shortcut.type))
    }
    private fun demoCommand(id: String, action: String, values: JSONObject) {
        val old = entities[id] ?: return
        val attrs = JSONObject(old.attrs.toString())
        var state = old.state
        when (action) {
            "media_play_pause" -> state = if (state == "playing") "paused" else "playing"
            "media_play", "play_media" -> state = "playing"
            "media_pause" -> state = "paused"
            "media_next_track", "media_previous_track" -> attrs.put("media_title", if (attrs.optString("media_title") == "Slow Morning") "Quiet Hours" else "Slow Morning")
            "volume_set" -> attrs.put("volume_level", values.getDouble("volume_level"))
            "turn_on" -> { state = "on"; if (values.has("brightness_pct")) attrs.put("brightness", values.getInt("brightness_pct") * 255 / 100) }
            "turn_off" -> state = "off"
            "set_temperature" -> attrs.put("temperature", values.getDouble("temperature"))
            "unlock" -> state = "unlocked"
            "lock" -> state = "locked"
        }
        entities = entities + (id to Entity(id, state, attrs))
        navigation.playback(playing, hubEnabled, now()); page = navigation.page()
        message = "Demo only — no device was contacted."
    }
    private fun demoData() {
        config = DashboardConfig("Good morning", listOf("light.living_room", "climate.living_room", "switch.kitchen", "lock.front_door"),
            listOf("scene.good_morning", "scene.movie_time"), listOf("media_player.living_room"),
            listOf(MusicShortcut("Slow mornings", "demo:morning", "music"), MusicShortcut("Evening unwind", "demo:evening", "music")))
        fun entity(id: String, state: String, name: String, extras: String = "{}") =
            Entity(id, state, JSONObject(extras).put("friendly_name", name))
        entities = listOf(
            entity("light.living_room", "on", "Living room lights", "{\"brightness\":166,\"supported_color_modes\":[\"brightness\"]}"),
            entity("climate.living_room", "heat", "Climate", "{\"temperature\":72,\"current_temperature\":70,\"min_temp\":50,\"max_temp\":86,\"target_temp_step\":1,\"supported_features\":1}"),
            entity("switch.kitchen", "off", "Kitchen plug"), entity("lock.front_door", "locked", "Front door"),
            entity("scene.good_morning", "unknown", "Good morning"), entity("scene.movie_time", "unknown", "Movie time"),
            entity("media_player.living_room", "paused", "Living room speakers", "{\"media_title\":\"Slow Morning\",\"media_artist\":\"Mira Vale\",\"volume_level\":0.4,\"supported_features\":16777215}")
        ).associateBy { it.id }
        selectedPlayer = config.players.first()
    }
}
