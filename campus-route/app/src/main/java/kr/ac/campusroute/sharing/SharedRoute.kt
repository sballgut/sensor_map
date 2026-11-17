package kr.ac.campusroute.sharing

import kr.ac.campusroute.domain.model.*

data class SharedRoute(val id: String, val title: String, val destinationId: String, val mapVersion: String, val nodeIds: List<String>)

/** Received IDs must resolve to this map and traverse existing allowed edges in order. */
fun SharedRoute.resolve(campus: Campus): Route {
    require(id.isNotBlank() && id.length<=100 && title.isNotBlank() && title.length<=100) { "경로 이름 또는 ID가 올바르지 않습니다." }
    require(mapVersion==campus.version) { "지도 버전이 다릅니다. 현재 지도에 맞는 경로를 받아 주세요." }
    require(nodeIds.size in 2..5000) { "경로 지점은 2~5000개여야 합니다." }
    val destination=campus.destinations.firstOrNull { it.id==destinationId }
    require(destination!=null && nodeIds.last()==destination.nodeId) { "경로 끝점과 목적지가 일치하지 않습니다." }
    val nodes=nodeIds.map { requireNotNull(campus.byId[it]) { "지도에 없는 경로 지점입니다." } }
    var distance=0.0
    nodeIds.zipWithNext().forEach { (a,b) ->
        val edge=campus.edges.filter { it.accessible && (it.from==a && it.to==b || !it.directed && it.from==b && it.to==a) }.minByOrNull { it.distance }
        require(edge!=null) { "연결되지 않았거나 통행할 수 없는 경로입니다." }
        distance+=edge.distance
    }
    return Route(nodes,distance,RouteOrigin.SHARED)
}
