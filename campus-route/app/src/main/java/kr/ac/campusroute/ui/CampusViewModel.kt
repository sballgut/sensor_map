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
    val departureDialog: Boolean = false,
    val indoorBuildingId: String? = null,
    val message: String? = null
)
class CampusViewModel(val campus: Campus, private val planner: RoutePlanner = AStar()) : ViewModel() {
    private val mutable = MutableStateFlow(CampusState())
    val state = mutable.asStateFlow()
    private var planning: Job?=null
    private var indoorSelectedAt: Long = Long.MIN_VALUE
    private val arrival=ArrivalTracker()
    private var lastFix: MapFix?=null
    private val locationGate=LiveLocationGate(campus.originLatitude,campus.originLongitude)
    fun select(id: String?) {
        if(id!=null && campus.destinations.none { it.id==id }) return
        if(state.value.navigating || state.value.destination==id) return
        planning?.cancel()
        mutable.update { it.copy(destination=id,route=null,message=null,planning=false,arrived=false,indoorBuildingId=null,departureDialog=false) }
    }
    fun updateLocation(input: MapLocationState) {
        val location=locationGate.update(input)
        mutable.update { it.copy(location=location) }
        val fix=location.fix ?: return
        if(!location.canFollow || (fix.accuracyMetres ?: Double.MAX_VALUE)>30) { arrival.reset(); return }
        val current=state.value
        val route=current.route ?: return
        if(!current.navigating || current.arrived) return
        if(lastFix?.elapsedRealtimeMs==fix.elapsedRealtimeMs) return
        val point=projectWgs84(fix.latitude,fix.longitude,campus.originLatitude,campus.originLongitude)
        lastFix=fix
        val building=locateBuilding(campus,point,fix.accuracyMetres!!)
        if(building.relation==BuildingRelation.INSIDE_FOOTPRINT && boundaryDistance(point,building.building!!.footprint)>fix.accuracyMetres) {
            arrival.reset()
            mutable.update { it.copy(message="건물 내부로 추정되어 실외 경로 진행을 잠시 멈춥니다.") }
            return
        }
        val match=matchRoute(route,point)
        val off=match.offset>maxOf(20.0,fix.accuracyMetres!!)
        if(off) arrival.reset()
        val arrived=!off && arrival.update(route,point,match,fix.accuracyMetres,fix.elapsedRealtimeMs)
        mutable.update { it.copy(travelled=if(off) it.travelled else match.along,offRoute=off,arrived=arrived,
            message=when { arrived -> "목적지 인근 보행로에 도착했습니다."; off -> "경로에서 벗어났습니다. 현재 위치에서 다시 찾기를 눌러 주세요."; else -> null }) }
    }
    fun requestStart() {
        if(state.value.destination!=null) mutable.update { it.copy(departureDialog=true,message=null) }
    }
    fun dismissDeparture() { mutable.update { it.copy(departureDialog=false) } }
    fun selectIndoorBuilding(id: String) {
        if(campus.buildings.none { it.id==id && it.footprint.size>=3 }) return
        planning?.cancel()
        indoorSelectedAt=state.value.location.fix?.elapsedRealtimeMs ?: Long.MIN_VALUE
        mutable.update { it.copy(indoorBuildingId=id,departureDialog=false,route=null,planning=false,navigating=false,message=null) }
    }
    fun cancelIndoorDeparture() { mutable.update { it.copy(indoorBuildingId=null,message=null) } }
    fun start() {
        dismissDeparture()
        val snapshot=state.value
        val target=campus.destinations.firstOrNull { it.id==snapshot.destination } ?: return
        val fix=snapshot.location.fix
        fun error(message: String) { mutable.update { it.copy(message=message) } }
        if(!snapshot.location.canFollow || fix==null || (fix.accuracyMetres ?: Double.MAX_VALUE)>30) {
            error("정확한 현재 위치가 필요합니다. 위치 권한을 허용하고 실외에서 수신을 기다려 주세요."); return
        }
        val position=projectWgs84(fix.latitude,fix.longitude,campus.originLatitude,campus.originLongitude)
        if(!containsPoint(position,campus.boundary)) { error("길찾기는 충북대학교 개신캠퍼스 안에서 사용할 수 있습니다."); return }
        val buildingLocation=locateBuilding(campus,position,fix.accuracyMetres!!)
        val indoor=campus.buildings.firstOrNull { it.id==snapshot.indoorBuildingId }
        if(indoor!=null && (fix.elapsedRealtimeMs<=indoorSelectedAt || !canLeaveBuilding(indoor,position,fix.accuracyMetres))) {
            error("출구 밖에서 새 위치를 기다려 주세요. 건물 외곽과 위치 오차가 겹치면 실외 확인을 보류합니다."); return
        }
        if(buildingLocation.relation==BuildingRelation.INSIDE_FOOTPRINT) {
            error("${buildingLocation.building?.name?.ifBlank { "건물" }} 부근입니다. 출구 밖에서 위치를 다시 확인한 뒤 기본 길찾기를 시작해 주세요.")
            return
        }
        planning?.cancel()
        mutable.update { it.copy(planning=true,message=null) }
        planning=viewModelScope.launch {
            val route=withContext(Dispatchers.Default) { routeFromPosition(campus,position,target.nodeId,planner) }
            if(route==null) { mutable.update { it.copy(planning=false,message="30m 이내 보행로에서 목적지로 연결되는 경로가 없습니다.") }; return@launch }
            arrival.reset(); lastFix=null
            mutable.update { it.copy(route=route,navigating=true,mapPreview=false,travelled=0.0,offRoute=false,arrived=false,planning=false,message=null,indoorBuildingId=null) }
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
        if(locateBuilding(campus,position,fix.accuracyMetres!!).relation==BuildingRelation.INSIDE_FOOTPRINT) {
            mutable.update { it.copy(message="건물 출구 밖에서 위치가 확인된 후 공유 경로를 시작해 주세요.") }; return
        }
        if(!containsPoint(position,campus.boundary) || position.distance(route.nodes.first().position)>30) {
            mutable.update { it.copy(message="공유 경로의 시작점 30m 이내로 이동한 뒤 시작해 주세요.") }; return
        }
        planning?.cancel(); arrival.reset(); lastFix=null
        mutable.update { it.copy(destination=shared.destinationId,route=route,navigating=true,mapPreview=false,travelled=0.0,offRoute=false,arrived=false,planning=false,message=null,indoorBuildingId=null) }
        updateLocation(state.value.location)
    }
    fun end() { planning?.cancel(); arrival.reset(); mutable.update { it.copy(navigating=false,mapPreview=false,planning=false) } }
    fun toggleMap() { mutable.update { it.copy(mapPreview=!it.mapPreview) } }
}
