package kr.ac.campusroute.ui.map

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kr.ac.campusroute.ui.*

/** The user confirms indoor departure; footprint matching is only a suggestion. */
@Composable
fun DepartureDialog(model: CampusViewModel, state: CampusState) {
    if(!state.departureDialog) return
    var indoor by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest=model::dismissDeparture,
        title={Text(if(indoor) "현재 있는 건물 선택" else "어디에서 출발하나요?")},
        text={
            Column {
                Text("건물 안에서는 먼저 출구 밖으로 이동한 뒤, 실외 위치로 다른 건물까지 안내합니다. 실내 통로·출입구는 아직 지도에 연결되지 않았습니다.")
                state.message?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
                if(indoor) {
                    OutlinedTextField(query,{query=it},label={Text("건물 이름 검색")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    val buildings=model.campus.buildings.filter { it.name.isNotBlank() && it.footprint.size>=3 && it.name.contains(query,ignoreCase=true) }.sortedBy { it.name }
                    if(buildings.isEmpty()) Text("검색 결과가 없습니다.")
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max=260.dp)) {
                        items(buildings,key={it.id}) { b ->
                            TextButton(onClick={model.selectIndoorBuilding(b.id)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text(b.name) }
                        }
                    }
                } else {
                    Button(onClick=model::start,modifier=Modifier.fillMaxWidth()) { Text("건물 밖 · 현재 위치로 출발") }
                    OutlinedButton(onClick={indoor=true},modifier=Modifier.fillMaxWidth()) { Text("건물 안 · 현재 건물 선택") }
                }
            }
        },confirmButton={TextButton(onClick=model::dismissDeparture) { Text("취소") }})
}
