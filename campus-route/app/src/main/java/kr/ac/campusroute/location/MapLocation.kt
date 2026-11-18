package kr.ac.campusroute.location

import kr.ac.campusroute.domain.model.Point
import kotlinx.coroutines.flow.Flow
import kotlin.math.*

/** A/B -> D-2 display contract. WGS84 degrees; no synthetic accuracy or heading. */
data class MapFix(
    val latitude: Double, val longitude: Double,
    val accuracyMetres: Double?, val measuredAtUtcMs: Long, val elapsedRealtimeMs: Long,
    val bearingDegrees: Double? = null, val approximate: Boolean = false,
    val source: String = "device", val isMock: Boolean = false
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0)
        require(accuracyMetres == null || accuracyMetres.isFinite() && accuracyMetres >= 0)
    }
}
enum class LocationCondition { PERMISSION_REQUIRED, WAITING, VALID, UNCERTAIN, STALE, DISABLED, UNAVAILABLE, PAUSED }
data class MapLocationState(val condition: LocationCondition, val fix: MapFix? = null, val ageSeconds: Long? = null) {
    val canFollow get() = condition == LocationCondition.VALID
}
interface LocationFeed { fun updates(): Flow<MapLocationState> }

/** Provisional display thresholds, to be agreed with B/C. No routing or measurement correction. */
fun classifyFix(fix: MapFix, nowElapsedMs: Long): MapLocationState {
    val age = (nowElapsedMs - fix.elapsedRealtimeMs).coerceAtLeast(0) / 1000
    val condition = when {
        nowElapsedMs < fix.elapsedRealtimeMs || age >= 30 -> LocationCondition.STALE
        fix.approximate || fix.accuracyMetres == null || fix.accuracyMetres > 100 -> LocationCondition.UNCERTAIN
        else -> LocationCondition.VALID
    }
    return MapLocationState(condition, fix, age)
}
/** Same equirectangular metre projection as tools/import_osm.py; origin comes from map metadata. */
fun projectWgs84(latitude: Double, longitude: Double, originLatitude: Double, originLongitude: Double): Point {
    require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
    val radius = 6371008.8
    return Point(Math.toRadians(longitude-originLongitude)*radius*cos(Math.toRadians(originLatitude)), Math.toRadians(latitude-originLatitude)*radius)
}
fun containsPoint(point: Point, polygon: List<Point>): Boolean {
    if (polygon.size < 3) return false
    var inside = false
    polygon.indices.forEach { i ->
        val a=polygon[i]; val b=polygon[(i+1)%polygon.size]
        if ((a.y>point.y)!=(b.y>point.y) && point.x<(b.x-a.x)*(point.y-a.y)/(b.y-a.y)+a.x) inside=!inside
    }
    return inside
}
