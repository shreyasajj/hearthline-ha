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
    var pairingChoices by mutableStateOf<List<Pair<String,String>>>(emptyList()); private set
    var pairingStatus by mutableStateOf("No remembered server"); private set
    var configRevision by mutableStateOf(""); private set
    var telemetryEnabled by mutableStateOf(false); private set
    var lastPush by mutableStateOf<String?>(null); private set
    var tabletIdentity by mutableStateOf(""); private set
    var paired by mutableStateOf(false); private set
    private var pairingStore: TabletPairingStore? = null
    private var remembered: JSONObject? = null
    private var telemetrySnapshot: (() -> JSONObject)? = null
    private var telemetrySession = ""
    private var telemetrySequence = 0L
    private var lastTelemetryShape = ""
    private var lastPushMonotonic = 0L
    private var failures = 0
    private var authRevoked = false
    fun attachPairing(context: android.content.Context, host: HubHost, screen: () -> String) {
        telemetrySnapshot = { tabletTelemetry(context,host,screen(),tabletIdentity,telemetrySession,telemetrySequence) }
        if(pairingStore != null) return
        try {
            val store=TabletPairingStore(context.applicationContext); pairingStore=store; tabletIdentity=store.tabletId
            remembered=store.load()
            remembered?.let { record ->
                api=HomeAssistant(record.getString("origin"),record.getString("credential"),record.getString("entry_id"))
                paired=true; telemetryEnabled=record.optBoolean("telemetry",false); demo=false; entities=emptyMap(); config=DashboardConfig(); selectedPlayer=""
                pairingStatus="Restoring remembered pairing"; foreground(foreground)
            }
        } catch(_: Exception) { pairingStatus="Stored pairing unavailable. Forget server and pair again." }
    }
    fun loadPairingChoices() {
        if(!unlocked() || busy) return
        busy=true
        viewModelScope.launch {
            try {
                val choices=HomeAssistant(connectionDraft.url,connectionDraft.token).pairingChoices()
                pairingChoices=(0 until choices.length()).map { choices.getJSONObject(it) }.filter { !it.getBoolean("paired") }
                    .map { it.getString("entry_id") to it.getString("name") }
                connectionDraft.note=if(pairingChoices.isEmpty()) "Add a Hearthline tablet in HA, or revoke its previous pairing in options." else "Choose the tablet dashboard to remember."
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { connectionDraft.note="Could not load tablets. Check HTTPS, administrator token and integration installation." }
            finally { busy=false }
        }
    }
    fun pairTablet(entry: String) {
        if(!unlocked() || busy || pairingChoices.none { it.first==entry }) return
        val store=pairingStore ?: return
        busy=true
        viewModelScope.launch {
            try {
                val setup=HomeAssistant(connectionDraft.url,connectionDraft.token)
                val result=setup.pair(store.tabletId,entry)
                val credential=result.getString("credential")
                val server=HomeAssistant(setup.origin.toString(),credential,entry)
                try { store.save(server.origin.toString(),entry,credential,false) }
                catch(e: Exception) { server.unpair(); throw e }
                remembered=store.load(); api=server; paired=true; authRevoked=false; failures=0
                demo=false; connected=false; entities=emptyMap(); config=DashboardConfig(); selectedPlayer=""
                telemetrySession=""; lastPush=null; telemetryEnabled=false; pairingStatus="Pairing remembered with Android Keystore encryption"
                connectionDraft.clearToken(); pairingChoices=emptyList()
                assistant.configure(assistant.enabled,result.getString("display_id"))
                foreground(foreground)
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { connectionDraft.note="Pairing failed. Check the tablet entry and revoke a previous pairing before retrying." }
            finally { busy=false }
        }
    }
    fun telemetry(value: Boolean) {
        if(!unlocked()) return
        val record=remembered ?: return
        try {
            pairingStore?.save(record.getString("origin"),record.getString("entry_id"),record.getString("credential"),value)
            telemetryEnabled=value; telemetrySession=""; lastTelemetryShape=""
        } catch(_: Exception) { pairingStatus="Could not save telemetry preference" }
    }
    fun reconnect() { if(unlocked()) { authRevoked=false; failures=0; telemetrySession=""; foreground(foreground) } }
    fun forgetServer() {
        if(!unlocked() || busy) return
        val server=api?.takeIf { it.pairedEntry!=null }
        busy=true
        viewModelScope.launch {
            try {
                if(server!=null) { try { server.unpair() } catch(_: HaAuthorizationException) { /* Already revoked */ } }
                pairingStore?.forget(); remembered=null; paired=false; telemetryEnabled=false; telemetrySession=""
                api=null; poll?.cancel(); poll=null; assistant.lifecycle(foreground,null); connected=false
                demo=false; entities=emptyMap(); config=DashboardConfig(); artwork=null; selectedPlayer=""
                pairingStatus="Server forgotten and pairing revoked"; configRevision=""; lastPush=null
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { pairingStatus="Could not revoke. Restore connection and retry, or revoke in HA options and use Forget locally." }
            finally { busy=false }
        }
    }
    fun forgetLocally() {
        if(!unlocked()) return
        pairingStore?.forget(); remembered=null; paired=false; telemetryEnabled=false
        api=null; poll?.cancel(); poll=null; connected=false; demo=false; entities=emptyMap(); config=DashboardConfig(); artwork=null
        assistant.lifecycle(foreground,null); pairingStatus="Forgot locally. Revoke the tablet in HA options to invalidate its server credential."
    }
    private suspend fun sendTelemetry(server: HomeAssistant) {
        if(!telemetryEnabled || server.pairedEntry==null) return
        if(telemetrySession.isEmpty()) { telemetrySession=server.telemetrySession(); telemetrySequence=0; lastTelemetryShape="" }
        val payload=telemetrySnapshot?.invoke() ?: return
        val shape=JSONObject(payload.toString()).apply { remove("sequence"); remove("observed_at"); remove("session_id") }.toString()
        if(shape==lastTelemetryShape && now()-lastPushMonotonic<30000) return
        server.pushTelemetry(payload)
        telemetrySequence++; lastTelemetryShape=shape; lastPushMonotonic=now(); lastPush=java.time.OffsetDateTime.now().toString()
    }
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
        if(!value) telemetrySession=""
        assistant.lifecycle(value && !authRevoked, api)
        if (value && api != null) poll = viewModelScope.launch {
            while (isActive && !authRevoked) {
                refresh()
                val backoff=if(failures==0) 2000L else (2000L shl failures.coerceAtMost(5)).coerceAtMost(60000L)
                delay(backoff + if(failures>0) kotlin.random.Random.nextLong(0,1000) else 0)
            }
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
            paired=false; authRevoked=false; failures=0; telemetrySession=""
            demo = false; connected = false; message = null; entities = emptyMap(); config = DashboardConfig()
            selectedPlayer = ""; configEntity = sensor; artwork = null; artworkKey = ""
            navigation.reset(now()); page = navigation.page(); foreground(foreground)
        } catch (e: IllegalArgumentException) { message = e.message ?: "Invalid server URL" }
    }
    fun useDemo() {
        if (!unlocked()) return
        api = null; paired=false; poll?.cancel(); connected = false; artwork = null; artworkKey = ""
        assistant.lifecycle(foreground, null)
        demo = true; message = null; navigation.reset(now()); page = navigation.page(); demoData()
    }
    private suspend fun refresh() = mutex.withLock {
        val server = api ?: return@withLock
        try {
            val next = if(server.pairedEntry!=null) {
                val managed=server.tabletConfig()
                configRevision=managed.getString("revision")
                if(assistant.displayId!=managed.getString("display_id")) assistant.configure(assistant.enabled,managed.getString("display_id"))
                parseConfig(managed.getJSONObject("dashboard"))
            } else null
            val states = server.states()
            val resolved = next ?: parseConfig((states[configEntity]?.takeIf { it.available } ?: error("Dashboard config missing")).attrs)
            if (api !== server) return@withLock
            config = resolved; entities = states; lastStateAt = now()
            if (!connected) message = null
            connected = true; failures=0; pairingStatus=if(paired) "Connected · remembered pairing" else "Legacy session connection"
            sendTelemetry(server)
            if (selectedPlayer !in resolved.players) selectedPlayer = resolved.players.firstOrNull().orEmpty()
            navigation.playback(playing, hubEnabled, now()); page = navigation.page()
            val path = player?.attrs?.optString("entity_picture").orEmpty()
            val key = selectedPlayer + path
            if (key != artworkKey) {
                artworkKey = key; artwork = null
                if (path.isNotBlank()) {
                    val bitmap = server.artwork(path,selectedPlayer)
                    if (api === server && artworkKey == key) artwork = bitmap
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: HaAuthorizationException) {
            if(api === server) {
                authRevoked=true; connected=false; telemetrySession=""; assistant.lifecycle(false,null)
                pairingStatus="Authorization revoked or expired. Revoke/forget the pairing and pair again."
                message=pairingStatus
            }
        }
        catch (_: Exception) {
            if (api === server) {
                failures++; telemetrySession=""
                connected = false
                message = "Offline. Retrying with bounded backoff; displayed values may be stale."
                pairingStatus="Offline · reconnecting"
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
