package kr.ac.campusroute.routing

import kr.ac.campusroute.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class LiveRoutingTest {
    private val a=Node("a",Point(0.0,0.0),"A")
    private val b=Node("b",Point(100.0,0.0),"B")
    private fun campus(directed: Boolean=false, accessible: Boolean=true)=Campus("test","test",listOf(a,b),listOf(Edge("a","b",100.0,accessible,directed)),emptyList())
    @Test fun startsAtEdgeProjectionNotGate() {
        val route=routeFromPosition(campus(),Point(60.0,5.0),"b")!!
        assertEquals(Point(60.0,0.0),route.nodes.first().position)
        assertEquals(40.0,route.totalDistance,.001)
    }
    @Test fun respectsDirection() { assertNull(routeFromPosition(campus(true),Point(60.0,0.0),"a")) }
    @Test fun canWalkBackOnUndirectedEdge() { assertEquals(60.0,routeFromPosition(campus(),Point(60.0,0.0),"a")!!.totalDistance,.001) }
    @Test fun rejectsDistantRoad() { assertNull(routeFromPosition(campus(),Point(60.0,31.0),"b")) }
    @Test fun excludesBlockedRoad() { assertNull(routeFromPosition(campus(accessible=false),Point(60.0,0.0),"b")) }
    @Test fun exactEndpointDoesNotCreateZeroEdge() { assertEquals(listOf("a","b"),routeFromPosition(campus(),a.position,"b")!!.nodes.map { it.id }) }
    @Test fun progressTracksMeasuredPositionAndOffset() {
        val match=matchRoute(Route(listOf(a,b),100.0),Point(40.0,12.0))
        assertEquals(40.0,match.along,.001); assertEquals(12.0,match.offset,.001)
    }
    @Test fun sameFixCannotTriggerArrival() {
        val tracker=ArrivalTracker(); val route=Route(listOf(a,b),100.0); val match=matchRoute(route,b.position)
        assertFalse(tracker.update(route,b.position,match,5.0,1))
        assertFalse(tracker.update(route,b.position,match,5.0,1))
        assertTrue(tracker.update(route,b.position,match,5.0,2))
    }
    @Test fun inaccurateFixDoesNotArrive() {
        val tracker=ArrivalTracker(); val route=Route(listOf(a,b),100.0); val match=matchRoute(route,b.position)
        assertFalse(tracker.update(route,b.position,match,50.0,1)); assertFalse(tracker.update(route,b.position,match,50.0,2))
    }
    @Test fun passingNearGoalAtStartOfLoopDoesNotArrive() {
        val route=Route(listOf(a,b,Node("c",Point(0.0,10.0),"C")),200.0)
        val tracker=ArrivalTracker(); val match=matchRoute(route,a.position)
        assertFalse(tracker.update(route,a.position,match,5.0,1)); assertFalse(tracker.update(route,a.position,match,5.0,2))
    }
}
