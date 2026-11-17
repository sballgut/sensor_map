package kr.ac.campusroute.domain.model

import kotlin.math.hypot

/** Local metres: x east, y north. Geographic fields are null for the synthetic map. */
data class Point(val x: Double, val y: Double) {
    fun distance(other: Point) = hypot(x - other.x, y - other.y)
    fun interpolate(other: Point, t: Double) = Point(x + (other.x - x) * t, y + (other.y - y) * t)
}
data class Node(val id: String, val position: Point, val name: String, val latitude: Double? = null, val longitude: Double? = null)
data class Edge(val from: String, val to: String, val distance: Double, val accessible: Boolean = true, val directed: Boolean = false)
data class Building(val name: String, val center: Point, val width: Double, val depth: Double, val height: Double, val footprint: List<Point> = emptyList())
data class Destination(val id: String, val name: String, val nodeId: String)
data class Campus(val version: String, val name: String, val nodes: List<Node>, val edges: List<Edge>, val buildings: List<Building>, val boundary: List<Point> = emptyList(), val startNodeId: String = nodes.first().id, val destinations: List<Destination> = nodes.map { Destination(it.id, it.name, it.id) }, val originLatitude: Double = 36.6285, val originLongitude: Double = 127.4565) {
    val center: Point get() {
        val points = boundary.ifEmpty { nodes.map { it.position } }
        return Point((points.minOf { it.x } + points.maxOf { it.x }) / 2, (points.minOf { it.y } + points.maxOf { it.y }) / 2)
    }
    val mapWidth: Float get() = ((boundary.ifEmpty { nodes.map { it.position } }.maxOf { it.x } - boundary.ifEmpty { nodes.map { it.position } }.minOf { it.x }) + 90).toFloat()
    val mapHeight: Float get() = ((boundary.ifEmpty { nodes.map { it.position } }.maxOf { it.y } - boundary.ifEmpty { nodes.map { it.position } }.minOf { it.y }) + 90).toFloat()
    val byId = nodes.associateBy { it.id }
    init {
        require(nodes.isNotEmpty() && byId.size == nodes.size) { "Duplicate or empty nodes" }
        require(nodes.all { it.position.x.isFinite() && it.position.y.isFinite() })
        require(startNodeId in byId && destinations.isNotEmpty() && destinations.all { it.nodeId in byId })
        require(edges.all { it.from in byId && it.to in byId && it.distance.isFinite() && it.distance > 0 })
    }
}
enum class RouteOrigin { LOCAL, SHARED }

data class Route(val nodes: List<Node>, val totalDistance: Double, val origin: RouteOrigin = RouteOrigin.LOCAL) {
    init { require(nodes.isNotEmpty()) }
    val geometricDistance = nodes.zipWithNext().sumOf { (a, b) -> a.position.distance(b.position) }
}
