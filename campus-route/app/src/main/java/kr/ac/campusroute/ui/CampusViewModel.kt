package kr.ac.campusroute.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kr.ac.campusroute.domain.model.*
import kr.ac.campusroute.routing.*

data class CampusState(
    val destination: String = "library",
    val route: Route? = null,
    val navigating: Boolean = false,
    val mapPreview: Boolean = false,
    val travelled: Double = 0.0,
    val playing: Boolean = true,
    val speed: Double = 1.4,
    val message: String? = null
)

class CampusViewModel(val campus: Campus, private val planner: RoutePlanner = AStar()) : ViewModel() {
    private val mutable = MutableStateFlow(CampusState(destination = campus.destinations.firstOrNull { it.name.contains("중앙도서관 신관") }?.id ?: campus.destinations.first().id))
    val state = mutable.asStateFlow()
    private var playback: Job? = null
    private var foreground = false
    fun select(id: String) { if (campus.destinations.any { it.id == id }) mutable.update { it.copy(destination = id, route = null, message = null) } }
    fun start() {
        val route = planner.find(campus, campus.startNodeId, campus.destinations.first { it.id == state.value.destination }.nodeId)
        if (route == null) { mutable.update { it.copy(message = "연결된 보행 경로가 없습니다.") }; return }
        playback?.cancel()
        mutable.update { it.copy(route = route, navigating = true, mapPreview = false, travelled = 0.0, playing = true, message = null) }
        playback = viewModelScope.launch {
            while (isActive) {
                delay(50)
                mutable.update { current ->
                    if (foreground && current.playing && current.navigating)
                        current.copy(travelled = (current.travelled + current.speed * 0.05).coerceAtMost(route.geometricDistance))
                    else current
                }
                if (state.value.travelled >= route.geometricDistance) {
                    delay(2500)
                    // Keep the arrival result and route on the 2D map.
                    mutable.update { it.copy(navigating = false, mapPreview = false, message = "${campus.destinations.first { it.id == state.value.destination }.name}에 도착했습니다.") }
                    break
                }
            }
        }
    }
    fun end() { playback?.cancel(); mutable.update { it.copy(navigating = false, mapPreview = false, playing = false) } }
    fun setForeground(active: Boolean) { foreground = active }
    fun togglePlayback() { mutable.update { it.copy(playing = !it.playing) } }
    fun toggleSpeed() { mutable.update { it.copy(speed = if (it.speed == 1.4) 8.0 else 1.4) } }
    fun toggleMap() { mutable.update { it.copy(mapPreview = !it.mapPreview) } }
}
