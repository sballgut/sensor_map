package kr.ac.campusroute.routing

import kr.ac.campusroute.domain.model.*
import kotlin.math.*

data class RouteMatch(val position: Point, val along: Double, val offset: Double)
fun projectSegment(point: Point, a: Point, b: Point): Pair<Point, Double> {
    val dx=b.x-a.x; val dy=b.y-a.y
    val denominator=dx*dx+dy*dy
    val t=if(denominator==0.0) 0.0 else (((point.x-a.x)*dx+(point.y-a.y)*dy)/denominator).coerceIn(0.0,1.0)
    return a.interpolate(b,t) to t
}
fun matchRoute(route: Route, position: Point): RouteMatch {
    var distance=0.0
    var best=RouteMatch(route.nodes.first().position,0.0,position.distance(route.nodes.first().position))
    route.nodes.zipWithNext().forEach { (a,b) ->
        val (point,t)=projectSegment(position,a.position,b.position)
        val offset=point.distance(position)
        if(offset<best.offset) best=RouteMatch(point,distance+a.position.distance(b.position)*t,offset)
        distance+=a.position.distance(b.position)
    }
    return best
}

/** Start on the nearest accessible edge, preserving its direction and cost. No invented connector. */
fun routeFromPosition(campus: Campus, position: Point, destination: String, planner: RoutePlanner=AStar()): Route? {
    if(campus.buildings.any { footprintContains(position,it.footprint) }) return null
    val edge=campus.edges.filter { it.accessible }.minByOrNull {
        projectSegment(position,campus.byId.getValue(it.from).position,campus.byId.getValue(it.to).position).first.distance(position)
    } ?: return null
    val a=campus.byId.getValue(edge.from); val b=campus.byId.getValue(edge.to)
    val (point,t)=projectSegment(position,a.position,b.position)
    if(point.distance(position)>30.0) return null
    if(t<.000001) return planner.find(campus,a.id,destination)
    if(t>.999999) return planner.find(campus,b.id,destination)
    val start=Node("__live_start__",point,"현재 위치 인근 보행로")
    val additions=buildList {
        add(Edge(start.id,b.id,edge.distance*(1-t),directed=true))
        if(!edge.directed) add(Edge(start.id,a.id,edge.distance*t,directed=true))
    }
    val graph=campus.copy(nodes=campus.nodes+start,edges=campus.edges+additions)
    return planner.find(graph,start.id,destination)
}

/** Arrival requires proximity to the end AND progress near the end, on two distinct good fixes. */
class ArrivalTracker {
    private var lastTimestamp: Long?=null
    private var count=0
    fun update(route: Route, point: Point, match: RouteMatch, accuracy: Double, timestamp: Long): Boolean {
        if(timestamp==lastTimestamp) return count>=2
        lastTimestamp=timestamp
        val close=accuracy<=20 && point.distance(route.nodes.last().position)<=15 && route.geometricDistance-match.along<=20
        count=if(close) count+1 else 0
        return count>=2
    }
    fun reset() { count=0; lastTimestamp=null }
}
