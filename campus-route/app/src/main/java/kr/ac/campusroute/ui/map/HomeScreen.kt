package kr.ac.campusroute.ui.map

import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.sensorae.ui.d1.DestinationHomeView
import com.example.sensorae.ui.d1.DestinationSelectionTarget
import com.example.sensorae.ui.d1.Destination as D1Destination
import kr.ac.campusroute.ui.*

/** D-1's Java search/detail UI with D-2 Compose/OpenGL map attached through its public contract. */
@Composable
fun HomeScreen(model: CampusViewModel, state: CampusState, location: DeviceLocationBinding) {
    if(state.navigating) {
        Column(Modifier.fillMaxSize()) {
            CampusMapPanel(model.campus,state.route,state.destination,location.state,Modifier.weight(1f).fillMaxWidth(),
                onDestinationSelected={},onRequestLocation=location.requestPermission,onOpenLocationSettings=location.openSettings,
                permissionDenied=location.denied,headingDegrees=location.headingDegrees)
            Text(state.message ?: "실제 위치로 안내 중",Modifier.padding(12.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                Button(onClick=model::toggleMap) { Text("3D 안내") }
                TextButton(onClick=model::end) { Text("안내 종료") }
            }
        }
        return
    }
    val latest by rememberUpdatedState(state)
    val binding by rememberUpdatedState(location)
    val saved = rememberSaveable { Bundle() }
    val theme=MaterialTheme.colorScheme
    val catalog=remember(model.campus) { model.campus.destinations.map { d ->
        val n=model.campus.byId.getValue(d.nodeId)
        D1Destination(d.id,d.name,"건물 인근 보행로","OSM 지도 기반 목적지입니다. 실제 건물 출입구와 통행 가능 여부를 현장에서 확인하세요.",n.latitude,n.longitude,false,listOf(d.name.substringBefore(" ·")))
    } }
    Column(Modifier.fillMaxSize().imePadding()) {
        AndroidView(modifier=Modifier.weight(1f).fillMaxWidth(), factory={ context ->
            DestinationHomeView(context) {}.apply host@ {
                setDestinations(catalog)
                restoreSelection(saved)
                state.destination?.let { selectDestination(it) }
                setRouteAction { model.requestStart() }
                val map=ComposeView(context).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                    setContent {
                        MaterialTheme(colorScheme=theme) {
                            CampusMapPanel(model.campus,latest.route,latest.destination,binding.state,Modifier.fillMaxSize(),
                                onDestinationSelected={ id -> if(!latest.navigating) this@host.selectDestination(id) },
                                onRequestLocation=binding.requestPermission,onOpenLocationSettings=binding.openSettings,permissionDenied=binding.denied,headingDegrees=binding.headingDegrees)
                        }
                    }
                }
                attachMap(map,object: DestinationSelectionTarget {
                    override fun onDestinationSelected(destination: D1Destination) { model.select(destination.id); saveSelection(saved) }
                    override fun onSelectionCleared() { model.select(null); saveSelection(saved) }
                })
            }
        }, update={ view ->
            view.setLocationStatus(state.message ?: locationMessage(location.state,location.denied))
            view.setRouteStatus(if(state.planning) "경로 계산 중…" else if(state.navigating) "안내 진행 중" else "현재 위치에서 길찾기",!state.planning && !state.navigating)
            view.saveSelection(saved)
        })
        if(state.navigating) Row(Modifier.fillMaxWidth().padding(8.dp),horizontalArrangement=Arrangement.SpaceEvenly) {
            Button(onClick=model::toggleMap) { Text("3D 안내") }
            TextButton(onClick=model::end) { Text("안내 종료") }
        }
    }
}
