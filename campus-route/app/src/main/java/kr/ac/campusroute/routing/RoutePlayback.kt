package kr.ac.campusroute.routing

import kr.ac.campusroute.domain.model.*
import kotlin.math.*

data class RoutePose(val position: Point, val heading: Double, val segment: Int, val remaining: Double, val nextDistance: Double, val instruction: String, val arrived: Boolean)

/** Heading in radians: 0 = north, positive = east. Distance is along polyline in metres. */
fun routePose(route: Route, travelled: Double): RoutePose {
    val nodes = route.nodes
    val total = route.geometricDistance
    val progress = travelled.coerceIn(0.0, total)
    if (nodes.size == 1 || progress >= total) {
        val heading = if (nodes.size > 1) bearing(nodes[nodes.lastIndex - 1].position, nodes.last().position) else 0.0
        return RoutePose(nodes.last().position, heading, max(0, nodes.size - 2), 0.0, 0.0, "목적지 도착", true)
    }
    var left = progress
    for (i in 0 until nodes.lastIndex) {
        val a = nodes[i].position
        val b = nodes[i + 1].position
        val length = a.distance(b)
        if (left < length && length > 0) {
            val heading = bearing(a, b)
            // Skip straight intermediate vertices when finding the next turn.
            var nextDistance = length - left
            var instruction = "목적지까지 직진"
            for (j in i + 1 until nodes.lastIndex) {
                val before = bearing(nodes[j - 1].position, nodes[j].position)
                val after = bearing(nodes[j].position, nodes[j + 1].position)
                val turn = angleDelta(before, after)
                if (abs(turn) > Math.toRadians(25.0)) {
                    instruction = if (turn > 0) "오른쪽으로 이동" else "왼쪽으로 이동"
                    break
                }
                nextDistance += nodes[j].position.distance(nodes[j + 1].position)
            }
            return RoutePose(a.interpolate(b, left / length), heading, i, total - progress, nextDistance, instruction, false)
        }
        left -= length
    }
    return RoutePose(nodes.last().position, 0.0, max(0, nodes.size - 2), 0.0, 0.0, "목적지 도착", true)
}
fun bearing(a: Point, b: Point) = atan2(b.x - a.x, b.y - a.y)
fun angleDelta(from: Double, to: Double) = atan2(sin(to - from), cos(to - from))
fun smoothHeading(current: Double, target: Double, dt: Double): Double = current + angleDelta(current, target) * (1 - exp(-dt.coerceAtLeast(0.0) * 5))
