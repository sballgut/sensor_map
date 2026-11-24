package kr.ac.campusroute.ui.map

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kr.ac.campusroute.ui.CampusViewModel
import org.json.JSONArray

@Composable
fun DestinationSearchScreen(model: CampusViewModel, onBack: () -> Unit, onSelected: (String) -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("destination_search", Context.MODE_PRIVATE) }
    val destinations = model.campus.destinations
    var recent by remember {
        mutableStateOf(runCatching {
            val array = JSONArray(preferences.getString("recent_ids", "[]"))
            List(array.length()) { array.getString(it) }.distinct()
                .filter { id -> destinations.any { it.id == id } }.take(20)
        }.getOrDefault(emptyList()))
    }
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun save(ids: List<String>) {
        recent = ids
        preferences.edit().putString("recent_ids", JSONArray(ids).toString()).apply()
    }
    fun close() { keyboard?.hide(); onBack() }
    BackHandler { close() }
    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
    val searchText = query.trim()
    val results = if (searchText.isNotEmpty()) {
        destinations.filter { it.name.contains(searchText, ignoreCase=true) }
    } else if (tab == 0) {
        recent.mapNotNull { id -> destinations.firstOrNull { it.id == id } }
    } else destinations.sortedBy { it.name }

    Surface(color=MaterialTheme.colorScheme.surface, modifier=Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={ close() }, modifier=Modifier.semantics { contentDescription="지도로 돌아가기" }) {
                    Text("‹", style=MaterialTheme.typography.headlineLarge)
                }
                OutlinedTextField(
                    value=query, onValueChange={ query=it },
                    placeholder={ Text("어디로 갈까요?") }, singleLine=true,
                    modifier=Modifier.weight(1f).focusRequester(focus),
                    shape=RoundedCornerShape(28.dp),
                    keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),
                    keyboardActions=KeyboardActions(onSearch={ keyboard?.hide() }),
                    trailingIcon={ if (query.isNotEmpty()) IconButton(onClick={ query="" },
                        modifier=Modifier.semantics { contentDescription="검색어 지우기" }) { Text("×") } }
                )
            }
            TabRow(selectedTabIndex=tab) {
                Tab(selected=tab==0, onClick={ tab=0; query="" }, text={ Text("최근 검색") })
                Tab(selected=tab==1, onClick={ tab=1; query="" }, text={ Text("전체 건물") })
            }
            Text(if (searchText.isNotEmpty()) "검색 결과 ${results.size}개" else if (tab==0) "최근 선택한 목적지" else "전체 건물",
                Modifier.padding(20.dp), style=MaterialTheme.typography.titleSmall)
            if (results.isEmpty()) {
                Text(if (searchText.isNotEmpty()) "검색 결과가 없어요. 건물 이름을 다시 입력해 주세요."
                    else "최근 검색한 목적지가 없어요. 건물을 검색하거나 전체 건물에서 골라 주세요.",
                    Modifier.padding(horizontal=20.dp), color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.weight(1f)) {
                items(results, key={ it.id }) { destination ->
                    Row(Modifier.fillMaxWidth().heightIn(min=88.dp).padding(horizontal=20.dp),
                        verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable {
                            save((listOf(destination.id) + recent.filter { it != destination.id }).take(20))
                            keyboard?.hide()
                            onSelected(destination.id)
                        }.padding(vertical=20.dp)) {
                            Text(destination.name, style=MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text("개신캠퍼스 · 건물 인근 보행로", style=MaterialTheme.typography.bodySmall,
                                color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (tab==0 && searchText.isEmpty()) {
                            IconButton(onClick={ save(recent.filter { it != destination.id }) },
                                modifier=Modifier.semantics { contentDescription="${destination.name} 최근 검색 삭제" }) {
                                Text("×", style=MaterialTheme.typography.headlineSmall)
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal=20.dp))
                }
            }
        }
    }
}
