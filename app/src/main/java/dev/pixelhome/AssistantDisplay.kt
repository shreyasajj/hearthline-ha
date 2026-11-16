package dev.pixelhome

import android.os.SystemClock
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

/** Session-only, foreground-only transport. The tablet opens no listening network port. */
class AssistantDisplay(private val scope: CoroutineScope, private val unlocked: () -> Boolean, private val currentScreen: () -> String) {
    var answer by mutableStateOf<DisplayResponse?>(null); private set
    var enabled by mutableStateOf(false); private set
    var displayId by mutableStateOf("pixel_tablet"); private set
    var status by mutableStateOf("Display bridge off"); private set
    var mapConfiguration by mutableStateOf<MapConfiguration?>(null); private set
    fun configureMap(style:String,origins:String,attribution:String) {
        if(!mayInteract()) return
        try { mapConfiguration=MapConfiguration.parse(style,origins,attribution); status="Map provider configured for this session" }
        catch(_:Exception) { status="Use an HTTPS style, allowed HTTPS origins and provider attribution." }
    }
    private var receivedAt=0L
    private var sequence=0
    private var foreground = false
    private var server: HomeAssistant? = null
    private var job: Job? = null
    private var session = ""
    private var ack: JSONObject? = null
    private val seen = mutableSetOf<String>()
    private var deadline = Long.MAX_VALUE
    private var confirmed = false
    fun mayInteract() = foreground && unlocked()
    fun configure(value: Boolean, id: String) {
        if(!unlocked()) return
        if(!id.matches(Regex("[a-z0-9_]{1,48}"))) { status="Use lowercase letters, digits and underscores in the display ID."; return }
        enabled=value; displayId=id; restart()
    }
    fun lifecycle(value: Boolean, api: HomeAssistant?) {
        foreground=value; server=api; restart()
    }
    private fun restart() {
        job?.cancel(); job=null; answer=null; ack=null; seen.clear(); confirmed=false
        session=UUID.randomUUID().toString()
        val api=server
        if(!enabled || !foreground || api==null) { status=if(enabled) "Waiting for foreground Home Assistant connection" else "Display bridge off"; return }
        val generation=session
        job=scope.launch {
            while(isActive) {
                try {
                    concealIfLocked()
                    val data=JSONObject().put("display_id",displayId).put("session_id",generation)
                        .put("capability_revision",DisplayProtocol.REVISION).put("locked",!unlocked()).put("foreground",foreground).put("map_configured",mapConfiguration!=null).put("current_screen",if(unlocked()) currentScreen() else "locked_clock_music")
                    ack?.let { data.put("ack",it) }
                    val start=SystemClock.elapsedRealtime()
                    val response=api.displayExchange(data)
                    if(generation!=session || server!==api) return@launch
                    status="Display bridge connected · $displayId"
                    response.optJSONObject("command")?.let { command ->
                        require(command.getString("session_id")==generation)
                        val id=command.getString("response_id")
                        when(command.getString("kind")) {
                            "dismiss" -> {
                                if(answer?.id==id) answer=null
                                ack=JSONObject().put("response_id",id).put("state","dismissed")
                                seen.add(id)
                            }
                            "update" -> if(answer?.id==id && mayInteract() && command.getInt("sequence")>sequence) {
                                try {
                                    val parsed=DisplayProtocol.parse(command.getJSONObject("request").toString())
                                    require(parsed.id==id && parsed.layout==answer?.layout && parsed.template!=null)
                                    require(SystemClock.elapsedRealtime()<start+command.getLong("remaining_ms"))
                                    deadline=start+command.getLong("remaining_ms"); receivedAt=SystemClock.elapsedRealtime()
                                    sequence=command.getInt("sequence"); confirmed=false; answer=parsed
                                    ack=JSONObject().put("response_id",id).put("state","accepted").put("sequence",sequence)
                                } catch(_:Exception) {
                                    answer=null; ack=JSONObject().put("response_id",id).put("state","rejected")
                                }
                            }
                            "present" -> if(id !in seen) {
                                seen.add(id)
                                try {
                                    require(seen.size<=128 && mayInteract() && answer==null)
                                    // Conservatively charge the whole request duration against server TTL.
                                    deadline=start+command.getLong("remaining_ms")
                                    require(SystemClock.elapsedRealtime()<deadline)
                                    val parsed=DisplayProtocol.parse(command.getJSONObject("request").toString())
                                    require(parsed.id==id)
                                    receivedAt=SystemClock.elapsedRealtime(); sequence=command.optInt("sequence",0); confirmed=false; answer=parsed
                                    ack=JSONObject().put("response_id",id).put("state","accepted").put("sequence",sequence)
                                } catch(_:Exception) { ack=JSONObject().put("response_id",id).put("state","rejected") }
                            }
                        }
                    }
                } catch(e:CancellationException) { throw e }
                catch(_:Exception) { status="Display bridge unavailable — check server setup, display ID and connection" }
                delay(2000)
            }
        }
    }
    fun concealIfLocked() { if(!unlocked() && answer!=null) dismiss() }
    fun rendered(response: DisplayResponse) {
        if(answer !== response) return
        val id=response.id
        if(!mayInteract() || SystemClock.elapsedRealtime()>=deadline) {
            answer=null; ack=JSONObject().put("response_id",id).put("state","rejected"); return
        }
        if(!confirmed) { android.util.Log.i("PixelHomeTiming","${answer?.layout} receive_to_frame_ms=${SystemClock.elapsedRealtime()-receivedAt}"); confirmed=true; ack=JSONObject().put("response_id",id).put("state","rendered").put("sequence",sequence) }
    }
    fun dismiss() {
        answer?.let { ack=JSONObject().put("response_id",it.id).put("state","dismissed") }
        answer=null
    }
    fun previewTemplate(raw:String) {
        if(!mayInteract() || server!=null) return
        receivedAt=SystemClock.elapsedRealtime(); deadline=Long.MAX_VALUE; confirmed=false; answer=DisplayProtocol.parse(raw)
    }
    fun preview() {
        if(!mayInteract() || server!=null) return
        receivedAt=SystemClock.elapsedRealtime(); deadline=Long.MAX_VALUE; confirmed=false; answer=DisplayProtocol.preview()
    }
}
