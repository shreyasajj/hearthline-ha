package dev.pixelhome

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter

val LocalSendspinReceiver = staticCompositionLocalOf<SendspinReceiver> { error("Sendspin receiver required") }

@Composable fun SendspinSettingsCard(receiver: SendspinReceiver) {
    var name by remember(receiver.config) { mutableStateOf(receiver.config.name) }
    var server by remember(receiver.config) { mutableStateOf(receiver.config.server) }
    var port by remember(receiver.config) { mutableStateOf(receiver.config.port.toString()) }
    var pairingToken by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if(event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) { pairingToken = null; confirmReset = false }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); pairingToken = null }
    }
    OutlinedCard(Modifier.widthIn(max = 800.dp).fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Tablet / dock audio receiver", style = MaterialTheme.typography.headlineSmall)
            Text("Receive music from Music Assistant using Sendspin. Runs while Hearthline is visible, including its visible dock clock. Leaving the app stops audio and network listening.")
            Row {
                Text("Enable Sendspin receiver", Modifier.weight(1f))
                Switch(receiver.enabled,receiver::enable,enabled = !receiver.resetting)
            }
            Text(receiver.status, style = MaterialTheme.typography.titleLarge)
            Text(receiver.output)
            receiver.title?.let { Text("Received track: $it") }
            Text("${receiver.discovery}${receiver.listeningPort?.let { " · Listening port $it" } ?: ""}")
            Text("Paired servers: ${receiver.pairedServers ?: "unavailable until receiver starts"}")
            receiver.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(name,{name=it},label={Text("Receiver name")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(server,{server=it},label={Text("Optional Sendspin server URL")},placeholder={Text("ws://music-assistant.local:8927/sendspin")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Text("Leave the server URL blank for automatic discovery: Music Assistant connects to this tablet. Set its Sendspin provider to discover players. A manual URL uses the dedicated Sendspin endpoint, usually port 8927; it is separate from your HA URL and token.")
            OutlinedTextField(port,{port=it},label={Text("Listening port (automatic discovery mode)")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedButton(onClick={ receiver.configure(name,server,port) },modifier=Modifier.heightIn(min=56.dp)) { Text("Apply receiver settings / reconnect") }
            OutlinedButton(onClick={ pairingToken = receiver.pairingToken() },modifier=Modifier.heightIn(min=56.dp)) { Text("Show receiver pairing QR") }
            Text("Pair this receiver in Music Assistant using its own pairing token. Unpaired playback is disabled. Requires a server supporting encrypted Sendspin; legacy plaintext servers must be upgraded.")
            Text("Receiver volume: ${receiver.volume}% · Android media volume also applies")
            Slider(receiver.volume.toFloat(),{ receiver.changeVolume(it.toInt()) },valueRange=0f..100f)
            Row {
                Text("Mute receiver",Modifier.weight(1f)); Switch(receiver.muted,receiver::changeMute)
            }
            Text("Android chooses the media output (tablet, dock, Bluetooth or wired). Dock detection does not prove audio is playing through the dock. Set static output delay in Music Assistant if needed; synchronized playback has not been measured on your hardware.")
            OutlinedButton(onClick={confirmReset=true},modifier=Modifier.heightIn(min=56.dp)) { Text("Reset receiver identity & pairings") }
            Text("Native player library: SendspinDroid, MIT license. License included in APK assets/licenses/SendspinDroid.txt.",style=MaterialTheme.typography.bodySmall)
        }
    }
    if(confirmReset) AlertDialog(onDismissRequest={confirmReset=false},title={Text("Reset receiver identity?")},text={Text("Stops and disables the receiver, removes its pairing records and creates a new identity next time. You will need to pair it again in Music Assistant.")},confirmButton={TextButton(onClick={confirmReset=false;receiver.resetIdentity()}){Text("Reset receiver")}},dismissButton={TextButton(onClick={confirmReset=false}){Text("Cancel")}})
    pairingToken?.let { token ->
        val bitmap = remember(token) {
            val matrix = MultiFormatWriter().encode(token,BarcodeFormat.QR_CODE,512,512,mapOf(EncodeHintType.MARGIN to 3))
            Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888).apply {
                val pixels = IntArray(512*512) { i -> if(matrix[i%512,i/512]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                setPixels(pixels,0,512,0,0,512,512)
            }
        }
        Dialog(onDismissRequest={pairingToken=null},properties=DialogProperties(securePolicy=SecureFlagPolicy.SecureOn)) {
            Surface(shape=MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Text("Pair tablet receiver",style=MaterialTheme.typography.headlineSmall)
                    Text("Use Music Assistant's Sendspin player pairing flow. This is the receiver's private pairing token, separate from a Home Assistant access token.")
                    Image(bitmap.asImageBitmap(),"Private Sendspin pairing QR",Modifier.sizeIn(maxWidth=256.dp,maxHeight=256.dp).aspectRatio(1f))
                    Text(token,fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)
                    Button(onClick={pairingToken=null},modifier=Modifier.heightIn(min=56.dp)) { Text("Close pairing QR") }
                }
            }
        }
    }
}
