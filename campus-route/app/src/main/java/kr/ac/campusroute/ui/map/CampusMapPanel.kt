package kr.ac.campusroute.ui.map

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kr.ac.campusroute.domain.model.*
import kr.ac.campusroute.routing.routePose
import kr.ac.campusroute.ui.*
import kr.ac.campusroute.location.*
import kotlin.math.*

@Composable
fun CampusMapPanel(
    campus: Campus, route: Route?, selectedDestinationId: String?,
    location: MapLocationState, modifier: Modifier = Modifier,
    onDestinationSelected: (String) -> Unit,
    onRequestLocation: () -> Unit, onOpenLocationSettings: () -> Unit,
    permissionDenied: Boolean = false, headingDegrees: Double? = null, initiallyFollowing: Boolean = true
) {
    var show3d by remember { mutableStateOf(false) }
    val previewPoints=if(!initiallyFollowing) route?.nodes?.map { it.position }.orEmpty() else emptyList()
    val initialCenter=if(previewPoints.isEmpty()) campus.center else Point((previewPoints.minOf { it.x }+previewPoints.maxOf { it.x })/2,(previewPoints.minOf { it.y }+previewPoints.maxOf { it.y })/2)
    val initialZoom=if(previewPoints.isEmpty()) 1f else min(campus.mapWidth/(previewPoints.maxOf { it.x }-previewPoints.minOf { it.x }+150),campus.mapHeight/(previewPoints.maxOf { it.y }-previewPoints.minOf { it.y }+150)).toFloat().coerceIn(.5f,8f)
    var zoom by remember { mutableFloatStateOf(initialZoom) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var center by remember(campus) { mutableStateOf(initialCenter) }
    var following by remember { mutableStateOf(initiallyFollowing) }
    val fix=location.fix
    val current=fix?.let { projectWgs84(it.latitude,it.longitude,campus.originLatitude,campus.originLongitude) }
    val outside=current?.let { !containsPoint(it,campus.boundary) } == true
    LaunchedEffect(current,location.canFollow,following) {
        if (following && location.canFollow && current != null) { center=current; pan=Offset.Zero }
    }
    val transform: (Offset,Float) -> Unit = { move,scale -> following=false; pan+=move; zoom=(zoom*scale).coerceIn(.5f,12f) }
    Box(modifier.clipToBounds()) {
        if (show3d) CampusOverview(campus,route,current,selectedDestinationId,center,zoom,pan,transform,fix?.accuracyMetres,location.canFollow,headingDegrees)
        else CampusMap(campus,route,current,fix?.accuracyMetres,location.canFollow,selectedDestinationId,center,zoom,pan,transform,onDestinationSelected,headingDegrees)
        Column(Modifier.align(Alignment.TopEnd).padding(12.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
            FilledTonalButton(onClick={ show3d=!show3d; pan=Offset.Zero }) { Text(if(show3d) "2D 지도" else "3D 둘러보기") }
            FilledTonalButton(onClick={ zoom=(zoom*1.3f).coerceAtMost(12f) }) { Text("＋ 확대") }
            FilledTonalButton(onClick={ zoom=(zoom/1.3f).coerceAtLeast(.5f) }) { Text("－ 축소") }
            FilledTonalButton(onClick={
                when {
                    location.condition==LocationCondition.PERMISSION_REQUIRED -> if(permissionDenied) onOpenLocationSettings() else onRequestLocation()
                    location.condition==LocationCondition.DISABLED -> onOpenLocationSettings()
                    current != null -> { center=current; pan=Offset.Zero; following=location.canFollow }
                }
            },enabled=current!=null || location.condition in listOf(LocationCondition.PERMISSION_REQUIRED,LocationCondition.DISABLED)) { Text(if(current==null) "위치 허용/설정" else "내 위치") }
            FilledTonalButton(onClick={ center=campus.center; pan=Offset.Zero; zoom=1f; following=false }) { Text("전체 보기") }
        }
        Surface(Modifier.align(Alignment.BottomStart).padding(8.dp),color=Color.White.copy(alpha=.94f)) {
            Column(Modifier.padding(6.dp)) {
                Text(locationMessage(location,permissionDenied),style=MaterialTheme.typography.labelMedium)
                if (outside) Text("개신캠퍼스 밖 · 주변 지도는 제공하지 않음",style=MaterialTheme.typography.labelSmall)
                if (location.condition==LocationCondition.PERMISSION_REQUIRED) {
                    Text("지도 표시용 · 화면 사용 중에만 요청 · 저장/전송 없음",style=MaterialTheme.typography.labelSmall)
                    TextButton(onClick=if(permissionDenied) onOpenLocationSettings else onRequestLocation) { Text(if(permissionDenied) "앱 설정에서 위치 권한 변경" else "내 위치 표시 허용") }
                } else if (location.condition==LocationCondition.DISABLED) {
                    TextButton(onClick=onOpenLocationSettings) { Text("기기 위치 켜기") }
                }
                Text("N ↑ · 개신캠퍼스 · 오프라인 지도",style=MaterialTheme.typography.labelSmall)
                MapAttribution()
            }
        }
    }
}

fun locationMessage(state: MapLocationState, denied: Boolean = false): String {
    val accuracy=state.fix?.accuracyMetres?.let { " · 정확도 반경 약 ${ceil(it).toInt()}m" }.orEmpty()
    val sourceLabel=if(state.fix?.isMock==true) "시험 위치 (Mock)" else "현재 위치"
    return when(state.condition) {
        LocationCondition.PERMISSION_REQUIRED -> if(denied) "위치 권한 거부됨 · 현재 위치 없음" else "위치 권한 필요 · 현재 위치 없음"
        LocationCondition.WAITING -> "현재 위치 수신 대기 · 실외에서 잠시 기다려 주세요"
        LocationCondition.VALID -> "$sourceLabel · ${state.ageSeconds ?: 0}초 전$accuracy"
        LocationCondition.UNCERTAIN -> "위치 불확실${if(state.fix?.approximate==true) " (대략적 위치)" else ""}$accuracy"
        LocationCondition.STALE -> "위치 신호 공백 · 마지막 위치 ${state.ageSeconds ?: 0}초 전"
        LocationCondition.DISABLED -> "기기 위치 서비스 꺼짐 · 실시간 위치 아님"
        LocationCondition.UNAVAILABLE -> "위치 제공자 사용 불가 · 현재 위치 없음"
        LocationCondition.PAUSED -> "위치 갱신 일시 중지 · 실시간 위치 아님"
    }
}

@Composable
private fun CampusMap(campus: Campus, route: Route?, current: Point?, accuracy: Double?, reliable: Boolean, selected: String?, center: Point, zoom: Float, pan: Offset, onTransform: (Offset, Float) -> Unit, onSelect: (String) -> Unit, headingDegrees: Double?) {
    Canvas(Modifier.fillMaxSize().clipToBounds()
        .pointerInput(Unit) { detectTransformGestures { _, move, scale, _ -> onTransform(move, scale) } }
        .pointerInput(zoom, pan, center, campus) {
            detectTapGestures { tap ->
                val scale = min(size.width / campus.mapWidth, size.height / campus.mapHeight) * zoom
                fun point(p: Point) = Offset(size.width / 2f + (p.x - center.x).toFloat() * scale, size.height / 2f - (p.y - center.y).toFloat() * scale) + pan
                campus.destinations.minByOrNull { (point(campus.byId.getValue(it.nodeId).position) - tap).getDistance() }?.takeIf { (point(campus.byId.getValue(it.nodeId).position) - tap).getDistance() < 28.dp.toPx() }?.let { onSelect(it.id) }
            }
        }) {
        drawRect(Color(0xFFF5F5F5))
        val scale = min(size.width / campus.mapWidth, size.height / campus.mapHeight) * zoom
        fun point(p: Point) = Offset(size.width / 2 + (p.x - center.x).toFloat() * scale, size.height / 2 - (p.y - center.y).toFloat() * scale) + pan
        if (campus.boundary.isNotEmpty()) {
            val boundaryPath = Path().apply {
                campus.boundary.forEachIndexed { index, p -> val q = point(p); if (index == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y) }; close()
            }
            drawPath(boundaryPath, Color(0xFFEEEEEE))
            drawPath(boundaryPath, Color(0xFFC6C6C6), style = Stroke(1.5.dp.toPx()))
        }
        // Complete each layer before the next so adjoining segments have no scalloped borders.
        for ((color, width) in listOf(Color(0xFFDADADA) to 9f, Color.White to 7f)) {
            for (edge in campus.edges.filter { it.accessible }) {
                val a = point(campus.byId.getValue(edge.from).position)
                val b = point(campus.byId.getValue(edge.to).position)
                drawLine(color, a, b, width * scale, StrokeCap.Round)
            }
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(80, 80, 80); textSize = 11.dp.toPx(); textAlign = Paint.Align.CENTER }
        campus.buildings.forEach { b ->
            val c = point(b.center); val dim = Size(b.width.toFloat() * scale, b.depth.toFloat() * scale)
            if (b.footprint.isNotEmpty()) {
                val outline = Path().apply { b.footprint.forEachIndexed { i, p -> val q = point(p); if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y) }; close() }
                // A small neutral shadow separates white footprints from the pale ground.
                val shadowOffset = 1.5.dp.toPx()
                withTransform({ translate(shadowOffset, shadowOffset) }) {
                    drawPath(outline, Color(0x1A000000))
                }
                drawPath(outline, Color(0xFFFAFAFA))
                drawPath(outline, Color(0xFFD0D0D0), style = Stroke(1f))
            } else drawRect(Color(0xFFF8F8F8), c - Offset(dim.width / 2, dim.height / 2), dim)
            if (zoom >= 2.5f && b.name.isNotBlank()) drawContext.canvas.nativeCanvas.drawText(b.name, c.x, c.y + 4.dp.toPx(), paint)
        }
        route?.nodes?.zipWithNext()?.forEach { (a, b) -> drawLine(if (route.origin == RouteOrigin.SHARED) Color(0xFF16834A) else Color(0xFF2166F3), point(a.position), point(b.position), 4.dp.toPx(), StrokeCap.Round) }
        campus.destinations.forEach { drawCircle(Color(0xFF999999), 2.dp.toPx(), point(campus.byId.getValue(it.nodeId).position)) }
        campus.destinations.firstOrNull { it.id == selected }?.let {
            val destination = point(campus.byId.getValue(it.nodeId).position)
            drawCircle(Color(0xFF303030), 11.dp.toPx(), destination)
            drawCircle(Color.White, 4.dp.toPx(), destination)
        }
        current?.let { position ->
            val marker = point(position)
            val color = if (reliable) Color(0xFF2166F3) else Color(0xFF777777)
            accuracy?.let { metres ->
                val radius=(metres * scale).toFloat()
                drawCircle(color.copy(alpha=.10f), radius, marker)
                drawCircle(color.copy(alpha=.45f), radius, marker, style=Stroke(1.dp.toPx()))
            }
            headingDegrees?.takeIf { reliable }?.let { heading ->
                val angle=Math.toRadians(heading)
                val forward=Offset(sin(angle).toFloat(),-cos(angle).toFloat())
                val right=Offset(cos(angle).toFloat(),sin(angle).toFloat())
                val tip=marker+forward*27.dp.toPx()
                val left=marker+forward*12.dp.toPx()-right*7.dp.toPx()
                val side=marker+forward*12.dp.toPx()+right*7.dp.toPx()
                drawPath(Path().apply { moveTo(tip.x,tip.y); lineTo(left.x,left.y); lineTo(side.x,side.y); close() },color)
            }
            drawCircle(color.copy(alpha=.20f),17.dp.toPx(),marker)
            drawCircle(color,8.dp.toPx(),marker)
            drawCircle(Color.White,9.dp.toPx(),marker,style=Stroke(2.dp.toPx()))
        }
    }
}

@Composable
fun MapAttribution() {
    val uri = LocalUriHandler.current
    Surface(color = Color.White.copy(alpha = 0.92f)) {
        Text("© OpenStreetMap contributors · ODbL", Modifier.clickable { uri.openUri("https://www.openstreetmap.org/copyright") }.padding(4.dp), style = MaterialTheme.typography.labelSmall)
    }
}
