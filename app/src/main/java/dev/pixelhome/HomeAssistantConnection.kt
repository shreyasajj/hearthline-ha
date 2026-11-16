package dev.pixelhome

import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.hardware.Camera
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.*
import com.journeyapps.barcodescanner.camera.CameraSettings

private fun Context.hostActivity(): MainActivity? = when(this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.hostActivity()
    else -> null
}

@Composable fun HomeAssistantConnectionCard(vm: HubViewModel) {
    val draft = vm.connectionDraft
    val activity = LocalContext.current.hostActivity()
    // The draft is never rememberSaveable, persisted, copied to clipboard or included in screenshots.
    DisposableEffect(activity) {
        val window = activity?.window
        val alreadySecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0 && window != null
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if(!alreadySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    OutlinedCard(Modifier.widthIn(max = 800.dp).fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Hearthline · Home Assistant", style = MaterialTheme.typography.titleLarge)
            Text(vm.pairingStatus)
            if(vm.configRevision.isNotEmpty()) Text("Dashboard revision: ${vm.configRevision}")
            if(vm.tabletIdentity.isNotEmpty()) Text("Installation: ${vm.tabletIdentity}",style=MaterialTheme.typography.bodySmall)
            if(!vm.paired) TextButton(onClick=vm::forgetLocally,enabled=!vm.busy) { Text("Forget stored server locally") }
            if(vm.paired) {
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(checked=vm.telemetryEnabled,onCheckedChange=vm::telemetry)
                    Text("Share tablet telemetry while app is visible")
                }
                Text("Last successful telemetry push: ${vm.lastPush ?: "None"}")
                TextButton(onClick=vm::reconnect) { Text("Reconnect") }
                TextButton(onClick=vm::forgetServer,enabled=!vm.busy) { Text("Unpair and forget server") }
                TextButton(onClick=vm::forgetLocally,enabled=!vm.busy) { Text("Forget locally (revoke in HA separately)") }
            }
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Switch(checked=draft.legacy,onCheckedChange={ draft.legacy=it })
                Text("Legacy / manual dashboard sensor")
            }
            if(!draft.legacy) Text("Add hearthline-ha in HA Settings → Devices & services. Choose entities, then authorize once below. The scoped tablet pairing is remembered encrypted on this device. The HA account token stays in memory and is cleared after pairing. Pairing permits selected entity reads, supported dashboard controls, display delivery and optional tablet telemetry.")
            Text("On another screen: Home Assistant → your profile → Security → Long-lived access tokens → Create token → Generate QR code.")
            Text("Scan the token QR or choose its image. Enter your HTTPS server address separately; Home Assistant's token QR contains only the token.")
            OutlinedTextField(draft.url, { draft.url = it }, label = { Text("HTTPS server URL") }, placeholder = { Text("https://home.example.com") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { activity?.scanHomeAssistantToken() }, enabled = activity != null && !draft.importingImage,
                    modifier = Modifier.heightIn(min = 56.dp)) { Text("Scan Home Assistant token QR") }
                OutlinedButton(onClick = { activity?.chooseHomeAssistantTokenImage() }, enabled = activity != null && !draft.importingImage,
                    modifier = Modifier.heightIn(min = 56.dp)) { Text("Import token QR image") }
            }
            if(draft.importingImage) LinearProgressIndicator(Modifier.fillMaxWidth())
            draft.note?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            OutlinedTextField(draft.token, draft::updateToken, label = { Text("Long-lived access token") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true, modifier = Modifier.fillMaxWidth())
            if(draft.token.isNotEmpty()) TextButton(onClick = { draft.clearToken(); draft.note = "Token cleared." },
                modifier = Modifier.heightIn(min = 56.dp)) { Text("Clear token") }
            if(draft.legacy) OutlinedTextField(draft.sensor, { draft.sensor = it }, label = { Text("Dashboard sensor entity ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if(!draft.legacy) {
                Button(onClick=vm::loadPairingChoices,enabled=draft.token.isNotBlank() && !vm.busy) { Text("Find configured tablets") }
                vm.pairingChoices.forEach { (entry,name) ->
                    OutlinedButton(onClick={ vm.pairTablet(entry) },enabled=!vm.busy) { Text("Pair and remember $name") }
                }
            }
            Text("QR decoding runs on this tablet. Camera access is used only while the scanner is open. The token stays in process memory; rescan or paste it after restarting the app.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if(draft.legacy) Button(onClick = {
                    val token = draft.token
                    draft.clearToken(); draft.cancelRequests(); draft.note = null
                    vm.connect(draft.url, token, draft.sensor)
                }, enabled = draft.token.isNotBlank() && !draft.importingImage, modifier = Modifier.heightIn(min = 56.dp)) { Text("Connect") }
                OutlinedButton(onClick = { draft.clearToken(); draft.cancelRequests(); vm.useDemo() }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Use demo / disconnect") }
            }
        }
    }
    if(draft.scannerRequested) TokenScannerDialog(
        onDismiss = { draft.scannerRequested = false },
        onToken = vm::acceptTokenQr,
        onFailure = { draft.scannerRequested = false; draft.note = it }
    )
}

@Suppress("DEPRECATION")
@Composable private fun TokenScannerDialog(onDismiss: () -> Unit, onToken: (HaQrToken) -> Unit, onFailure: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val keyguard = context.getSystemService(KeyguardManager::class.java)
    val cameras = remember { runCatching { (0 until Camera.getNumberOfCameras()).toList() }.getOrDefault(emptyList()) }
    var cameraIndex by remember { mutableIntStateOf(0) }
    val currentToken by rememberUpdatedState(onToken)
    val currentFailure by rememberUpdatedState(onFailure)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Scan Home Assistant token", style = MaterialTheme.typography.headlineSmall)
                Text("Point the camera at the QR generated for your long-lived access token.")
                key(cameraIndex) {
                    val session = remember { QrImportSession(SystemClock.elapsedRealtime()) }
                    var active by remember { mutableStateOf(true) }
                    val view = remember {
                        BarcodeView(context).apply {
                            decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                            cameraSettings = CameraSettings().apply { if(cameras.isNotEmpty()) requestedCameraId = cameras[cameraIndex % cameras.size] }
                            addStateListener(object : CameraPreview.StateListener {
                                override fun previewSized() {}
                                override fun previewStarted() {}
                                override fun previewStopped() {}
                                override fun cameraClosed() {}
                                override fun cameraError(error: Exception) {
                                    if(active) { active = false; currentFailure("Camera unavailable. Check Android's camera permission/privacy setting, or import a QR image.") }
                                }
                            })
                            decodeContinuous(object : BarcodeCallback {
                                override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) {}
                                override fun barcodeResult(result: BarcodeResult?) {
                                    if(!active || result == null) return
                                    val resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                                    val unlocked = !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked
                                    if(!resumed || !unlocked) return
                                    active = false; pause()
                                    try { currentToken(session.accept(result.text, SystemClock.elapsedRealtime(), unlocked, resumed)) }
                                    catch(_: Exception) { currentFailure(HaQrToken.INVALID) }
                                }
                            })
                        }
                    }
                    DisposableEffect(view, owner) {
                        val observer = LifecycleEventObserver { _, event ->
                            when(event) {
                                Lifecycle.Event.ON_RESUME -> if(active && !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked) view.resume()
                                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> { session.cancel(); active = false; view.pause(); onDismiss() }
                                else -> Unit
                            }
                        }
                        owner.lifecycle.addObserver(observer)
                        if(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked) view.resume()
                        onDispose { active = false; session.cancel(); owner.lifecycle.removeObserver(observer); view.pause() }
                    }
                    AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(280.dp))
                }
                if(cameras.size > 1) OutlinedButton(onClick = { cameraIndex = (cameraIndex + 1) % cameras.size }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Switch camera") }
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) { Text("Cancel scan") }
            }
        }
    }
}
