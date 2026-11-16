package dev.pixelhome

import android.os.Bundle
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.withResumed
import kotlinx.coroutines.launch
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import dev.pixelhome.PlaybackNavigation.Page

class MainActivity : ComponentActivity() {
    private lateinit var model: HubViewModel
    private lateinit var host: HubHost
    private lateinit var receiver: SendspinReceiver
    private lateinit var ambient: AmbientHost
    private var wakingGesture = false
    private var activityResumed = false
    private val tokenCameraPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if(::model.isInitialized && ::host.isInitialized) model.connectionDraft.cameraResult(granted, android.os.SystemClock.elapsedRealtime(), !host.isLockedNow() && model.page == Page.SETTINGS)
    }
    private val tokenImagePicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if(::model.isInitialized && model.connectionDraft.consumeImageRequest(android.os.SystemClock.elapsedRealtime()) && uri != null) {
            lifecycleScope.launch { lifecycle.withResumed {
                if(::host.isInitialized && !host.isLockedNow() && model.page == Page.SETTINGS) model.importTokenQrImage(applicationContext.contentResolver, uri)
            } }
        }
    }
    private val ambientCameraPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if(::ambient.isInitialized && ::host.isInitialized && !host.isLockedNow() && model.page == Page.SETTINGS) {
            if(granted) ambient.change(ambient.settings.copy(faceWake=true)) else ambient.permissionNotice("Camera")
        }
    }
    private val ambientMicrophonePermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if(::ambient.isInitialized && ::host.isInitialized && !host.isLockedNow() && model.page == Page.SETTINGS) {
            if(granted) ambient.change(ambient.settings.copy(voiceWake=true)) else ambient.permissionNotice("Microphone")
        }
    }
    fun enableFaceWake(enabled: Boolean) {
        if(!::host.isInitialized || host.isLockedNow()) return
        if(!enabled || checkSelfPermission(android.Manifest.permission.CAMERA)==android.content.pm.PackageManager.PERMISSION_GRANTED) ambient.change(ambient.settings.copy(faceWake=enabled))
        else ambientCameraPermission.launch(android.Manifest.permission.CAMERA)
    }
    fun enableVoiceWake(enabled: Boolean) {
        if(!::host.isInitialized || host.isLockedNow()) return
        if(!enabled || checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED) ambient.change(ambient.settings.copy(voiceWake=enabled))
        else ambientMicrophonePermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }
    fun scanHomeAssistantToken() {
        if(!::host.isInitialized || host.isLockedNow() || model.page != Page.SETTINGS) return
        model.connectionDraft.requestCamera(android.os.SystemClock.elapsedRealtime())
        if(checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            model.connectionDraft.cameraResult(true, android.os.SystemClock.elapsedRealtime(), true)
        else tokenCameraPermission.launch(android.Manifest.permission.CAMERA)
    }
    fun chooseHomeAssistantTokenImage() {
        if(!::host.isInitialized || host.isLockedNow() || model.page != Page.SETTINGS) return
        model.connectionDraft.requestImage(android.os.SystemClock.elapsedRealtime())
        tokenImagePicker.launch(arrayOf("image/*"))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val automaticReturn = intent.getBooleanExtra(DockReturnService.AUTO_RETURN, false)
        val launchGuard = getSystemService(android.app.KeyguardManager::class.java)
        if(automaticReturn && !launchGuard.isKeyguardLocked && !launchGuard.isDeviceLocked) {
            finish(); return
        }
        intent.removeExtra(DockReturnService.AUTO_RETURN)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[HubViewModel::class.java]
        receiver = ViewModelProvider(this)[SendspinReceiver::class.java]
        host = HubHost(this)
        ambient = AmbientHost(this)
        host.onDeviceLocked = ambient::wake
        val keyguard = applicationContext.getSystemService(android.app.KeyguardManager::class.java)
        model.attachAccessCheck { !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked }
        model.attachPairing(this,host) {
            when {
                ambient.asleep -> "clock"
                host.isLockedNow() && !host.lockScreenEligible -> "unknown"
                host.isLockedNow() -> if(host.showLockedDashboard) "home" else "clock"
                host.dashboardPreview -> "home"
                model.page == Page.IDLE -> "clock"
                else -> model.page.name.lowercase()
            }
        }
        host.start()
        if(automaticReturn && (!host.lockScreenEligible || !host.dockEligible())) { finish(); return }
        host.restorePreferences(model)
        host.refresh()
        host.applyWindowPolicy()
        if(automaticReturn) host.prepareDockReturn()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) { host.refresh(); model.assistant.concealIfLocked(); ambient.tick(model,receiver,host); delay(if(ambient.asleep) 1000 else 200) }
            }
        }
        setContent {
            SideEffect {
                if (ambient.keepScreenOn(model,host)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                host.persistPreferences(model)
            }
            CompositionLocalProvider(LocalHubHost provides host, LocalSendspinReceiver provides receiver, LocalAmbient provides ambient) {
                PixelTheme(ambient.dark(model.dark),ambient.black(model.dark)) {
                    if(ambient.asleep && (!host.locked || host.lockScreenEligible)) AmbientClock(ambient::wake)
                    else if (host.locked) { if(host.lockScreenEligible) LockedHubScreen(model, host) else Surface(Modifier.fillMaxSize()) {} }
                    else if(host.dashboardPreview) DockDashboard(model,host,preview=true)
                    else PixelHome(model)
                }
            }
        }
    }
    override fun onStart() {
        super.onStart()
        if(::receiver.isInitialized) receiver.visible(true)
    }
    override fun onStop() {
        if(::receiver.isInitialized) receiver.visible(false)
        super.onStop()
    }
    override fun onDestroy() {
        if (::ambient.isInitialized) ambient.close()
        if (::host.isInitialized) host.close()
        super.onDestroy()
    }
    override fun onResume() {
        super.onResume()
        activityResumed = true
        if(::ambient.isInitialized) ambient.resume()
        if (::host.isInitialized) { host.refresh(); host.applyWindowPolicy(); host.startLockWatcherIfConfigured() }
        if (::model.isInitialized) model.foreground(true)
    }
    override fun onPause() {
        activityResumed = false
        if(::ambient.isInitialized) ambient.pause()
        if (::host.isInitialized) host.conceal()
        if (::model.isInitialized) model.foreground(false)
        super.onPause()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::host.isInitialized) host.refresh()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val automaticReturn = intent.getBooleanExtra(DockReturnService.AUTO_RETURN, false)
        val launchGuard = getSystemService(android.app.KeyguardManager::class.java)
        if(automaticReturn && !launchGuard.isKeyguardLocked && !launchGuard.isDeviceLocked) {
            if(!activityResumed) moveTaskToBack(true)
            return
        }
        intent.removeExtra(DockReturnService.AUTO_RETURN)
        if(::host.isInitialized) host.refresh()
        if(automaticReturn && ::host.isInitialized && (!host.lockScreenEligible || !host.dockEligible())) {
            if(!activityResumed) moveTaskToBack(true)
            return
        }
        if(automaticReturn && ::host.isInitialized) { if(::ambient.isInitialized) ambient.wake();host.prepareDockReturn() }
        setIntent(intent)
        if (::model.isInitialized && intent.hasCategory(Intent.CATEGORY_HOME)) model.open(Page.HOME)
    }
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if(::ambient.isInitialized && ev.actionMasked == MotionEvent.ACTION_DOWN && ambient.asleep) {
            wakingGesture = true; ambient.wake(); return true
        }
        if(wakingGesture) { if(ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) wakingGesture=false; return true }
        if(::ambient.isInitialized) ambient.interaction()
        if (::host.isInitialized && host.isLockedNow() && !host.locked) { host.refresh(); return true }
        if (::model.isInitialized) model.touch()
        return super.dispatchTouchEvent(ev)
    }
}
@Composable private fun LockedHubScreen(vm: HubViewModel, host: HubHost) {
    if(host.showLockedDashboard) DockDashboard(vm,host) else PrivateLockedClock(vm,host)
}
@Composable private fun PrivateLockedClock(vm: HubViewModel, host: HubHost) {
    var clock by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); clock = LocalDateTime.now() } }
    BackHandler { host.systemLockScreen() }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Hearthline", style = MaterialTheme.typography.titleLarge)
                Icon(Icons.Outlined.Lock, "Device locked")
            }
            Text(clock.format(DateTimeFormatter.ofPattern("h:mm")), fontSize = 76.sp, fontWeight = FontWeight.Light)
            Text(clock.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), style = MaterialTheme.typography.headlineSmall)
            if(host.dockTestActive) {
                Text("Assumed dock · test", style = MaterialTheme.typography.labelMedium)
                OutlinedButton(onClick = host::endDockTest, modifier = Modifier.heightIn(min = 56.dp)) { Text("End dock test") }
            }
            Text(host.dock.status, style = MaterialTheme.typography.labelLarge)
            if (vm.playing && host.showLockedMusic) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                AlbumArt(vm, Modifier.size(128.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { TrackText(vm); Text("Unlock to use music controls") }
            }
            if (vm.demo) Text("Demo mode · no audio playback", style = MaterialTheme.typography.labelLarge)
            Text("Your tablet is locked. Unlock to open home controls and settings.")
            Button(onClick = host::unlock, modifier = Modifier.heightIn(min = 56.dp)) { Text("Unlock controls") }
            TextButton(onClick = host::systemLockScreen) { Text("Dismiss · use Android lock screen") }
            host.notice?.let { Text(it) }
        }
    }
}
internal val LocalDockReadOnly = staticCompositionLocalOf { false }
@Composable private fun DockDashboard(vm: HubViewModel, host: HubHost, preview: Boolean = false) {
    var clock by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while(true) { delay(1000);clock=LocalDateTime.now() } }
    BackHandler { if(preview) host.previewDashboard(false) else host.systemLockScreen() }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Outlined.Lock,"Controls locked")
                Column(Modifier.weight(1f)) {
                    Text(if(preview) "Dock dashboard preview" else "Dock dashboard",style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                    if(host.dockTestActive) Text("Assumed dock · test",style=MaterialTheme.typography.labelSmall,maxLines=1)
                }
                if(host.dockTestActive) {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick={menu=true},modifier=Modifier.size(56.dp)) {Icon(Icons.Outlined.MoreVert,"Dock test options")}
                        DropdownMenu(menu,{menu=false}) {DropdownMenuItem(text={Text("End test")},onClick={menu=false;host.endDockTest()})}
                    }
                }
                if(preview) OutlinedButton(onClick={host.previewDashboard(false)},modifier=Modifier.heightIn(min=56.dp)) { Text("Exit preview") }
                else {
                    Button(onClick=host::unlock,modifier=Modifier.heightIn(min=56.dp)) { Text("Unlock tablet") }
                    TextButton(onClick=host::systemLockScreen) { Text("Dismiss dock") }
                }
            }
            Text(if(vm.demo) "Design demo · sample devices · no audio playback" else if(vm.connected) "Home Assistant connected · unlock to control" else "Disconnected · displayed values may be stale",modifier=Modifier.padding(horizontal=24.dp),style=MaterialTheme.typography.labelMedium)
            CompositionLocalProvider(LocalDockReadOnly provides true) {
                Box(Modifier.weight(1f)) { HomeOverview(vm,clock,showMusic=host.showLockedMusic) }
            }
        }
    }
}
private val Sage = Color(0xFFDCE8C4)
private val Olive = Color(0xFF30372A)
@Composable private fun PixelTheme(dark: Boolean, black: Boolean = false, content: @Composable () -> Unit) {
    val colors = if (dark) darkColorScheme(primary = Sage, onPrimary = Olive,
        background = if(black) Color.Black else Color(0xFF191D17), surface = if(black) Color.Black else Color(0xFF191D17),
        surfaceVariant = Color(0xFF30372A), onSurface = Color(0xFFE9EDDF),
        primaryContainer = Color(0xFF3D4C2D), onPrimaryContainer = Color(0xFFE5EED5))
    else lightColorScheme(primary = Color(0xFF5C703D), onPrimary = Color.White,
        background = Color(0xFFF7F3E8), surface = Color(0xFFF7F3E8),
        surfaceVariant = Color(0xFFEDE9DC), onSurface = Olive, onSurfaceVariant = Color(0xFF535A4B),
        primaryContainer = Sage, onPrimaryContainer = Olive)
    MaterialTheme(colorScheme = colors, shapes = Shapes(medium = RoundedCornerShape(24.dp), large = RoundedCornerShape(28.dp)), content = content)
}
@Composable private fun PixelHome(vm: HubViewModel) {
    var clock by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); vm.tick(); clock = LocalDateTime.now() } }
    val nativeAnswer = vm.assistant.answer?.takeIf { it.template != null }
    if (nativeAnswer != null) { NativeTemplateScreen(vm, nativeAnswer); return }
    AssistantSheet(vm)
    BackHandler(vm.page != Page.HOME && vm.assistant.answer == null) { vm.open(Page.HOME) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            Surface(color = if (vm.demo) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if(LocalHubHost.current.dockTestActive) Text("Assumed dock · test",style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(end=16.dp))
                    Text(if (vm.demo) "Design demo · sample devices · no audio playback" else if (vm.connected) "Home Assistant connected" else "Disconnected · displayed values may be stale", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { vm.open(Page.SETTINGS) }) { Text("Connection") }
                }
            }
            vm.message?.let { message ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::clearMessage) { Text("Dismiss") }
                }
            }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            BoxWithConstraints(Modifier.weight(1f)) {
                val wide = maxWidth >= 840.dp
                Row(Modifier.fillMaxSize()) {
                    if (wide && vm.page != Page.IDLE) NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                        Spacer(Modifier.height(20.dp))
                        listOf(Triple(Page.HOME, Icons.Outlined.Home, "Home"), Triple(Page.MUSIC, Icons.Outlined.MusicNote, "Music"), Triple(Page.SETTINGS, Icons.Outlined.Settings, "Settings")).forEach { (page, icon, label) ->
                            NavigationRailItem(vm.page == page, { vm.open(page) }, { Icon(icon, label) }, label = { Text(label) }, modifier = Modifier.padding(vertical = 8.dp))
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        if (!wide && vm.page != Page.IDLE) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            TextButton({ vm.open(Page.HOME) }) { Text("Home") }
                            TextButton({ vm.open(Page.MUSIC) }) { Text("Music") }
                            TextButton({ vm.open(Page.SETTINGS) }) { Text("Settings") }
                        }
                        AnimatedContent(targetState = vm.page, label = "Page transition", modifier = Modifier.weight(1f)) { page ->
                            if(page == Page.HOME) HomeOverview(vm,clock)
                            else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if (wide) 28.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                when (page) {
                                    Page.MUSIC -> MusicScreen(vm, wide)
                                    Page.IDLE -> IdleScreen(vm, wide, clock)
                                    Page.SETTINGS -> SettingsScreen(vm)
                                    Page.HOME -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun Title(text: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, fontSize = 36.sp, fontWeight = FontWeight.Medium)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable private fun Tile(modifier: Modifier = Modifier, active: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(28.dp), color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable internal fun DeviceGrid(vm: HubViewModel, wide: Boolean) {
    vm.config.favorites.chunked(if (wide) 2 else 1).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            pair.forEach { id -> DeviceTile(vm, id, Modifier.weight(1f)) }
            if (wide && pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}
@Composable internal fun DeviceTile(vm: HubViewModel, id: String, modifier: Modifier) {
    val entity = vm.entities[id]
    val domain = id.substringBefore('.')
    val enabled = !LocalDockReadOnly.current && vm.canControl && entity?.available == true
    var confirm by remember { mutableStateOf(false) }
    val icon = when (domain) { "light" -> Icons.Outlined.Lightbulb; "climate" -> Icons.Outlined.Thermostat; "lock" -> Icons.Outlined.Lock; else -> Icons.Outlined.PowerSettingsNew }
    Tile(modifier, entity?.state == "on") {
        Icon(icon, null, Modifier.size(28.dp))
        Text(entity?.name ?: id, style = MaterialTheme.typography.titleLarge)
        Text(if (entity?.available != true) "Unavailable" else entity.state.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium)
        when (domain) {
            "light", "switch", "fan" -> {
                Switch(checked = entity?.state == "on", onCheckedChange = { vm.command(id, if (it) "turn_on" else "turn_off") }, enabled = enabled)
                if (domain == "light" && entity?.attrs?.has("brightness") == true) ValueSlider("Brightness", entity.attrs.optDouble("brightness", 0.0).toFloat() / 255f, enabled) {
                    vm.command(id, "turn_on", JSONObject().put("brightness_pct", (it * 100).toInt()))
                }
            }
            "climate" -> {
                val target = entity?.attrs?.optDouble("temperature", Double.NaN) ?: Double.NaN
                Text(if (target.isFinite()) "${target.toInt()}°" else "—", fontSize = 34.sp)
                val current = entity?.attrs?.optDouble("current_temperature", Double.NaN) ?: Double.NaN
                if (current.isFinite()) Text("Inside ${current.toInt()}°")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(-1, 1).forEach { direction ->
                        OutlinedButton(onClick = {
                            val step = entity?.attrs?.optDouble("target_temp_step", 1.0) ?: 1.0
                            val min = entity?.attrs?.optDouble("min_temp", 7.0) ?: 7.0
                            val max = entity?.attrs?.optDouble("max_temp", 35.0) ?: 35.0
                            vm.command(id, "set_temperature", JSONObject().put("temperature", (target + direction * step).coerceIn(min, max)))
                        }, enabled = enabled && target.isFinite() && entity?.supports(1) == true) { Text(if (direction < 0) "−" else "+", fontSize = 24.sp) }
                    }
                }
            }
            "lock" -> OutlinedButton(onClick = { if (entity?.state == "locked") confirm = true else vm.command(id, "lock") }, enabled = enabled) { Text(if (entity?.state == "locked") "Unlock…" else "Lock") }
            else -> Text("Read-only in this version", style = MaterialTheme.typography.labelMedium)
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Unlock ${entity?.name}?") }, text = { Text("This will unlock the physical door.") },
        confirmButton = { TextButton(onClick = { confirm = false; vm.command(id, "unlock") }, enabled = enabled) { Text("Unlock") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}
@Composable private fun Scenes(vm: HubViewModel) {
    if (vm.config.scenes.isNotEmpty()) {
        Text("Scenes", style = MaterialTheme.typography.titleLarge)
        vm.config.scenes.forEach { id ->
            OutlinedButton(onClick = { vm.command(id, "turn_on") }, enabled = !LocalDockReadOnly.current && vm.canControl && vm.entities[id] != null && vm.entities[id]?.state != "unavailable", modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Icon(Icons.Outlined.AutoAwesome, null); Spacer(Modifier.width(12.dp)); Text(vm.entities[id]?.name ?: id)
            }
        }
    }
}
@Composable internal fun AlbumArt(vm: HubViewModel, modifier: Modifier = Modifier) {
    val bitmap = vm.artwork
    Box(modifier.clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), "Current album artwork", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Canvas(Modifier.fillMaxSize()) {
            drawRect(Color(0xFFF0E8CF)); drawCircle(Color(0xFFEBC986), size.width * .15f, Offset(size.width * .5f, size.height * .3f))
            listOf(.64f to Color(0xFFB9C7A2), .78f to Color(0xFF91A784), .9f to Color(0xFF6D8664)).forEach { (y, color) ->
                val path = Path().apply { moveTo(0f, size.height * y); cubicTo(size.width * .3f, size.height * (y - .25f), size.width * .6f, size.height * (y + .12f), size.width, size.height * (y - .18f)); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
                drawPath(path, color)
            }
        }
    }
}
@Composable private fun TrackText(vm: HubViewModel, large: Boolean = false) {
    Text(vm.player?.attrs?.optString("media_title")?.takeIf { it.isNotBlank() } ?: "Nothing playing", fontSize = if (large) 40.sp else 28.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text(vm.player?.attrs?.optString("media_artist")?.takeIf { it.isNotBlank() } ?: "Select music to begin", style = MaterialTheme.typography.titleMedium)
}
@Composable internal fun Transport(vm: HubViewModel, large: Boolean = false) {
    val p = vm.player
    val enabled = !LocalDockReadOnly.current && vm.canControl && p?.available == true
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.command(vm.selectedPlayer, "media_previous_track") }, enabled = enabled && p?.supports(16) == true, modifier = Modifier.size(if (large) 80.dp else 56.dp)) { Icon(Icons.Outlined.SkipPrevious, "Previous track", Modifier.size(if (large) 48.dp else 32.dp)) }
        Spacer(Modifier.width(if (large) 40.dp else 12.dp))
        FilledIconButton(onClick = { vm.command(vm.selectedPlayer, if (vm.playing) "media_pause" else "media_play") }, enabled = enabled && p?.supports(if (vm.playing) 1 else 16384) == true, modifier = Modifier.size(if (large) 112.dp else 76.dp)) {
            Icon(if (vm.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (vm.playing) "Pause" else "Play", Modifier.size(if (large) 64.dp else 40.dp))
        }
        Spacer(Modifier.width(if (large) 40.dp else 12.dp))
        IconButton(onClick = { vm.command(vm.selectedPlayer, "media_next_track") }, enabled = enabled && p?.supports(32) == true, modifier = Modifier.size(if (large) 80.dp else 56.dp)) { Icon(Icons.Outlined.SkipNext, "Next track", Modifier.size(if (large) 48.dp else 32.dp)) }
    }
}
@Composable private fun ValueSlider(label: String, value: Float, enabled: Boolean, onCommit: (Float) -> Unit) {
    val safeValue = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
    var draft by remember { mutableFloatStateOf(safeValue) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(safeValue, dragging) { if (!dragging) draft = safeValue }
    Column {
        Text("$label · ${(draft * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
        Slider(value = draft, onValueChange = { dragging = true; draft = it }, onValueChangeFinished = { onCommit(draft); dragging = false }, enabled = enabled, modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
    }
}
@Composable internal fun Volume(vm: HubViewModel) {
    ValueSlider("Volume", vm.player?.attrs?.optDouble("volume_level", 0.0)?.toFloat() ?: 0f, !LocalDockReadOnly.current && vm.canControl && vm.player?.available == true && vm.player?.supports(4) == true) {
        vm.command(vm.selectedPlayer, "volume_set", JSONObject().put("volume_level", it.toDouble()))
    }
}
@Composable internal fun SpeakerPicker(vm: HubViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = !LocalDockReadOnly.current, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Icon(Icons.Outlined.Speaker, null); Spacer(Modifier.width(10.dp)); Text(vm.player?.name ?: "Select speaker", Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis); Icon(Icons.Outlined.ExpandMore, null)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            vm.config.players.forEach { id -> DropdownMenuItem(text = { Text(vm.entities[id]?.name ?: id) }, onClick = { vm.select(id); expanded = false }) }
        }
    }
}
@Composable private fun PlayerCard(vm: HubViewModel, modifier: Modifier = Modifier) {
    Tile(modifier.fillMaxWidth(), true) {
        Text("NOW PLAYING", style = MaterialTheme.typography.labelLarge)
        AlbumArt(vm, Modifier.size(144.dp).clickable(enabled = !LocalDockReadOnly.current, onClickLabel = "Open music") { vm.open(Page.MUSIC) })
        TrackText(vm)
        Text(vm.player?.state ?: "No player configured", style = MaterialTheme.typography.labelLarge)
        Transport(vm); SpeakerPicker(vm); Volume(vm)
        TextButton(onClick = { vm.open(Page.MUSIC) }, enabled = !LocalDockReadOnly.current) { Text("Open music") }
    }
}
@Composable private fun MusicScreen(vm: HubViewModel, wide: Boolean) {
    Title("Music", "Music Assistant · ${if (vm.demo) "demo collection" else "Home Assistant player controls"}")
    val receiver = LocalSendspinReceiver.current
    if(receiver.enabled) Text("Tablet receiver: ${receiver.status} · ${receiver.output}", style = MaterialTheme.typography.bodyLarge)
    val browse: @Composable () -> Unit = {
        Text("Your collection", style = MaterialTheme.typography.headlineSmall)
        Text("Music shortcuts configured in Home Assistant", color = MaterialTheme.colorScheme.onSurfaceVariant)
        vm.config.shortcuts.forEach { shortcut ->
            Tile(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.LibraryMusic, null, Modifier.size(32.dp)); Spacer(Modifier.width(16.dp))
                    Text(shortcut.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = { vm.playShortcut(shortcut) }, enabled = vm.canControl && vm.player?.available == true && vm.player?.supports(512) == true) { Icon(Icons.Outlined.PlayArrow, "Play ${shortcut.title}") }
                }
            }
        }
        if (vm.config.shortcuts.isEmpty()) Text("No music shortcuts configured yet.")
        Text("Full library search and queue editing are planned; this version plays configured music shortcuts.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { vm.open(Page.IDLE) }) { Text("Simple player") }
        TextButton(onClick = { vm.open(Page.HOME) }) { Text("Back to Home") }
    }
    if (wide) Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(16.dp)) { browse() }
        PlayerCard(vm, Modifier.weight(1f))
    } else { PlayerCard(vm); browse() }
}
@Composable private fun IdleScreen(vm: HubViewModel, wide: Boolean, clock: LocalDateTime) {
    if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
        AlbumArt(vm, Modifier.size(210.dp).clickable(onClickLabel = "Open music") { vm.open(Page.MUSIC) })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { TrackText(vm, true); Text(vm.player?.name.orEmpty(), style = MaterialTheme.typography.titleLarge) }
        Text(clock.format(DateTimeFormatter.ofPattern("h:mm")), fontSize = 48.sp)
    } else { AlbumArt(vm, Modifier.size(160.dp).clickable(onClickLabel = "Open music") { vm.open(Page.MUSIC) }); TrackText(vm) }
    Spacer(Modifier.height(24.dp))
    Transport(vm, true)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) { Volume(vm) } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        FilledTonalButton(onClick = { vm.open(Page.HOME) }, modifier = Modifier.heightIn(min = 64.dp)) { Icon(Icons.Outlined.Home, null); Spacer(Modifier.width(12.dp)); Text("Home", fontSize = 22.sp) }
    }
}
@Composable private fun SettingsScreen(vm: HubViewModel) {
    val host = LocalHubHost.current
    var displayId by remember { mutableStateOf(vm.assistant.displayId) }
    Title("Make it yours", "Home Assistant manages the dashboard content")
    AmbientSettingsCard(LocalAmbient.current)
    Tile(Modifier.widthIn(max = 800.dp).fillMaxWidth()) {
        Text("Assistant display", style = MaterialTheme.typography.titleLarge)
        Text(vm.assistant.status)
        OutlinedTextField(displayId, { displayId = it }, label = { Text("Display ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.assistant.configure(true, displayId) }) { Text("Enable display bridge") }
            OutlinedButton(onClick = { vm.assistant.configure(false, displayId) }) { Text("Disable") }
        }
        Text("Install the included pixel_home integration on your Home Assistant server first. Answers appear only while this app is visible and unlocked. Display ID, bridge enablement and credentials are session-only.")
        if(vm.demo) OutlinedButton(onClick = vm.assistant::preview) { Text("Preview an assistant answer") }
        if(vm.demo) {
            val context=androidx.compose.ui.platform.LocalContext.current
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("journey","weather","home_status").forEach { kind->
                    OutlinedButton(onClick={ vm.assistant.previewTemplate(context.assets.open("demo-$kind.json").bufferedReader().use { it.readText() }) }) { Text("Preview ${kind.replace('_',' ')}") }
                }
            }
        }
        var mapStyle by remember { mutableStateOf("") }
        var mapOrigins by remember { mutableStateOf("") }
        var mapAttribution by remember { mutableStateOf("") }
        Text("Journey map provider",style=MaterialTheme.typography.titleLarge)
        Text("Session-only. Supply your licensed HTTPS style, every tile/glyph/sprite origin and required attribution. No default public tile service is used.")
        OutlinedTextField(mapStyle,{mapStyle=it},label={Text("HTTPS style URL")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(mapOrigins,{mapOrigins=it},label={Text("Allowed HTTPS origins, comma separated")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(mapAttribution,{mapAttribution=it},label={Text("Map/data attribution")},modifier=Modifier.fillMaxWidth())
        OutlinedButton(onClick={vm.assistant.configureMap(mapStyle,mapOrigins,mapAttribution)}) { Text("Use map provider for this session") }

        Text("Voice listening, speech playback and follow-up buttons are not included in this build.", style = MaterialTheme.typography.bodySmall)
    }
    Tile(Modifier.widthIn(max = 800.dp).fillMaxWidth()) {
        Text("Startup & lock screen", style = MaterialTheme.typography.titleLarge)
        Text(if (host.isDefaultHome) "Hearthline is your default Home app" else "Default Home app: another launcher")
        Text("Choose Hearthline as Android's Home app to open this interface when Android returns Home, including after startup and the first unlock.")
        Button(onClick = host::makeHome, enabled = !host.isDefaultHome) { Text("Make Hearthline my Home app") }
        OutlinedButton(onClick = host::homeSettings) { Text("Change default Home app") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Show over lock screen", Modifier.weight(1f))
            Switch(host.showWhenLocked, host::showOverLock)
        }
        Text(host.dock.status, style = MaterialTheme.typography.titleMedium)
        Text("Battery ${host.dock.batteryPercent?.let { "$it%" } ?: "unavailable"} · Power: ${host.dock.powerSource}")
        Text("Normal mode requires a confirmed dock connection; undocked or unconfirmed means no dock lock-screen view.")
        if(host.dockTestActive) {
            Text("Assumed dock · test (10 minutes)", style = MaterialTheme.typography.labelLarge)
            OutlinedButton(onClick = host::endDockTest, modifier = Modifier.heightIn(min = 56.dp)) { Text("End dock test") }
        } else {
            OutlinedButton(onClick = { host.assumeDockForTesting(); vm.open(Page.HOME); host.autoReturn(true); }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Assume docked for testing") }
        }
        Text("Assume docked opens the main Home screen immediately. The test lasts 10 minutes or until the app process restarts; it can test return on lock once Android permissions are allowed. Actual dock readings are unchanged.")
        OutlinedButton(onClick = { host.previewDashboard(true) }, modifier = Modifier.heightIn(min=56.dp)) { Text("Preview locked dock dashboard") }
        Row(verticalAlignment=Alignment.CenterVertically) { Text("Show main dashboard while locked",Modifier.weight(1f)); Switch(host.showLockedDashboard,{host.lockedLayout(dashboard=it)}) }
        Text("The dock dashboard shows configured device and music information. Its controls stay disabled until Android is unlocked. Turn this off for the private clock-only view.")
        Row(verticalAlignment=Alignment.CenterVertically) { Text("Wake dock display immediately on lock",Modifier.weight(1f)); Switch(host.wakeScreenOnLock,{host.lockedLayout(wake=it)}) }
        Text("Dock detection", style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DockDetectionMode.entries.filter { it != DockDetectionMode.FORCE_DOCKED }.forEach { mode ->
                OutlinedButton(onClick = { host.detectionMode(mode) }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text("${if(host.dock.mode == mode) "✓ " else ""}${when(mode) { DockDetectionMode.AUTO -> "Automatic dock signals"; DockDetectionMode.FORCE_DOCKED -> "Assume docked (manual)"; DockDetectionMode.FORCE_UNDOCKED -> "Assume undocked (manual)" }}")
                }
            }
        }
        Text("Automatic mode uses Android dock/audio/power evidence. Ordinary AC, USB or wireless charging does not confirm a dock. A manual undocked setting can disable presentation. Automatic return requires real docking, except during the explicitly activated 10-minute local dock test.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Show dock view again on next lock", Modifier.weight(1f))
            Switch(host.rearmOnLock, { host.configureDock(rearm = it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Keep dock view awake while locked", Modifier.weight(1f))
            Switch(host.keepDockAwake, { host.configureDock(awake = it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Show music information while locked", Modifier.weight(1f))
            Switch(host.showLockedMusic, { host.configureDock(music = it) })
        }
        OutlinedButton(onClick = host::resumeDockDisplay, modifier = Modifier.heightIn(min = 56.dp)) { Text("Resume dismissed dock view") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Automatically return on lock from other apps", Modifier.weight(1f))
            Switch(host.autoReturnOnLock, host::autoReturn)
        }
        Text(if(host.autoReturnOnLock && DockReturnService.running) "Monitoring locks · unlocked use is never interrupted" else "Automatic lock monitoring is off or not running")
        Text("For automatic return, Android requires Display over other apps and a visible monitoring notification. Enable this while unlocked. It only opens the protected view after locking and when a dock is confirmed or the temporary local test is active; dismissing does not reopen it during that lock cycle.")
        OutlinedButton(onClick = host::overlaySettings, modifier = Modifier.heightIn(min = 56.dp)) { Text(if(host.overlayPermission) "Review automatic-return permission" else "Grant automatic-return permission") }

        Text("Dismiss the protected view to use Android's lock screen, or choose Unlock controls to authenticate. When enabled, the next lock re-arms the view while docked. Without automatic return, this applies while Hearthline is the foreground/Home activity. With the optional permission and monitor enabled, it can return from other apps only after locking. Android authentication remains required.")
        Text("All dock display preferences persist. With keep-awake off, Power wakes the screen normally. No PIN, lock bypass, boot auto-launch or vendor cloud service is used.", style = MaterialTheme.typography.bodySmall)
    }
    SendspinSettingsCard(LocalSendspinReceiver.current)
    HomeAssistantConnectionCard(vm)
    Tile(Modifier.widthIn(max = 800.dp).fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Hub mode", Modifier.weight(1f)); Switch(vm.hubEnabled, vm::hub) }
        Text("Keeps Hearthline awake while visible. Starts Music when the selected player begins playback; simplifies Music after ${vm.config.idleSeconds} seconds. This is independent of the dock-only lock-screen preference.")
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Dark appearance", Modifier.weight(1f)); Switch(vm.dark, { vm.dark = it }) }
        Text("If Hearthline is your default Home app, the Home gesture returns here. Use Change default Home app above to restore your normal launcher. Dock presentation and automatic detection are configured above.")
    }
}
