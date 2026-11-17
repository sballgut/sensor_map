package kr.ac.campusroute.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kr.ac.campusroute.domain.model.*
import kr.ac.campusroute.location.*
import kr.ac.campusroute.routing.*
import kr.ac.campusroute.sharing.*

data class CampusState(
    val destination: String? = null,
    val route: Route? = null,
    val navigating: Boolean = false,
    val mapPreview: Boolean = false,
    val travelled: Double = 0.0,
    val location: MapLocationState = MapLocationState(LocationCondition.PERMISSION_REQUIRED),
    val offRoute: Boolean = false,
    val arrived: Boolean = false,
    val planning: Boolean = false,
    val message: String? = null
)
class CampusViewModel(val campus: Campus, private val planner: RoutePlanner = AStar()) : ViewModel() {
    private val mutable = MutableStateFlow(CampusState())
    val state = mutable.asStateFlow()
    private var planning: Job?=null
    private val arrival=ArrivalTracker()
    private var lastFix: MapFix?=null
    fun select(id: String?) {
        if(id!=null && campus.destinations.none { it.id==id }) return
        if(state.value.navigating) return
        planning?.cancel()
        mutable.update { it.copy(destination=id,route=null,message=null,planning=false,arrived=false) }
    }
    fun updateLocation(location: MapLocationState) {
        mutable.update { it.copy(location=location) }
        val fix=location.fix ?: return
        if(!location.canFollow || (fix.accuracyMetres ?: Double.MAX_VALUE)>30) { arrival.reset(); return }
        val current=state.value
        val route=current.route ?: return
        if(!current.navigating || current.arrived) return
        if(lastFix?.elapsedRealtimeMs==fix.elapsedRealtimeMs) return
        val point=projectWgs84(fix.latitude,fix.longitude,campus.originLatitude,campus.originLongitude)
        val old=lastFix
        lastFix=fix
        if(old!=null) {
            val elapsed=(fix.elapsedRealtimeMs-old.elapsedRealtimeMs)/1000.0
            val previous=projectWgs84(old.latitude,old.longitude,campus.originLatitude,campus.originLongitude)
            if(elapsed<=0 || point.distance(previous)>maxOf(40.0,elapsed*6)) {
                arrival.reset(); mutable.update { it.copy(message="위치가 크게 변경되어 다음 위치 수신을 기다립니다.") }; return
            }
        }
        val match=matchRoute(route,point)
        val off=match.offset>maxOf(20.0,fix.accuracyMetres!!)
        if(off) arrival.reset()
        val arrived=!off && arrival.update(route,point,match,fix.accuracyMetres,fix.elapsedRealtimeMs)
        mutable.update { it.copy(travelled=if(off) it.travelled else match.along,offRoute=off,arrived=arrived,
            message=when { arrived -> "목적지 인근 보행로에 도착했습니다."; off -> "경로에서 벗어났습니다. 현재 위치에서 다시 찾기를 눌러 주세요."; else -> null }) }
    }
    fun start() {
        val snapshot=state.value
        val target=campus.destinations.firstOrNull { it.id==snapshot.destination } ?: return
        val fix=snapshot.location.fix
        fun error(message: String) { mutable.update { it.copy(message=message) } }
        if(!snapshot.location.canFollow || fix==null || (fix.accuracyMetres ?: Double.MAX_VALUE)>30) {
            error("정확한 현재 위치가 필요합니다. 위치 권한을 허용하고 실외에서 수신을 기다려 주세요."); return
        }
        val position=projectWgs84(fix.latitude,fix.longitude,campus.originLatitude,campus.originLongitude)
        if(!containsPoint(position,campus.boundary)) { error("길찾기는 충북대학교 개신캠퍼스 안에서 사용할 수 있습니다."); return }
        planning?.cancel()
        mutable.update { it.copy(planning=true,message=null) }
        planning=viewModelScope.launch {
            val route=withContext(Dispatchers.Default) { routeFromPosition(campus,position,target.nodeId,planner) }
            if(route==null) { mutable.update { it.copy(planning=false,message="30m 이내 보행로에서 목적지로 연결되는 경로가 없습니다.") }; return@launch }
            arrival.reset(); lastFix=null
            mutable.update { it.copy(route=route,navigating=true,mapPreview=false,travelled=0.0,offRoute=false,arrived=false,planning=false,message=null) }
            updateLocation(state.value.location)
        }
    }
    fun clearMessage() { mutable.update { it.copy(message=null) } }
    fun startShared(shared: SharedRoute) {
        val snapshot=state.value
        val fix=snapshot.location.fix
        if(!snapshot.location.canFollow || fix==null || (fix.accuracyMetres ?: Double.MAX_VALUE)>30) {
            mutable.update { it.copy(message="정확한 현재 위치를 수신한 뒤 시작해 주세요.") }; return
        }
        val route=runCatching { shared.resolve(campus) }.getOrElse { problem ->
            mutable.update { it.copy(message=problem.message) }; return
        }
        val position=projectWgs84(fix.latitude,fix.longitude,campus.originLatitude,campus.originLongitude)
        if(!containsPoint(position,campus.boundary) || position.distance(route.nodes.first().position)>30) {
            mutable.update { it.copy(message="공유 경로의 시작점 30m 이내로 이동한 뒤 시작해 주세요.") }; return
        }
        planning?.cancel(); arrival.reset(); lastFix=null
        mutable.update { it.copy(destination=shared.destinationId,route=route,navigating=true,mapPreview=false,travelled=0.0,offRoute=false,arrived=false,planning=false,message=null) }
        updateLocation(state.value.location)
    }
    fun end() { planning?.cancel(); arrival.reset(); mutable.update { it.copy(navigating=false,mapPreview=false,planning=false) } }
    fun toggleMap() { mutable.update { it.copy(mapPreview=!it.mapPreview) } }
}
