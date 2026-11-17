package kr.ac.campusroute.sharing

import kr.ac.campusroute.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class SharedRouteTest {
    private val campus=Campus("v1","Campus",listOf(Node("a",Point(0.0,0.0),"A"),Node("b",Point(10.0,0.0),"B"),Node("c",Point(20.0,0.0),"C")),listOf(Edge("a","b",10.0,directed=true),Edge("b","c",10.0)),emptyList())
    private val valid=SharedRoute("id","친구가 보낸 경로","c","v1",listOf("a","b","c"))
    @Test fun resolvesReceivedNodesAndGreenOrigin() {
        val route=valid.resolve(campus)
        assertEquals(RouteOrigin.SHARED,route.origin); assertEquals(20.0,route.totalDistance,0.0)
        assertEquals(listOf("a","b","c"),route.nodes.map { it.id })
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsOtherMap() { valid.copy(mapVersion="v2").resolve(campus) }
    @Test(expected=IllegalArgumentException::class) fun rejectsUnknownNode() { valid.copy(nodeIds=listOf("missing","c")).resolve(campus) }
    @Test(expected=IllegalArgumentException::class) fun rejectsDisconnectedShortcut() { valid.copy(nodeIds=listOf("a","c")).resolve(campus) }
    @Test(expected=IllegalArgumentException::class) fun rejectsReversedOneWay() { valid.copy(destinationId="a",nodeIds=listOf("b","a")).resolve(campus) }
    @Test(expected=IllegalArgumentException::class) fun rejectsWrongDestination() { valid.copy(destinationId="b").resolve(campus) }
    @Test(expected=IllegalArgumentException::class) fun rejectsBlockedEdge() { valid.resolve(campus.copy(edges=campus.edges.map { it.copy(accessible=false) })) }
    @Test(expected=IllegalArgumentException::class) fun rejectsUnboundedRoute() { valid.copy(nodeIds=List(5001){"c"}).resolve(campus) }
}
