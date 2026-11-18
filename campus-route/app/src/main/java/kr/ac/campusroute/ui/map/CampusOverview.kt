package kr.ac.campusroute.ui.map

import android.opengl.GLSurfaceView
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import kr.ac.campusroute.domain.model.*
import kr.ac.campusroute.rendering.*
import kr.ac.campusroute.routing.routePose
import kotlin.math.max

/** Real GL overview with an elevated perspective camera. Destination selection remains in the list. */
@Composable
fun CampusOverview(campus: Campus, route: Route?, current: Point?, selected: String?, center: Point, zoom: Float, pan: Offset, onTransform: (Offset, Float) -> Unit, accuracy: Double? = null, reliable: Boolean = true, headingDegrees: Double? = null) {
    val context = LocalContext.current
    val destination = campus.byId.getValue(campus.destinations.firstOrNull { it.id == selected }?.nodeId ?: campus.startNodeId)
    val displayRoute = remember(route, destination) { route ?: Route(listOf(destination), 0.0) }
    val renderer = remember(campus, displayRoute) { CampusRenderer(campus, displayRoute) }
    val surface = remember(renderer, context) {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(2); setEGLConfigChooser(8, 8, 8, 8, 24, 0)
            preserveEGLContextOnPause = true
            renderer.overview = OverviewCamera(center, zoom)
            renderer.showDestination = selected != null
            renderer.showUserMarker = current != null
            renderer.userMarkerReliable = reliable
            renderer.userHeadingKnown = reliable && headingDegrees != null
            renderer.userAccuracyMetres = accuracy
            renderer.target = routePose(displayRoute, 0.0).copy(position = current ?: destination.position,heading=headingDegrees?.let { Math.toRadians(it) } ?: 0.0)
            setRenderer(renderer)
        }
    }
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
    val transform by rememberUpdatedState(onTransform)
    AndroidView(factory = { surface }, modifier = Modifier.fillMaxSize().clipToBounds()
        .pointerInput(Unit) { detectTransformGestures { _, move, scale, _ -> transform(move, scale) } },
        update = { view ->
            val width = view.width.coerceAtLeast(1).toFloat()
            val height = view.height.coerceAtLeast(1).toFloat()
            val metresPerPixel = max(campus.mapHeight, campus.mapWidth * height / width) * 1.05 / (zoom * height)
            renderer.overview = OverviewCamera(Point(center.x - pan.x * metresPerPixel, center.y + pan.y * metresPerPixel / .78), zoom)
            renderer.showDestination = selected != null
            renderer.showUserMarker = current != null
            renderer.userMarkerReliable = reliable
            renderer.userHeadingKnown = reliable && headingDegrees != null
            renderer.userAccuracyMetres = accuracy
            renderer.target = routePose(displayRoute, 0.0).copy(position = current ?: destination.position,heading=headingDegrees?.let { Math.toRadians(it) } ?: 0.0)
        })
}
