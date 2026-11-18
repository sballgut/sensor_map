package kr.ac.campusroute.ui.navigation

import android.opengl.GLSurfaceView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clipToBounds
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
    val renderer = remember(route) { CampusRenderer(model.campus, route).apply { target=pose; showUserMarker=point!=null; userMarkerReliable=usable } }
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val headerLimit=maxHeight*.32f
        val footerLimit=maxHeight*.30f
        Column(Modifier.fillMaxSize()) {
            Surface(tonalElevation=2.dp) {
                Column(Modifier.fillMaxWidth().heightIn(max=headerLimit).verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=8.dp)) {
                    Text(if(route.origin == RouteOrigin.SHARED) "공유받은 경로 안내" else "현재 위치로 안내 중", style=MaterialTheme.typography.labelLarge,
                        color=if(route.origin == RouteOrigin.SHARED) Color(0xFF16834A) else MaterialTheme.colorScheme.primary)
                    Text(if(pose.arrived) "목적지 인근 도착" else if(!usable) "정확한 위치 수신 대기" else if(state.offRoute) "경로 이탈" else "${ceil(pose.nextDistance).toInt()}m · ${pose.instruction}",style=MaterialTheme.typography.titleLarge)
                    Text("${model.campus.destinations.first { it.id == state.destination }.name} · 남은 거리 ${ceil(pose.remaining).toInt()}m",style=MaterialTheme.typography.bodyMedium)
                    Text(locationMessage(state.location),style=MaterialTheme.typography.bodySmall)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                AndroidView(factory={surface},modifier=Modifier.fillMaxSize())
                Box(Modifier.align(Alignment.BottomStart)) { MapAttribution() }
            }
            Surface(tonalElevation=2.dp) {
                Column(Modifier.fillMaxWidth().heightIn(max=footerLimit).verticalScroll(rememberScrollState()).padding(horizontal=12.dp,vertical=8.dp)) {
                    LinearProgressIndicator(progress={if(route.geometricDistance==0.0) 1f else (state.travelled/route.geometricDistance).toFloat()},modifier=Modifier.fillMaxWidth())
                    Text(state.message ?: if(!usable) "위치 갱신을 기다리는 동안 이동 표시를 멈춥니다." else "수신한 위치와 이동 표시를 함께 갱신합니다.",Modifier.padding(top=6.dp),style=MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick=model::start,enabled=!state.planning && !state.arrived,modifier=Modifier.weight(1f).heightIn(min=48.dp)) { Text("다시 찾기",maxLines=1) }
                        TextButton(onClick=model::toggleMap,modifier=Modifier.weight(1f).heightIn(min=48.dp)) { Text("2D 지도",maxLines=1) }
                        TextButton(onClick=model::end,modifier=Modifier.weight(1f).heightIn(min=48.dp)) { Text("안내 종료",maxLines=1) }
                    }
                }
            }
        }
    }
}
