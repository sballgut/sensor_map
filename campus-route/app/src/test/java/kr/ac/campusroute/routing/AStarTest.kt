package kr.ac.campusroute.routing

import kr.ac.campusroute.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class AStarTest {
    private val nodes = listOf(Node("a", Point(0.0,0.0), "A"), Node("b", Point(10.0,0.0), "B"), Node("c", Point(20.0,0.0), "C"), Node("d", Point(10.0,10.0), "D"))
    private fun graph(edges: List<Edge>) = Campus("test", "test", nodes, edges, emptyList())
    private val planner = AStar()
    @Test fun shortestPath() {
        val route = planner.find(graph(listOf(Edge("a","b",10.0), Edge("b","c",10.0), Edge("a","d",15.0), Edge("d","c",15.0))), "a", "c")!!
        assertEquals(listOf("a","b","c"), route.nodes.map { it.id })
        assertEquals(20.0, route.totalDistance, 0.001)
    }
    @Test fun disconnectedReturnsNull() { assertNull(planner.find(graph(listOf(Edge("a","b",10.0))), "a", "c")) }
    @Test fun sameNodeIsZeroLength() {
        val route = planner.find(graph(emptyList()), "a", "a")!!
        assertEquals(listOf("a"), route.nodes.map { it.id }); assertEquals(0.0, route.totalDistance, 0.0)
    }
    @Test fun usesEdgeCostInsteadOfHopCount() {
        val route = planner.find(graph(listOf(Edge("a","c",80.0), Edge("a","b",10.0), Edge("b","c",10.0))), "a", "c")!!
        assertEquals(listOf("a","b","c"), route.nodes.map { it.id })
    }
    @Test fun scaledHeuristicRemainsAdmissibleForDiscountedEdges() {
        val route = planner.find(graph(listOf(Edge("a","c",10.0), Edge("a","d",1.0), Edge("d","c",1.0))), "a", "c")!!
        assertEquals(2.0, route.totalDistance, 0.0)
    }
    @Test fun blockedEdgeIsExcluded() { assertNull(planner.find(graph(listOf(Edge("a","c",20.0, accessible=false))), "a", "c")) }
    @Test fun directedEdgeCannotBeReversed() { assertNull(planner.find(graph(listOf(Edge("a","c",20.0,directed=true))), "c", "a")) }
    @Test fun undirectedEdgeCanBeReversed() { assertNotNull(planner.find(graph(listOf(Edge("a","c",20.0))), "c", "a")) }
    @Test fun unknownDestination() { assertNull(planner.find(graph(emptyList()), "a", "unknown")) }
    @Test(expected = IllegalArgumentException::class) fun rejectsNegativeCost() { graph(listOf(Edge("a","c",-1.0))) }
    @Test fun relaxationFindsImprovedRoute() {
        val route = planner.find(graph(listOf(Edge("a","b",50.0), Edge("a","d",15.0), Edge("d","b",10.0), Edge("b","c",10.0))), "a", "c")!!
        assertEquals(listOf("a","d","b","c"), route.nodes.map { it.id }); assertEquals(35.0, route.totalDistance, .001)
    }
}
