package dev.pixelhome

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Fixed overview, shared by the unlocked Home page and read-only dock presentation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun HomeOverview(vm: HubViewModel, clock: LocalDateTime, showMusic: Boolean=true) {
    val readOnly=LocalDockReadOnly.current
    var details by remember { mutableStateOf<String?>(null) }
    val scale=LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp)) {
        val plan=homeOverviewPlan(maxWidth.value,maxHeight.value,scale,vm.config.favorites.size,showMusic)
        Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(vm.config.title,fontSize=30.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(clock.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),style=MaterialTheme.typography.bodyLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                if(plan.visibleFavorites<vm.config.favorites.size) TextButton(onClick={details="favorites"},modifier=Modifier.heightIn(min=56.dp)) {
                    Text("All ${vm.config.favorites.size}",maxLines=1)
                }
                Text(clock.format(DateTimeFormatter.ofPattern("h:mm")),fontSize=40.sp,fontWeight=FontWeight.Light,maxLines=1,softWrap=false)
            }
            if(plan.landscape) Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                OverviewDevices(vm,plan,{details=it},Modifier.weight(1.45f).fillMaxHeight())
                if(showMusic) OverviewPlayer(vm,Modifier.weight(1f).fillMaxHeight())
            } else {
                OverviewDevices(vm,plan,{details=it},Modifier.weight(1f).fillMaxWidth())
                if(showMusic) OverviewPlayer(vm,Modifier.fillMaxWidth().height(plan.musicHeight.dp),horizontal=true)
            }
        }
    }
    if(details != null) ModalBottomSheet(onDismissRequest={details=null}) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(when(details) { "favorites" -> "All favorites"; "scenes" -> "All scenes"; else -> "Device details" },style=MaterialTheme.typography.headlineSmall,modifier=Modifier.weight(1f))
                TextButton(onClick={details=null},modifier=Modifier.heightIn(min=56.dp)) { Text("Close details") }
            }
            if(readOnly) Text("Tablet locked · unlock to use controls",style=MaterialTheme.typography.bodyLarge)
            when(details) {
                "favorites" -> DeviceGrid(vm,true)
                "scenes" -> vm.config.scenes.forEach { id ->
                    OutlinedButton(onClick={vm.command(id,"turn_on")},enabled=!readOnly && vm.canControl && vm.entities[id]?.available==true,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text(vm.entities[id]?.name ?: id) }
                }
                else -> DeviceTile(vm,details!!,Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
@Composable private fun OverviewDevices(vm: HubViewModel,plan: HomeOverviewPlan,open: (String)->Unit,modifier: Modifier) {
    val visible=vm.config.favorites.take(plan.visibleFavorites)
    Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Favorites",style=MaterialTheme.typography.labelLarge)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(visible.isEmpty()) Surface(Modifier.fillMaxSize(),shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceVariant) {
                Box(Modifier.padding(24.dp),contentAlignment=Alignment.Center) {Text("Choose favorite devices in Home Assistant",style=MaterialTheme.typography.titleLarge)}
            }
            visible.chunked(plan.columns).forEach { row ->
                Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    row.forEach { id -> OverviewDevice(vm,id,{open(id)},Modifier.weight(1f).fillMaxHeight()) }
                    repeat(plan.columns-row.size) {Spacer(Modifier.weight(1f))}
                }
            }
        }
        if(vm.config.scenes.isNotEmpty()) {
            val readOnly=LocalDockReadOnly.current
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                vm.config.scenes.take(2).forEach { id ->
                    OutlinedButton(onClick={vm.command(id,"turn_on")},enabled=!readOnly && vm.canControl && vm.entities[id]?.available==true,
                        modifier=Modifier.weight(1f).heightIn(min=56.dp)) {
                        Icon(Icons.Outlined.AutoAwesome,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp))
                        Text(vm.entities[id]?.name ?: id,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                }
                if(vm.config.scenes.size>2) OutlinedButton(onClick={open("scenes")},modifier=Modifier.heightIn(min=56.dp)) {Text("+${vm.config.scenes.size-2}")}
            }
        }
    }
}
@Composable private fun OverviewDevice(vm: HubViewModel,id: String,open: ()->Unit,modifier: Modifier) {
    val entity=vm.entities[id]
    val domain=id.substringBefore('.')
    val enabled=!LocalDockReadOnly.current && vm.canControl && entity?.available==true
    val status=if(entity?.available != true) "Unavailable" else {
        val state=entity.state.replaceFirstChar {it.uppercase()}
        val inside=entity.attrs.optDouble("current_temperature",Double.NaN)
        if(domain=="climate" && inside.isFinite()) "$state · Inside ${inside.toInt()}°" else state
    }
    val icon=when(domain) {"light" -> Icons.Outlined.Lightbulb;"climate" -> Icons.Outlined.Thermostat;"lock" -> Icons.Outlined.Lock;else -> Icons.Outlined.PowerSettingsNew}
    Surface(modifier,shape=MaterialTheme.shapes.large,color=if(entity?.state=="on") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.clickable(onClickLabel="Details for ${entity?.name ?: id}",onClick=open).padding(16.dp),verticalArrangement=Arrangement.SpaceBetween) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Icon(icon,null,Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(entity?.name ?: id,style=MaterialTheme.typography.titleLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(status,style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                Icon(Icons.Outlined.ChevronRight,"Device details",Modifier.size(20.dp))
            }
            Row(Modifier.fillMaxWidth().heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                when(domain) {
                    "light","switch","fan" -> {
                        Switch(entity?.state=="on",{vm.command(id,if(it) "turn_on" else "turn_off")},enabled=enabled)
                        if(domain=="light" && entity?.attrs?.has("brightness")==true) Text("${(entity.attrs.optDouble("brightness",0.0)*100/255).toInt()}%",style=MaterialTheme.typography.titleLarge)
                    }
                    "climate" -> {
                        val target=entity?.attrs?.optDouble("temperature",Double.NaN) ?: Double.NaN
                        Text(if(target.isFinite()) "${target.toInt()}°" else "—",fontSize=28.sp)
                        Row {
                            listOf(-1,1).forEach {direction ->
                                IconButton(onClick={
                                    val step=entity!!.attrs.optDouble("target_temp_step",1.0)
                                    val min=entity.attrs.optDouble("min_temp",7.0);val max=entity.attrs.optDouble("max_temp",35.0)
                                    vm.command(id,"set_temperature",JSONObject().put("temperature",(target+direction*step).coerceIn(min,max)))
                                },enabled=enabled && target.isFinite() && entity?.supports(1)==true,modifier=Modifier.size(56.dp)) {
                                    Icon(if(direction<0) Icons.Outlined.Remove else Icons.Outlined.Add,if(direction<0) "Lower temperature" else "Raise temperature")
                                }
                            }
                        }
                    }
                    else -> TextButton(onClick=open,modifier=Modifier.heightIn(min=56.dp)) {Text("Details")}
                }
            }
        }
    }
}
@Composable private fun OverviewPlayer(vm: HubViewModel,modifier: Modifier,horizontal: Boolean=false) {
    Surface(modifier,shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.primaryContainer) {
        if(horizontal) Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {OverviewTrack(vm);SpeakerPicker(vm)}
            Column(Modifier.weight(1.1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {Transport(vm);Volume(vm)}
        } else Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.SpaceBetween) {
            OverviewTrack(vm)
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {Transport(vm);SpeakerPicker(vm);Volume(vm)}
        }
    }
}
@Composable private fun OverviewTrack(vm: HubViewModel) {
    val readOnly=LocalDockReadOnly.current
    val title=vm.player?.attrs?.optString("media_title")?.takeIf {it.isNotBlank()} ?: "Nothing playing"
    val artist=vm.player?.attrs?.optString("media_artist")?.takeIf {it.isNotBlank()} ?: "Select music to begin"
    if(LocalDensity.current.fontScale>1.3f) {
        Column(Modifier.fillMaxWidth().clickable(enabled=!readOnly,onClickLabel="Open music") { vm.open(PlaybackNavigation.Page.MUSIC) },verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(title,fontSize=22.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text("$artist · ${vm.player?.state ?: "unavailable"}",style=MaterialTheme.typography.bodyLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        return
    }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("NOW PLAYING",style=MaterialTheme.typography.labelLarge)
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            AlbumArt(vm,Modifier.size(72.dp).clickable(enabled=!readOnly,onClickLabel="Open music") {vm.open(PlaybackNavigation.Page.MUSIC)})
            Column(Modifier.weight(1f)) {
                Text(vm.player?.attrs?.optString("media_title")?.takeIf {it.isNotBlank()} ?: "Nothing playing",fontSize=22.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(vm.player?.attrs?.optString("media_artist")?.takeIf {it.isNotBlank()} ?: "Select music to begin",style=MaterialTheme.typography.bodyLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
        }
        Text(vm.player?.state ?: "No player configured",style=MaterialTheme.typography.labelMedium,maxLines=1)
    }
}
