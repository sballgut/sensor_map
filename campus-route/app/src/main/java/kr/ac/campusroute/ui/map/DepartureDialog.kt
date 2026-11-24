package kr.ac.campusroute.ui.map

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kr.ac.campusroute.ui.*

@Composable
fun DepartureDialog(model: CampusViewModel, state: CampusState, onSharedRoutes: () -> Unit) {
    if (!state.departureDialog) return
    AlertDialog(
        onDismissRequest = model::dismissDeparture,
        title = { Text("어떤 경로로 갈까요?") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                RouteChoiceCard("기본 길찾기", "현재 위치에서 목적지까지 경로를 찾아요.",
                    Color(0xFF2166F3), model::start)
                RouteChoiceCard("다른 사람의 경로", "공유받은 경로 목록에서 골라요.",
                    Color(0xFF16834A)) {
                    model.dismissDeparture()
                    onSharedRoutes()
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = model::dismissDeparture) { Text("취소") } }
    )
}

@Composable
private fun RouteChoiceCard(title: String, description: String, accent: Color, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 128.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.08f))
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = accent)
            Text(description, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
