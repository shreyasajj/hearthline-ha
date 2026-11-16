package dev.pixelhome

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.module.http.HttpRequestUtil
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/** User supplied configuration, never accepted from a display payload or model. */
data class MapConfiguration(val style:String,val origins:Set<String>,val attribution:String) {
    companion object {
        fun parse(style:String,origins:String,attribution:String):MapConfiguration {
            fun origin(s:String):String { val u=s.toHttpUrl(); require(u.isHttps && u.username.isEmpty() && u.password.isEmpty() && u.encodedPath=="/" && u.query==null && u.fragment==null); return "${u.scheme}://${u.host}:${u.port}" }
            val allowed=origins.split(',').map { origin(it.trim()) }.toSet(); require(allowed.size in 1..8)
            val url=style.trim().toHttpUrl(); require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.fragment==null && "${url.scheme}://${url.host}:${url.port}" in allowed)
            require(attribution.isNotBlank() && attribution.length<=500)
            return MapConfiguration(url.toString(),allowed,attribution)
        }
    }
}
/** Full-screen semantic templates; generic assistant answers retain their existing bottom sheet. */
@Composable fun NativeTemplateScreen(vm: HubViewModel, answer: DisplayResponse) {
    val template = answer.template ?: return
    val display = vm.assistant
    var expanded by remember(answer.id) { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(answer.id) { while (true) { now = Instant.now(); delay(1000) } }
    BackHandler(onBack = display::dismiss)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            LaunchedEffect(answer) { withFrameNanos { }; withFrameNanos { }; display.rendered(answer) }
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("Hearthline", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (vm.demo) TemplatePill("Demo · sample data")
                    Text(templateTime(now), fontSize = 22.sp)
                }
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    val result = template.result
                    if (result == null) {
                        TemplateHeading(answer.title)
                        if (template.state == "loading") LoadingTemplate(answer.layout, answer.summary)
                        else TemplateCard(Modifier.fillMaxWidth()) {
                            TemplateHeading(when(template.state) {
                                "clarification" -> "A little more detail"
                                "unavailable" -> "Provider unavailable"
                                else -> "Unable to load this answer"
                            })
                            TemplateSupport(answer.summary)
                        }
                    } else when (result) {
                        is JourneyData -> JourneyContent(result, display.mapConfiguration, expanded) { expanded = !expanded }
                        is WeatherData -> WeatherContent(result, answer.summary)
                        is HomeData -> HomeContent(result, expanded) { expanded = !expanded }
                    }
                    if (template.state == "partial") TemplateSupport("Partial data · some information is unavailable")
                }
                Surface(color = templateSage(), shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(if (template.stale(now)) Icons.Outlined.History else Icons.Outlined.Update, null)
                        Text("${template.source} · Updated ${templateTime(template.updatedAt)}${if(template.stale(now)) " · Stale — last known data" else ""}", fontSize = 18.sp)
                    }
                }
                TemplateFooter(onHome = { display.dismiss(); vm.open(PlaybackNavigation.Page.HOME) }, onDismiss = display::dismiss)
            }
        }
}

@Composable private fun wideTemplate(): Boolean = LocalConfiguration.current.let { it.screenWidthDp >= 1000 && it.fontScale <= 1.3f }

@Composable private fun LoadingTemplate(kind: String, summary: String) {
    LinearProgressIndicator(Modifier.fillMaxWidth())
    TemplateSupport(summary)
    when(kind) {
        "journey" -> TemplateCard(Modifier.fillMaxWidth()) { HeroMeasurement("—"); TemplateSupport("Journey duration, route and directions pending") }
        "weather" -> TemplateCard(Modifier.fillMaxWidth()) { HeroMeasurement("—"); TemplateSupport("Temperature and forecast pending") }
        "home_status" -> repeat(4) { TemplateCard(Modifier.fillMaxWidth()) { TemplateSupport("Checking configured entity…"); Text("Reported state pending") } }
    }
}

@Composable private fun JourneyContent(d: JourneyData, config: MapConfiguration?, detail: Boolean, toggle: () -> Unit) {
    val overview: @Composable () -> Unit = {
        TemplateCard(Modifier.fillMaxWidth().heightIn(min = 370.dp)) {
            TemplateHeading(d.destination)
            Text(d.locality, fontSize = 18.sp)
            TemplateSupport("Route overview")
            d.directions.take(2).forEachIndexed { index, step ->
                Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .7f), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${index + 1}", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Text(step, fontSize = 22.sp, lineHeight = 28.sp)
                    }
                }
            }
            if (d.directions.isNotEmpty()) FilledTonalButton(onClick = toggle, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
                Text(if(detail) "Hide directions" else "View all directions", fontSize = 20.sp)
            }
        }
    }
    val summary: @Composable () -> Unit = {
        TemplateCard(Modifier.fillMaxWidth().heightIn(min = 370.dp), highlighted = false) {
            Spacer(Modifier.height(20.dp))
            val duration = when {
                d.durationSeconds >= 86400 -> templateNumber(d.durationSeconds / 86400, 1) to "days"
                d.durationSeconds >= 7200 -> templateNumber(d.durationSeconds / 3600, 1) to "hr"
                else -> templateNumber(ceil(d.durationSeconds / 60)) to "min"
            }
            HeroMeasurement(duration.first, duration.second)
            val imperial = java.util.Locale.getDefault().country in setOf("US", "LR", "MM")
            TemplateSupport("${templateNumber(d.distanceMeters / if(imperial) 1609.344 else 1000.0, 1)} ${if(imperial) "miles" else "km"}")
            TemplateSupport("Arrive ${templateTime(d.departure.plusMillis((d.durationSeconds * 1000).toLong()), d.timezone)}")
            Text(if(d.traffic == "live") "Includes live traffic" else "Estimated time · traffic unavailable", fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (wideTemplate()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1.15f)) { overview() }
        Column(Modifier.weight(1f)) { summary() }
        Column(Modifier.weight(2.15f), verticalArrangement = Arrangement.spacedBy(12.dp)) { JourneyMap(d, config); Text(d.attribution, fontSize = 16.sp) }
    } else {
        TemplateHeading(d.destination); summary(); overview(); JourneyMap(d, config); Text(d.attribution, fontSize = 16.sp)
    }
    if (detail) TemplateCard(Modifier.fillMaxWidth(), highlighted = false) {
        TemplateHeading("All directions")
        d.directions.forEachIndexed { index, step -> TemplateSupport("${index + 1}. $step") }
    }
}

@Composable private fun WeatherContent(d: WeatherData, takeaway: String) {
    TemplateHeading(if(d.location.equals("Home", ignoreCase = true)) "Weather at home" else "Weather · ${d.location}")
    val current: @Composable () -> Unit = {
        TemplateCard(Modifier.fillMaxWidth().heightIn(min = 300.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HeroMeasurement(templateNumber(d.temperature), d.unit)
                WeatherIcon(d.condition)
            }
            TemplateSupport(conditionName(d.condition))
            if (d.high != null || d.low != null) Text("High ${templateNumber(d.high)} · Low ${templateNumber(d.low)} ${d.unit}", fontSize = 22.sp)
        }
    }
    val insight: @Composable () -> Unit = {
        TemplateCard(Modifier.fillMaxWidth().heightIn(min = 300.dp), highlighted = false) {
            TemplateHeading("The outlook")
            TemplateSupport(takeaway)
            Text("Precipitation probability: ${d.probability?.let { "${templateNumber(it)}%" } ?: "unavailable"}", fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val forecast: @Composable () -> Unit = {
        TemplateCard(Modifier.fillMaxWidth().heightIn(min = 300.dp)) {
            Text("Next hours", fontSize = 20.sp, fontWeight = FontWeight.Medium)
            if (d.hours.isEmpty()) TemplateSupport("Hourly forecast unavailable")
            else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                d.hours.forEach { hour ->
                    Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text(DateTimeFormatter.ofPattern("ha", java.util.Locale.getDefault()).withZone(ZoneId.systemDefault()).format(hour.at), fontSize = 20.sp)
                        WeatherIcon(hour.condition, Modifier.size(46.dp).semantics { contentDescription = conditionName(hour.condition) })
                        Text("${templateNumber(hour.temperature)}${if(d.unit == "K") " K" else "°"}", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                        Text(hour.probability?.let { "${templateNumber(it)}%" } ?: "—", fontSize = 18.sp)
                    }
                }
            }
            if(d.hours.any { it.probability == null }) Text("Precipitation probability · — = unavailable", fontSize = 16.sp)
            else if(d.hours.isNotEmpty()) Text("Precipitation probability", fontSize = 16.sp)
        }
    }
    if (wideTemplate()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1.35f)) { current() }
        Column(Modifier.weight(1f)) { insight() }
        Column(Modifier.weight(2f)) { forecast() }
    } else { current(); insight(); forecast() }
}

private fun entityIcon(id: String) = when(id.substringBefore('.')) {
    "light" -> Icons.Outlined.Lightbulb
    "climate" -> Icons.Outlined.Thermostat
    "lock" -> Icons.Outlined.Lock
    "switch", "fan" -> Icons.Outlined.PowerSettingsNew
    else -> Icons.Outlined.Sensors
}

@Composable private fun HomeContent(d: HomeData, detail: Boolean, toggle: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    TemplatePill("Home status", Icons.Outlined.Home)
    TemplateHeading("Your home, at a glance")
    val lights = d.items.filter { it.entityId.startsWith("light.") }
    val on = lights.count { it.state == "on" }
    Text(if(lights.isEmpty()) "${d.items.size} configured items" else "$on ${if(on == 1) "light" else "lights"} on",
        fontSize = 64.sp, lineHeight = 72.sp, fontWeight = FontWeight.SemiBold)
    TemplateSupport(d.scope)
    if (lights.isNotEmpty()) Text("Among ${lights.size} configured lights · ${lights.count { it.state in setOf("unknown", "unavailable") }} unknown or unavailable", fontSize = 20.sp)
    }
    val card: @Composable (HomeItem) -> Unit = { item ->
        TemplateCard(Modifier.fillMaxWidth().heightIn(min = 170.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(entityIcon(item.entityId), null, Modifier.size(36.dp)); Text(item.name, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("${item.state.replace('_', ' ').replaceFirstChar { it.uppercase() }}${item.unit?.let { " $it" } ?: ""}", fontSize = 30.sp)
            Text("Reported state", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (wideTemplate()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        d.items.take(3).forEach { item -> key(item.entityId) { Column(Modifier.weight(1f)) { card(item) } } }
    } else d.items.take(3).forEach { item -> key(item.entityId) { card(item) } }
    d.items.getOrNull(3)?.let { item ->
        OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if(item.state in setOf("unknown", "unavailable")) Icons.Outlined.WarningAmber else entityIcon(item.entityId), null)
                TemplateSupport("${item.name} · ${item.state.replace('_', ' ')}")
            }
        }
    }
    if (d.items.isEmpty()) TemplateSupport("No configured entities")
    if (d.items.size > 4) TextButton(onClick = toggle, modifier = Modifier.heightIn(min = 56.dp)) {
        Text(if(detail) "Show less" else "View all ${d.items.size} configured items", fontSize = 20.sp)
    }
    if (detail) TemplateCard(Modifier.fillMaxWidth(), highlighted = false) {
        TemplateHeading("Configured items")
        d.items.forEach { item -> key(item.entityId) {
            TemplateSupport("${item.name} · ${item.state}${item.unit?.let { " $it" } ?: ""}")
            Text(item.entityId, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
    }
    }
}
@Composable private fun JourneyMap(d:JourneyData,config:MapConfiguration?) {
    if(config==null || d.geometry.isEmpty()) {
        OutlinedCard(Modifier.fillMaxWidth().heightIn(min=370.dp)) { Column(Modifier.padding(24.dp)) { TemplateSupport("Map unavailable"); Text(if(config==null) "Configure a map style in Settings to show imagery." else "The route provider did not return map geometry.") } }; return
    }
    val context=LocalContext.current; val owner=LocalLifecycleOwner.current
    var failed by remember(d,config) { mutableStateOf(false) }; var loaded by remember(d,config) { mutableStateOf(false) }
    val map=remember(d,config) {
        MapLibre.getInstance(context)
        // Enforce the user's allowlist on every style/tile/glyph/sprite request. No HA credentials.
        HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(12,TimeUnit.SECONDS).addInterceptor { chain->
            val u=chain.request().url
            if(!(u.isHttps && "${u.scheme}://${u.host}:${u.port}" in config.origins && u.username.isEmpty() && u.password.isEmpty())) throw java.io.IOException("Map origin not allowed")
            chain.proceed(chain.request())
        }.build())
        MapView(context).apply { onCreate(null); addOnDidFailLoadingMapListener { failed=true } }
    }
    DisposableEffect(map,owner) {
        val observer=LifecycleEventObserver { _,event->when(event) {
            Lifecycle.Event.ON_START->map.onStart(); Lifecycle.Event.ON_RESUME->map.onResume()
            Lifecycle.Event.ON_PAUSE->map.onPause(); Lifecycle.Event.ON_STOP->map.onStop(); else->Unit
        } }
        owner.lifecycle.addObserver(observer)
        if(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) map.onStart()
        if(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) map.onResume()
        onDispose { owner.lifecycle.removeObserver(observer); map.onPause(); map.onStop(); map.onDestroy() }
    }
    LaunchedEffect(map) { delay(15000); if(!loaded) failed=true }
    if(failed) OutlinedCard(Modifier.fillMaxWidth().heightIn(min=370.dp)) { Column(Modifier.padding(24.dp)) { TemplateSupport("Map unavailable"); Text("Your route summary and directions are still available.") } }
    else AndroidView(factory={map.apply { getMapAsync { m->
        m.uiSettings.isAttributionEnabled=false; m.uiSettings.isLogoEnabled=false
        m.uiSettings.isRotateGesturesEnabled=false; m.uiSettings.isTiltGesturesEnabled=false
        m.setStyle(config.style) {
            val points=d.geometry.map { LatLng(it.second,it.first) }
            m.addPolyline(PolylineOptions().addAll(points).color(android.graphics.Color.rgb(70,100,76)).width(5f))
            m.addMarker(MarkerOptions().position(points.last()).title(d.destination))
            map.post { if(points.distinct().size>=2) m.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.fromLatLngs(points),40)) else m.moveCamera(CameraUpdateFactory.newLatLngZoom(points.last(),13.0)) }
            loaded=true
        }
    } }},modifier=Modifier.fillMaxWidth().height(370.dp))
    Text(config.attribution,fontSize=14.sp)
}
