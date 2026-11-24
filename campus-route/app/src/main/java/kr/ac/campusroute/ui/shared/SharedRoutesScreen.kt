package kr.ac.campusroute.ui.shared

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kr.ac.campusroute.sharing.*
import kr.ac.campusroute.ui.*
import kr.ac.campusroute.ui.map.*
import kotlin.math.ceil

@Composable
fun SharedRoutesScreen(model: CampusViewModel, state: CampusState, location: DeviceLocationBinding) {
    val context=LocalContext.current
    val store=remember(context,model.campus) { SharedRouteStore(context,model.campus) }
    var routes by remember { mutableStateOf(store.read()) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<SharedRoute?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) scope.launch {
            busy=true; error=null
            try {
                val received=withContext(Dispatchers.IO) {
                    val bytes=context.contentResolver.openInputStream(uri)?.use { input ->
                        val output=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
                        while(true) { val size=input.read(buffer); if(size<0) break; require(output.size()+size<=256*1024) { "경로 파일이 너무 큽니다. 최대 256KB입니다." }; output.write(buffer,0,size) }
                        output.toByteArray()
                    } ?: throw IllegalStateException("파일을 열 수 없습니다.")
                    store.import(bytes.toString(Charsets.UTF_8))
                }
                routes=store.read(); selectedId=received.id; model.clearMessage()
            } catch(e: CancellationException) { throw e }
              catch(e: Exception) { error=if(e is org.json.JSONException) "공유 경로 JSON 형식이 올바르지 않습니다." else e.message ?: "경로 파일을 가져오지 못했습니다." }
            finally { busy=false }
        }
    }
    val selected=routes.firstOrNull { it.id==selectedId }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("공유 경로",style=MaterialTheme.typography.headlineSmall)
            TextButton(onClick={picker.launch(arrayOf("application/json","text/plain","application/octet-stream"))},enabled=!busy) { Text(if(busy) "가져오는 중…" else "파일 가져오기") }
        }
        Text("서버 경로 연동 준비 중 · 기기에 저장한 공유 경로를 볼 수 있어요.", Modifier.padding(horizontal=16.dp), style=MaterialTheme.typography.bodySmall)
        error?.let { Text(it,Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.error) }
        if(selected==null) {
            if(routes.isEmpty()) Column(Modifier.padding(24.dp)) {
                Text("아직 받은 경로가 없어요",style=MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Text("전달받은 캠퍼스 경로 JSON 파일을 가져오면 이곳에 보관됩니다. 목록에서 선택해 초록색 경로를 확인하고 안내를 시작할 수 있어요.")
                Spacer(Modifier.height(12.dp))
                Text("자동 수신·공유 링크는 아직 지원하지 않습니다. 가져온 경로만 기기에 보관하며 내 이동 위치는 저장하지 않습니다.",style=MaterialTheme.typography.bodySmall)
            }
            LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                items(routes,key={it.id}) { item ->
                    Card(onClick={ selectedId=item.id; model.clearMessage() },modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(item.title,style=MaterialTheme.typography.titleMedium)
                            Text("공유받은 경로 · ${ceil(item.resolve(model.campus).geometricDistance).toInt()}m",color=Color(0xFF16834A))
                            Text(model.campus.destinations.first { it.id==item.destinationId }.name,style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } else {
            val route=remember(selected) { selected.resolve(model.campus) }
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                TextButton(onClick={selectedId=null; model.clearMessage()}) { Text("목록으로") }
                TextButton(onClick={pendingDelete=selected},enabled=!busy) { Text("삭제") }
            }
            Text(selected.title,Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.titleLarge)
            Text("공유받은 경로 · ${ceil(route.geometricDistance).toInt()}m",Modifier.padding(horizontal=16.dp),color=Color(0xFF16834A))
            key(selected.id) {
                CampusMapPanel(model.campus,route,selected.destinationId,location.state,Modifier.weight(1f).fillMaxWidth(),{},location.requestPermission,location.openSettings,location.denied,location.headingDegrees,initiallyFollowing=false)
            }
            Column(Modifier.padding(16.dp)) {
                Text("공유 경로의 시작점 30m 이내에서 안내를 시작하세요.",style=MaterialTheme.typography.bodySmall)
                state.message?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                Button(onClick={model.startShared(selected)},modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF16834A))) { Text("이 공유 경로로 안내 시작") }
            }
        }
    }
    pendingDelete?.let { item -> AlertDialog(onDismissRequest={pendingDelete=null},title={Text("공유 경로 삭제")},text={Text("‘${item.title}’을 기기에서 삭제할까요?")},confirmButton={TextButton(onClick={
        scope.launch {
            busy=true
            try { withContext(Dispatchers.IO) { store.delete(item.id) }; routes=store.read(); selectedId=null; model.clearMessage() }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { error="경로를 삭제하지 못했습니다." }
            finally { busy=false; pendingDelete=null }
        }
    },enabled=!busy) { Text("삭제") }},dismissButton={TextButton(onClick={pendingDelete=null}) { Text("취소") }}) }
}
