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
import kotlin.math.*

@Composable
fun HomeScreen(model: CampusViewModel, state: CampusState) {
    var show3d by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val current = state.route?.let { routePose(it, state.travelled).position } ?: model.campus.byId.getValue(model.campus.startNodeId).position
    var center by remember { mutableStateOf(model.campus.center) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("CAMPUS ROUTE · 충북대 개신캠퍼스", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("어디로 걸어갈까요?", style = MaterialTheme.typography.headlineMedium)
            Text("OSM 실좌표 지도 · 출입구/통행 현장 확인 전", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("개신캠퍼스 건물 검색") }, singleLine = true)
            Text("데모 위치  •  GPS / BLE 미연결", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium, color = Color(0xFF707070))
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            if (show3d) CampusOverview(model.campus, state.route, current, state.destination, center, zoom, pan,
                onTransform = { move, scale -> pan += move; zoom = (zoom * scale).coerceIn(0.5f, 12f) })
            else CampusMap(model.campus, state.route, current, state.destination, center, zoom, pan,
                onTransform = { move, scale -> pan += move; zoom = (zoom * scale).coerceIn(0.5f, 12f) },
                onSelect = { if (!state.navigating) model.select(it) })
            Column(Modifier.align(Alignment.TopEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(onClick = { show3d = !show3d; pan = Offset.Zero }) { Text(if (show3d) "2D 지도" else "3D 둘러보기") }
                FilledTonalButton(onClick = { zoom = (zoom * 1.3f).coerceAtMost(12f) }) { Text("＋ 확대") }
                FilledTonalButton(onClick = { zoom = (zoom / 1.3f).coerceAtLeast(0.5f) }) { Text("－ 축소") }
                FilledTonalButton(onClick = { center = current; pan = Offset.Zero }) { Text("내 위치") }
                FilledTonalButton(onClick = { center = model.campus.center; pan = Offset.Zero; zoom = 1f }) { Text("전체 보기") }
            }
            Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                Text("N ↑ · 개신캠퍼스 · 오프라인", style = MaterialTheme.typography.labelSmall)
                MapAttribution()
            }
        }
        Surface(shadowElevation = 8.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                if (state.navigating) {
                    Text("데모 경로 진행 중", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = model::toggleMap, modifier = Modifier.fillMaxWidth()) { Text("3D 안내로 돌아가기") }
                    TextButton(onClick = model::end) { Text("안내 종료") }
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(model.campus.destinations.filter { it.name.contains(query, ignoreCase = true) }) { node ->
                            FilterChip(selected = state.destination == node.id, onClick = { model.select(node.id) }, label = { Text(node.name) })
                        }
                    }
                    if (model.campus.destinations.none { it.name.contains(query, ignoreCase = true) }) Text("검색 결과가 없습니다.")
                    Text("출발: 정문 인근 → ${model.campus.destinations.first { it.id == state.destination }.name}", style = MaterialTheme.typography.bodyMedium)
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    Button(onClick = model::start, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("경로 찾기 · 이동 시뮬레이션") }
                }
            }
        }
    }
}

@Composable
private fun CampusMap(campus: Campus, route: Route?, current: Point, selected: String, center: Point, zoom: Float, pan: Offset, onTransform: (Offset, Float) -> Unit, onSelect: (String) -> Unit) {
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
        route?.nodes?.zipWithNext()?.forEach { (a, b) -> drawLine(Color(0xFF2166F3), point(a.position), point(b.position), 4.dp.toPx(), StrokeCap.Round) }
        campus.destinations.forEach { drawCircle(Color(0xFF999999), 2.dp.toPx(), point(campus.byId.getValue(it.nodeId).position)) }
        val destination = point(campus.byId.getValue(campus.destinations.first { it.id == selected }.nodeId).position)
        drawCircle(Color(0xFF303030), 11.dp.toPx(), destination)
        drawCircle(Color.White, 4.dp.toPx(), destination)
        val marker = point(current)
        drawCircle(Color(0x332166F3), 17.dp.toPx(), marker)
        drawCircle(Color(0xFF2166F3), 8.dp.toPx(), marker)
        drawCircle(Color.White, 9.dp.toPx(), marker, style = Stroke(2.dp.toPx()))
    }
}

@Composable
fun MapAttribution() {
    val uri = LocalUriHandler.current
    Surface(color = Color.White.copy(alpha = 0.92f)) {
        Text("© OpenStreetMap contributors · ODbL", Modifier.clickable { uri.openUri("https://www.openstreetmap.org/copyright") }.padding(4.dp), style = MaterialTheme.typography.labelSmall)
    }
}
