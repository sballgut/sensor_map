package kr.ac.campusroute.routing

import kr.ac.campusroute.domain.model.*
import java.util.PriorityQueue

interface RoutePlanner { fun find(campus: Campus, start: String, destination: String): Route? }

/** Straight-line heuristic scaled down when imported edge costs are shorter than geometry. */
class AStar : RoutePlanner {
    override fun find(campus: Campus, start: String, destination: String): Route? {
        val source = campus.byId[start] ?: return null
        val goal = campus.byId[destination] ?: return null
        if (start == destination) return Route(listOf(source), 0.0)
        val adjacency = mutableMapOf<String, MutableList<Pair<String, Double>>>()
        val edges = campus.edges.filter { it.accessible }
        for (edge in edges) {
            adjacency.getOrPut(edge.from) { mutableListOf() }.add(edge.to to edge.distance)
            if (!edge.directed) adjacency.getOrPut(edge.to) { mutableListOf() }.add(edge.from to edge.distance)
        }
        val scale = edges.mapNotNull {
            val length = campus.byId.getValue(it.from).position.distance(campus.byId.getValue(it.to).position)
            if (length > 0.0) it.distance / length else null
        }.minOrNull()?.coerceIn(0.0, 1.0) ?: 0.0
        fun heuristic(id: String) = campus.byId.getValue(id).position.distance(goal.position) * scale
        data class Entry(val id: String, val cost: Double, val score: Double)
        val open = PriorityQueue(compareBy<Entry> { it.score }.thenBy { it.id })
        val costs = mutableMapOf(start to 0.0)
        val previous = mutableMapOf<String, String>()
        open.add(Entry(start, 0.0, heuristic(start)))
        while (open.isNotEmpty()) {
            val current = open.remove()
            if (current.cost > costs.getValue(current.id)) continue
            if (current.id == destination) {
                val ids = mutableListOf(destination)
                while (ids.last() != start) ids.add(previous.getValue(ids.last()))
                return Route(ids.asReversed().map { campus.byId.getValue(it) }, current.cost)
            }
            for ((next, distance) in adjacency[current.id].orEmpty()) {
                val candidate = current.cost + distance
                if (candidate < (costs[next] ?: Double.POSITIVE_INFINITY)) {
                    costs[next] = candidate
                    previous[next] = current.id
                    open.add(Entry(next, candidate, candidate + heuristic(next)))
                }
            }
        }
        return null
    }
}
