package dev.pixelhome

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AssistantSheet(vm: HubViewModel) {
    val display=vm.assistant
    val answer=display.answer ?: return
    if(answer.template!=null) return
    val uri=LocalUriHandler.current
    var expanded by remember(answer.id) { mutableStateOf(answer.layout!="compact") }
    var link by remember(answer.id) { mutableStateOf<String?>(null) }
    val sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)
    ModalBottomSheet(onDismissRequest=display::dismiss, sheetMaxWidth=900.dp,
        sheetState=sheetState) {
        LaunchedEffect(answer.id) {
            snapshotFlow { sheetState.currentValue }.first { it != SheetValue.Hidden }
            withFrameNanos { }; withFrameNanos { }; display.rendered(answer)
        }
        Column(Modifier.fillMaxWidth().heightIn(max=560.dp).verticalScroll(rememberScrollState()).padding(horizontal=28.dp).padding(bottom=32.dp), verticalArrangement=Arrangement.spacedBy(18.dp)) {
            Text(if(vm.demo) "ASSISTANT PREVIEW · SAMPLE ANSWER" else "HOME ASSISTANT",style=MaterialTheme.typography.labelLarge)
            Text(answer.title,style=MaterialTheme.typography.headlineMedium)
            Text(answer.summary,style=MaterialTheme.typography.titleLarge)
            if(!expanded) TextButton(onClick={expanded=true}) { Text("Read more") }
            if(expanded) for(i in 0 until answer.blocks.length()) {
                val b=answer.blocks.getJSONObject(i)
                if(b.has("heading")) Text(b.getString("heading"),style=MaterialTheme.typography.titleMedium)
                when(b.getString("type")) {
                    "paragraph" -> Text(b.getString("text"),style=MaterialTheme.typography.bodyLarge)
                    "bullets", "steps" -> { val a=b.getJSONArray("items"); for(j in 0 until a.length()) Text("${if(b.getString("type")=="steps") "${j+1}." else "•"} ${a.getString(j)}",style=MaterialTheme.typography.bodyLarge) }
                    "sources" -> { val a=b.getJSONArray("items"); for(j in 0 until a.length()) { val s=a.getJSONObject(j)
                        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                            TextButton(onClick={link=s.getString("url")}) { Text(s.getString("title")) }
                            Text(java.net.URI(s.getString("url")).host,style=MaterialTheme.typography.labelMedium)
                            if(s.has("excerpt"))Text(s.getString("excerpt"))
                        } }
                    } }
                    "table" -> { val cols=b.getJSONArray("columns"); val rows=b.getJSONArray("rows")
                        Column(Modifier.horizontalScroll(rememberScrollState())) { TableRow(cols,true); for(j in 0 until rows.length()) { HorizontalDivider(); TableRow(rows.getJSONArray(j),false) } }
                    }
                    "status" -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(b.getString("state").replaceFirstChar { it.uppercase() },style=MaterialTheme.typography.labelLarge)
                        Text(b.getString("label"),style=MaterialTheme.typography.titleMedium)
                        if(b.has("detail"))Text(b.getString("detail"))
                    } }
                }
            }
            FilledTonalButton(onClick=display::dismiss,modifier=Modifier.heightIn(min=56.dp)) { Text("Done") }
        }
    }
    link?.let { target -> AlertDialog(onDismissRequest={link=null},title={Text("Open source in browser?")},text={Text(target)},
        confirmButton={TextButton(onClick={link=null; if(display.mayInteract())try { uri.openUri(target) } catch(_:Exception) { vm.assistantNotice() }}){Text("Open source")}},
        dismissButton={TextButton(onClick={link=null}){Text("Cancel")}}) }
}
@Composable private fun TableRow(cells: JSONArray, header:Boolean) {
    Row { for(i in 0 until cells.length()) Text(cells.getString(i),Modifier.width(180.dp).padding(12.dp),style=if(header)MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge) }
}
