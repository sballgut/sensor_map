package kr.ac.campusroute.routing

import kr.ac.campusroute.domain.model.*
import kotlin.math.*

enum class BuildingRelation { INSIDE_FOOTPRINT, NEAR_BOUNDARY, OUTSIDE }
data class BuildingLocation(val relation: BuildingRelation, val building: Building? = null)

fun footprintContains(point: Point, polygon: List<Point>): Boolean {
    var inside=false
    polygon.indices.forEach { i ->
        val a=polygon[i]; val b=polygon[(i+1)%polygon.size]
        if((a.y>point.y)!=(b.y>point.y) && point.x<(b.x-a.x)*(point.y-a.y)/(b.y-a.y)+a.x) inside=!inside
    }
    return inside
}
fun boundaryDistance(point: Point, polygon: List<Point>): Double = polygon.indices.minOfOrNull { i ->
    projectSegment(point,polygon[i],polygon[(i+1)%polygon.size]).first.distance(point)
} ?: Double.POSITIVE_INFINITY

/** Footprint inference only; GPS does not establish actual indoor presence or floor. */
fun locateBuilding(campus: Campus, point: Point, accuracyMetres: Double): BuildingLocation {
    val buildings=campus.buildings.filter { it.footprint.size>=3 }
    val inside=buildings.firstOrNull { footprintContains(point,it.footprint) }
    if(inside!=null) return BuildingLocation(BuildingRelation.INSIDE_FOOTPRINT,inside)
    val near=buildings.minByOrNull { boundaryDistance(point,it.footprint) }
    return if(near!=null && boundaryDistance(point,near.footprint)<=accuracyMetres.coerceAtLeast(0.0)) BuildingLocation(BuildingRelation.NEAR_BOUNDARY,near)
        else BuildingLocation(BuildingRelation.OUTSIDE)
}
fun canLeaveBuilding(building: Building, point: Point, accuracyMetres: Double): Boolean =
    building.footprint.size>=3 && !footprintContains(point,building.footprint) && boundaryDistance(point,building.footprint)>accuracyMetres.coerceAtLeast(0.0)
