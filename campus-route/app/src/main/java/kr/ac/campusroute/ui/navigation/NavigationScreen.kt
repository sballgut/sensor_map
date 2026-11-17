package kr.ac.campusroute.ui.navigation

import android.opengl.GLSurfaceView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import kr.ac.campusroute.rendering.CampusRenderer
import kr.ac.campusroute.domain.model.RouteOrigin
import androidx.compose.ui.graphics.Color
import kr.ac.campusroute.routing.routePose
import kr.ac.campusroute.location.projectWgs84
import kr.ac.campusroute.ui.map.locationMessage
import kr.ac.campusroute.ui.*
import kr.ac.campusroute.ui.map.MapAttribution
import kotlin.math.ceil

@Composable
fun NavigationScreen(model: CampusViewModel, state: CampusState, headingDegrees: Double? = null) {
    val route = state.route ?: return
    val routePose = routePose(route, state.travelled)
    val fix=state.location.fix
    val usable=state.location.canFollow && (fix?.accuracyMetres ?: Double.MAX_VALUE)<=30
    val point=fix?.let { projectWgs84(it.latitude,it.longitude,model.campus.originLatitude,model.campus.originLongitude) }
    val pose=routePose.copy(position=point ?: routePose.position,arrived=state.arrived,
        heading=(headingDegrees ?: fix?.bearingDegrees)?.let { Math.toRadians(it) } ?: routePose.heading)
    val context = LocalContext.current
    val renderer = remember(route) { CampusRenderer(model.campus, route) }
    val surface = remember(route, context) { GLSurfaceView(context).apply { setEGLContextClientVersion(2); setEGLConfigChooser(8, 8, 8, 8, 24, 0); preserveEGLContextOnPause = true; setRenderer(renderer) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(surface, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> surface.onResume()
                Lifecycle.Event.ON_PAUSE -> surface.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) surface.onResume()
        onDispose { lifecycle.removeObserver(observer); surface.onPause() }
    }
    SideEffect {
        renderer.target = pose
        renderer.showUserMarker = point != null
        renderer.userMarkerReliable = usable
        renderer.userAccuracyMetres = fix?.accuracyMetres
        renderer.userHeadingKnown = state.location.canFollow && (headingDegrees != null || fix?.bearingDegrees != null)
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { surface }, modifier = Modifier.fillMaxSize())
        Card(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(if(route.origin == RouteOrigin.SHARED) "3D 보행 안내 · 공유받은 경로" else "3D 보행 안내 · 기기 위치", style = MaterialTheme.typography.labelLarge, color = if(route.origin == RouteOrigin.SHARED) Color(0xFF16834A) else MaterialTheme.colorScheme.primary)
                Text(if (pose.arrived) "목적지 인근 도착" else if(!usable) "정확한 위치 수신 대기" else if(state.offRoute) "경로 이탈" else "${ceil(pose.nextDistance).toInt()} m  ${pose.instruction}", style = MaterialTheme.typography.headlineSmall)
                Text("${model.campus.destinations.first { it.id == state.destination }.name} · 남은 거리 ${ceil(pose.remaining).toInt()} m")
                Text(locationMessage(state.location), style = MaterialTheme.typography.bodySmall)
                Text("OSM 보행로 · 출입구/통행 현장 확인 전", style = MaterialTheme.typography.bodySmall)
                MapAttribution()
            }
        }
        Card(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(12.dp)) {
                LinearProgressIndicator(progress = { if (route.geometricDistance == 0.0) 1f else (state.travelled / route.geometricDistance).toFloat() }, modifier = Modifier.fillMaxWidth())
                Text(state.message ?: "실제 이동에 따라 안내가 갱신됩니다.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = model::start, enabled = !state.planning && !state.arrived) { Text("현재 위치에서 다시 찾기") }
                    TextButton(onClick = model::toggleMap) { Text("2D 지도") }
                }
                Button(onClick = model::end, modifier = Modifier.fillMaxWidth()) { Text("안내 종료") }
            }
        }
    }
}
