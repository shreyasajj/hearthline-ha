package dev.pixelhome

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable fun AmbientClock(wake: ()->Unit) {
    var clock by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while(true) { delay(60000L-System.currentTimeMillis()%60000L);clock=LocalDateTime.now() } }
    BackHandler(onBack= wake)
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).clickable(role=Role.Button,onClickLabel="Wake display",onClick=wake),contentAlignment=Alignment.Center) {
        // Small minute-by-minute movement avoids a permanently fixed set of pixels.
        val shift=((clock.minute%3)-1)*8
        Text(clock.format(DateTimeFormatter.ofPattern("h:mm")),color=Color(0xFF969990),fontSize=if(maxWidth >= 840.dp) 176.sp else 100.sp,
            fontWeight=FontWeight.Light,maxLines=1,softWrap=false,modifier=Modifier.offset(shift.dp,(-shift).dp).padding(24.dp))
    }
}
@Composable private fun AmbientToggle(label: String, checked: Boolean, change: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Text(label,Modifier.weight(1f));Switch(checked,change) }
}
@Composable fun AmbientSettingsCard(host: AmbientHost) {
    val s=host.settings
    val activity=LocalContext.current.mainActivity()
    var presence by remember(s.presenceEntity) { mutableStateOf(s.presenceEntity) }
    OutlinedCard(Modifier.widthIn(max=800.dp).fillMaxWidth()) {
        Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("Ambient clock & wake",style=MaterialTheme.typography.headlineSmall)
            AmbientToggle("Dim to a black clock when idle",s.enabled) { host.change(s.copy(enabled=it)) }
            Text("After inactivity, show only a large clock on black. Tap anywhere to wake; the first tap only wakes the display. Android authentication and dock-only lock-screen rules still apply.")
            Text("Idle delay: ${s.idleSeconds} seconds")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(15,30,60,120,300).forEach { seconds -> OutlinedButton(onClick={host.change(s.copy(idleSeconds=seconds))}) {Text("${seconds}s")} } }
            Text("Ambient backlight: ${s.dimPercent}% (capped at 2% in a dark room)")
            Slider(s.dimPercent.toFloat(),{host.change(s.copy(dimPercent=it.toInt()))},valueRange=1f..15f,steps=13)
            Text(if(s.activePercent==0) "Full interface brightness: Android system / adaptive brightness" else "Full interface brightness: ${s.activePercent}%")
            OutlinedButton(onClick={host.change(s.copy(activePercent=0))}) {Text("Follow Android brightness")}
            Text("Optional manual full-interface brightness")
            Slider((if(s.activePercent==0) 60 else s.activePercent).toFloat(),{host.change(s.copy(activePercent=it.toInt()))},valueRange=5f..100f)
            AmbientToggle("Keep ambient clock on while docked",s.keepClockDocked) {host.change(s.copy(keepClockDocked=it))}
            AmbientToggle("Wake / stay bright for real music playback",s.wakeOnMusic) {host.change(s.copy(wakeOnMusic=it))}
            Text("Demo music does not count as actual playback. Sendspin AudioTrack playback and fresh real HA player state do. Existing music navigation is preserved.")
            AmbientToggle("Automatically use dark theme in a dim room",s.autoDark) {host.change(s.copy(autoDark=it))}
            AmbientToggle("Use a pure black background in dark theme",s.blackInDark) {host.change(s.copy(blackInDark=it))}
            AmbientToggle("Always use the black theme",s.alwaysBlack) {host.change(s.copy(alwaysBlack=it))}
            Text(if(host.lightAvailable) "Ambient light: ${host.lux?.let { "%.1f lux".format(it) } ?: "waiting for sensor"}" else "Ambient-light sensor unavailable · Android/manual brightness remains available")
            Text("The app changes only its own window brightness. “Follow Android brightness” follows your existing Android brightness mode; it does not change system settings.")
            OutlinedButton(onClick=host::preview,enabled=s.enabled,modifier=Modifier.heightIn(min=56.dp)) {Text("Preview ambient clock")}
            Text("Wake signals",style=MaterialTheme.typography.titleLarge)
            AmbientToggle("Offline “wake up” while the clock is showing",s.voiceWake) {activity?.enableVoiceWake(it)}
            Text("${host.voiceStatus}. Uses the bundled English Vosk model on this tablet. Microphone permission is requested only when enabled. No speech/audio is saved or sent to a server; this wakes the interface only.")
            AmbientToggle("Front-camera face wake (optional)",s.faceWake) {activity?.enableFaceWake(it)}
            Text("${host.cameraStatus}. Detects a face locally at roughly one frame per second. Camera frames stay in memory; no identity recognition or image storage. Runs while the app is visible outside Settings/assistant flows. Uses extra power and may miss faces in darkness or at a distance.")
            if(host.proximityAvailable) AmbientToggle("Wake on near proximity",s.proximityWake) {host.change(s.copy(proximityWake=it))}
            else Text("Public proximity sensor unavailable on this tablet")
            OutlinedTextField(presence,{presence=it},label={Text("Optional HA presence binary_sensor entity")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedButton(onClick={host.change(s.copy(presenceEntity=presence.trim()))}) {Text("Use presence sensor")}
            Text(host.presenceStatus)
            Text("Choose a real presence sensor covering this display. On keeps the full interface awake. Unknown, unavailable, stale and demo data never imply someone is present. HA data is refreshed only while the app is foreground.")
            host.notice?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }
    }
}

private fun Context.mainActivity(): MainActivity? = when(this) { is MainActivity -> this; is ContextWrapper -> baseContext.mainActivity(); else -> null }
