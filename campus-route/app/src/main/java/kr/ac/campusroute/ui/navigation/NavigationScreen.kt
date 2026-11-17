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
import kr.ac.campusroute.routing.routePose
import kr.ac.campusroute.ui.*
import kr.ac.campusroute.ui.map.MapAttribution
import kotlin.math.ceil

@Composable
fun NavigationScreen(model: CampusViewModel, state: CampusState) {
    val route = state.route ?: return
    val pose = routePose(route, state.travelled)
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
    SideEffect { renderer.target = pose }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { surface }, modifier = Modifier.fillMaxSize())
        Card(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("3D 보행 안내 · 가상 이동 데모", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(if (pose.arrived) "목적지 도착" else "${ceil(pose.nextDistance).toInt()} m  ${pose.instruction}", style = MaterialTheme.typography.headlineSmall)
                Text("${model.campus.destinations.first { it.id == state.destination }.name} · 남은 거리 ${ceil(pose.remaining).toInt()} m")
                Text("개신캠퍼스 · GPS/BLE 미사용 · 현장 확인 전", style = MaterialTheme.typography.bodySmall)
                MapAttribution()
            }
        }
        Card(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(12.dp)) {
                LinearProgressIndicator(progress = { if (route.geometricDistance == 0.0) 1f else (state.travelled / route.geometricDistance).toFloat() }, modifier = Modifier.fillMaxWidth())
                Text(if (pose.arrived) "잠시 후 2D 지도로 돌아갑니다." else "${if (state.playing) "보행 시뮬레이션" else "일시 정지"} · ${state.speed} m/s", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = model::togglePlayback, enabled = !pose.arrived) { Text(if (state.playing) "일시 정지" else "재생") }
                    TextButton(onClick = model::toggleSpeed, enabled = !pose.arrived) { Text(if (state.speed == 1.4) "빠르게" else "보행 속도") }
                    TextButton(onClick = model::toggleMap) { Text("2D 지도") }
                }
                Button(onClick = model::end, modifier = Modifier.fillMaxWidth()) { Text("안내 종료") }
            }
        }
    }
}
