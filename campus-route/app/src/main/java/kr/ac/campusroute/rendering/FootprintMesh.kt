package kr.ac.campusroute.rendering

import kr.ac.campusroute.domain.model.Point
import kotlin.math.abs

/** Ear clipping for simple OSM exterior rings, including concave building outlines. */
fun triangulateFootprint(points: List<Point>): List<Int> {
    if (points.size < 3) return emptyList()
    fun cross(a: Point, b: Point, c: Point) = (b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)
    val area = points.indices.sumOf { i -> val a=points[i]; val b=points[(i+1)%points.size]; a.x*b.y-b.x*a.y }
    val remaining = points.indices.toMutableList().apply { if (area < 0) reverse() }
    val result = mutableListOf<Int>()
    while (remaining.size > 3) {
        var clipped = false
        for (i in remaining.indices) {
            val a=remaining[(i+remaining.size-1)%remaining.size]; val b=remaining[i]; val c=remaining[(i+1)%remaining.size]
            val turn = cross(points[a],points[b],points[c])
            if (abs(turn) < 1e-7) { remaining.removeAt(i); clipped=true; break }
            if (turn < 0) continue
            val occupied=remaining.any { p -> p!=a && p!=b && p!=c && cross(points[a],points[b],points[p])>=-1e-7 && cross(points[b],points[c],points[p])>=-1e-7 && cross(points[c],points[a],points[p])>=-1e-7 }
            if (!occupied) { result.addAll(listOf(a,b,c)); remaining.removeAt(i); clipped=true; break }
        }
        // Invalid/self-crossing outlines must not be filled with a misleading triangle fan.
        if (!clipped) return emptyList()
    }
    if (remaining.size == 3) result.addAll(remaining)
    return result
}
