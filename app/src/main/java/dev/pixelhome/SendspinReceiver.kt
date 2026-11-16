package dev.pixelhome

import android.app.Application
import android.app.KeyguardManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sendspindroid.coordinator.InboundConnections
import com.sendspindroid.coordinator.TransportState
import com.sendspindroid.coordinator.FailureReason
import com.sendspindroid.discovery.NsdAdvertiser
import com.sendspindroid.model.PlaybackStateType
import com.sendspindroid.playback.SendSpinEngine
import com.sendspindroid.sendspin.*
import com.sendspindroid.sendspin.crypto.*
import com.sendspindroid.sendspin.pairing.PairingOutcome
import com.sendspindroid.sendspin.protocol.AdmissionState
import com.sendspindroid.sendspin.protocol.TrackMetadata
import com.sendspindroid.sendspin.transport.SendspinWebSocketServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean

/** Visible-activity receiver, deliberately independent of HA controls and dock auto-return. */
class SendspinReceiver(app: Application) : AndroidViewModel(app) {
    private val context: Application = app
    private val prefs = context.getSharedPreferences("sendspin_options", 0)
    private val handler = Handler(Looper.getMainLooper())
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null
    private var closing: Job? = null
    var resetting by mutableStateOf(false); private set
    private var visible = false
    private var runtime: Runtime? = null
    var enabled by mutableStateOf(prefs.getBoolean("enabled", false)); private set
    var config by mutableStateOf(SendspinConfig(prefs.getString("name", "Hearthline")!!, prefs.getString("server", "")!!, prefs.getInt("port", 8928))); private set
    var status by mutableStateOf(if(enabled) "Paused · Hearthline is not visible" else "Disabled"); private set
    var listeningPort by mutableStateOf<Int?>(null); private set
    var discovery by mutableStateOf("Not advertising"); private set
    var output by mutableStateOf("No audio stream"); private set
    private var playerRunning by mutableStateOf(false)
    private var focusHeld by mutableStateOf(false)
    val audioPlaying get() = enabled && playerRunning && focusHeld
    var title by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var volume by mutableIntStateOf(prefs.getInt("volume", 30).coerceIn(0,100)); private set
    var muted by mutableStateOf(prefs.getBoolean("muted",false)); private set
    var pairedServers by mutableStateOf<Int?>(null); private set
    private fun unlocked(): Boolean = context.getSystemService(KeyguardManager::class.java).let { !it.isKeyguardLocked && !it.isDeviceLocked }
    fun visible(value: Boolean) { if(visible != value) { visible = value; reconcile() } }
    fun enable(value: Boolean) {
        if(!unlocked() || resetting) return
        enabled = value; prefs.edit().putBoolean("enabled",value).apply(); reconcile()
    }
    fun configure(name: String, server: String, port: String) {
        if(!unlocked() || resetting) return
        try {
            val next = SendspinConfig(name,server,port.toIntOrNull() ?: 0).validated()
            config = next; prefs.edit().putString("name",next.name).putString("server",next.server).putInt("port",next.port).apply()
            reconcile()
        } catch(e: IllegalArgumentException) { error = e.message }
        catch(_: Exception) { error = "Enter a valid player name, listening port and optional Sendspin URL." }
    }
    fun changeVolume(value: Int) {
        if(!unlocked()) return
        applyVolume(value,muted); runtime?.client?.setVolume(volume / 100.0)
    }
    fun changeMute(value: Boolean) {
        if(!unlocked()) return
        applyVolume(volume,value); runtime?.client?.setMuted(value)
    }
    private fun applyVolume(value: Int, silence: Boolean) {
        volume = value.coerceIn(0,100); muted = silence
        prefs.edit().putInt("volume",volume).putBoolean("muted",muted).apply()
        runtime?.engine?.setTrackGain(volume / 100f)
        runtime?.engine?.setMuted(SyncAudioPlayer.MuteReason.PLAYER,muted)
    }
    fun pairingToken(): String? {
        if(!unlocked() || resetting) return null
        return try { (runtime?.store ?: SendspinStore(context)).token() }
        catch(_: Exception) { error = "Receiver keys unavailable. Reset the receiver identity to pair again."; null }
    }
    fun resetIdentity() {
        if(!unlocked() || resetting) return
        enable(false)
        resetting = true
        val waitForClose = closing
        viewModelScope.launch {
            try {
                waitForClose?.join()
                withContext(Dispatchers.IO) { SendspinStore.reset(context) }
                pairedServers = 0; error = null
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { error = "Receiver identity could not be reset." }
            finally { resetting = false }
        }
    }
    private fun reconcile() {
        startJob?.cancel(); startJob = null
        val previous = runtime; runtime = null
        previous?.silence()
        val pending = closing
        val waitForClose = cleanup.launch { pending?.join(); previous?.close() }
        closing = waitForClose
        listeningPort = null; discovery = "Not advertising"; output = "No audio stream"; playerRunning = false; focusHeld = false; title = null; error = null
        status = if(!enabled) "Disabled" else if(!visible) "Paused · Hearthline is not visible" else "Starting receiver…"
        if(!enabled || !visible) return
        startJob = viewModelScope.launch {
            try {
                waitForClose.join()
                val checked = config.validated()
                val store = withContext(Dispatchers.IO) { SendspinStore(context) }
                val next = Runtime(checked,store)
                runtime = next; pairedServers = store.trust.listRecords().size
                next.engine.setTrackGain(volume / 100f)
                next.start()
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) {
                val failed = runtime; runtime = null; failed?.silence(); cleanup.launch { failed?.close() }
                status = "Receiver unavailable"; error = "Could not start the receiver. Check the network/settings, or reset its identity if encrypted storage is unavailable."
            }
        }
    }
    private inner class Runtime(val settings: SendspinConfig, val store: SendspinStore) {
        val active = AtomicBoolean(true)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, _ ->
            handler.post { if(runtime === this) { status = "Receiver connection failed"; error = "Reconnect from receiver Settings to retry." } }
            silence(); cleanup.launch { close() }
        })
        @Volatile var client: SendSpin? = null
        private var server: SendspinWebSocketServer? = null
        private var inbound: InboundConnections? = null
        private var advertiser: NsdAdvertiser? = null
        private val audio = context.getSystemService(AudioManager::class.java)
        private var hasFocus = false
        @Volatile private var reconnectAllowed = true
        private val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        private var observingNetwork = false
        private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { post { advertiser?.refresh(); client?.onNetworkChanged() } }
        }
        private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener({ change ->
                if(!active.get()) return@setOnAudioFocusChangeListener
                hasFocus = change == AudioManager.AUDIOFOCUS_GAIN
                focusHeld = hasFocus
                engine.setMuted(SyncAudioPlayer.MuteReason.INTERRUPTION,!hasFocus)
                client?.setExternalSource(!hasFocus)
                output = if(hasFocus) "Audio focus restored · awaiting output" else "Silenced · another app owns audio focus"
            },handler).build()
        val engine: SendSpinEngine = SendSpinEngine(object : SendSpinEngine.Listener {
            override fun onStreamStarted() {
                if(!active.get()) return
                hasFocus = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                focusHeld = hasFocus
                engine.setMuted(SyncAudioPlayer.MuteReason.INTERRUPTION,!hasFocus)
                client?.setExternalSource(!hasFocus)
                output = if(hasFocus) "Buffering synchronized audio" else "Silenced · audio focus unavailable"
            }
            override fun onPlayerChanged(player: SyncAudioPlayer?) {}
            override fun onPlayerStateChanged(state: PlaybackState) {
                if(active.get()) playerRunning = state == PlaybackState.PLAYING
                if(active.get()) output = when {
                    !hasFocus -> "Silenced · audio focus unavailable"
                    muted || volume == 0 -> "Muted"
                    state == PlaybackState.PLAYING -> "AudioTrack playing · Android media output"
                    else -> "Buffering / waiting for synchronized audio"
                }
            }
        }, { true }, { muted })
        private fun post(action: () -> Unit) { handler.post { if(active.get() && runtime === this) action() } }
        private val host = object : SendSpinHost {
            override val softwareVersion = "Hearthline 0.6.1"
            override val lowMemoryMode = true
            override val highPowerMode = false
            override val serverColors = false
            override fun preferredCodec() = "pcm"
            override fun outputDelayMs() = prefs.getInt("output_delay",0)
            override fun setOutputDelayMs(delayMs: Int) { prefs.edit().putInt("output_delay",delayMs).apply() }
            override fun clientIdentity() = store.identity
            override fun trustStore() = store.trust
            override fun pairingConfigStore() = store.pairing
            override fun pairingCodeFailures() = prefs.getInt("pairing_failures",0)
            override fun setPairingCodeFailures(value: Int) { prefs.edit().putInt("pairing_failures",value).apply() }
            override fun rememberServerName(serverId: String, name: String) {}
            override fun forgetServerName(serverId: String) {}
            override fun onPairingOutcome(outcome: PairingOutcome) { post {
                pairedServers = store.trust.listRecords().size
                status = when(outcome) {
                    is PairingOutcome.Paired -> "Paired · waiting for server activation"
                    is PairingOutcome.Unpaired -> "Unpaired by server"
                    else -> "Pairing did not complete · retry in Music Assistant"
                }
            } }
        }
        private fun newClient(): SendSpin {
            lateinit var source: SendSpin
            fun fromClient(action: () -> Unit) { post { if(client === source) action() } }
            fun ownsOutput() = active.get() && client === source
            val result = SendSpin(settings.name,object : SendSpin.Callback {
                override fun onStateChanged(state: String) { fromClient { engine.applyServerPlayback(PlaybackStateType.fromString(state)) } }
                override fun onGroupUpdate(groupId: String, groupName: String, playbackState: String) { fromClient { engine.applyServerPlayback(PlaybackStateType.fromString(playbackState)) } }
                override fun onMetadataUpdate(metadata: TrackMetadata, positionMs: Long) { fromClient { title = metadata.title?.take(160) } }
                override fun onArtwork(imageData: ByteArray) {}
                override fun onArtworkCleared() {}
                override fun onStreamStart(codec: String, sampleRate: Int, channels: Int, bitDepth: Int, codecHeader: ByteArray?) {
                    if(ownsOutput()) engine.onStreamStart(codec,sampleRate,channels,bitDepth,codecHeader)
                }
                override fun onStreamClear() { if(ownsOutput()) engine.onStreamClear() }
                override fun onStreamEnd() { if(ownsOutput()) engine.onStreamEnd(); fromClient { output = "Stream ended"; audio.abandonAudioFocusRequest(focusRequest); hasFocus = false; focusHeld = false; playerRunning = false } }
                override fun onAudioChunk(serverTimeMicros: Long, audioData: ByteArray) { if(ownsOutput()) engine.onAudioChunk(serverTimeMicros,audioData) }
                override fun onVolumeChanged(volume: Int) { fromClient { applyVolume(volume,muted) } }
                override fun onMutedChanged(muted: Boolean) { fromClient { applyVolume(volume,muted) } }
                override fun onSyncOffsetApplied(offsetMs: Double, source: String) {}
                override fun onNetworkChanged() { if(ownsOutput()) engine.onNetworkChanged() }
                override fun onSyncMuteChanged(muted: Boolean) { if(ownsOutput()) engine.onSyncMuteChanged(muted) }
                override fun onPlayerRoleChanged(active: Boolean) { fromClient { if(!active) { engine.endStream(); engine.releasePlayer(); output = "Player role inactive"; audio.abandonAudioFocusRequest(focusRequest); hasFocus = false; focusHeld = false; playerRunning = false } } }
                override fun onAdmissionStateChanged(state: AdmissionState) { fromClient {
                    status = when(state) {
                        AdmissionState.AWAITING_APPROVAL -> "Awaiting approval in Music Assistant"
                        AdmissionState.PAIRING -> "Pairing · use the receiver pairing QR in Music Assistant"
                        AdmissionState.READY -> "Connected · waiting for stream"
                    }
                } }
                override fun onDisconnected(reconnect: Boolean) {
                    if(!ownsOutput()) return
                    reconnectAllowed = reconnect
                    engine.endStream()
                    fromClient {
                    engine.releasePlayer(); output = "No audio stream"; playerRunning = false; focusHeld = false; title = null
                    audio.abandonAudioFocusRequest(focusRequest); hasFocus = false
                    status = if(settings.server.isEmpty()) "Listening · waiting for Music Assistant" else "Disconnected · reconnecting"
                } }
            },host)
            source = result
            result.setInitialVolume(volume,muted)
            return result
        }
        suspend fun start() {
            connectivity.registerDefaultNetworkCallback(networkCallback); observingNetwork = true
            if(settings.server.isEmpty()) {
                val connections = InboundConnections(scope,{ newClient() },{ admitted ->
                    engine.endStream() // clear the old format before a new connection can send stream/start
                    client = admitted
                    post { if(client === admitted) { engine.releasePlayer(); engine.client = admitted; status = "Connected · awaiting activation" } }
                },{ prefs.getString("last_server",null) },{ prefs.edit().putString("last_server",it).apply() })
                inbound = connections; connections.open()
                val listener = SendspinWebSocketServer { transport ->
                    if(active.get()) connections.onConnection(transport,transport.remoteAddress) else transport.close(1001,"app not visible")
                }
                server = listener
                val port = withContext(Dispatchers.IO) { listener.start(settings.port) }
                if(!active.get()) return
                listeningPort = port; status = "Listening · waiting for Music Assistant"
                advertiser = NsdAdvertiser(context) { success -> post { discovery = if(success) "Advertised on local network" else "Discovery unavailable · use tablet address and listening port" } }
                advertiser!!.start(settings.name,port,"/sendspin")
            } else scope.launch {
                var backoff = 2000L
                while(isActive && active.get()) {
                    reconnectAllowed = true
                    val next = newClient(); client = next; engine.client = next
                    status = "Connecting to Music Assistant…"
                    next.connect(settings.server.removeSuffix("/sendspin"),"/sendspin")
                    val end = next.connectionState.first { it is TransportState.Idle || it is TransportState.Failed }
                    engine.endStream(); engine.releasePlayer(); next.destroy(); if(client === next) client = null
                    if(!reconnectAllowed) { status = "Disconnected by server · reconnect from Settings"; break }
                    if(end is TransportState.Failed && end.reason == FailureReason.ServerLacksEncryption) {
                        status = "Server requires upgrade · encrypted Sendspin is required"; break
                    }
                    status = "Disconnected · retrying in ${backoff / 1000}s"
                    delay(backoff); backoff = (backoff * 2).coerceAtMost(30000)
                }
            }
        }
        fun silence() {
            active.set(false)
            engine.setMuted(SyncAudioPlayer.MuteReason.INTERRUPTION,true)
            advertiser?.stop()
            if(observingNetwork) { runCatching { connectivity.unregisterNetworkCallback(networkCallback) }; observingNetwork = false }
            audio.abandonAudioFocusRequest(focusRequest); hasFocus = false
            focusHeld = false; playerRunning = false
        }
        fun close() {
            scope.cancel(); inbound?.close(); client?.destroy(); server?.stop(); engine.release()
        }
    }
    override fun onCleared() {
        startJob?.cancel(); val old = runtime; runtime = null; old?.silence()
        val pending = closing
        cleanup.launch { pending?.join(); old?.close(); cleanup.cancel() }
    }
}
